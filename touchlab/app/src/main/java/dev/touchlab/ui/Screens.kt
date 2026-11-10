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
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Macro", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "Mỗi trigger chạy target click theo thứ tự. Có thể chỉnh vị trí tại đây hoặc ngay trên overlay TL.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        MacroSettings(
            state = state,
            screenW = screenW,
            screenH = screenH,
            onChanged = onEdit,
            onAddMacro = {
                state.macros.add(
                    dev.touchlab.model.MacroModel(
                        x = screenW * 0.7f,
                        y = screenH * 0.5f,
                        targets = mutableListOf(
                            dev.touchlab.model.MacroTarget(x = screenW * 0.7f, y = screenH * 0.42f)
                        ),
                    )
                )
                onEdit()
            },
            onRemoveMacro = {
                if (it in state.macros.indices) {
                    state.macros.removeAt(it)
                    onEdit()
                }
            },
        )
    }
}

/* ------------------------------------------------------------------ Sensitivity */

@Composable
fun SensiScreen(
    state: AppState,
    screenW: Int,
    screenH: Int,
    onEdit: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Sensitivity", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Sensi có overlay và cấu hình độc lập với Macro.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp)) {
                SensiSettings(state.sensi, screenW, screenH, onEdit)
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

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
