package dev.touchlab.engine

import android.content.Context
import dev.touchlab.shizuku.ShizukuHelper
import dev.touchlab.shizuku.ShellResult
import java.io.File
import java.util.Locale

/**
 * Điều khiển engine native (`touchlab_engine`) chạy dưới quyền shell của Shizuku.
 *
 * Vì sao phải chạy qua Shizuku: `/dev/uinput` chỉ ghi được bởi uid 2000 (shell) trở lên.
 * App thường không có quyền đó, còn shell thì có.
 *
 * Giao thức: engine đọc config + theo dõi file lệnh bằng inotify. Mỗi lệnh của app chỉ là
 * một dòng ghi thêm vào file đó, nên không cần binder, không cần socket.
 *
 *   <bin> <cfg> --cmd <cmd> --log <log>
 *
 * Các lệnh hỗ trợ: TAP, MACRO, HOLD, RELEASE, DRAG, SENS_BEGIN, SENS_MOVE, SENS_END,
 * RECONFIG, PING, STOP.
 */
object EngineController {

    /** Thư mục làm việc của engine (shell ghi được, app chỉ đọc/ghi qua Shizuku). */
    const val DIR = "/data/local/tmp/touchlab"
    const val CFG_PATH = "$DIR/touchlab.cfg"
    const val CMD_PATH = "$DIR/cmd"
    const val LOG_PATH = "$DIR/engine.log"

    /**
     * Tên file executable trong APK (nativeLibraryDir/lib<abi>/).
     * Phải có dạng `lib*.so` thì AGP mới đóng gói; khi deploy sẽ đổi tên thành
     * `/data/local/tmp/touchlab/touchlab_engine`.
     */
    private const val BIN_NAME = "libtouchlab_engine.so"

    /** Có tiến trình engine nào đang chạy không (theo pid file do shell ghi). */
    @Volatile
    var running: Boolean = false
        private set

    /** Lỗi gần nhất, để UI hiển thị. */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * Shell `sh` sống lâu dùng để ghi lệnh vào file cmd.
     * Mỗi lệnh chỉ là một dòng ghi vào stdin nên không phải spawn shell mới (quan trọng với
     * SENS_MOVE chạy 60–120 lần/giây).
     */
    private var shell: ShizukuHelper.RemoteProcess? = null
    private var shellWriter: java.io.BufferedWriter? = null

    /** Đường dẫn executable trong APK; null nếu ABI không được build. */
    fun binaryPath(context: Context): File? {
        val f = File(context.applicationInfo.nativeLibraryDir, BIN_NAME)
        return if (f.exists()) f else null
    }

    /**
     * Copy engine ra /data/local/tmp và cấp quyền thực thi.
     * Gọi mỗi lần bật overlay (nhanh, vài chục ms) để chắc chắn dùng bản mới sau khi update app.
     */
    fun deploy(context: Context): Result<Unit> = runCatching {
        val bin = binaryPath(context) ?: error("Không tìm thấy $BIN_NAME trong APK (ABI không được hỗ trợ?)")
        ShizukuHelper.runCommand("mkdir -p $DIR && rm -f $DIR/touchlab_engine.tmp").requireOk("Tạo thư mục engine")
        // `cat >` thay cho cp vì đường dẫn nativeLibraryDir nằm trong /data/app, shell đọc được,
        // nhưng copy qua cat vẫn ổn định hơn trên một số ROM.
        val cp = ShizukuHelper.runCommand("cat '${bin.absolutePath}' > $DIR/touchlab_engine && chmod 700 $DIR/touchlab_engine")
        cp.requireOk("Cài engine")
        // Kiểm tra file chạy được (thiếu quyền / sai ABI → báo lỗi ngay, đừng đợi tới lúc start).
        val probe = ShizukuHelper.runCommand("test -x $DIR/touchlab_engine && $DIR/touchlab_engine 2>&1 | head -1")
        if (probe.code == 126 || probe.code == 127) {
            error("Engine không chạy được (mã ${probe.code}): ${probe.stderr.trim()}")
        }
        Unit
    }.onFailure { lastError = it.message }

    /**
     * Đẩy config đã sinh ra engine.
     *
     * Nội dung được ghi vào thư mục external files của app (shell đọc được) rồi `cp`
     * sang /data/local/tmp. Cách này tránh phải escape nội dung khi nhúng vào lệnh shell.
     */
    fun pushConfig(context: Context, content: String): Result<Unit> = runCatching {
        val staging = File(context.getExternalFilesDir(null) ?: context.filesDir, "touchlab.cfg")
        staging.writeText(content)
        ShizukuHelper.runCommand("mkdir -p $DIR && cat '${staging.absolutePath}' > $CFG_PATH")
            .requireOk("Ghi config")
        Unit
    }.onFailure { lastError = it.message }

