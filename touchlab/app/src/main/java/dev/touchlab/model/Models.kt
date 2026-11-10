package dev.touchlab.model

import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Vùng hiển thị/giới hạn của macro hoặc của sensitivity. */
enum class ZonePreset(val label: String) {
    FULL("Toàn màn hình"),
    LEFT("Nửa trái"),
    RIGHT("Nửa phải"),
    CIRCLE("Vùng tròn tự chọn"),
    TOP("Nửa trên"),
    BOTTOM("Nửa dưới");

    /** Zone ghi xuống engine: 0 = toàn màn hình, 1 = trái, 2 = phải, 3 = vòng tròn. */
    fun engineZone(): Int = when (this) {
        LEFT -> 1
        RIGHT -> 2
        CIRCLE -> 3
        else -> 0
    }

    fun rectOf(screenW: Int, screenH: Int, custom: RectF?): RectF {
        val w = screenW.toFloat()
        val h = screenH.toFloat()
        return when (this) {
            FULL -> RectF(0f, 0f, w, h)
            LEFT -> RectF(0f, 0f, w / 2f, h)
            RIGHT -> RectF(w / 2f, 0f, w, h)
            TOP -> RectF(0f, 0f, w, h / 2f)
            BOTTOM -> RectF(0f, h / 2f, w, h)
            CIRCLE -> custom?.let { RectF(it) } ?: RectF(0f, 0f, w, h)
        }
    }

    companion object {
        fun of(name: String?): ZonePreset = when (name) {
            "CUSTOM" -> CIRCLE
            else -> entries.firstOrNull { it.name == name } ?: FULL
        }
    }
}

/** Kiểu hoạt động của trigger macro. */
object MacroMode {
    const val CLICK = 0
    const val HOLD = 1
    val LABELS = arrayOf("Click", "Hold")
}

/** Một điểm được bấm tuần tự khi trigger tương ứng được kích hoạt. */
data class MacroTarget(
    val id: String = UUID.randomUUID().toString(),
    var x: Float = 540f,
    var y: Float = 1200f,
    var sizeDp: Int = 24,
    var locked: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("x", x.toDouble())
        put("y", y.toDouble())
        put("sizeDp", sizeDp)
        put("locked", locked)
    }

    companion object {
        fun fromJson(o: JSONObject): MacroTarget = MacroTarget(
            id = o.optString("id", UUID.randomUUID().toString()),
            x = o.optDouble("x", 540.0).toFloat(),
            y = o.optDouble("y", 1200.0).toFloat(),
            sizeDp = o.optInt("sizeDp", 24).coerceIn(12, 72),
            locked = o.optBoolean("locked", true),
        )
    }
}

/**
 * Một nút macro trên overlay.
 *
 * @param x,y tâm nút (px màn hình)
 * @param targets các điểm click được phát theo đúng thứ tự trong danh sách
 */
