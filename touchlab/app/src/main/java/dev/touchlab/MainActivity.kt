package dev.touchlab

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
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
import dev.touchlab.shizuku.ShizukuHelper
import dev.touchlab.ui.GameLabScreen
import dev.touchlab.ui.HomeScreen
import dev.touchlab.ui.MacroScreen
import dev.touchlab.ui.SensiScreen
import dev.touchlab.ui.TouchLabTheme
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var config: TouchConfigManager

    private var shizukuStatus by mutableStateOf(ShizukuHelper.Status.NO_SERVICE)
    private var overlayRunning by mutableStateOf(false)
    private var logText by mutableStateOf("")
    private var screenSize by mutableStateOf(0 to 0)
    private val io = Executors.newSingleThreadExecutor()
    private var stateRevision by mutableIntStateOf(0)

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
        screenSize = config.screenSize()
        refreshState()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            TouchLabTheme {
                App(
                    config = config,
                    shizukuStatus = shizukuStatus,
                    overlayRunning = overlayRunning,
                    logText = logText,
                    screenW = screenSize.first,
                    screenH = screenSize.second,
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
                    revision = stateRevision,
                    bumpRevision = { stateRevision++ },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        runCatching { Shizuku.addRequestPermissionResultListener(permissionListener) }
    }

    override fun onStop() {
        runCatching { Shizuku.removeRequestPermissionResultListener(permissionListener) }
        config.flushPendingSave()
        super.onStop()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // Quay lại từ màn hình cấp quyền overlay → cập nhật lại trạng thái.
        config.reload()
        screenSize = config.screenSize()
        stateRevision++
        refreshState()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        screenSize = config.screenSize()
    }

    private fun refreshState() {
        val status = ShizukuHelper.status()
        shizukuStatus = status
        overlayRunning = OverlayService.isRunning
        if (status != ShizukuHelper.Status.READY) {
            logText = ""
            return
        }
        io.execute {
            val latestLog = EngineController.logTail(40)
            runOnUiThread {
                if (shizukuStatus == ShizukuHelper.Status.READY) logText = latestLog
            }
        }
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
    screenW: Int,
    screenH: Int,
    onRequestShizuku: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onStartOverlay: () -> Unit,
    onStopOverlay: () -> Unit,
    onRefresh: () -> Unit,
    revision: Int,
    bumpRevision: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    // `revision` là key của remember → đọc nó ở đây để cả cây recompose sau mỗi lần sửa state.
    val state = remember(revision) { config.state }
    val onEdit: () -> Unit = {
        config.saveDebounced()
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
                1 -> MacroScreen(state, screenW, screenH, onEdit)
                2 -> SensiScreen(state, screenW, screenH, onEdit)
                else -> GameLabScreen()
            }
        }
    }
}
