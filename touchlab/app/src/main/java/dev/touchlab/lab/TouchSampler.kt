package dev.touchlab.lab

import android.view.MotionEvent
import kotlin.math.sqrt

/** Số liệu đo được từ một lần chạm. */
data class LabStats(
    val samples: Int = 0,
    val hz: Float = 0f,
    val avgIntervalMs: Float = 0f,
    val jitterMs: Float = 0f,
    val minIntervalMs: Float = 0f,
    val maxIntervalMs: Float = 0f,
    val holdMs: Float = 0f,
    val moves: Int = 0,
)

/**
 * Đo sampling rate của cảm ứng và thời gian giữ (tap latency).
 *
 * MotionEvent có thể gộp nhiều mẫu vào "historical" khi tần số cảm biến cao hơn tần số
 * khung hình, nên phải tính cả historical để ra đúng Hz.
 */
class TouchSampler(private val capacity: Int = 240) {

    private val deltas = ArrayDeque<Long>()
    private var lastTime = 0L
    private var downTime = 0L
    private var holdMs = 0f
    private var moves = 0
    private var total = 0

    val sampleCount: Int get() = total

    fun reset() {
        deltas.clear()
        lastTime = 0L
        downTime = 0L
        holdMs = 0f
        moves = 0
        total = 0
    }

    fun onEvent(action: Int, eventTime: Long, history: LongArray = LongArray(0)) {
        if (action == MotionEvent.ACTION_DOWN) {
            // Mỗi lần đặt ngón là một phiên đo mới.
            deltas.clear()
            lastTime = 0L
            downTime = eventTime
            moves = 0
            total = 0
        }
        history.forEach { addSample(it) }
        addSample(eventTime)
        when (action) {
            MotionEvent.ACTION_MOVE -> moves++
            MotionEvent.ACTION_UP -> holdMs = (eventTime - downTime).toFloat()
        }
    }

    private fun addSample(t: Long) {
        if (lastTime != 0L) {
            val d = t - lastTime
            // Bỏ qua khoảng bất thường (event lỗi, hoặc app bị treo giữa chừng).
            if (d in 1..250) {
                deltas.addLast(d)
                while (deltas.size > capacity) deltas.removeFirst()
            }
        }
        lastTime = t
        total++
    }

    fun stats(): LabStats {
        if (deltas.isEmpty()) {
            return LabStats(samples = total, holdMs = holdMs, moves = moves)
        }
        val n = deltas.size.toFloat()
        val avg = deltas.sum().toFloat() / n
        var variance = 0f
        for (d in deltas) {
            val diff = d.toFloat() - avg
            variance += diff * diff
        }
        variance /= n
        return LabStats(
            samples = total,
            hz = if (avg > 0f) 1000f / avg else 0f,
            avgIntervalMs = avg,
            jitterMs = sqrt(variance),
            minIntervalMs = deltas.min().toFloat(),
            maxIntervalMs = deltas.max().toFloat(),
            holdMs = holdMs,
            moves = moves,
        )
    }
}
