package com.example.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.cloud.CloudTaskRecord
import com.example.cloud.MorventCloudRepository
import com.example.device.AgentBridgeCapabilities
import com.example.device.SystemBridgeManager
import com.example.model.ActionCardData
import com.example.model.ActionType
import com.example.model.ChatMessage
import com.example.model.DeviceStatus
import com.example.model.InstalledApp
import com.example.model.MessageStatus
import com.example.model.MorventMacro
import com.example.nlp.AgentStatus
import com.example.nlp.ApiConnectionTestResult
import com.example.nlp.MorventAgentHub
import com.example.service.CapturedNotificationMessage
import com.example.service.MorventAccessibilityService
import com.example.service.MorventFloatingAgentService
import com.example.service.MorventNotificationListenerService
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class MorventTab {
    CHAT,
    CONTROL_HUB,
    APPS,
    SCENARIOS
}

class MorventViewModel(application: Application) : AndroidViewModel(application) {

    private val agentHub = MorventAgentHub.getInstance(application)
    private val systemBridge = SystemBridgeManager(application)
    val apiKeyManager = agentHub.apiKeyManager
    val deviceController = agentHub.deviceController
    val artemisEngine = agentHub.artemisEngine

    val messages: StateFlow<List<ChatMessage>> = agentHub.messages
    val isThinking: StateFlow<Boolean> = agentHub.isThinking
    val isTtsEnabled: StateFlow<Boolean> = agentHub.isTtsEnabled
    val agentStatus: StateFlow<AgentStatus> = artemisEngine.status
    val isFloatingServiceRunning: StateFlow<Boolean> = MorventFloatingAgentService.runningFlow

    val silentExecutionMode: StateFlow<Boolean> = apiKeyManager.silentExecutionMode
    val autoReplyWhatsApp: StateFlow<Boolean> = apiKeyManager.autoReplyWhatsApp
    val liveMessages: StateFlow<List<CapturedNotificationMessage>> = MorventNotificationListenerService.recentMessages

    val apiKey: StateFlow<String> = apiKeyManager.apiKey
    val selectedModel: StateFlow<String> = apiKeyManager.selectedModel
    val connectionStatus: StateFlow<String> = apiKeyManager.lastConnectionStatus

    private val _bridgeCapabilities = MutableStateFlow(AgentBridgeCapabilities())
    val bridgeCapabilities: StateFlow<AgentBridgeCapabilities> = _bridgeCapabilities.asStateFlow()

    private val _isTestingConnection = MutableStateFlow(false)
    val isTestingConnection: StateFlow<Boolean> = _isTestingConnection.asStateFlow()

    private val _connectionTestResult = MutableStateFlow<ApiConnectionTestResult?>(null)
    val connectionTestResult: StateFlow<ApiConnectionTestResult?> = _connectionTestResult.asStateFlow()

    private val _cloudTasks = MutableStateFlow<List<CloudTaskRecord>>(emptyList())
    val cloudTasks: StateFlow<List<CloudTaskRecord>> = _cloudTasks.asStateFlow()

    private var cloudObserverJob: Job? = null
    private var observedUserId: String? = null

    private val _isHumanPaced = MutableStateFlow(artemisEngine.status.value.isHumanPaced)
    val isHumanPaced: StateFlow<Boolean> = _isHumanPaced.asStateFlow()

    private val _activeTab = MutableStateFlow(MorventTab.CHAT)
    val activeTab: StateFlow<MorventTab> = _activeTab.asStateFlow()

    private val _deviceStatus = MutableStateFlow(DeviceStatus())
    val deviceStatus: StateFlow<DeviceStatus> = _deviceStatus.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps.asStateFlow()

    val macros = listOf(
        MorventMacro(
            id = "sleep",
            title = "وضع النوم والاسترخاء",
            titleEn = "Sleep Mode",
            description = "كتم الرنين والأصوات، إطفاء الكشاف، وضبط بيئة هادئة لتوفير الشحن.",
            iconName = "bedtime",
            commands = listOf("كتم الصوت", "إطفاء الكشاف", "فحص البطارية")
        ),
        MorventMacro(
            id = "work",
            title = "وضع الاجتماع والعمل",
            titleEn = "Meeting Mode",
            description = "تفعيل الاهتزاز، كتم أصوات الوسائط، ومنع الإزعاج الصوتي.",
            iconName = "work",
            commands = listOf("وضع الاهتزاز", "كتم وسائط الصوت")
        ),
        MorventMacro(
            id = "power_save",
            title = "بروتوكول توفير الطاقة الأقصى",
            titleEn = "Ultra Power Save",
            description = "إطفاء الفلاش، فحص استهلاك الطاقة، وخفض السطوع.",
            iconName = "battery_saver",
            commands = listOf("اطفي الكشاف", "فحص البطارية", "اعدادات الشاشة")
        ),
        MorventMacro(
            id = "emergency",
            title = "وضع الطوارئ الفوري",
            titleEn = "Emergency Alert",
            description = "تشغيل كشاف الفلاش بأقصى قوة، رفع الصوت لـ 100%، وتجهيز الاتصال.",
            iconName = "warning",
            commands = listOf("شغل الكشاف", "ارفع الصوت", "اتصال الطوارئ")
        ),
        MorventMacro(
            id = "diagnostic",
            title = "الفحص الشامل للهاتف",
            titleEn = "Full Device Diagnostic",
            description = "تحليل عميق للبطارية، التخزين، الذاكرة العشوائية RAM، والمستشعرات.",
            iconName = "speed",
            commands = listOf("فحص الهاتف", "حالة البطارية")
        )
    )

