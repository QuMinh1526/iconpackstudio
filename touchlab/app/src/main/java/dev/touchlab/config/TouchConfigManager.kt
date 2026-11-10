package dev.touchlab.config

import android.content.Context
import android.content.SharedPreferences
import android.graphics.RectF
import android.util.DisplayMetrics
import android.view.WindowManager
import dev.touchlab.engine.EngineController
import dev.touchlab.model.AppState
import dev.touchlab.model.DragKind
import dev.touchlab.model.MacroMode
import dev.touchlab.model.ZonePreset
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
            dev.touchlab.model.MacroModel(x = w * 0.75f, y = h * 0.78f, x2 = w * 0.75f, y2 = h * 0.70f)
        )
        s.macros.add(
            dev.touchlab.model.MacroModel(x = w * 0.85f, y = h * 0.62f, x2 = w * 0.85f, y2 = h * 0.54f)
        )
        s.drags = mutableListOf(
            dev.touchlab.model.DragModel(DragKind.DRAG, x = w * 0.3f, y = h * 0.5f, size = 120),
            dev.touchlab.model.DragModel(DragKind.STABLE, x = w * 0.3f, y = h * 0.4f, size = 120),
        )
        return s
    }

    /** Ghi trạng thái xuống đĩa. */
    fun save(state: AppState = this.state) {
        this.state = state
        prefs.edit().putString(KEY_STATE, state.toJson().toString()).apply()
        notifyListeners()
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
     * GỐC (1.0–3.45). Các dòng phụ `Z` (custom zone) và `F` (crosshair) là phần mở rộng.
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
        // ② screenW screenH macroCount
        sb.append("$w $h ${macros.size}\n")

        // ③ mỗi macro: x1 y1 r x2 y2 mode p0 p1
        macros.forEach { m ->
            val mode = m.mode.coerceIn(0, 3)
            sb.append(
                String.format(
                    Locale.US, "%.1f %.1f %.1f %.1f %.1f %d %d %d\n",
                    m.x, m.y, m.size / 2f, m.x2, m.y2, mode,
                    MacroMode.P0[mode], MacroMode.P1[mode]
                )
            )
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

        // ⑥ custom zone (chỉ khi zone = CUSTOM) — phần mở rộng so với Zayon
        val rect = customRectFor(state)
        if (rect != null) {
            sb.append(
                String.format(
                    Locale.US, "Z %.0f %.0f %.0f %.0f\n",
                    rect.left, rect.top, rect.right, rect.bottom
                )
            )
        }
        return sb.toString()
    }

    private fun customRectFor(state: AppState): RectF? {
        val (w, h) = screenSize()
        return when {
            state.sensi.zone == ZonePreset.CUSTOM -> state.sensi.customRect ?: RectF(0f, 0f, w.toFloat(), h.toFloat())
            state.macros.any { it.zone == ZonePreset.CUSTOM && it.customRect != null } ->
                state.macros.first { it.zone == ZonePreset.CUSTOM && it.customRect != null }.customRect
            else -> null
        }
    }

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

        /** Giới hạn giống engine/permit của bản gốc. */
        const val MAX_MACROS = 8
        const val MAX_DRAGS = 2
        const val SIZE_MIN = 24
        const val SIZE_MAX = 220
        const val SENSI_MIN = 1.0f
        const val SENSI_MAX = 3.45f
    }
}
