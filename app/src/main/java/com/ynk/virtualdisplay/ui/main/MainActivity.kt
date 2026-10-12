package com.ynk.virtualdisplay.ui.main

import android.os.Bundle
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.koin.androidx.compose.koinViewModel
import com.ynk.virtualdisplay.MyApplication
import com.ynk.virtualdisplay.data.model.ShizukuState
import com.ynk.virtualdisplay.manager.ShizukuManager
import com.ynk.virtualdisplay.ui.screens.SettingsScreen
import com.ynk.virtualdisplay.ui.screens.ShizukuPermissionScreen
import com.ynk.virtualdisplay.ui.screens.VirtualDisplayScreen
import com.ynk.virtualdisplay.ui.theme.MyApplicationTheme
import org.koin.android.ext.android.inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.ynk.virtualdisplay.ui.input.AccessibilityServiceBootstrap
import rikka.shizuku.Shizuku
import rikka.sui.Sui

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private val shizukuManager: ShizukuManager by inject()
    private var accessibilityBootstrapJob: Job? = null
    private var accessibilityFailureToastShown = false

    private val shizukuPermissionResultListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) ensureUnicodeAccessibilityEnabled()
    }
    private val shizukuBinderListener = Shizuku.OnBinderReceivedListener {
        ensureUnicodeAccessibilityEnabled()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionResultListener)
        Shizuku.addBinderReceivedListener(shizukuBinderListener)

        try {
            if (Sui.init(packageName)) {
                Log.i(TAG, "Sui initialized successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sui init failed", e)
        }

        // 首次检查 Shizuku 状态
        shizukuManager.refreshState()

        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainScreen(
                    shizukuManager = shizukuManager,
                    onRequestPermission = {
                        try {
                            Shizuku.requestPermission(ShizukuManager.REQUEST_CODE)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to request permission", e)
                        }
                    },
                    onRetryCheck = {
                        shizukuManager.refreshState()
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Keep this idempotent: if Shizuku is already authorized, the service is
        // enabled automatically; if the user grants Shizuku while this Activity is
        // open, the permission-result listener above retries immediately.
        ensureUnicodeAccessibilityEnabled()
    }

    private fun ensureUnicodeAccessibilityEnabled() {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return
        if (!runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)) return
        if (accessibilityBootstrapJob?.isActive == true) return

        accessibilityBootstrapJob = lifecycleScope.launch(Dispatchers.IO) {
            when (val result = AccessibilityServiceBootstrap.ensureEnabled(applicationContext)) {
                AccessibilityServiceBootstrap.Result.Enabled ->
                    Log.i(TAG, "Unicode accessibility input is enabled through Shizuku")
                AccessibilityServiceBootstrap.Result.ShizukuUnavailable -> Unit
                is AccessibilityServiceBootstrap.Result.Failed -> {
                    Log.e(TAG, "Automatic accessibility enable failed: ${result.reason}")
                    withContext(Dispatchers.Main) {
                        if (!accessibilityFailureToastShown && !isFinishing) {
                            accessibilityFailureToastShown = true
                            Toast.makeText(
                                this@MainActivity,
                                "无法通过 Shizuku 自动启用 Unicode 输入辅助，请检查 Shizuku 是否有修改安全设置的权限。",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionResultListener)
        Shizuku.removeBinderReceivedListener(shizukuBinderListener)
        accessibilityBootstrapJob?.cancel()
        super.onDestroy()
    }
}

@Composable
private fun MainScreen(
    shizukuManager: ShizukuManager,
    onRequestPermission: () -> Unit,
    onRetryCheck: () -> Unit
) {
    val viewModel: MainViewModel = koinViewModel()
    val uiState by viewModel.uiState.collectAsState()

    // 首次进入时尝试连接
    LaunchedEffect(Unit) {
        viewModel.handleIntent(MainIntent.CheckShizuku)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = uiState.currentTab == ScreenTab.CONSOLE,
                    onClick = { viewModel.handleIntent(MainIntent.SwitchTab(ScreenTab.CONSOLE)) },
                    icon = { Icon(Icons.Default.Home, contentDescription = "控制台") },
                    label = { Text("控制台") }
                )
                NavigationBarItem(
                    selected = uiState.currentTab == ScreenTab.SETTINGS,
                    onClick = { viewModel.handleIntent(MainIntent.SwitchTab(ScreenTab.SETTINGS)) },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "设置") },
                    label = { Text("设置") }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (uiState.currentTab) {
                ScreenTab.CONSOLE -> VirtualDisplayScreen(viewModel = viewModel)
                ScreenTab.SETTINGS -> SettingsScreen(viewModel = viewModel)
            }

            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}
