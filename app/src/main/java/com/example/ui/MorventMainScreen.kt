package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.ui.components.ApiKeySettingsDialog
import com.example.ui.components.MorventAiOrb
import com.example.ui.screens.AppsScreen
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.ControlHubScreen
import com.example.ui.screens.ScenariosScreen
import com.example.ui.theme.MorventAmber
import com.example.ui.theme.MorventCyan
import com.example.ui.theme.MorventDarkBg
import com.example.ui.theme.MorventDarkBorder
import com.example.ui.theme.MorventDarkSurface
import com.example.ui.theme.MorventDarkSurfaceElevated
import com.example.ui.theme.MorventEmerald
import com.example.ui.theme.MorventPurple
import com.example.ui.theme.MorventRose

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MorventMainScreen(
    viewModel: MorventViewModel,
    userDisplayName: String? = null,
    userEmail: String? = null,
    onSignOut: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val activeTab by viewModel.activeTab.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val deviceStatus by viewModel.deviceStatus.collectAsState()
    val isThinking by viewModel.isThinking.collectAsState()
    val isTtsEnabled by viewModel.isTtsEnabled.collectAsState()
    val installedApps by viewModel.installedApps.collectAsState()
    val isFloatingRunning by viewModel.isFloatingServiceRunning.collectAsState()
    val isHumanPaced by viewModel.isHumanPaced.collectAsState()
    val agentStatus by viewModel.agentStatus.collectAsState()

    val apiKey by viewModel.apiKey.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val isTestingConnection by viewModel.isTestingConnection.collectAsState()
    val connectionTestResult by viewModel.connectionTestResult.collectAsState()
    val cloudTasks by viewModel.cloudTasks.collectAsState()

    var showApiKeyDialog by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshDeviceStatus()
                viewModel.loadInstalledApps()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(MorventDarkBg),
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 4.dp)
                    ) {
                        MorventAiOrb(
                            isThinking = isThinking,
                            isFlashlightOn = deviceStatus.flashlightOn,
                            onClick = { viewModel.sendCommand("فحص الهاتف") },
                            size = 40.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        androidx.compose.foundation.layout.Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "مورڤينت",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MorventCyan.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = "Artemis Agent",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MorventCyan,
                                        fontSize = 10.sp,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = if (deviceStatus.accessibilityServiceActive) "التحكم بالنظام متاح ✓" else "انقر لتفعيل خدمة الوصول ⚙️",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (deviceStatus.accessibilityServiceActive) MorventEmerald else Color(0xFFEAB308),
                                fontSize = 11.sp,
                                modifier = Modifier.clickable {
                                    if (!deviceStatus.accessibilityServiceActive) {
                                        viewModel.openAccessibilitySettings()
                                    }
                                }
                            )
                        }
                    }
                },
                actions = {
                    // Gemini API Key & Cloud Settings Dialog Trigger
                    IconButton(
                        onClick = { showApiKeyDialog = true },
                        modifier = Modifier.testTag("open_api_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = "مفتاح Gemini API وإعدادات Firebase",
                            tint = if (apiKey.isNotBlank()) MorventEmerald else MorventAmber
                        )
                    }

                    // Floating Background Agent HUD Toggle
                    IconButton(
                        onClick = { viewModel.toggleFloatingService() }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Layers,
                            contentDescription = "العمل في الخلفية",
                            tint = if (isFloatingRunning) MorventCyan else Color.Gray
                        )
                    }

                    // Voice response toggle
                    IconButton(
                        onClick = { viewModel.toggleTts() },
                        modifier = Modifier.testTag("toggle_tts_button")
                    ) {
                        Icon(
                            imageVector = if (isTtsEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                            contentDescription = "الرد الصوتي",
                            tint = if (isTtsEnabled) MorventCyan else Color.Gray
                        )
                    }

                    // Clear chat
                    IconButton(
                        onClick = { viewModel.clearChat() }
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "مسح المحادثة",
                            tint = Color.LightGray
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MorventDarkBg,
                    titleContentColor = Color.White
                ),
                modifier = Modifier.statusBarsPadding()
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MorventDarkSurface,
                modifier = Modifier
                    .navigationBarsPadding()
                    .border(androidx.compose.foundation.BorderStroke(0.5.dp, MorventDarkBorder)),
                tonalElevation = 6.dp
            ) {
                NavigationBarItem(
                    selected = activeTab == MorventTab.CHAT,
                    onClick = { viewModel.setTab(MorventTab.CHAT) },
                    icon = { Icon(Icons.Default.Chat, contentDescription = "شات الأوامر") },
                    label = { Text("الشات") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.Black,
                        selectedTextColor = MorventCyan,
                        indicatorColor = MorventCyan,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray
                    )
                )

                NavigationBarItem(
                    selected = activeTab == MorventTab.CONTROL_HUB,
                    onClick = { viewModel.setTab(MorventTab.CONTROL_HUB) },
                    icon = { Icon(Icons.Default.Tune, contentDescription = "لوحة التحكم") },
                    label = { Text("التحكم") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.Black,
                        selectedTextColor = MorventCyan,
                        indicatorColor = MorventCyan,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray
                    )
                )

                NavigationBarItem(
                    selected = activeTab == MorventTab.APPS,
                    onClick = { viewModel.setTab(MorventTab.APPS) },
                    icon = { Icon(Icons.Default.Apps, contentDescription = "التطبيقات") },
                    label = { Text("التطبيقات") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.Black,
                        selectedTextColor = MorventCyan,
                        indicatorColor = MorventCyan,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray
                    )
                )

                NavigationBarItem(
                    selected = activeTab == MorventTab.SCENARIOS,
                    onClick = { viewModel.setTab(MorventTab.SCENARIOS) },
                    icon = { Icon(Icons.Default.AutoAwesome, contentDescription = "السيناريوهات") },
                    label = { Text("الأوضاع") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.Black,
                        selectedTextColor = MorventCyan,
                        indicatorColor = MorventCyan,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray
                    )
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MorventDarkBg)
        ) {
            when (activeTab) {
                MorventTab.CHAT -> ChatScreen(
                    messages = messages,
                    isThinking = isThinking,
                    agentStatus = agentStatus,
                    onSendCommand = { viewModel.sendCommand(it) },
                    onPauseAgent = { viewModel.pauseAgent() },
                    onResumeAgent = { viewModel.resumeAgent() },
                    onSkipAgent = { viewModel.skipAgentStep() },
                    onStopAgent = { viewModel.stopAgent() },
                    onOpenAccessibilitySettings = { viewModel.openAccessibilitySettings() },
                    onOpenOverlaySettings = { viewModel.openOverlayPermissionSettings() },
                    onOpenWriteSettings = { viewModel.openWriteSettingsPermission() },
                    onOpenDndSettings = { viewModel.openDndPermissionSettings() }
                )
                MorventTab.CONTROL_HUB -> ControlHubScreen(
                    deviceStatus = deviceStatus,
                    onToggleFlashlight = { viewModel.toggleFlashlight() },
                    onSetVolume = { viewModel.setVolume(it) },
                    onSetRingerMode = { viewModel.setRingerMode(it) },
                    onCommand = { viewModel.sendCommand(it) },
                    onRefresh = { viewModel.refreshDeviceStatus() },
                    onOpenAccessibilitySettings = { viewModel.openAccessibilitySettings() },
                    isFloatingRunning = isFloatingRunning,
                    isHumanPaced = isHumanPaced,
                    onToggleFloatingService = { viewModel.toggleFloatingService() },
                    onToggleHumanPacing = { viewModel.toggleHumanPacing() },
                    onSetBrightness = { viewModel.setBrightnessPercent(it) },
                    onSetAutoRotate = { viewModel.setAutoRotate(it) },
                    onSetDoNotDisturb = { viewModel.setDoNotDisturb(it) },
                    onOpenWriteSettings = { viewModel.openWriteSettingsPermission() },
                    onOpenDndSettings = { viewModel.openDndPermissionSettings() },
                    onOpenOverlaySettings = { viewModel.openOverlayPermissionSettings() }
                )
                MorventTab.APPS -> AppsScreen(
                    installedApps = installedApps,
                    onLaunchApp = { pkg, label -> viewModel.launchApp(pkg, label) },
                    onAutomationCommand = {
                        viewModel.setTab(MorventTab.CHAT)
                        viewModel.sendCommand(it)
                    }
                )
                MorventTab.SCENARIOS -> ScenariosScreen(
                    macros = viewModel.macros,
                    deviceStatus = deviceStatus,
                    onRunMacro = { viewModel.runMacro(it) },
                    apiKeyConfigured = apiKey.isNotBlank(),
                    selectedModel = selectedModel,
                    connectionStatus = connectionStatus,
                    syncedTasksCount = cloudTasks.size,
                    onOpenApiSettings = { showApiKeyDialog = true }
                )
            }

            if (showApiKeyDialog) {
                ApiKeySettingsDialog(
                    currentApiKey = apiKey,
                    selectedModel = selectedModel,
                    connectionStatus = connectionStatus,
                    isTestingConnection = isTestingConnection,
                    testResult = connectionTestResult,
                    userDisplayName = userDisplayName,
                    userEmail = userEmail,
                    syncedTasksCount = cloudTasks.size,
                    onSaveApiKey = { viewModel.saveApiKey(it) },
                    onSelectModel = { viewModel.selectAiModel(it) },
                    onTestConnection = { viewModel.testAiConnection(it) },
                    onResetDefaultKey = { viewModel.resetDefaultApiKey() },
                    onClearKey = { viewModel.clearApiKey() },
                    onSignOut = {
                        showApiKeyDialog = false
                        onSignOut()
                    },
                    onDismiss = { showApiKeyDialog = false }
                )
            }
        }
    }
}
