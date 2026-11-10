package dev.touchlab.ui

import android.graphics.RectF
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.touchlab.config.TouchConfigManager
import dev.touchlab.lab.LabStats
import dev.touchlab.lab.TouchSampler
import dev.touchlab.model.AppState
import dev.touchlab.model.DragKind
import dev.touchlab.model.MacroMode
import dev.touchlab.model.ZonePreset
import dev.touchlab.shizuku.ShizukuHelper

/* ------------------------------------------------------------------ Trang chủ */

@Composable
fun HomeScreen(
    status: ShizukuHelper.Status,
    overlayRunning: Boolean,
    overlayPermitted: Boolean,
    engineLog: String,
    onRequestShizuku: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onStartOverlay: () -> Unit,
    onStopOverlay: () -> Unit,
    onRefresh: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("TouchLab", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Macro + sensitivity qua /dev/uinput, chạy bằng Shizuku (không cần root).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )

        SectionTitle("Trạng thái")
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                StatusRow("Shizuku", status.label, ok = status == ShizukuHelper.Status.READY)
                StatusRow("Quyền hiển thị trên app khác", if (overlayPermitted) "Đã cấp" else "Chưa cấp", overlayPermitted)
                StatusRow("Overlay", if (overlayRunning) "Đang chạy" else "Đã tắt", overlayRunning)
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (status != ShizukuHelper.Status.READY) {
                Button(onClick = onRequestShizuku, modifier = Modifier.weight(1f)) { Text("Cấp quyền Shizuku") }
            }
            if (!overlayPermitted) {
                OutlinedButton(onClick = onOpenOverlaySettings, modifier = Modifier.weight(1f)) {
                    Text("Cấp quyền overlay")
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onStartOverlay,
                enabled = !overlayRunning,
                modifier = Modifier.weight(1f),
            ) { Text("Bật overlay") }
            OutlinedButton(
                onClick = onStopOverlay,
                enabled = overlayRunning,
                modifier = Modifier.weight(1f),
            ) { Text("Tắt overlay") }
            TextButton(onClick = onRefresh) { Text("Làm mới") }
        }

        SectionTitle("Hướng dẫn nhanh")
        Text(
            "1. Cài Shizuku (Play Store / GitHub), bật dịch vụ bằng adb hoặc root.\n" +
                "2. Cấp quyền Shizuku + quyền hiển thị trên ứng dụng khác cho TouchLab.\n" +
                "3. Bấm \"Bật overlay\" → nút TL xuất hiện, chạm để mở panel cấu hình.\n" +
                "4. Kéo nút macro tới vị trí mong muốn, chạm để bắn macro vào game.\n\n" +
                "Lưu ý: engine cần /dev/uinput. Nếu máy chặn (một số ROM), log sẽ báo " +
                "\"không mở được /dev/uinput\".",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
        )

        SectionTitle("Log engine (40 dòng cuối)")
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0B0F14))) {
            Text(
                text = engineLog.ifBlank { "(chưa có log — bật overlay để engine chạy)" },
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = Color(0xFF9CCC65),
                modifier = Modifier.fillMaxWidth().padding(10.dp),
            )
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (ok) Color(0xFF81C784) else Color(0xFFE57373))
        )
        Spacer(Modifier.width(6.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

/* ------------------------------------------------------------------ Macro */

@Composable
fun MacroScreen(
    state: AppState,
    screenW: Int,
    screenH: Int,
    onEdit: () -> Unit,
    onPickZone: (String, RectF?, (RectF) -> Unit) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Macro", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "Kéo nút trong khung xem trước để đổi vị trí. Nút trên overlay cũng kéo được trực tiếp.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(10.dp))
        ScreenPreview(state, screenW, screenH, onEdit)

        state.macros.forEachIndexed { index, macro ->
            Card(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Nút ${index + 1}", fontWeight = FontWeight.Medium)
                        Spacer(Modifier.weight(1f))
                        Switch(checked = macro.enabled, onCheckedChange = {
                            macro.enabled = it
                            onEdit()
                        })
                        TextButton(onClick = {
                            state.macros.removeAt(index)
                            onEdit()
                        }) { Text("Xoá", color = MaterialTheme.colorScheme.error) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MacroMode.LABELS.forEachIndexed { mode, label ->
                            FilterChip(
                                selected = macro.mode == mode,
                                onClick = {
                                    macro.mode = mode
                                    onEdit()
                                },
                                label = { Text(label, fontSize = 10.sp) },
                            )
                        }
                    }
                    LabeledSlider(
                        label = "Kích thước: ${macro.size}px",
                        value = macro.size.toFloat(),
                        range = TouchConfigManager.SIZE_MIN.toFloat()..TouchConfigManager.SIZE_MAX.toFloat(),
                    ) {
                        macro.size = it.toInt()
                        onEdit()
                    }
                    ZonePicker(current = macro.zone, onPick = {
                        macro.zone = it
                        onEdit()
                    }, onCustom = {
                        onPickZone("Vùng của nút macro", macro.customRect) { r ->
                            macro.customRect = r
                            macro.zone = ZonePreset.CUSTOM
                            onEdit()
                        }
                    })
                    Text(
                        "Vị trí: ${macro.x.toInt()}, ${macro.y.toInt()}  •  trigger: ${macro.x2.toInt()}, ${macro.y2.toInt()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
        }

        if (state.macros.size < TouchConfigManager.MAX_MACROS) {
            OutlinedButton(
                onClick = {
                    state.macros.add(
                        dev.touchlab.model.MacroModel(
                            x = screenW * 0.7f,
                            y = screenH * 0.5f,
                            x2 = screenW * 0.7f,
                            y2 = screenH * 0.42f,
                        )
                    )
                    onEdit()
                },
                modifier = Modifier.padding(top = 10.dp),
            ) { Text("+ Thêm macro") }
        }
    }
}

/** Khung xem trước tỉ lệ màn hình, kéo nút để đổi vị trí. */
@Composable
private fun ScreenPreview(state: AppState, screenW: Int, screenH: Int, onEdit: () -> Unit) {
    var selected by remember { mutableIntStateOf(-1) }
    var dragTick by remember { mutableIntStateOf(0) }
    val aspect = if (screenH > 0) screenW.toFloat() / screenH else 0.46f

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0A0E13))
            .border(1.dp, Color(0xFF2A3542), RoundedCornerShape(10.dp)),
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { constraints.maxWidth.toFloat() }
        val scale = if (screenW > 0) widthPx / screenW else 1f

        val zoneRect = state.sensi.zone.rectOf(screenW, screenH, state.sensi.customRect)
        Box(
            Modifier
                .offset { IntOffset((zoneRect.left * scale).toInt(), (zoneRect.top * scale).toInt()) }
                .size(
                    with(density) { (zoneRect.width() * scale).toDp() },
                    with(density) { (zoneRect.height() * scale).toDp() },
                )
                .border(1.dp, Color(0x664FC3F7)),
        )

        // key(dragTick): vẽ lại các nút sau mỗi bước kéo.
        key(dragTick) {
            state.macros.forEachIndexed { index, macro ->
                val d = with(density) { (macro.size * scale).toDp() }
                Box(
                    Modifier
                        .offset {
                            IntOffset(
                                ((macro.x - macro.size / 2f) * scale).toInt(),
                                ((macro.y - macro.size / 2f) * scale).toInt(),
                            )
                        }
                        .size(d)
                        .clip(CircleShape)
                        .background(
                            if (index == selected) Color(0xFF4FC3F7)
                            else Color(0xCC607D8B)
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${index + 1}", fontSize = 9.sp, color = Color.White)
                }
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(state.macros.size) {
                    detectDragGestures(
                        onDragStart = { off ->
                            val sx = off.x / scale
                            val sy = off.y / scale
                            selected = state.macros.indexOfFirst { m ->
                                val dx = m.x - sx
                                val dy = m.y - sy
                                dx * dx + dy * dy <= (m.size / 2f + 40f) * (m.size / 2f + 40f)
                            }
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            state.macros.getOrNull(selected)?.let { m ->
                                m.x = (m.x + amount.x / scale).coerceIn(0f, screenW.toFloat())
                                m.y = (m.y + amount.y / scale).coerceIn(0f, screenH.toFloat())
                                dragTick++
                            }
                        },
                        onDragEnd = { onEdit() },
                        onDragCancel = { onEdit() },
                    )
                }
        )
    }
}

/* ------------------------------------------------------------------ Sensitivity */

@Composable
fun SensiScreen(
    state: AppState,
    onEdit: () -> Unit,
    onPickZone: (String, RectF?, (RectF) -> Unit) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Sensitivity", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "Hệ số gốc 1.0 – 3.45 (1.0 = không đổi). Accel thêm gain phi tuyến khi kéo nhanh.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(8.dp))

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp)) {
                SwitchRow("Bật sensitivity", state.sensi.enabled) {
                    state.sensi.enabled = it
                    onEdit()
                }
                SwitchRow("Accel phi tuyến", state.sensi.accel) {
                    state.sensi.accel = it
                    onEdit()
                }
                ZonePicker(current = state.sensi.zone, onPick = {
                    state.sensi.zone = it
                    onEdit()
                }, onCustom = {
                    onPickZone("Vùng sensitivity", state.sensi.customRect) { r ->
                        state.sensi.customRect = r
                        state.sensi.zone = ZonePreset.CUSTOM
                        onEdit()
                    }
                })
            }
        }

        SectionTitle("Hệ số theo zone")
        val zoneNames = listOf("Zone 0 (toàn màn hình)", "Zone 1 (trái)", "Zone 2 (phải)", "Zone 3 (custom)")
        repeat(4) { i ->
            Card(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(zoneNames[i], fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    LabeledSlider(
                        label = "X: ${fmt(state.sensi.sx[i])}",
                        value = state.sensi.sx[i],
                        range = TouchConfigManager.SENSI_MIN..TouchConfigManager.SENSI_MAX,
                    ) {
                        state.sensi.sx[i] = it
                        onEdit()
                    }
                    LabeledSlider(
                        label = "Y: ${fmt(state.sensi.sy[i])}",
                        value = state.sensi.sy[i],
                        range = TouchConfigManager.SENSI_MIN..TouchConfigManager.SENSI_MAX,
                    ) {
                        state.sensi.sy[i] = it
                        onEdit()
                    }
                }
            }
        }

        SectionTitle("Drag / Aim")
        state.drags.forEach { drag ->
            Card(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(12.dp)) {
                    SwitchRow(drag.kind.label, drag.on) {
                        drag.on = it
                        onEdit()
                    }
                    LabeledSlider(
                        label = "Lực: ${drag.strength}",
                        value = drag.strength.toFloat(),
                        range = 1f..100f,
                    ) {
                        drag.strength = it.toInt()
                        onEdit()
                    }
                    LabeledSlider(
                        label = "Kích thước: ${drag.size}px",
                        value = drag.size.toFloat(),
                        range = 40f..300f,
                    ) {
                        drag.size = it.toInt()
                        onEdit()
                    }
                    if (drag.kind != DragKind.DRAG) {
                        LabeledSlider(
                            label = "Độ trễ: ${drag.delay}ms",
                            value = drag.delay.toFloat(),
                            range = 0f..300f,
                        ) {
                            drag.delay = it.toInt()
                            onEdit()
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

private fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.2f", v)

/* ------------------------------------------------------------------ Game Lab */

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GameLabScreen() {
    val sampler = remember { TouchSampler() }
    var stats by remember { mutableStateOf(LabStats()) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Game Lab", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "Chạm và kéo trong khung để đo sampling rate cảm ứng và thời gian giữ. " +
                "Số liệu càng ổn định thì macro/injection càng chính xác.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(10.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .height(300.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF0F1620))
                .border(1.dp, Color(0xFF2A3542), RoundedCornerShape(12.dp))
                .pointerInteropFilter { ev ->
                    val history = LongArray(ev.historySize) { ev.getHistoricalEventTime(it) }
                    sampler.onEvent(ev.actionMasked, ev.eventTime, history)
                    if (ev.actionMasked == MotionEvent.ACTION_UP || sampler.sampleCount % 6 == 0) {
                        stats = sampler.stats()
                    }
                    true
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Chạm / kéo trong vùng này",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }

        SectionTitle("Kết quả")
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                InfoLine("Số mẫu", "${stats.samples}")
                InfoLine("Sampling rate", "%.1f Hz".format(java.util.Locale.US, stats.hz))
                InfoLine("Khoảng cách TB", "%.2f ms".format(java.util.Locale.US, stats.avgIntervalMs))
                InfoLine("Jitter (độ lệch)", "%.2f ms".format(java.util.Locale.US, stats.jitterMs))
                InfoLine(
                    "Min / Max",
                    "%.1f / %.1f ms".format(
                        java.util.Locale.US, stats.minIntervalMs, stats.maxIntervalMs
                    ),
                )
                InfoLine("Thời gian giữ", "%.1f ms".format(java.util.Locale.US, stats.holdMs))
                InfoLine("Số lần move", "${stats.moves}")
            }
        }

        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                sampler.reset()
                stats = LabStats()
            }) { Text("Đo lại") }
        }

        Text(
            "Gợi ý: sampling rate < 120 Hz thường làm macro tap bị hụt; jitter > 4 ms thì " +
                "cảm ứng không ổn định (nền màn hình ướt/bẩn hoặc app tối ưu quá tay).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}
