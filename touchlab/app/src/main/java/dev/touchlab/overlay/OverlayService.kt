package dev.touchlab.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.touchlab.MainActivity
import dev.touchlab.R
import dev.touchlab.config.TouchConfigManager
import dev.touchlab.engine.EngineController
import dev.touchlab.model.AppState
import dev.touchlab.model.MacroMode
import dev.touchlab.model.MacroModel
import dev.touchlab.model.MacroTarget
import dev.touchlab.model.ZonePreset
import dev.touchlab.shizuku.ShizukuHelper
import java.util.concurrent.Executors

/**
 * Foreground service giữ overlay + engine.
 *
 * Mỗi thành phần overlay là một cửa sổ nhỏ riêng (không phải một cửa sổ full-screen), nhờ vậy
 * nút macro không chặn touch của game ở các vùng còn lại.
 *
 * Thứ tự z-order: lớp sensitivity (nếu bật) → nút macro → nút menu → panel/zone editor.
 */
class OverlayService : Service(), TouchConfigManager.Listener {

    companion object {
        const val ACTION_START = "dev.touchlab.overlay.START"
        const val ACTION_STOP = "dev.touchlab.overlay.STOP"
        const val ACTION_REBUILD = "dev.touchlab.overlay.REBUILD"

        private const val CHANNEL_ID = "touchlab_overlay"
        private const val NOTIF_ID = 4201

        /** Overlay có đang chạy không (dùng cho MainActivity). */
        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, OverlayService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }

        fun rebuild(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, OverlayService::class.java).setAction(ACTION_REBUILD))
            }
        }
    }

    /** Một cửa sổ overlay kèm LayoutParams để cập nhật vị trí. */
    private class Win(val view: View, val params: WindowManager.LayoutParams)

    private lateinit var wm: WindowManager
    private lateinit var config: TouchConfigManager
    private val main = Handler(Looper.getMainLooper())
    private val engineExecutor = Executors.newSingleThreadExecutor()
    private val rebuildAfterEdit = Runnable { rebuildWidgets() }

    private var screenW = 1080
    private var screenH = 2400
    private var density = 3f

    private val widgetWins = mutableListOf<Win>()
    private val macroWins = mutableMapOf<Int, Win>()
    private val targetWins = mutableMapOf<String, Win>()
    private var panelWin: Win? = null
    private var sensiCircleWin: Win? = null
    private var menuWin: Win? = null
    private val activeHolds = mutableSetOf<Int>()

    @Volatile private var engineOk = false
    @Volatile private var engineMsg: String? = null

    /** Đang tự sửa config (để không nghe lại listener của chính mình). */
    private var selfEdit = false
    private var pushPending = false
    private var stopping = false

    /** Macro trigger đang được giữ — -1 nếu không có. */
    private var holdIndex = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        config = TouchConfigManager(this)
        density = resources.displayMetrics.density
        readScreenSize()
        createChannel()
        config.addListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_REBUILD -> {
                startForegroundCompat()
                selfEdit = true
                try {
                    config.reload()
                } finally {
                    selfEdit = false
                }
                rebuildWidgets()
                return START_STICKY
            }
            else -> {
                startForegroundCompat()
                startEverything()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (!stopping) {
            stopping = true
            engineExecutor.execute { EngineController.stop() }
            engineExecutor.shutdown()
        }
        removeAllWindows()
        config.removeListener(this)
        isRunning = false
        super.onDestroy()
    }

    // ---------------------------------------------------------------- khởi động

    private fun startEverything() {
        readScreenSize()
        if (!Settings.canDrawOverlays(this)) {
            fail("Cần cấp quyền \"Hiển thị trên ứng dụng khác\" cho TouchLab")
            return
        }
        when (ShizukuHelper.status()) {
            ShizukuHelper.Status.NO_SERVICE -> {
                fail("Chưa cài hoặc chưa chạy Shizuku. Mở Shizuku rồi thử lại.")
                return
            }
            ShizukuHelper.Status.NO_PERMISSION -> {
                fail("Chưa cấp quyền Shizuku cho TouchLab")
                return
            }
            ShizukuHelper.Status.READY -> Unit
        }

        selfEdit = true
        try {
            config.reload()
        } finally {
            selfEdit = false
        }
        val configText = config.buildConfigText()
        engineOk = false
        engineMsg = null
        isRunning = true
        rebuildWidgets()

        engineExecutor.execute {
            val result = runCatching {
                EngineController.clearLog()
                EngineController.pushConfig(this, configText).getOrThrow()
                EngineController.start(this).getOrThrow()
            }
            main.post {
                if (stopping) return@post
                engineOk = result.isSuccess
                engineMsg = result.exceptionOrNull()?.message
                if (engineMsg != null) {
                    toast("Engine lỗi: $engineMsg")
                }
            }
        }
    }

    /**
     * Không tắt service ngay khi lỗi: giữ panel để người dùng đọc lỗi và bấm "Thử lại",
     * muốn dừng thì bấm "Dừng" trong panel.
     */
    private fun fail(message: String) {
        engineOk = false
        engineMsg = message
        toast(message)
        showPanel()
    }

    private fun shutdown() {
        if (stopping) return
        stopping = true
        main.removeCallbacksAndMessages(null)
        removeAllWindows()
        engineExecutor.execute { EngineController.stop() }
        engineExecutor.shutdown()
        config.removeListener(this)
        OverlayService.isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------------------------------------------------------------- cửa sổ

    private val baseFlags: Int
        get() = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    private fun params(w: Int, h: Int, x: Int, y: Int, flags: Int = baseFlags) =
        WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

    private fun dp(v: Float): Int = (v * density).toInt()

    private fun addWindow(view: View, p: WindowManager.LayoutParams, widget: Boolean): Win? = try {
        wm.addView(view, p)
        Win(view, p).also { if (widget) widgetWins.add(it) }
    } catch (t: Throwable) {
        // BadTokenException khi app mất quyền overlay giữa lúc chạy → báo, không crash.
        toast("Không thêm được overlay: ${t.message}")
        null
    }

    private fun removeWin(win: Win?) {
        if (win == null) return
        runCatching { wm.removeView(win.view) }
        widgetWins.remove(win)
        (win.view as? OverlayComposeView)?.release()
    }

    private fun removeAllWindows() {
        hidePanel()
        releaseHolds()
        widgetWins.toList().forEach { removeWin(it) }
        widgetWins.clear()
        macroWins.clear()
        targetWins.clear()
        sensiCircleWin = null
        menuWin = null
    }

    private fun readScreenSize() {
        val (w, h) = config.screenSize()
        screenW = w
        screenH = h
    }

    /** Vẽ lại lớp sensi + nút macro + nút menu (giữ panel nếu đang mở). */
    private fun rebuildWidgets() {
        if (stopping) return
        readScreenSize()
        releaseHolds()
        widgetWins.toList().forEach { removeWin(it) }
        widgetWins.clear()
        macroWins.clear()
        targetWins.clear()
        sensiCircleWin = null
        menuWin = null

        val state = config.state
        if (state.sensi.enabled) {
            addSensiLayer()
            if (state.sensi.zone == ZonePreset.CIRCLE) addSensiCircle(state.sensi)
        }
        addMacroButtons(state)
        addMenuButton()
    }

    private fun addSensiLayer() {
        val view = OverlayComposeView(this) {
            SensiTouchLayer(
                onBegin = { x, y -> sendEngineCommand("SENS_BEGIN $x $y") },
                onMove = { x, y -> sendEngineCommand("SENS_MOVE $x $y") },
                onEnd = { sendEngineCommand("SENS_END") },
            )
        }
        addWindow(
            view,
            params(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                0, 0,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            ),
            widget = true,
        )
    }

    private fun addSensiCircle(sensi: dev.touchlab.model.SensiModel) {
        val diameter = dp(sensi.circleDiameterDp.toFloat())
        val p = params(
            diameter,
            diameter,
            (sensi.circleX - diameter / 2f).toInt(),
            (sensi.circleY - diameter / 2f).toInt(),
            baseFlags or if (sensi.circleLocked) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0,
        )
        val view = OverlayComposeView(this) {
            SensiCircleContent(
                diameter = diameter,
                alpha = sensi.circleAlpha,
                locked = sensi.circleLocked,
                onDrag = { dx, dy ->
                    sensi.circleX = (sensi.circleX + dx).coerceIn(0f, screenW.toFloat())
                    sensi.circleY = (sensi.circleY + dy).coerceIn(0f, screenH.toFloat())
                    sensiCircleWin?.let { win ->
                        win.params.x = (sensi.circleX - diameter / 2f).toInt()
                        win.params.y = (sensi.circleY - diameter / 2f).toInt()
                        runCatching { wm.updateViewLayout(win.view, win.params) }
                    }
                },
                onDragEnd = { editAndSave() },
            )
        }
        sensiCircleWin = addWindow(view, p, widget = true)
    }

    private fun addMacroButtons(state: AppState) {
        state.macros.take(TouchConfigManager.MAX_MACROS).forEachIndexed { index, macro ->
            if (!macro.enabled) return@forEachIndexed

            macro.targets.take(MacroModel.MAX_TARGETS).forEachIndexed { targetIndex, target ->
                addMacroTarget(targetIndex, target)
            }
            val sizePx = dp(macro.sizeDp.toFloat())
            val view = OverlayComposeView(this) {
                MacroButtonContent(
                    index = index,
                    size = sizePx,
                    mode = macro.mode,
                    locked = macro.triggerLocked,
                    holdActive = holdIndex == index,
                    onPress = {
                        if (macro.mode == MacroMode.HOLD) {
                            holdIndex = index
                            activeHolds.add(index)
                            sendEngineCommand("TRIGGER_DOWN $index")
                        }
                    },
                    onRelease = {
                        if (macro.mode == MacroMode.HOLD) {
                            if (holdIndex == index) holdIndex = -1
                            activeHolds.remove(index)
                            sendEngineCommand("TRIGGER_UP $index")
                        }
                    },
                    onTap = { sendEngineCommand("TRIGGER $index") },
                    onDrag = { dx, dy ->
                        macroWins[index]?.let { w ->
                            w.params.x += dx.toInt()
                            w.params.y += dy.toInt()
                            runCatching { wm.updateViewLayout(w.view, w.params) }
                        }
                    },
                    onDragEnd = {
                        macroWins[index]?.let { w ->
                            val half = w.params.width / 2f
                            macro.x = (w.params.x + half).coerceIn(half, screenW - half)
                            macro.y = (w.params.y + half).coerceIn(half, screenH - half)
                            w.params.x = (macro.x - half).toInt()
                            w.params.y = (macro.y - half).toInt()
                            runCatching { wm.updateViewLayout(w.view, w.params) }
                        }
                        editAndSave()
                    },
                )
            }
            val p = params(
                sizePx, sizePx,
                (macro.x - sizePx / 2f).toInt(),
                (macro.y - sizePx / 2f).toInt(),
            )
            addWindow(view, p, widget = true)?.let { macroWins[index] = it }
        }
    }

    private fun addMacroTarget(targetIndex: Int, target: MacroTarget) {
        val diameter = dp(target.sizeDp.toFloat())
        val p = params(
            diameter,
            diameter,
            (target.x - diameter / 2f).toInt(),
            (target.y - diameter / 2f).toInt(),
            baseFlags or if (target.locked) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0,
        )
        val view = OverlayComposeView(this) {
            MacroTargetContent(
                index = targetIndex,
                size = diameter,
                locked = target.locked,
                onDrag = { dx, dy ->
                    target.x = (target.x + dx).coerceIn(0f, screenW.toFloat())
                    target.y = (target.y + dy).coerceIn(0f, screenH.toFloat())
                    targetWins[target.id]?.let { win ->
                        win.params.x = (target.x - diameter / 2f).toInt()
                        win.params.y = (target.y - diameter / 2f).toInt()
                        runCatching { wm.updateViewLayout(win.view, win.params) }
                    }
                },
                onDragEnd = { editAndSave() },
            )
        }
        addWindow(view, p, widget = true)?.let { targetWins[target.id] = it }
    }

    private fun addMenuButton() {
        val size = dp(52f)
        val p = params(size, size, dp(8f), screenH / 3)
        val view = OverlayComposeView(this) {
            MenuButtonContent(
                onTap = { showPanel() },
                onDrag = { dx, dy ->
                    menuWin?.let { w ->
                        w.params.x += dx.toInt()
                        w.params.y += dy.toInt()
                        runCatching { wm.updateViewLayout(w.view, w.params) }
                    }
                },
            )
        }
        menuWin = addWindow(view, p, widget = true)
    }

    // ---------------------------------------------------------------- panel / editor

    private fun showPanel() {
        if (panelWin != null) return
        val view = OverlayComposeView(this) {
            OverlayPanelContent(
                state = config.state,
                screenW = screenW,
                screenH = screenH,
                engineOk = engineOk,
                engineMessage = engineMsg,
                onEdit = { editAndSave() },
                onApply = { applyNow() },
                onAddMacro = { addMacro() },
                onRemoveMacro = { removeMacro(it) },
                onRestartEngine = { restartEngine() },
                onClose = { hidePanel() },
                onStop = { shutdown() },
            )
        }
        panelWin = addWindow(
            view,
            params(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                0, 0,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            ),
            widget = false,
        )
    }

    private fun hidePanel() {
        removeWin(panelWin)
        panelWin = null
    }

    // ---------------------------------------------------------------- hành động

    /** Lưu thay đổi do overlay tạo ra (không nghe lại listener của chính mình). */
    private fun editAndSave() {
        config.saveDebounced(notifyListeners = false)
        main.removeCallbacks(rebuildAfterEdit)
        main.postDelayed(rebuildAfterEdit, 180)
        schedulePush()
    }

    private fun addMacro() {
        val state = config.state
        if (state.macros.size >= TouchConfigManager.MAX_MACROS) {
            toast("Tối đa ${TouchConfigManager.MAX_MACROS} macro")
            return
        }
        state.macros.add(
            MacroModel(
                x = screenW * 0.7f,
                y = screenH * 0.5f,
                targets = mutableListOf(
                    MacroTarget(x = screenW * 0.7f, y = screenH * 0.42f)
                ),
            )
        )
        editAndSave()
    }

    private fun removeMacro(index: Int) {
        val state = config.state
        if (index !in state.macros.indices) return
        state.macros.removeAt(index)
        editAndSave()
    }

    private fun restartEngine() {
        engineOk = false
        val configText = config.buildConfigText()
        engineExecutor.execute {
            val result = runCatching {
                EngineController.stop()
                EngineController.pushConfig(this, configText).getOrThrow()
                EngineController.start(this).getOrThrow()
            }
            main.post {
                if (stopping) return@post
                engineOk = result.isSuccess
                engineMsg = result.exceptionOrNull()?.message
                if (engineOk) {
                    toast("Engine đã chạy")
                } else {
                    toast("Engine lỗi: ${engineMsg}")
                }
                hidePanel()
                showPanel()
            }
        }
    }

    private fun applyNow() {
        selfEdit = true
        try {
            config.save()
        } finally {
            selfEdit = false
        }
        rebuildWidgets()
        if (!engineOk) {
            toast("Đã lưu cấu hình (engine chưa chạy)")
            return
        }
        pushConfigAsync(notifyEngine = true) { result ->
            toast(if (result.isSuccess) "Đã áp dụng cấu hình" else "Lỗi áp dụng: ${result.exceptionOrNull()?.message}")
        }
    }

    /** Gộp các thay đổi liên tiếp (kéo slider) thành một lần đẩy config. */
    private fun schedulePush() {
        if (pushPending) return
        pushPending = true
        main.postDelayed({
            pushPending = false
            pushConfigAsync(notifyEngine = true)
        }, 400)
    }

    override fun onConfigChanged(state: AppState) {
        if (selfEdit) return
        main.post {
            if (stopping) return@post
            selfEdit = true
            try {
                config.reload()
            } finally {
                selfEdit = false
            }
            rebuildWidgets()
            pushConfigAsync(notifyEngine = true)
        }
    }

    private fun pushConfigAsync(notifyEngine: Boolean, onComplete: ((Result<Unit>) -> Unit)? = null) {
        if (stopping) return
        val text = config.buildConfigText()
        engineExecutor.execute {
            val result = EngineController.pushConfig(this, text).mapCatching {
                if (notifyEngine && engineOk) EngineController.send("RECONFIG")
                Unit
            }
            if (onComplete != null) {
                main.post { if (!stopping) onComplete(result) }
            }
        }
    }

    private fun sendEngineCommand(command: String) {
        if (stopping) return
        engineExecutor.execute { EngineController.send(command) }
    }

    private fun releaseHolds() {
        activeHolds.toList().forEach { sendEngineCommand("TRIGGER_UP $it") }
        activeHolds.clear()
        holdIndex = -1
    }

    // ---------------------------------------------------------------- tiện ích

    private fun toast(msg: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } else {
            main.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun startForegroundCompat() {
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, notif, type)
    }
}