    /** Khởi động engine (kill bản cũ trước). */
    fun start(context: Context): Result<Unit> = runCatching {
        deploy(context).getOrThrow()
        stopInternal()
        // setsid + nohup: engine sống độc lập với shell, log ghi ra file.
        val cmd = "cd $DIR && setsid nohup $DIR/touchlab_engine $CFG_PATH --cmd $CMD_PATH " +
            "--log $LOG_PATH >/dev/null 2>&1 & echo \$! > $DIR/pid"
        ShizukuHelper.runCommand(cmd).requireOk("Khởi động engine")
        Thread.sleep(250) // chờ engine mở uinput
        val pid = ShizukuHelper.runCommand("cat $DIR/pid 2>/dev/null").stdout.trim()
        if (pid.isEmpty() || !isAlive(pid)) {
            val log = logTail(last = 5)
            error("Engine thoát ngay khi khởi động${if (log.isBlank()) "" else ":\n$log"}")
        }
        running = true
        Unit
    }.onFailure {
        running = false
        lastError = it.message
    }

    /** Dừng engine. Không báo lỗi nếu nó vốn không chạy. */
    fun stop() {
        stopInternal()
        running = false
    }

    private fun stopInternal() {
        runCatching {
            // Ưu tiên STOP (engine tự đóng uinput), rồi pkill phòng khi nó treo.
            ShizukuHelper.runCommand("printf 'STOP\\n' >> $CMD_PATH 2>/dev/null; sleep 0.1; pkill -f '$DIR/touchlab_engine' 2>/dev/null; rm -f $DIR/pid")
        }
        closeShell()
    }

    /** Đóng shell dùng để gửi lệnh. */
    @Synchronized
    fun closeShell() {
        runCatching { shellWriter?.close() }
        runCatching { shell?.destroy() }
        shellWriter = null
        shell = null
    }

    /** Lấy writer của shell, mở lại nếu shell đã chết. */
    @Synchronized
    private fun writer(): java.io.BufferedWriter? {
        val current = shell
        if (current != null && current.isAlive()) return shellWriter
        closeShell()
        val p = ShizukuHelper.openShell() ?: return null
        return try {
            val w = p.outputStream.bufferedWriter()
            shell = p
            shellWriter = w
            w
        } catch (t: Throwable) {
            runCatching { p.destroy() }
            null
        }
    }

    private fun isAlive(pid: String): Boolean =
        ShizukuHelper.runCommand("kill -0 $pid 2>/dev/null").ok

    /** Gửi một lệnh cho engine. Trả về false nếu chưa sẵn sàng. */
    fun send(command: String): Boolean {
        if (!running) return false
        val line = command.replace("\n", " ").trim()
        // Escape nháy đơn kiểu shell: ' → '\''
        val escaped = line.replace("'", "'\\''")
        val w = writer()
        if (w == null) {
            lastError = "Không mở được shell Shizuku"
            return false
        }
        return try {
            w.write("printf '%s\\n' '$escaped' >> $CMD_PATH\n")
            w.flush()
            true
        } catch (t: Throwable) {
            closeShell()
            lastError = "Không gửi được lệnh: ${t.message}"
            false
        }
    }

    /**
     * Gửi lệnh với tần suất cao (SENS_MOVE). Vẫn dùng [send] nhưng tách tên để chỗ gọi
     * thấy rõ đây là đường nóng, cần throttle ở phía overlay.
     */
    fun sendFast(command: String): Boolean = send(command)

    /** `n` dòng cuối của log engine. */
    fun logTail(last: Int = 30): String = runCatching {
        ShizukuHelper.runCommand("tail -n $last $LOG_PATH 2>/dev/null").stdout.trim()
    }.getOrDefault("")

    /** Xoá log (dùng trước khi start để log chỉ chứa lần chạy hiện tại). */
    fun clearLog(): ShellResult =
        ShizukuHelper.runCommand("rm -f $LOG_PATH")

    /** Phiên bản engine (dòng usage) để hiển thị ở màn hình Home. */
    fun versionInfo(): String = runCatching {
        ShizukuHelper.runCommand("$DIR/touchlab_engine 2>&1 | head -1").stdout.trim()
    }.getOrDefault("")

    /** Định dạng số kiểu C (`%.3f`) — luôn dùng dấu chấm. */
    fun fmt(value: Float, digits: Int = 1): String =
        String.format(Locale.US, "%.${digits}f", value)
}