data class MacroModel(
    val id: String = UUID.randomUUID().toString(),
    var x: Float = 540f,
    var y: Float = 1400f,
    var sizeDp: Int = 56,
    var mode: Int = MacroMode.CLICK,
    var enabled: Boolean = true,
    var triggerLocked: Boolean = true,
    var holdDelayMs: Int = 10,
    var pressDurationMs: Int = 10,
    var clickDelayMs: Int = 0,
    var targets: MutableList<MacroTarget> = mutableListOf(MacroTarget()),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("x", x.toDouble())
        put("y", y.toDouble())
        put("sizeDp", sizeDp)
        put("mode", mode)
        put("enabled", enabled)
        put("triggerLocked", triggerLocked)
        put("holdDelayMs", holdDelayMs)
        put("pressDurationMs", pressDurationMs)
        put("clickDelayMs", clickDelayMs)
        put("targets", JSONArray().also { a -> targets.take(MAX_TARGETS).forEach { a.put(it.toJson()) } })
    }

    companion object {
        const val MAX_TARGETS = 10

        fun fromJson(o: JSONObject): MacroModel {
            val storedMode = o.optInt("mode", MacroMode.CLICK)
            val targets = mutableListOf<MacroTarget>()
            o.optJSONArray("targets")?.let { a ->
                for (i in 0 until minOf(MAX_TARGETS, a.length())) {
                    a.optJSONObject(i)?.let { targets.add(MacroTarget.fromJson(it)) }
                }
            }
            if (targets.isEmpty()) {
                val oldRect = o.optJSONArray("rect")
                val oldTarget = oldRect?.let {
                    MacroTarget(
                        x = (it.optDouble(0, 0.0) + it.optDouble(2, 1080.0)).toFloat() / 2f,
                        y = (it.optDouble(1, 0.0) + it.optDouble(3, 2400.0)).toFloat() / 2f,
                    )
                } ?: MacroTarget(
                    x = o.optDouble("x2", 540.0).toFloat(),
                    y = o.optDouble("y2", 1300.0).toFloat(),
                )
                targets.add(oldTarget)
            }
            return MacroModel(
                id = o.optString("id", UUID.randomUUID().toString()),
                x = o.optDouble("x", 540.0).toFloat(),
                y = o.optDouble("y", 1400.0).toFloat(),
                sizeDp = o.optInt("sizeDp", o.optInt("size", 56)).coerceIn(32, 96),
                mode = if (storedMode == 3 || o.optString("mode") == "HOLD") MacroMode.HOLD else MacroMode.CLICK,
                enabled = o.optBoolean("enabled", true),
                triggerLocked = o.optBoolean("triggerLocked", true),
                holdDelayMs = o.optInt("holdDelayMs", 10).coerceIn(0, 100),
                pressDurationMs = o.optInt("pressDurationMs", 10).coerceIn(1, 50),
                clickDelayMs = o.optInt("clickDelayMs", 0).coerceIn(0, 50),
                targets = targets,
            )
        }
    }
}

