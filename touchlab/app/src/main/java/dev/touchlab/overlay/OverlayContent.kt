package dev.touchlab.overlay

import android.graphics.RectF
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.touchlab.config.TouchConfigManager
import dev.touchlab.model.AppState
import dev.touchlab.model.DragKind
import dev.touchlab.model.MacroMode
import dev.touchlab.ui.LabeledSlider
import dev.touchlab.ui.MacroSettings
import dev.touchlab.ui.SectionTitle
import dev.touchlab.ui.SensiSettings
import dev.touchlab.ui.SwitchRow

/* ------------------------------------------------------------------ nút trên overlay */

/**
 * Nút macro hình tròn. Kéo để di chuyển (cửa sổ overlay đi theo), chạm để bắn macro.
 *
 * @param holdActive trigger Hold đang được nhấn
 */
@Composable
fun MacroButtonContent(
    index: Int,
    size: Int,
    mode: Int,
    locked: Boolean,
    holdActive: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    onTap: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    var pressed by remember(index) { mutableStateOf(holdActive) }
    val accent = when (index) {
        0 -> Color(0xFF4FC3F7)
        1 -> Color(0xFFFFB74D)
        2 -> Color(0xFF81C784)
        3 -> Color(0xFFE57373)
        else -> Color(0xFFBA68C8)
    }
    Box(
        Modifier
            .size(with(androidx.compose.ui.platform.LocalDensity.current) { size.toDp() })
            .clip(CircleShape)
            .background(if (pressed) accent else accent.copy(alpha = 0.45f))
            .pointerInput(index, locked) {
                if (!locked) {
                    detectDragGestures(
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragEnd,
                    ) { change, amount ->
                        change.consume()
                        onDrag(amount.x, amount.y)
                    }
                }
            }
            .pointerInput(index, mode) {
                detectTapGestures(
                    onPress = {
                        if (mode == MacroMode.HOLD) {
                            pressed = true
                            onPress()
                        }
                        val released = tryAwaitRelease()
                        if (mode == MacroMode.HOLD) {
                            pressed = false
                            onRelease()
                        }
                        else if (released) onTap()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (holdActive) "H" else "${index + 1}",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )
    }
}

@Composable
fun MacroTargetContent(
    index: Int,
    size: Int,
    locked: Boolean,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    Box(
        Modifier
            .size(with(androidx.compose.ui.platform.LocalDensity.current) { size.toDp() })
            .clip(CircleShape)
            .background(Color(0x8856D6C9))
            .pointerInput(index, locked) {
                if (!locked) {
                    detectDragGestures(
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragEnd,
                    ) { change, amount ->
                        change.consume()
                        onDrag(amount.x, amount.y)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("${index + 1}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
fun SensiCircleContent(
    diameter: Int,
    alpha: Int,
    locked: Boolean,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    Box(
        Modifier
            .size(with(androidx.compose.ui.platform.LocalDensity.current) { diameter.toDp() })
            .clip(CircleShape)
            .background(Color(0xFF4FC3F7).copy(alpha = alpha.coerceIn(0, 100) / 100f))
            .pointerInput(locked) {
                if (!locked) {
                    detectDragGestures(
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragEnd,
                    ) { change, amount ->
                        change.consume()
                        onDrag(amount.x, amount.y)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                Color(0xFF4FC3F7).copy(alpha = alpha.coerceIn(0, 100) / 100f),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}

/** Nút mở panel cấu hình, kéo được. */
@Composable
fun MenuButtonContent(onTap: () -> Unit, onDrag: (Float, Float) -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .clip(CircleShape)
            .background(Color(0xCC212121))
            .pointerInput(Unit) {
                detectDragGestures { change, amount ->
                    change.consume()
                    onDrag(amount.x, amount.y)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onTap() })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("TL", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

/**
 * Lớp phủ toàn màn hình chuyển touch thật thành lệnh cho engine để engine bơm lại
 * với hệ số sensitivity. Chỉ dùng khi bật sensitivity (nó sẽ chặn touch trực tiếp của game,
 * đó là cách các app kiểu này hoạt động).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SensiTouchLayer(
    onBegin: (Float, Float) -> Unit,
    onMove: (Float, Float) -> Unit,
    onEnd: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .pointerInteropFilter { ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> onBegin(ev.x, ev.y)
                    MotionEvent.ACTION_MOVE -> onMove(ev.x, ev.y)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onEnd()
                }
                true
            }
    )
}

/* ------------------------------------------------------------------ panel cấu hình */

/**
 * Panel cấu hình hiện trên overlay.
 *
 * Toàn bộ chỉnh sửa đều ghi vào [AppState] dùng chung rồi gọi [onEdit]; service sẽ lưu
 * và đẩy config xuống engine (gộp 400 ms).
 */
@Composable
fun OverlayPanelContent(
    state: AppState,
    screenW: Int,
    screenH: Int,
    engineOk: Boolean,
    engineMessage: String?,
    onEdit: () -> Unit,
    onApply: () -> Unit,
    onAddMacro: () -> Unit,
    onRemoveMacro: (Int) -> Unit,
    onRestartEngine: () -> Unit,
    onClose: () -> Unit,
    onStop: () -> Unit,
) {
    // AppState là object mutable thường. `tick` là bộ đếm buộc panel recompose sau mỗi
    // lần sửa; nó được đọc qua key của remember() nên là một "read" thật trong composition.
    var tick by remember { mutableIntStateOf(0) }
    val macroCount = remember(tick) { state.macros.size }
    val changed = {
        tick++
        onEdit()
    }
    val scroll = rememberScrollState()

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xB3000000))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClose() },
        contentAlignment = Alignment.Center,
    ) {
        Card(
            Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.86f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { /* chặn tap xuyên xuống scrim */ },
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("TouchLab", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = if (engineOk) "engine OK" else "engine lỗi",
                        color = if (engineOk) Color(0xFF81C784) else Color(0xFFE57373),
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("Đóng", color = Color(0xFF90CAF9)) }
                }
                if (!engineOk && engineMessage != null) {
                    Text(
                        engineMessage,
                        color = Color(0xFFFFB74D),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }

                Column(Modifier.weight(1f).verticalScroll(scroll)) {
                    SectionTitle("Macro · $macroCount/${TouchConfigManager.MAX_MACROS}")
                    Card(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2630)),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            MacroSettings(
                                state = state,
                                screenW = screenW,
                                screenH = screenH,
                                onChanged = changed,
                                onAddMacro = { onAddMacro(); tick++ },
                                onRemoveMacro = { onRemoveMacro(it); tick++ },
                            )
                        }
                    }

                    SectionTitle("Sensi · độc lập với Macro")
                    Card(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2630)),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            SensiSettings(state.sensi, screenW, screenH, changed)
                        }
                    }

                    SectionTitle("Drag / Aim")
                    state.drags.forEach { drag ->
                        Card(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2630)),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                SwitchRow(drag.kind.label, drag.on) {
                                    drag.on = it
                                    changed()
                                }
                                LabeledSlider(
                                    label = "Lực: ${drag.strength}",
                                    value = drag.strength.toFloat(),
                                    range = 1f..100f,
                                ) {
                                    drag.strength = it.toInt()
                                    changed()
                                }
                                LabeledSlider(
                                    label = "Kích thước: ${drag.size}px",
                                    value = drag.size.toFloat(),
                                    range = 40f..300f,
                                ) {
                                    drag.size = it.toInt()
                                    changed()
                                }
                                if (drag.kind != DragKind.DRAG) {
                                    LabeledSlider(
                                        label = "Độ trễ: ${drag.delay}ms",
                                        value = drag.delay.toFloat(),
                                        range = 0f..300f,
                                    ) {
                                        drag.delay = it.toInt()
                                        changed()
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = onApply, modifier = Modifier.weight(1f)) { Text("Áp dụng") }
                    OutlinedButton(onClick = onRestartEngine) { Text("Khởi động lại engine") }
                    OutlinedButton(onClick = onStop) { Text("Dừng") }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ editor vùng */

/**
 * Màn hình kéo chọn vùng (dùng cho "custom zone").
 * Toạ độ trả về là px màn hình.
 */
@Composable
fun ZoneEditorContent(
    title: String,
    initial: RectF?,
    onCancel: () -> Unit,
    onSave: (RectF) -> Unit,
) {
    var rect by remember { mutableStateOf(initial?.let { RectF(it) }) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }

    Box(Modifier.fillMaxSize().background(Color(0x99000000))) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { dragStart = it },
                        onDragEnd = {
                            val s = dragStart
                            val r = rect
                            if (s != null && r != null) {
                                val norm = normalize(s, Offset(r.right, r.bottom))
                                rect = if (norm.width() < 40f || norm.height() < 40f) null else norm
                            }
                            dragStart = null
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val s = dragStart
                            if (s != null) rect = normalize(s, change.position)
                        },
                    )
                },
        ) {
            canvasSize = size
            val r = rect
            if (r != null) {
                drawRect(
                    color = Color(0x334FC3F7),
                    topLeft = Offset(r.left, r.top),
                    size = Size(r.width(), r.height()),
                )
                drawRect(
                    color = Color(0xFF4FC3F7),
                    topLeft = Offset(r.left, r.top),
                    size = Size(r.width(), r.height()),
                    style = Stroke(width = 4f),
                )
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .align(Alignment.TopCenter),
        ) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                "Kéo trên màn hình để vẽ vùng. Vùng hiện tại: " +
                    (rect?.let {
                        "${it.left.toInt()},${it.top.toInt()} → ${it.right.toInt()},${it.bottom.toInt()}"
                    } ?: "chưa có"),
                color = Color(0xFFBDBDBD),
                fontSize = 12.sp,
            )
        }

        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Huỷ") }
            Button(
                onClick = { rect?.let(onSave) },
                enabled = rect != null,
                modifier = Modifier.weight(1f),
            ) { Text("Lưu vùng") }
        }
    }
}

private fun fmt2(v: Float): String = String.format(java.util.Locale.US, "%.2f", v)

private fun normalize(a: Offset, b: Offset): RectF = RectF(
    minOf(a.x, b.x),
    minOf(a.y, b.y),
    maxOf(a.x, b.x),
    maxOf(a.y, b.y),
)
