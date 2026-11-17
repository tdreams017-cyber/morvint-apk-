package com.example.model

import java.util.UUID

/**
 * Uploaded photo or document attachment in Chat.
 */
data class ChatAttachment(
    val uriString: String,
    val displayName: String,
    val mimeType: String,
    val isImage: Boolean,
    val sizeBytes: Long = 0L,
    val localLinuxPath: String? = null
)

/**
 * Chat message model representing commands and agent responses.
 */
data class ChatMessage(
    val text: String,
    val isUser: Boolean = false,
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val status: MessageStatus = MessageStatus.SUCCESS,
    val actionType: ActionType? = null,
    val actionDetail: String? = null,
    val subSteps: List<String> = emptyList(),
    val cardData: ActionCardData? = null,
    val attachments: List<ChatAttachment> = emptyList()
)

enum class MessageStatus {
    PENDING,
    EXECUTING,
    SUCCESS,
    ERROR,
    INFO
}

enum class ActionType {
    FLASHLIGHT,
    VOLUME,
    BRIGHTNESS,
    WIFI,
    BLUETOOTH,
    BATTERY,
    DEVICE_INFO,
    LAUNCH_APP,
    PHONE_CALL,
    SEND_SMS,
    WHATSAPP,
    ALARM,
    TIMER,
    ACCESSIBILITY_NAV,
    SCREENSHOT,
    LOCK_SCREEN,
    WEB_SEARCH,
    MACRO_SCENARIO,
    DIAGNOSTICS,
    HELP,
    IN_APP_CLICK,
    IN_APP_TYPE,
    IN_APP_SCROLL,
    IN_APP_INSPECT,
    ACCOUNT_WORKFLOW,
    LINUX_TERMINAL
}

sealed class ActionCardData {
    data class BatteryCard(
        val percentage: Int,
        val isCharging: Boolean,
        val temperatureCelsius: Float,
        val voltageMv: Int,
        val health: String
    ) : ActionCardData()

    data class DeviceInfoCard(
        val freeStorageGb: Float,
        val totalStorageGb: Float,
        val freeRamGb: Float,
        val totalRamGb: Float,
        val osVersion: String,
        val deviceModel: String
    ) : ActionCardData()

    data class VolumeCard(
        val mediaVolume: Int,
        val maxMediaVolume: Int,
        val ringVolume: Int,
        val maxRingVolume: Int,
        val isMuted: Boolean
    ) : ActionCardData()

    data class AppLaunchedCard(
        val appName: String,
        val packageName: String
    ) : ActionCardData()

    data class MacroResultCard(
        val title: String,
        val executedSteps: List<String>
    ) : ActionCardData()

    data class ScreenInspectionCard(
        val packageName: String,
        val totalElements: Int,
        val buttonsFound: List<String>,
        val textInputs: List<String>,
        val clickableCount: Int
    ) : ActionCardData()

    data class InAppActionResultCard(
        val actionTitle: String,
        val target: String,
        val isSuccess: Boolean,
        val logs: List<String>
    ) : ActionCardData()

    data class AccountWorkflowCard(
        val appName: String,
        val workflowName: String,
        val detectedAuthOptions: List<String>,
        val stepsExecuted: List<String>,
        val isSuccess: Boolean
    ) : ActionCardData()

    data class LinuxCommandCard(
        val command: String,
        val workingDir: String,
        val exitCode: Int,
        val output: String,
        val durationMs: Long
    ) : ActionCardData()
}

/**
 * Representation of an inspected UI node from foreground apps.
 */
data class UiElementInfo(
    val text: String,
    val viewId: String? = null,
    val className: String? = null,
    val isClickable: Boolean = false,
    val isEditable: Boolean = false,
    val contentDescription: String? = null,
    val centerX: Int = 0,
    val centerY: Int = 0
)

/**
 * Screen analysis snapshot.
 */
data class ScreenInspectionReport(
    val packageName: String = "",
    val activityName: String = "",
    val elements: List<UiElementInfo> = emptyList(),
    val buttons: List<String> = emptyList(),
    val inputFields: List<String> = emptyList()
)

/**
 * Real-time hardware and system state.
 */
data class DeviceStatus(
    val batteryLevel: Int = 100,
    val isCharging: Boolean = false,
    val batteryTemp: Float = 28f,
    val freeStorageGb: Float = 0f,
    val totalStorageGb: Float = 0f,
    val freeRamGb: Float = 0f,
    val totalRamGb: Float = 0f,
    val flashlightOn: Boolean = false,
    val ringerMode: Int = 2, // 2: Normal, 1: Vibrate, 0: Silent
    val mediaVolumePercent: Int = 50,
    val brightnessPercent: Int = 50,
    val autoRotateEnabled: Boolean = false,
    val dndEnabled: Boolean = false,
    val powerSaveMode: Boolean = false,
    val canWriteSystemSettings: Boolean = false,
    val canManageDnd: Boolean = false,
    val overlayPermissionGranted: Boolean = false,
    val accessibilityServiceActive: Boolean = false,
    val notificationListenerActive: Boolean = false,
    val callPermissionsGranted: Boolean = false,
    val contactsPermissionGranted: Boolean = false,
    val batteryOptimizationIgnored: Boolean = false,
    val writeSecureSettingsGranted: Boolean = false,
    val usageStatsGranted: Boolean = false,
    val smsPermissionGranted: Boolean = false,
    val callLogPermissionGranted: Boolean = false,
    val calendarPermissionGranted: Boolean = false,
    val locationPermissionGranted: Boolean = false,
    val lightSensorLux: Float = 0f,
    val accelerometerZ: Float = 9.8f,
    val foregroundApp: String? = null,
    val foregroundPackage: String? = null
)

/**
 * Installed application representation.
 */
data class InstalledApp(
    val appName: String,
    val packageName: String,
    val isSystemApp: Boolean = false
)

/**
 * Automated Macro / Scenario.
 */
data class MorventMacro(
    val id: String,
    val title: String,
    val titleEn: String,
    val description: String,
    val iconName: String,
    val commands: List<String>
)