    init {
        viewModelScope.launch {
            deviceController.isFlashlightOn.collect { on ->
                _deviceStatus.update { it.copy(flashlightOn = on) }
            }
        }
        viewModelScope.launch {
            deviceController.lightSensorLux.collect { lux ->
                _deviceStatus.update { it.copy(lightSensorLux = lux) }
            }
        }
        viewModelScope.launch {
            deviceController.accelZ.collect { z ->
                _deviceStatus.update { it.copy(accelerometerZ = z) }
            }
        }

        refreshDeviceStatus()
        loadInstalledApps()
    }

    fun bindAuthenticatedUser(userId: String?) {
        if (userId.isNullOrBlank()) {
            cloudObserverJob?.cancel()
            cloudObserverJob = null
            observedUserId = null
            _cloudTasks.value = emptyList()
            return
        }
        if (observedUserId == userId && cloudObserverJob?.isActive == true) return
        observedUserId = userId
        cloudObserverJob?.cancel()

        val app = getApplication<Application>()
        if (FirebaseApp.getApps(app).isEmpty()) return

        cloudObserverJob = viewModelScope.launch {
            val repo = MorventCloudRepository(app, Firebase.auth)
            Firebase.auth.currentUser?.let { repo.ensureUserProfile(it, selectedModel.value) }
            repo.observeTasks(userId)
                .catch { /* logged inside repository */ }
                .collect { tasks -> _cloudTasks.value = tasks }
        }
    }

    fun saveApiKey(newKey: String) {
        apiKeyManager.saveApiKey(newKey)
        artemisEngine.resetCloudAvailability()
        _connectionTestResult.value = null
    }

    fun resetDefaultApiKey() {
        val key = apiKeyManager.resetToDefaultKey()
        artemisEngine.resetCloudAvailability()
        _connectionTestResult.value = null
        if (key.isNotBlank()) {
            testAiConnection(key)
        }
    }

    fun clearApiKey() {
        apiKeyManager.saveApiKey("")
        artemisEngine.resetCloudAvailability()
        _connectionTestResult.value = null
    }

    fun selectAiModel(modelName: String) {
        apiKeyManager.setSelectedModel(modelName)
        artemisEngine.resetCloudAvailability()
        val app = getApplication<Application>()
        if (FirebaseApp.getApps(app).isNotEmpty() && Firebase.auth.currentUser != null) {
            viewModelScope.launch(Dispatchers.IO) {
                MorventCloudRepository(app, Firebase.auth).updatePreferredModel(modelName)
            }
        }
    }

    fun testAiConnection(keyToTest: String) {
        if (_isTestingConnection.value) return
        _isTestingConnection.value = true
        _connectionTestResult.value = null
        viewModelScope.launch {
            val res = artemisEngine.testApiKeyConnection(keyToTest, selectedModel.value)
            _connectionTestResult.value = res
            _isTestingConnection.value = false
        }
    }

    fun setTab(tab: MorventTab) {
        _activeTab.value = tab
    }

    fun toggleTts() {
        agentHub.setTtsEnabled(!isTtsEnabled.value)
    }

    fun refreshDeviceStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            val battery = deviceController.getBatteryDetails()
            val devInfo = deviceController.getDeviceInfo()
            val vol = deviceController.getVolumeCardData()
            val caps = systemBridge.inspectCapabilities(deviceController)
            _bridgeCapabilities.value = caps

