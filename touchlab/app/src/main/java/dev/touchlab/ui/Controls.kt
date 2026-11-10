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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.touchlab.model.ZonePreset

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
    onCustom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("Vùng", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f), fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(ZonePreset.FULL, ZonePreset.LEFT, ZonePreset.RIGHT).forEach { z ->
                FilterChip(
                    selected = current == z,
                    onClick = { onPick(z) },
                    label = { Text(z.label, fontSize = 10.sp) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(ZonePreset.TOP, ZonePreset.BOTTOM).forEach { z ->
                FilterChip(
                    selected = current == z,
                    onClick = { onPick(z) },
                    label = { Text(z.label, fontSize = 10.sp) },
                )
            }
            FilterChip(
                selected = current == ZonePreset.CUSTOM,
                onClick = onCustom,
                label = { Text("Tự chọn", fontSize = 10.sp) },
            )
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
