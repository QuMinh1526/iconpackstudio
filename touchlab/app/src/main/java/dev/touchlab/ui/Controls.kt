package dev.touchlab.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.touchlab.config.TouchConfigManager
import dev.touchlab.model.ZonePreset
import dev.touchlab.model.AppState
import dev.touchlab.model.MacroMode
import dev.touchlab.model.MacroModel
import dev.touchlab.model.MacroTarget
import dev.touchlab.model.SensiModel

/* Các control dùng chung giữa màn hình chính và panel overlay. */

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        modifier = modifier.padding(top = 14.dp, bottom = 6.dp),
    )
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f), fontSize = 12.sp)
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
        )
    }
}

/** Chọn vùng: 5 preset + tự vẽ. */
@Composable
fun ZonePicker(
    current: ZonePreset,
    onPick: (ZonePreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("Vùng", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f), fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(ZonePreset.FULL, ZonePreset.LEFT, ZonePreset.RIGHT, ZonePreset.CIRCLE).forEach { z ->
                FilterChip(
                    selected = current == z,
                    onClick = { onPick(z) },
                    label = { Text(z.label, fontSize = 10.sp) },
                )
            }
        }
    }
}

@Composable
fun SensiSettings(
    sensi: SensiModel,
    screenW: Int,
    screenH: Int,
    onChanged: () -> Unit,
) {
    SwitchRow("Bật Sensi", sensi.enabled) {
        sensi.enabled = it
        onChanged()
    }
    if (!sensi.enabled) return

    SwitchRow("Accel phi tuyến", sensi.accel) {
        sensi.accel = it
        onChanged()
    }
    ZonePicker(current = sensi.zone, onPick = {
        sensi.zone = it
        onChanged()
    })
    val zoneIndex = sensi.zoneIndex()
    LabeledSlider(
        label = "Hệ số X (${sensi.zone.label}): ${"%.2f".format(sensi.sx[zoneIndex])}",
        value = sensi.sx[zoneIndex],
        range = 1f..3.45f,
    ) {
        sensi.sx[zoneIndex] = it
        onChanged()
    }
    LabeledSlider(
        label = "Hệ số Y: ${"%.2f".format(sensi.sy[zoneIndex])}",
        value = sensi.sy[zoneIndex],
        range = 1f..3.45f,
    ) {
        sensi.sy[zoneIndex] = it
        onChanged()
    }
    if (sensi.zone == ZonePreset.CIRCLE) {
        LabeledSlider(
            label = "Tâm X: ${sensi.circleX.toInt()} px",
            value = sensi.circleX,
            range = 0f..screenW.coerceAtLeast(1).toFloat(),
        ) {
            sensi.circleX = it
            onChanged()
        }
        LabeledSlider(
            label = "Tâm Y: ${sensi.circleY.toInt()} px",
            value = sensi.circleY,
            range = 0f..screenH.coerceAtLeast(1).toFloat(),
        ) {
            sensi.circleY = it
            onChanged()
        }
        LabeledSlider(
            label = "Đường kính: ${sensi.circleDiameterDp} dp",
            value = sensi.circleDiameterDp.toFloat(),
            range = 80f..600f,
        ) {
            sensi.circleDiameterDp = it.toInt()
            onChanged()
        }
        LabeledSlider(
            label = "Độ đậm: ${sensi.circleAlpha}%",
            value = sensi.circleAlpha.toFloat(),
            range = 0f..100f,
        ) {
            sensi.circleAlpha = it.toInt()
            onChanged()
        }
        SwitchRow("Khoá vị trí vòng tròn", sensi.circleLocked) {
            sensi.circleLocked = it
            onChanged()
        }
    }
}

