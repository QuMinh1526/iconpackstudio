package dev.touchlab.shizuku

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * Kết quả một lệnh shell chạy dưới quyền Shizuku.
 */
data class ShellResult(val code: Int, val stdout: String, val stderr: String) {
    val ok: Boolean get() = code == 0

    /** Ném lỗi kèm stderr để chỗ gọi hiển thị được cho người dùng. */
    fun requireOk(action: String): ShellResult {
        if (!ok) {
            val detail = stderr.ifBlank { stdout }.trim().take(400)
            throw IllegalStateException("$action thất bại (mã $code)${if (detail.isEmpty()) "" else ": $detail"}")
        }
        return this
    }
}

/**
 * Bọc API Shizuku.
 *
 * Shizuku chạy một tiến trình shell (uid 2000, hoặc root nếu người dùng chọn) và cho app gọi
 * binder vào đó. Nhờ vậy app mở được `/dev/uinput` mà không cần root.
 *
 * Lưu ý: `Shizuku.newProcess()` là private trong api 13, nên ở đây đi qua binder
 * `IShizukuService.newProcess()` (artifact `dev.rikka.shizuku:aidl`).
 *
 * Mọi hàm đều "an toàn khi lỗi": Shizuku chưa cài / chưa cấp quyền trả về trạng thái rõ ràng
 * thay vì ném exception ra ngoài.
 */
object ShizukuHelper {

    const val PERMISSION_REQUEST_CODE = 4101

    /** Trạng thái kết nối Shizuku, dùng để vẽ UI. */
    enum class Status(val label: String) {
        NO_SERVICE("Chưa cài / chưa chạy Shizuku"),
        NO_PERMISSION("Đã có Shizuku, chưa cấp quyền"),
        READY("Sẵn sàng"),
    }

    /** Shizuku (manager + service) có đang sống không. */
    fun isBinderAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        false
    }

    /** Quyền dùng API đã được cấp chưa. */
    fun hasPermission(): Boolean {
        if (!isBinderAlive()) return false
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            false
        }
    }

    fun status(): Status = when {
        !isBinderAlive() -> Status.NO_SERVICE
        !hasPermission() -> Status.NO_PERMISSION
        else -> Status.READY
    }

    /**
     * Bật hộp thoại xin quyền của Shizuku.
     * @return false nếu không thể xin (Shizuku cũ / binder chết).
     */
    fun requestPermission(): Boolean {
        if (!isBinderAlive()) return false
        return try {
            if (Shizuku.isPreV11()) {
                false
            } else {
                Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
                true
            }
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Bọc `IRemoteProcess` của Shizuku thành [Process] của Java để dùng như tiến trình thường.
     */
    class RemoteProcess(private val remote: IRemoteProcess) : Process() {

        private val stdin: OutputStream by lazy {
            ParcelFileDescriptor.AutoCloseOutputStream(remote.outputStream)
        }
        private val stdout: InputStream by lazy {
            ParcelFileDescriptor.AutoCloseInputStream(remote.inputStream)
        }
        private val stderr: InputStream by lazy {
            ParcelFileDescriptor.AutoCloseInputStream(remote.errorStream)
        }

        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = stderr
        override fun waitFor(): Int = remote.waitFor()
        override fun exitValue(): Int = remote.exitValue()
        override fun destroy() {
            runCatching { remote.destroy() }
        }

        /** Tiến trình còn sống (dùng để biết shell nền còn dùng được không). */
        override fun isAlive(): Boolean = runCatching { remote.alive() }.getOrDefault(false)
    }

    /** Lấy service shell của Shizuku; null nếu chưa sẵn sàng. */
    fun service(): IShizukuService? {
        if (!hasPermission()) return null
        return try {
            val binder = Shizuku.getBinder() ?: return null
            IShizukuService.Stub.asInterface(ShizukuBinderWrapper(binder))
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Chạy một tiến trình dưới quyền shell.
     * @return null nếu Shizuku chưa sẵn sàng.
     */
    fun newProcess(cmd: Array<String>): RemoteProcess? {
        return try {
            val svc = service() ?: return null
            RemoteProcess(svc.newProcess(cmd, null, null))
        } catch (t: Throwable) {
            null
        }
    }

    /** Mở một shell `sh` sống lâu để gửi nhiều lệnh mà không phải spawn lại. */
    fun openShell(): RemoteProcess? = newProcess(arrayOf("sh"))

    /**
     * Chạy `sh -c <command>` và chờ kết thúc.
     * Dùng cho các lệnh ngắn (cp, chmod, pkill, đọc log).
     */
    fun runCommand(command: String, timeoutSec: Long = 15): ShellResult {
        if (!hasPermission()) {
            return ShellResult(-1, "", "Chưa có quyền Shizuku")
        }
        val p = newProcess(arrayOf("sh", "-c", command))
            ?: return ShellResult(-1, "", "Không mở được tiến trình shell của Shizuku")
        return try {
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            val finished = waitWithTimeout(p, timeoutSec)
            if (finished) {
                ShellResult(p.exitValue(), out, err)
            } else {
                p.destroy()
                ShellResult(-1, out, "timeout sau ${timeoutSec}s")
            }
        } catch (t: Throwable) {
            ShellResult(-1, "", t.message ?: t.javaClass.simpleName)
        } finally {
            runCatching { p.destroy() }
        }
    }

    /** Chờ tiến trình kết thúc, tránh treo app nếu lệnh không trả về. */
    private fun waitWithTimeout(p: Process, timeoutSec: Long): Boolean {
        val worker = Thread {
            runCatching { p.waitFor() }
        }
        worker.isDaemon = true
        worker.start()
        worker.join(TimeUnit.SECONDS.toMillis(timeoutSec))
        return !worker.isAlive
    }
}