            _deviceStatus.update {
                it.copy(
                    batteryLevel = battery.percentage,
                    isCharging = battery.isCharging,
                    batteryTemp = battery.temperatureCelsius,
                    freeStorageGb = devInfo.freeStorageGb,
                    totalStorageGb = devInfo.totalStorageGb,
                    freeRamGb = devInfo.freeRamGb,
                    totalRamGb = devInfo.totalRamGb,
                    accessibilityServiceActive = caps.accessibilityEnabled,
                    notificationListenerActive = caps.notificationListenerEnabled,
                    callPermissionsGranted = caps.callPhoneGranted && caps.answerCallsGranted,
                    contactsPermissionGranted = caps.contactsGranted,
                    batteryOptimizationIgnored = caps.batteryOptimizationIgnored,
                    writeSecureSettingsGranted = caps.writeSecureSettingsGranted,
                    mediaVolumePercent = if (vol.maxMediaVolume > 0) (vol.mediaVolume * 100 / vol.maxMediaVolume) else 50,
                    brightnessPercent = deviceController.getScreenBrightnessPercent(),
                    autoRotateEnabled = deviceController.isAutoRotateEnabled(),
                    dndEnabled = deviceController.isDoNotDisturbEnabled(),
                    powerSaveMode = deviceController.isPowerSaveMode(),
                    canWriteSystemSettings = caps.writeSettingsGranted,
                    canManageDnd = caps.dndPolicyGranted,
                    overlayPermissionGranted = caps.overlayGranted
                )
            }
        }
    }

    fun loadInstalledApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val apps = deviceController.getInstalledApps()
            _installedApps.value = apps
        }
    }

    fun sendCommand(commandText: String) {
        agentHub.submitCommand(commandText, sourceLabel = "الشات") { _, _ ->
            refreshDeviceStatus()
        }
    }

    fun toggleFlashlight() {
        sendCommand("كشاف")
    }

    fun setVolume(percent: Int) {
        deviceController.setMediaVolumePercent(percent)
        _deviceStatus.update { it.copy(mediaVolumePercent = percent) }
    }

    fun setRingerMode(mode: Int) {
        deviceController.setRingerMode(mode)
        _deviceStatus.update { it.copy(ringerMode = mode) }
    }

    fun setBrightnessPercent(percent: Int) {
        if (!deviceController.canWriteSystemSettings()) {
            openWriteSettingsPermission()
            return
        }
        val (ok, _) = deviceController.setScreenBrightnessPercent(percent)
        if (ok) {
            _deviceStatus.update { it.copy(brightnessPercent = percent.coerceIn(1, 100), canWriteSystemSettings = true) }
        }
    }

    fun setAutoRotate(enabled: Boolean) {
        if (!deviceController.canWriteSystemSettings()) {
            openWriteSettingsPermission()
            return
        }
        val (ok, _) = deviceController.setAutoRotate(enabled)
        if (ok) {
            _deviceStatus.update { it.copy(autoRotateEnabled = enabled, canWriteSystemSettings = true) }
        }
    }

    fun setDoNotDisturb(enabled: Boolean) {
        if (!deviceController.canManageDoNotDisturb()) {
            openDndPermissionSettings()
            return
        }
        val (ok, _) = deviceController.setDoNotDisturb(enabled)
        if (ok) {
            _deviceStatus.update { it.copy(dndEnabled = enabled, canManageDnd = true) }
        }
    }

    fun openWriteSettingsPermission() {
        deviceController.openWriteSettingsPermission()
    }

    fun openDndPermissionSettings() {
        deviceController.openDndPermissionSettings()
    }

    fun openAccessibilitySettings() {
        MorventAccessibilityService.openAccessibilitySettings(getApplication())
    }

    fun openNotificationListenerSettings() {
        MorventNotificationListenerService.openSettings(getApplication())
    }

    fun openBatteryOptimizationSettings() {
        systemBridge.openBatteryOptimizationExemption()
    }

    fun getAdbBridgeCommands(): String = systemBridge.getAdbGrantCommand()

    fun toggleSilentExecution() {
        apiKeyManager.setSilentExecutionMode(!silentExecutionMode.value)
    }

    fun toggleAutoReplyWhatsApp() {
        val next = !autoReplyWhatsApp.value
        if (next && !MorventNotificationListenerService.isEnabled(getApplication()) &&
            !MorventNotificationListenerService.isConnected.value
        ) {
            openNotificationListenerSettings()
            return
        }
        apiKeyManager.setAutoReplyWhatsApp(next)
    }

    fun runMacro(macro: MorventMacro) {
        sendCommand(macro.title)
    }

    fun launchApp(packageName: String, label: String) {
        val (ok, msg) = deviceController.launchPackage(packageName, label)
        val chatMsg = ChatMessage(
            text = msg,
            isUser = false,
            status = if (ok) MessageStatus.SUCCESS else MessageStatus.ERROR,
            actionType = ActionType.LAUNCH_APP,
            cardData = ActionCardData.AppLaunchedCard(label, packageName)
        )
        agentHub.appendMessage(chatMsg)
        if (isTtsEnabled.value) deviceController.speak(msg)
    }

    fun clearChat() {
        agentHub.clearChat()
    }

    fun toggleFloatingService() {
        val app = getApplication<Application>()
        if (!Settings.canDrawOverlays(app)) {
            openOverlayPermissionSettings()
            return
        }
        if (MorventFloatingAgentService.isRunning) {
            MorventFloatingAgentService.stop(app)
        } else {
            MorventFloatingAgentService.start(app)
        }
    }

    fun openOverlayPermissionSettings() {
        val app = getApplication<Application>()
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${app.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        app.startActivity(intent)
    }

    fun toggleHumanPacing() {
        val newVal = !_isHumanPaced.value
        _isHumanPaced.value = newVal
        artemisEngine.setPacing(newVal)
    }

    fun pauseAgent() = artemisEngine.pause()
    fun resumeAgent() = artemisEngine.resume()
    fun skipAgentStep() = artemisEngine.skipCurrentStep()
    fun stopAgent() = artemisEngine.stop()
}