@Composable
fun MacroSettings(
    state: AppState,
    screenW: Int,
    screenH: Int,
    onChanged: () -> Unit,
    onAddMacro: () -> Unit,
    onRemoveMacro: (Int) -> Unit,
) {
    var expandedMacroId by remember { mutableStateOf<String?>(null) }
    var expandedTargetId by remember(expandedMacroId) { mutableStateOf<String?>(null) }
    state.macros.forEachIndexed { index, macro ->
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Trigger ${index + 1}", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    expandedMacroId = if (expandedMacroId == macro.id) null else macro.id
                }) { Text(if (expandedMacroId == macro.id) "Thu gọn" else "Cài đặt") }
                Switch(checked = macro.enabled, onCheckedChange = {
                    macro.enabled = it
                    onChanged()
                })
                TextButton(onClick = { onRemoveMacro(index) }) { Text("Xoá") }
            }
            if (expandedMacroId == macro.id) {
            LabeledSlider(
                label = "Vị trí X: ${macro.x.toInt()} px",
                value = macro.x,
                range = 0f..screenW.coerceAtLeast(1).toFloat(),
            ) {
                macro.x = it
                onChanged()
            }
            LabeledSlider(
                label = "Vị trí Y: ${macro.y.toInt()} px",
                value = macro.y,
                range = 0f..screenH.coerceAtLeast(1).toFloat(),
            ) {
                macro.y = it
                onChanged()
            }
            LabeledSlider(
                label = "Kích thước trigger: ${macro.sizeDp} dp",
                value = macro.sizeDp.toFloat(),
                range = 32f..96f,
            ) {
                macro.sizeDp = it.toInt()
                onChanged()
            }
            SwitchRow("Khoá vị trí trigger", macro.triggerLocked) {
                macro.triggerLocked = it
                onChanged()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MacroMode.LABELS.forEachIndexed { mode, label ->
                    FilterChip(
                        selected = macro.mode == mode,
                        onClick = {
                            macro.mode = mode
                            onChanged()
                        },
                        label = { Text(label, fontSize = 11.sp) },
                    )
                }
            }
            if (macro.mode == MacroMode.HOLD) {
                LabeledSlider(
                    label = "Nghỉ giữa click: ${macro.holdDelayMs} ms",
                    value = macro.holdDelayMs.toFloat(),
                    range = 0f..100f,
                ) {
                    macro.holdDelayMs = it.toInt()
                    onChanged()
                }
                LabeledSlider(
                    label = "Thời gian nhấn: ${macro.pressDurationMs} ms",
                    value = macro.pressDurationMs.toFloat(),
                    range = 1f..50f,
                ) {
                    macro.pressDurationMs = it.toInt().coerceAtLeast(1)
                    onChanged()
                }
            } else {
                LabeledSlider(
                    label = "Trễ click: ${macro.clickDelayMs} ms",
                    value = macro.clickDelayMs.toFloat(),
                    range = 0f..50f,
                ) {
                    macro.clickDelayMs = it.toInt()
                    onChanged()
                }
            }
            Text("Target click (${macro.targets.size}/${MacroModel.MAX_TARGETS})")
            macro.targets.forEachIndexed { targetIndex, target ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Target ${targetIndex + 1} · ${target.x.toInt()}, ${target.y.toInt()}",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = target.locked, onCheckedChange = {
                        target.locked = it
                        onChanged()
                    })
                    TextButton(onClick = {
                        expandedTargetId = if (expandedTargetId == target.id) null else target.id
                    }) { Text(if (expandedTargetId == target.id) "Ẩn" else "Sửa") }
                    if (macro.targets.size > 1) {
                        TextButton(onClick = {
                            macro.targets.removeAt(targetIndex)
                            if (expandedTargetId == target.id) expandedTargetId = null
                            onChanged()
                        }) { Text("Xoá") }
                    }
                }
                if (expandedTargetId == target.id) {
                    MacroTargetSettings(target, screenW, screenH, onChanged)
                }
            }
            if (macro.targets.size < MacroModel.MAX_TARGETS) {
                TextButton(onClick = {
                    macro.targets.add(
                        MacroTarget(
                            x = screenW * 0.5f,
                            y = screenH * 0.5f,
                        )
                    )
                    onChanged()
                }) { Text("+ Target click") }
            }
            }
        }
    }
    if (state.macros.size < TouchConfigManager.MAX_MACROS) {
        TextButton(onClick = onAddMacro) { Text("+ Thêm trigger") }
    }
}

@Composable
private fun MacroTargetSettings(
    target: MacroTarget,
    screenW: Int,
    screenH: Int,
    onChanged: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp)) {
        LabeledSlider(
            label = "X: ${target.x.toInt()} px",
            value = target.x,
            range = 0f..screenW.coerceAtLeast(1).toFloat(),
        ) {
            target.x = it
            onChanged()
        }
        LabeledSlider(
            label = "Y: ${target.y.toInt()} px",
            value = target.y,
            range = 0f..screenH.coerceAtLeast(1).toFloat(),
        ) {
            target.y = it
            onChanged()
        }
    }
}

/** Nhãn nhỏ + khoảng trống, dùng cho các dòng thông tin. */
@Composable
fun InfoLine(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        Text(value, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
    }
}
