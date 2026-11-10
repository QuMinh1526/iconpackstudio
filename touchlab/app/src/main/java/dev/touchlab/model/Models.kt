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
    TOP("Nửa trên"),
    BOTTOM("Nửa dưới"),
    CUSTOM("Vùng tự chọn");

    /** Zone ghi xuống engine: 0 = toàn màn hình, 1 = trái, 2 = phải, 3 = custom. */
    fun engineZone(): Int = when (this) {
        LEFT -> 1
        RIGHT -> 2
        CUSTOM -> 3
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
            CUSTOM -> custom?.let { RectF(it) } ?: RectF(0f, 0f, w, h)
        }
    }

    companion object {
        fun of(name: String?): ZonePreset = entries.firstOrNull { it.name == name } ?: FULL
    }
}

/** Bảng thời gian giữ theo mode, giống hệt engine gốc: {p0, p1}. */
object MacroMode {
    val P0 = intArrayOf(40, 70, 120, 0)
    val P1 = intArrayOf(0, 6, 30, 40)
    val LABELS = arrayOf("Tap nhanh", "Tap vừa", "Tap chậm", "Giữ (hold)")
}

/**
 * Một nút macro trên overlay.
 *
 * @param x,y tâm nút (px màn hình)
 * @param size đường kính nút (px)
 * @param x2,y2 tâm vùng trigger phụ (chạm vào cũng phát macro)
 * @param mode 0..3, xem [MacroMode]
 * @param zone vùng mà nút được phép nằm trong (dùng cho editor + hiển thị)
 */
data class MacroModel(
    val id: String = UUID.randomUUID().toString(),
    var x: Float = 540f,
    var y: Float = 1400f,
    var size: Int = 56,
    var x2: Float = 540f,
    var y2: Float = 1300f,
    var mode: Int = 0,
    var enabled: Boolean = true,
    var zone: ZonePreset = ZonePreset.FULL,
    var customRect: RectF? = null,
) {
    fun copyOf(): MacroModel = copy(customRect = customRect?.let { RectF(it) })

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("x", x.toDouble())
        put("y", y.toDouble())
        put("size", size)
        put("x2", x2.toDouble())
        put("y2", y2.toDouble())
        put("mode", mode)
        put("enabled", enabled)
        put("zone", zone.name)
        customRect?.let { put("rect", JSONArray().put(it.left).put(it.top).put(it.right).put(it.bottom)) }
    }

    companion object {
        fun fromJson(o: JSONObject): MacroModel = MacroModel(
            id = o.optString("id", UUID.randomUUID().toString()),
            x = o.optDouble("x", 540.0).toFloat(),
            y = o.optDouble("y", 1400.0).toFloat(),
            size = o.optInt("size", 56),
            x2 = o.optDouble("x2", 540.0).toFloat(),
            y2 = o.optDouble("y2", 1300.0).toFloat(),
            mode = o.optInt("mode", 0).coerceIn(0, 3),
            enabled = o.optBoolean("enabled", true),
            zone = ZonePreset.of(o.optString("zone")),
            customRect = o.optJSONArray("rect")?.let {
                RectF(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(),
                    it.getDouble(2).toFloat(), it.getDouble(3).toFloat())
            },
        )
    }
}

/** Sensitivity: 4 profile theo zone, mỗi zone có hệ số X/Y riêng. */
data class SensiModel(
    var enabled: Boolean = false,
    var accel: Boolean = false,
    var zone: ZonePreset = ZonePreset.FULL,
    var customRect: RectF? = null,
    var sx: FloatArray = FloatArray(4) { 1.4f },
    var sy: FloatArray = FloatArray(4) { 1.4f },
) {
    fun zoneIndex(): Int = when (zone) {
        ZonePreset.LEFT -> 1
        ZonePreset.RIGHT -> 2
        ZonePreset.CUSTOM -> 3
        else -> 0
    }

    fun factorX(): Float = sx[zoneIndex()]
    fun factorY(): Float = sy[zoneIndex()]

    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("accel", accel)
        put("zone", zone.name)
        put("sx", JSONArray().also { a -> sx.forEach { a.put(it.toDouble()) } })
        put("sy", JSONArray().also { a -> sy.forEach { a.put(it.toDouble()) } })
        customRect?.let { put("rect", JSONArray().put(it.left).put(it.top).put(it.right).put(it.bottom)) }
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
                customRect = o.optJSONArray("rect")?.let {
                    RectF(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(),
                        it.getDouble(2).toFloat(), it.getDouble(3).toFloat())
                },
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
