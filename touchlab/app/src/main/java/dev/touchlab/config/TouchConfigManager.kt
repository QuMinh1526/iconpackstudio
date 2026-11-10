package dev.touchlab.config

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import dev.touchlab.engine.EngineController
import dev.touchlab.model.AppState
import dev.touchlab.model.DragKind
import dev.touchlab.model.MacroModel
import org.json.JSONObject
import java.util.Locale

/**
 * Nguồn sự thật duy nhất cho cấu hình macro / sensitivity / drag.
 *
 * - Lưu trạng thái dưới dạng JSON trong SharedPreferences (thay cho 15 key rời của Zayon).
 * - Sinh file config đúng thứ tự dòng của `zayon_touch.cfg` để engine parse được.
 * - Đẩy config xuống engine qua [EngineController] (file + `RECONFIG`).
 */
class TouchConfigManager(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private var notifyAfterSave = false
    private var savePending = false
    private val pendingSave = Runnable {
        savePending = false
        prefs.edit().putString(KEY_STATE, state.toJson().toString()).apply()
        if (notifyAfterSave) notifyListeners()
        notifyAfterSave = false
    }

    /** Trạng thái hiện tại. Mọi thay đổi phải gọi [save] để ghi lại. */
    var state: AppState = load()
        private set

    /** Lắng nghe thay đổi cấu hình (overlay dùng để vẽ lại). */
    interface Listener {
        fun onConfigChanged(state: AppState)
    }

    private val listeners = mutableListOf<Listener>()

    fun addListener(l: Listener) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    /** Đọc lại từ SharedPreferences (dùng khi activity sửa xong rồi overlay cần cập nhật). */
    fun reload(): AppState {
        flushPendingSave()
        state = load()
        notifyListeners()
        return state
    }

    private fun load(): AppState {
        val raw = prefs.getString(KEY_STATE, null) ?: return defaultState()
        return try {
            AppState.fromJson(JSONObject(raw))
        } catch (t: Throwable) {
            // JSON hỏng (hiếm): quay về mặc định thay vì crash overlay.
            defaultState()
        }
    }

    private fun defaultState(): AppState {
        val (w, h) = screenSize()
        val s = AppState()
        s.macros.add(
            dev.touchlab.model.MacroModel(
                x = w * 0.75f,
                y = h * 0.78f,
                targets = mutableListOf(dev.touchlab.model.MacroTarget(x = w * 0.75f, y = h * 0.70f)),
            )
        )
        s.macros.add(
            dev.touchlab.model.MacroModel(
                x = w * 0.85f,
                y = h * 0.62f,
                targets = mutableListOf(dev.touchlab.model.MacroTarget(x = w * 0.85f, y = h * 0.54f)),
            )
        )
        s.sensi.circleX = w / 2f
        s.sensi.circleY = h / 2f
        s.drags = mutableListOf(
            dev.touchlab.model.DragModel(DragKind.DRAG, x = w * 0.3f, y = h * 0.5f, size = 120),
            dev.touchlab.model.DragModel(DragKind.STABLE, x = w * 0.3f, y = h * 0.4f, size = 120),
        )
        return s
    }

    /** Ghi trạng thái xuống đĩa. */
    fun save(state: AppState = this.state) {
        main.removeCallbacks(pendingSave)
        savePending = false
        notifyAfterSave = false
        this.state = state
        prefs.edit().putString(KEY_STATE, state.toJson().toString()).apply()
        notifyListeners()
    }

    /** Coalesce high-frequency slider/drag updates into one disk write and notification. */
    fun saveDebounced(notifyListeners: Boolean = true) {
        notifyAfterSave = notifyAfterSave || notifyListeners
        main.removeCallbacks(pendingSave)
        savePending = true
        main.postDelayed(pendingSave, SAVE_DEBOUNCE_MS)
    }

    fun flushPendingSave() {
        if (!savePending) return
        main.removeCallbacks(pendingSave)
        pendingSave.run()
    }

    private fun notifyListeners() {
        listeners.toList().forEach { it.onConfigChanged(state) }
    }

    /** Kích thước màn hình thật (px), đã tính xoay hiện tại. */
    fun screenSize(): Pair<Int, Int> {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    fun rotation(): Int {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        return wm.defaultDisplay.rotation
    }

    /**
     * Sinh nội dung file config.
     *
     * Format giống Zayon (cùng thứ tự dòng, cùng định dạng số) nhưng hệ số sensi là hệ số
     * GỐC (1.0–3.45). Dòng phụ `S` lưu tâm/bán kính vùng tròn Sensi.
     */
    fun buildConfigText(state: AppState = this.state): String {
        val (w, h) = screenSize()
        val sb = StringBuilder()

        val sensi = state.sensi
        val zone = sensi.zoneIndex()
        // ① sensiX sensiY zone accel 0 rotation
        sb.append(
            String.format(
                Locale.US, "%.3f %.3f %d %d 0 %d\n",
                sensi.sx[zone], sensi.sy[zone], sensi.zone.engineZone(), if (sensi.accel) 1 else 0, rotation()
            )
        )

        val macros = state.macros.take(MAX_MACROS)
        // ② screenW screenH macroCount formatVersion
        sb.append("$w $h ${macros.size} 2\n")

        // ③ trigger: x y diameter mode holdDelay pressMs clickDelay targetCount
        macros.forEach { m ->
            val targets = m.targets.take(MacroModel.MAX_TARGETS).ifEmpty {
                listOf(dev.touchlab.model.MacroTarget(x = m.x, y = m.y))
            }
            val mode = m.mode.coerceIn(0, 1)
            sb.append(
                String.format(
                    Locale.US, "%.1f %.1f %.1f %d %d %d %d %d\n",
                    m.x, m.y, m.sizeDp * density(), mode, m.holdDelayMs, m.pressDurationMs,
                    m.clickDelayMs, targets.size
                )
            )
            targets.forEach { target ->
                sb.append(
                    String.format(
                        Locale.US, "%.1f %.1f %.1f\n",
                        target.x, target.y, target.sizeDp * density(),
                    )
                )
            }
        }

        val drags = state.drags.take(MAX_DRAGS)
        // ④ dragCount
        sb.append("${drags.size}\n")
        // ⑤ mỗi drag: x y r strength kind delay
        drags.forEach { d ->
            sb.append(
                String.format(
                    Locale.US, "%.1f %.1f %.1f %d %d %d\n",
                    d.x, d.y, d.size / 2f, d.strength.coerceIn(1, 100), d.kind.cfgSign, d.delay.coerceIn(0, 500)
                )
            )
        }

        val radiusPx = state.sensi.circleDiameterDp * density() / 2f
        sb.append(String.format(Locale.US, "S %.1f %.1f %.1f\n", sensi.circleX, sensi.circleY, radiusPx))
        return sb.toString()
    }

    private fun density(): Float = appContext.resources.displayMetrics.density

    /** Đẩy config xuống engine. Trả về lỗi (nếu có) để UI hiển thị. */
    fun pushToEngine(notifyEngine: Boolean = true): Result<Unit> {
        val text = buildConfigText()
        return EngineController.pushConfig(appContext, text).mapCatching {
            if (notifyEngine) EngineController.send("RECONFIG")
        }
    }

    companion object {
        private const val PREFS = "touchlab_config"
        private const val KEY_STATE = "state"
        private const val SAVE_DEBOUNCE_MS = 140L

        /** Giới hạn giống engine/permit của bản gốc. */
        const val MAX_MACROS = 8
        const val MAX_DRAGS = 2
        const val SIZE_MIN = 24
        const val SIZE_MAX = 220
        const val SENSI_MIN = 1.0f
        const val SENSI_MAX = 3.45f
    }
}
