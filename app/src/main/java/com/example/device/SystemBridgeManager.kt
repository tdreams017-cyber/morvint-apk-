package com.example.device

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.service.MorventAccessibilityService
import com.example.service.MorventFloatingAgentService
import com.example.service.MorventNotificationListenerService

data class AgentBridgeCapabilities(
    val accessibilityEnabled: Boolean = false,
    val notificationListenerEnabled: Boolean = false,
    val overlayGranted: Boolean = false,
    val writeSettingsGranted: Boolean = false,
    val dndPolicyGranted: Boolean = false,
    val callPhoneGranted: Boolean = false,
    val answerCallsGranted: Boolean = false,
    val contactsGranted: Boolean = false,
    val batteryOptimizationIgnored: Boolean = false,
    val writeSecureSettingsGranted: Boolean = false,
    val floatingServiceActive: Boolean = false
) {
    val activeCount: Int
        get() = listOf(
            accessibilityEnabled,
            notificationListenerEnabled,
            overlayGranted,
            writeSettingsGranted,
            dndPolicyGranted,
            callPhoneGranted,
            answerCallsGranted,
            contactsGranted,
            batteryOptimizationIgnored
        ).count { it }

    val totalCount: Int = 9
}

/**
 * Inspects real Android OS capabilities, manages direct permission settings intents,
 * executes secure kernel-level Settings changes when ADB WRITE_SECURE_SETTINGS is granted,
 * and supplies exact one-line ADB bridge commands for advanced zero-touch permissions.
 */
class SystemBridgeManager(private val context: Context) {

    private val appContext = context.applicationContext

    fun inspectCapabilities(controller: DeviceController): AgentBridgeCapabilities {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val ignoringBattery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching { pm?.isIgnoringBatteryOptimizations(appContext.packageName) == true }.getOrDefault(false)
        } else true

        val callPhone = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        val answerCalls = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ANSWER_PHONE_CALLS
            ) == PackageManager.PERMISSION_GRANTED
        } else true

        val contacts = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        val writeSecure = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED

        return AgentBridgeCapabilities(
            accessibilityEnabled = MorventAccessibilityService.isRunning || MorventAccessibilityService.isServiceEnabled(appContext),
            notificationListenerEnabled = MorventNotificationListenerService.isConnected.value || MorventNotificationListenerService.isEnabled(appContext),
            overlayGranted = runCatching { Settings.canDrawOverlays(appContext) }.getOrDefault(false),
            writeSettingsGranted = controller.canWriteSystemSettings(),
            dndPolicyGranted = controller.canManageDoNotDisturb(),
            callPhoneGranted = callPhone,
            answerCallsGranted = answerCalls,
            contactsGranted = contacts,
            batteryOptimizationIgnored = ignoringBattery,
            writeSecureSettingsGranted = writeSecure,
            floatingServiceActive = MorventFloatingAgentService.isRunning
        )
    }

    fun openBatteryOptimizationExemption(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${appContext.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(intent)
                true
            } else false
        } catch (_: Exception) {
            runCatching {
                val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(fallback)
                true
            }.getOrDefault(false)
        }
    }

    fun getAdbGrantCommand(): String {
        val pkg = appContext.packageName
        val a11yComponent = "$pkg/${MorventAccessibilityService::class.java.name}"
        val notifComponent = "$pkg/${MorventNotificationListenerService::class.java.name}"
        return buildString {
            appendLine("adb shell pm grant $pkg android.permission.WRITE_SECURE_SETTINGS")
            appendLine("adb shell settings put secure enabled_accessibility_services $a11yComponent")
            appendLine("adb shell settings put secure accessibility_enabled 1")
            appendLine("adb shell cmd notification allow_listener $notifComponent")
            append("adb shell dumpsys deviceidle whitelist +$pkg")
        }
    }

    /**
     * Executes direct Settings.Secure / Settings.Global kernel setting writes silently
     * when WRITE_SECURE_SETTINGS has been granted via ADB bridge.
     */
    fun applySecureSystemToggle(key: String, enable: Boolean): Pair<Boolean, String> {
        val hasSecure = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasSecure) {
            return Pair(false, "NEED_WRITE_SECURE_SETTINGS")
        }

        return try {
            val cr = appContext.contentResolver
            when (key.lowercase()) {
                "battery_saver", "power_save" -> {
                    Settings.Global.putInt(cr, "low_power", if (enable) 1 else 0)
                    Pair(true, if (enable) "تم تفعيل وضع توفير الطاقة على مستوى النواة فوراً 🔋✓" else "تم إيقاف وضع توفير الطاقة فوراً ✓")
                }
                "airplane", "airplane_mode" -> {
                    Settings.Global.putInt(cr, Settings.Global.AIRPLANE_MODE_ON, if (enable) 1 else 0)
                    Pair(true, if (enable) "تم تفعيل وضع الطيران عبر إعدادات النظام العميقة ✈️✓" else "تم إيقاف وضع الطيران ✓")
                }
                "location", "gps" -> {
                    val mode = if (enable) Settings.Secure.LOCATION_MODE_HIGH_ACCURACY else Settings.Secure.LOCATION_MODE_OFF
                    @Suppress("DEPRECATION")
                    Settings.Secure.putInt(cr, Settings.Secure.LOCATION_MODE, mode)
                    Pair(true, if (enable) "تم تفعيل خدمة الموقع (GPS) على مستوى النظام 📍✓" else "تم إيقاف خدمة الموقع ✓")
                }
                "animations_off" -> {
                    val scale = if (enable) 0f else 1f
                    Settings.Global.putFloat(cr, Settings.Global.WINDOW_ANIMATION_SCALE, scale)
                    Settings.Global.putFloat(cr, Settings.Global.TRANSITION_ANIMATION_SCALE, scale)
                    Settings.Global.putFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
                    Pair(true, if (enable) "تم تسريع واجهة الهاتف وإيقاف الحركات الزائدة ⚡✓" else "تمت استعادة سرعة الحركات الافتراضية ✓")
                }
                else -> Pair(false, "مفتاح النظام غير مدعوم: $key")
            }
        } catch (e: Exception) {
            Pair(false, "تعذر تعديل إعداد النظام العميق: ${e.message}")
        }
    }
}
