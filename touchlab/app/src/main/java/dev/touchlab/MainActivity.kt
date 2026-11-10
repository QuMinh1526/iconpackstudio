package dev.touchlab

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.touchlab.config.TouchConfigManager
import dev.touchlab.engine.EngineController
import dev.touchlab.overlay.OverlayService
import dev.touchlab.overlay.ZoneEditorContent
import dev.touchlab.shizuku.ShizukuHelper
import dev.touchlab.ui.GameLabScreen
import dev.touchlab.ui.HomeScreen
import dev.touchlab.ui.MacroScreen
import dev.touchlab.ui.SensiScreen
import dev.touchlab.ui.TouchLabTheme
import rikka.shizuku.Shizuku

/** Vùng đang được vẽ trong editor toàn màn hình. */
private data class ZoneTarget(val title: String, val current: RectF?, val onSave: (RectF) -> Unit)

class MainActivity : ComponentActivity() {

    private lateinit var config: TouchConfigManager

    private var shizukuStatus by mutableStateOf(ShizukuHelper.Status.NO_SERVICE)
    private var overlayRunning by mutableStateOf(false)
    private var logText by mutableStateOf("")
    private var zoneTarget by mutableStateOf<ZoneTarget?>(null)

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* không bắt buộc */ }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == ShizukuHelper.PERMISSION_REQUEST_CODE) {
            refreshState()
            toast(
                if (grantResult == PackageManager.PERMISSION_GRANTED) "Đã cấp quyền Shizuku"
                else "Bạn đã từ chối quyền Shizuku"
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = TouchConfigManager(this)
        refreshState()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            TouchLabTheme {
                val tick = remember { mutableIntStateOf(0) }
                App(
                    config = config,
                    shizukuStatus = shizukuStatus,
                    overlayRunning = overlayRunning,
                    logText = logText,
                    onRequestShizuku = {
                        if (!ShizukuHelper.requestPermission()) {
                            toast("Không xin được quyền. Hãy mở app Shizuku và bật dịch vụ trước.")
                        }
                    },
                    onOpenOverlaySettings = { openOverlaySettings() },
                    onStartOverlay = { startOverlay() },
                    onStopOverlay = {
                        OverlayService.stop(this)
                        overlayRunning = false
                    },
                    onRefresh = { refreshState() },
                    onPickZone = { title, current, onSave ->
                        zoneTarget = ZoneTarget(title, current, onSave)
                    },
                    revision = tick.intValue,
                    bumpRevision = { tick.intValue++ },
                )

                zoneTarget?.let { target ->
                    Dialog(
                        onDismissRequest = { zoneTarget = null },
                        properties = DialogProperties(usePlatformDefaultWidth = false),
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            ZoneEditorContent(
                                title = target.title,
                                initial = target.current,
                                onCancel = { zoneTarget = null },
                                onSave = { rect ->
                                    target.onSave(rect)
                                    zoneTarget = null
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        runCatching { Shizuku.addRequestPermissionResultListener(permissionListener) }
    }

    override fun onStop() {
        runCatching { Shizuku.removeRequestPermissionResultListener(permissionListener) }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Quay lại từ màn hình cấp quyền overlay → cập nhật lại trạng thái.
        config.reload()
        refreshState()
    }

    private fun refreshState() {
        shizukuStatus = ShizukuHelper.status()
        overlayRunning = OverlayService.isRunning
        logText = if (shizukuStatus == ShizukuHelper.Status.READY) EngineController.logTail(40) else ""
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        runCatching { startActivity(intent) }
            .onFailure { toast("Không mở được màn hình cấp quyền overlay") }
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            toast("Hãy cấp quyền hiển thị trên ứng dụng khác")
            openOverlaySettings()
            return
        }
        if (shizukuStatus != ShizukuHelper.Status.READY) {
            toast("Cần Shizuku chạy và đã cấp quyền")
            ShizukuHelper.requestPermission()
            return
        }
        OverlayService.start(this)
        overlayRunning = true
        toast("Đang bật overlay…")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

@Composable
private fun App(
    config: TouchConfigManager,
    shizukuStatus: ShizukuHelper.Status,
    overlayRunning: Boolean,
    logText: String,
    onRequestShizuku: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onStartOverlay: () -> Unit,
    onStopOverlay: () -> Unit,
    onRefresh: () -> Unit,
    onPickZone: (String, RectF?, (RectF) -> Unit) -> Unit,
    revision: Int,
    bumpRevision: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    // `revision` là key của remember → đọc nó ở đây để cả cây recompose sau mỗi lần sửa state.
    val state = remember(revision) { config.state }
    val (screenW, screenH) = remember(revision) { config.screenSize() }

    val onEdit: () -> Unit = {
        config.save()
        bumpRevision()
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("Trang chủ") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.Tune, contentDescription = null) },
                    label = { Text("Macro") },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Speed, contentDescription = null) },
                    label = { Text("Sensi") },
                )
                NavigationBarItem(
                    selected = tab == 3,
                    onClick = { tab = 3 },
                    icon = { Icon(Icons.Default.Science, contentDescription = null) },
                    label = { Text("Game Lab") },
                )
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> HomeScreen(
                    status = shizukuStatus,
                    overlayRunning = overlayRunning,
                    overlayPermitted = Settings.canDrawOverlays(
                        androidx.compose.ui.platform.LocalContext.current
                    ),
                    engineLog = logText,
                    onRequestShizuku = onRequestShizuku,
                    onOpenOverlaySettings = onOpenOverlaySettings,
                    onStartOverlay = onStartOverlay,
                    onStopOverlay = onStopOverlay,
                    onRefresh = onRefresh,
                )
                1 -> MacroScreen(state, screenW, screenH, onEdit, onPickZone)
                2 -> SensiScreen(state, onEdit, onPickZone)
                else -> GameLabScreen()
            }
        }
    }
}