/** Sensitivity: 4 profile theo zone, mỗi zone có hệ số X/Y riêng. */
data class SensiModel(
    var enabled: Boolean = false,
    var accel: Boolean = false,
    var zone: ZonePreset = ZonePreset.FULL,
    var circleX: Float = 540f,
    var circleY: Float = 1200f,
    var circleDiameterDp: Int = 200,
    var circleAlpha: Int = 40,
    var circleLocked: Boolean = true,
    var sx: FloatArray = FloatArray(4) { 1.4f },
    var sy: FloatArray = FloatArray(4) { 1.4f },
) {
    fun zoneIndex(): Int = when (zone) {
        ZonePreset.LEFT -> 1
        ZonePreset.RIGHT -> 2
        ZonePreset.CIRCLE -> 3
        else -> 0
    }

    fun factorX(): Float = sx[zoneIndex()]
    fun factorY(): Float = sy[zoneIndex()]

    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("accel", accel)
        put("zone", zone.name)
        put("circleX", circleX.toDouble())
        put("circleY", circleY.toDouble())
        put("circleDiameterDp", circleDiameterDp)
        put("circleAlpha", circleAlpha)
        put("circleLocked", circleLocked)
        put("sx", JSONArray().also { a -> sx.forEach { a.put(it.toDouble()) } })
        put("sy", JSONArray().also { a -> sy.forEach { a.put(it.toDouble()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): SensiModel {
            val sx = FloatArray(4) { 1.4f }
            val sy = FloatArray(4) { 1.4f }
            o.optJSONArray("sx")?.let { a -> for (i in 0 until minOf(4, a.length())) sx[i] = a.optDouble(i, 1.4).toFloat() }
            o.optJSONArray("sy")?.let { a -> for (i in 0 until minOf(4, a.length())) sy[i] = a.optDouble(i, 1.4).toFloat() }
            return SensiModel(
                enabled = o.optBoolean("enabled", false),
                accel = o.optBoolean("accel", false),
                zone = ZonePreset.of(o.optString("zone")),
                circleX = o.optDouble("circleX", o.optJSONArray("rect")?.let {
                    (it.optDouble(0, 0.0) + it.optDouble(2, 1080.0)) / 2.0
                } ?: 540.0).toFloat(),
                circleY = o.optDouble("circleY", o.optJSONArray("rect")?.let {
                    (it.optDouble(1, 0.0) + it.optDouble(3, 2400.0)) / 2.0
                } ?: 1200.0).toFloat(),
                circleDiameterDp = o.optInt("circleDiameterDp", 200).coerceIn(80, 600),
                circleAlpha = o.optInt("circleAlpha", 40).coerceIn(0, 100),
                circleLocked = o.optBoolean("circleLocked", true),
                sx = sx,
                sy = sy,
            )
        }
    }
}

enum class DragKind(val label: String, val cfgSign: Int) {
    DRAG("Drag", -1),
    STABLE("Stable aim", 1),
    FIRE("Fire", 1);

    companion object {
        fun of(name: String?): DragKind = entries.firstOrNull { it.name == name } ?: DRAG
    }
}

/** Điểm drag / stable aim. */
data class DragModel(
    var kind: DragKind = DragKind.DRAG,
    var on: Boolean = false,
    var x: Float = 300f,
    var y: Float = 1200f,
    var size: Int = 120,
    var strength: Int = if (kind == DragKind.DRAG) 3 else 50,
    var opacity: Int = 40,
    var delay: Int = if (kind == DragKind.DRAG) 0 else 100,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("kind", kind.name)
        put("on", on)
        put("x", x.toDouble())
        put("y", y.toDouble())
        put("size", size)
        put("strength", strength)
        put("opacity", opacity)
        put("delay", delay)
    }

    companion object {
        fun fromJson(o: JSONObject): DragModel {
            val kind = DragKind.of(o.optString("kind"))
            return DragModel(
                kind = kind,
                on = o.optBoolean("on", false),
                x = o.optDouble("x", 300.0).toFloat(),
                y = o.optDouble("y", 1200.0).toFloat(),
                size = o.optInt("size", 120),
                strength = o.optInt("strength", if (kind == DragKind.DRAG) 3 else 50),
                opacity = o.optInt("opacity", 40),
                delay = o.optInt("delay", if (kind == DragKind.DRAG) 0 else 100),
            )
        }
    }
}

/** Toàn bộ trạng thái app (được lưu thành 1 JSON trong SharedPreferences). */
data class AppState(
    var macros: MutableList<MacroModel> = mutableListOf(),
    var sensi: SensiModel = SensiModel(),
    var drags: MutableList<DragModel> = mutableListOf(DragModel(DragKind.DRAG), DragModel(DragKind.STABLE)),
    var overlayEnabled: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("macros", JSONArray().also { a -> macros.forEach { a.put(it.toJson()) } })
        put("sensi", sensi.toJson())
        put("drags", JSONArray().also { a -> drags.forEach { a.put(it.toJson()) } })
        put("overlay", overlayEnabled)
    }

    companion object {
        fun fromJson(o: JSONObject): AppState {
            val macros = mutableListOf<MacroModel>()
            o.optJSONArray("macros")?.let { a ->
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { macros.add(MacroModel.fromJson(it)) }
            }
            val drags = mutableListOf<DragModel>()
            o.optJSONArray("drags")?.let { a ->
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { drags.add(DragModel.fromJson(it)) }
            }
            if (drags.isEmpty()) {
                drags.add(DragModel(DragKind.DRAG))
                drags.add(DragModel(DragKind.STABLE))
            }
            return AppState(
                macros = macros,
                sensi = o.optJSONObject("sensi")?.let { SensiModel.fromJson(it) } ?: SensiModel(),
                drags = drags,
                overlayEnabled = o.optBoolean("overlay", false),
            )
        }
    }
}
