package com.example.device

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.Manifest
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.model.ActionCardData
import com.example.model.InstalledApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale

class DeviceController(private val context: Context) : SensorEventListener {

    private val tag = "DeviceController"

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    private val _isFlashlightOn = MutableStateFlow(false)
    val isFlashlightOn: StateFlow<Boolean> = _isFlashlightOn.asStateFlow()

    private val _lightSensorLux = MutableStateFlow(0f)
    val lightSensorLux: StateFlow<Float> = _lightSensorLux.asStateFlow()

    private val _accelZ = MutableStateFlow(9.8f)
    val accelZ: StateFlow<Float> = _accelZ.asStateFlow()

    private var cameraIdWithFlash: String? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    init {
        setupFlashlight()
        setupSensors()
        setupTts()
    }

    private fun setupFlashlight() {
        try {
            cameraManager?.let { cm ->
                for (id in cm.cameraIdList) {
                    val characteristics = cm.getCameraCharacteristics(id)
                    val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    if (hasFlash) {
                        cameraIdWithFlash = id
                        break
                    }
                }
                cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                        if (cameraId == cameraIdWithFlash) {
                            _isFlashlightOn.value = enabled
                        }
                    }
                }, null)
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize camera flash", e)
        }
    }

    private fun setupSensors() {
        try {
            sensorManager?.let { sm ->
                val lightSensor = sm.getDefaultSensor(Sensor.TYPE_LIGHT)
                lightSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }

                val accelSensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                accelSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
            }
        } catch (e: Exception) {
            Log.e(tag, "Sensor setup failed", e)
        }
    }

    private fun setupTts() {
        try {
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    isTtsReady = true
                    // Try Arabic first, fallback to default/US
                    val arResult = tts?.setLanguage(Locale("ar"))
                    if (arResult == TextToSpeech.LANG_MISSING_DATA || arResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts?.setLanguage(Locale.US)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "TTS init error", e)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        when (event.sensor.type) {
            Sensor.TYPE_LIGHT -> _lightSensorLux.value = event.values[0]
            Sensor.TYPE_ACCELEROMETER -> _accelZ.value = event.values[2]
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ==========================================
    // 1. FLASHLIGHT
    // ==========================================
    fun toggleFlashlight(turnOn: Boolean? = null): Boolean {
        val cid = cameraIdWithFlash ?: return false
        val newState = turnOn ?: !_isFlashlightOn.value
        return try {
            cameraManager?.setTorchMode(cid, newState)
            _isFlashlightOn.value = newState
            vibratePulse(50)
            true
        } catch (e: Exception) {
            Log.e(tag, "Error setting flashlight", e)
            false
        }
    }

    // ==========================================
    // 2. AUDIO & VOLUME
    // ==========================================
    fun setMediaVolumePercent(percent: Int): Boolean {
        val am = audioManager ?: return false
        return try {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = ((percent.coerceIn(0, 100) / 100f) * max).toInt()
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
            vibratePulse(30)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun adjustMediaVolume(raise: Boolean): Boolean {
        val am = audioManager ?: return false
        val direction = if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        vibratePulse(30)
        return true
    }

    fun setRingerMode(mode: Int): Boolean {
        val am = audioManager ?: return false
        return try {
            am.ringerMode = mode
            vibratePulse(60)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun setStreamVolumePercent(streamType: Int, percent: Int): Boolean {
        val am = audioManager ?: return false
        return try {
            val max = am.getStreamMaxVolume(streamType).coerceAtLeast(1)
            val target = ((percent.coerceIn(0, 100) / 100f) * max).toInt()
            am.setStreamVolume(streamType, target, AudioManager.FLAG_SHOW_UI)
            vibratePulse(30)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun adjustStreamVolume(streamType: Int, raise: Boolean): Boolean {
        val am = audioManager ?: return false
        return try {
            val direction = if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            am.adjustStreamVolume(streamType, direction, AudioManager.FLAG_SHOW_UI)
            vibratePulse(30)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun getVolumeCardData(): ActionCardData.VolumeCard {
        val am = audioManager
        val media = am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 5
        val maxMedia = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
        val ring = am?.getStreamVolume(AudioManager.STREAM_RING) ?: 5
        val maxRing = am?.getStreamMaxVolume(AudioManager.STREAM_RING) ?: 7
        val isMuted = am?.ringerMode == AudioManager.RINGER_MODE_SILENT
        return ActionCardData.VolumeCard(
            mediaVolume = media,
            maxMediaVolume = maxMedia,
            ringVolume = ring,
            maxRingVolume = maxRing,
            isMuted = isMuted
        )
    }

    // ==========================================
    // 2B. WHOLE-SYSTEM OS SETTINGS & HARDWARE CONTROLS
    // ==========================================
    fun canWriteSystemSettings(): Boolean =
        try {
            Settings.System.canWrite(context)
        } catch (_: Exception) {
            false
        }

    fun canManageDoNotDisturb(): Boolean =
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.isNotificationPolicyAccessGranted == true
        } catch (_: Exception) {
            false
        }

    fun getScreenBrightnessPercent(): Int {
        return try {
            val raw = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            ((raw.coerceIn(0, 255) / 255f) * 100f).toInt().coerceIn(0, 100)
        } catch (_: Exception) {
            50
        }
    }

    fun setScreenBrightnessPercent(percent: Int): Pair<Boolean, String> {
        if (!canWriteSystemSettings()) {
            return Pair(false, "REQUIRE_WRITE_SETTINGS")
        }
        return try {
            val clamped = percent.coerceIn(1, 100)
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            val raw = ((clamped / 100f) * 255f).toInt().coerceIn(5, 255)
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, raw)
            vibratePulse(35)
            Pair(true, "تم ضبط سطوع شاشة النظام على $clamped% ☀️")
        } catch (e: Exception) {
            Pair(false, "تعذر تعديل سطوع النظام: ${e.message}")
        }
    }

    fun adjustScreenBrightness(increase: Boolean): Pair<Boolean, String> {
        val current = getScreenBrightnessPercent()
        val target = if (increase) (current + 20).coerceAtMost(100) else (current - 20).coerceAtLeast(5)
        return setScreenBrightnessPercent(target)
    }

    fun setAutoBrightness(enabled: Boolean): Pair<Boolean, String> {
        if (!canWriteSystemSettings()) {
            return Pair(false, "REQUIRE_WRITE_SETTINGS")
        }
        return try {
            val mode = if (enabled) {
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            } else {
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            }
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, mode)
            vibratePulse(35)
            Pair(true, if (enabled) "تم تفعيل السطوع التلقائي للنظام ☀️" else "تم تحويل السطوع إلى الوضع اليدوي")
        } catch (e: Exception) {
            Pair(false, "تعذر تغيير وضع السطوع: ${e.message}")
        }
    }

    fun isAutoRotateEnabled(): Boolean {
        return try {
            Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
        } catch (_: Exception) {
            false
        }
    }

    fun setAutoRotate(enabled: Boolean): Pair<Boolean, String> {
        if (!canWriteSystemSettings()) {
            return Pair(false, "REQUIRE_WRITE_SETTINGS")
        }
        return try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION,
                if (enabled) 1 else 0
            )
            vibratePulse(35)
            Pair(true, if (enabled) "تم تفعيل التدوير التلقائي لشاشة النظام 🔄" else "تم تثبيت اتجاه الشاشة (إيقاف التدوير التلقائي) 📱")
        } catch (e: Exception) {
            Pair(false, "تعذر تعديل التدوير التلقائي: ${e.message}")
        }
    }

    fun setScreenTimeoutSeconds(seconds: Int): Pair<Boolean, String> {
        if (!canWriteSystemSettings()) {
            return Pair(false, "REQUIRE_WRITE_SETTINGS")
        }
        return try {
            val ms = (seconds.coerceIn(15, 1800)) * 1000
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, ms)
            vibratePulse(35)
            val label = if (seconds >= 60) "${seconds / 60} دقيقة" else "$seconds ثانية"
            Pair(true, "تم ضبط مهلة سكون شاشة النظام على $label ⏱️")
        } catch (e: Exception) {
            Pair(false, "تعذر تعديل مهلة الشاشة: ${e.message}")
        }
    }

    fun isDoNotDisturbEnabled(): Boolean {
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val filter = nm?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL
            filter != NotificationManager.INTERRUPTION_FILTER_ALL
        } catch (_: Exception) {
            false
        }
    }

    fun setDoNotDisturb(enabled: Boolean): Pair<Boolean, String> {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return Pair(false, "تعذر الوصول لمدير الإشعارات")
        if (!nm.isNotificationPolicyAccessGranted) {
            return Pair(false, "REQUIRE_DND_PERMISSION")
        }
        return try {
            val filter = if (enabled) {
                NotificationManager.INTERRUPTION_FILTER_PRIORITY
            } else {
                NotificationManager.INTERRUPTION_FILTER_ALL
            }
            nm.setInterruptionFilter(filter)
            vibratePulse(40)
            Pair(true, if (enabled) "تم تفعيل وضع عدم الإزعاج (DND) على مستوى النظام 🔕" else "تم إيقاف وضع عدم الإزعاج وعودة الإشعارات 🔔")
        } catch (e: Exception) {
            Pair(false, "تعذر تعديل وضع عدم الإزعاج: ${e.message}")
        }
    }

    fun isPowerSaveMode(): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isPowerSaveMode == true
        } catch (_: Exception) {
            false
        }
    }

    fun openWriteSettingsPermission(): Boolean {
        return try {
            val intent = Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            openSettings(Settings.ACTION_SETTINGS)
        }
    }

    fun openDndPermissionSettings(): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            openSettings(Settings.ACTION_SETTINGS)
        }
    }

    fun openSystemPanel(panelKey: String): Pair<Boolean, String> {
        val lower = panelKey.lowercase(Locale.ROOT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val panelAction = when (lower) {
                "internet", "connectivity", "data" -> Settings.Panel.ACTION_INTERNET_CONNECTIVITY
                "wifi" -> Settings.Panel.ACTION_WIFI
                "volume", "sound" -> Settings.Panel.ACTION_VOLUME
                "nfc" -> Settings.Panel.ACTION_NFC
                else -> null
            }
            if (panelAction != null) {
                val ok = openSettings(panelAction)
                if (ok) return Pair(true, "تم فتح لوحة تحكم النظام السريعة ($panelKey)")
            }
        }
        val opened = openSpecificSettings(lower)
        return Pair(opened, if (opened) "تم فتح إعدادات النظام ($panelKey)" else "تعذر فتح لوحة النظام")
    }

    // ==========================================
    // 3. BATTERY & SYSTEM TELEMETRY
    // ==========================================
    fun getBatteryDetails(): ActionCardData.BatteryCard {
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus: Intent? = context.registerReceiver(null, ifilter)

        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else 75

        val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        val tempTenths = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 290) ?: 290
        val tempC = tempTenths / 10.0f
        val voltage = batteryStatus?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 4000) ?: 4000

        val healthCode = batteryStatus?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_GOOD)
        val healthStr = when (healthCode) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "ممتازة (Good)"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "حرارة مرتفعة (Overheat)"
            BatteryManager.BATTERY_HEALTH_DEAD -> "تالفة (Dead)"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "جهد زائد (Over Voltage)"
            else -> "جيدة (Normal)"
        }

        return ActionCardData.BatteryCard(
            percentage = pct,
            isCharging = isCharging,
            temperatureCelsius = tempC,
            voltageMv = voltage,
            health = healthStr
        )
    }

    fun getDeviceInfo(): ActionCardData.DeviceInfoCard {
        // Storage calculation
        val stat = StatFs(Environment.getDataDirectory().path)
        val blockSize = stat.blockSizeLong
        val totalBlocks = stat.blockCountLong
        val availableBlocks = stat.availableBlocksLong
        val totalStorageGb = (totalBlocks * blockSize) / (1024f * 1024f * 1024f)
        val freeStorageGb = (availableBlocks * blockSize) / (1024f * 1024f * 1024f)

        // RAM calculation
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager?.getMemoryInfo(memInfo)
        val totalRamGb = memInfo.totalMem / (1024f * 1024f * 1024f)
        val freeRamGb = memInfo.availMem / (1024f * 1024f * 1024f)

        return ActionCardData.DeviceInfoCard(
            freeStorageGb = String.format(Locale.US, "%.1f", freeStorageGb).toFloat(),
            totalStorageGb = String.format(Locale.US, "%.1f", totalStorageGb).toFloat(),
            freeRamGb = String.format(Locale.US, "%.1f", freeRamGb).toFloat(),
            totalRamGb = String.format(Locale.US, "%.1f", totalRamGb).toFloat(),
            osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            deviceModel = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
        )
    }

    // ==========================================
    // 4. INSTALLED APPS QUERY & LAUNCH
    // ==========================================
    fun getInstalledApps(): List<InstalledApp> {
        val pm = context.packageManager
        val apps = mutableListOf<InstalledApp>()
        try {
            val list = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (info in list) {
                val launchIntent = pm.getLaunchIntentForPackage(info.packageName)
                if (launchIntent != null) {
                    val label = pm.getApplicationLabel(info).toString()
                    val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    apps.add(InstalledApp(appName = label, packageName = info.packageName, isSystemApp = isSystem))
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to get installed apps", e)
        }
        return apps.sortedBy { it.appName }
    }

    fun launchAppByQuery(query: String): Pair<Boolean, String> {
        val apps = getInstalledApps()
        val qClean = query.trim().lowercase(Locale.ROOT)

        // Priority exact or starts-with match
        val matchedApp = apps.firstOrNull {
            it.appName.lowercase(Locale.ROOT) == qClean ||
            it.packageName.lowercase(Locale.ROOT) == qClean
        } ?: apps.firstOrNull {
            it.appName.lowercase(Locale.ROOT).contains(qClean) ||
            qClean.contains(it.appName.lowercase(Locale.ROOT))
        }

        if (matchedApp != null) {
            return launchPackage(matchedApp.packageName, matchedApp.appName)
        }

        // Try well-known system short aliases
        val targetPackage = when {
            qClean.contains("واتس") || qClean.contains("whatsapp") -> "com.whatsapp"
            qClean.contains("يوتيوب") || qClean.contains("youtube") -> "com.google.android.youtube"
            qClean.contains("كاميرا") || qClean.contains("camera") -> "com.google.android.GoogleCamera"
            qClean.contains("معرض") || qClean.contains("gallery") || qClean.contains("photos") -> "com.google.android.apps.photos"
            qClean.contains("خرائط") || qClean.contains("maps") -> "com.google.android.apps.maps"
            qClean.contains("كروم") || qClean.contains("chrome") || qClean.contains("متصفح") -> "com.android.chrome"
            qClean.contains("حاسبة") || qClean.contains("calculator") -> "com.google.android.calculator"
            else -> null
        }

        if (targetPackage != null) {
            val res = launchPackage(targetPackage, query)
            if (res.first) return res
        }

        return Pair(false, "لم يتم العثور على تطبيق يطابق '$query'")
    }

    fun launchPackage(packageName: String, label: String = packageName): Pair<Boolean, String> {
        return try {
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                vibratePulse(50)
                Pair(true, "تم فتح تطبيق $label بنجاح")
            } else {
                Pair(false, "التطبيق غير مثبت أو لا يمكن فتحه مباشرة")
            }
        } catch (e: Exception) {
            Pair(false, "تعذر فتح التطبيق: ${e.message}")
        }
    }

    // ==========================================
    // 5. SYSTEM SETTINGS SHORTCUTS
    // ==========================================
    fun openSettings(action: String): Boolean {
        return try {
            val intent = Intent(action).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            vibratePulse(40)
            true
        } catch (e: Exception) {
            // Fallback to main settings
            try {
                val fallback = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
                true
            } catch (ex: Exception) {
                false
            }
        }
    }

    // ==========================================
    // 6. COMMUNICATION (CALL / TELECOM / CONTACTS / SMS / WHATSAPP)
    // ==========================================
    fun findContactPhoneNumber(nameQuery: String): Pair<String, String>? {
        val clean = nameQuery.trim()
        if (clean.isEmpty()) return null
        val hasContacts = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasContacts) return null

        return try {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$clean%"),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0)?.trim().orEmpty()
                    val num = cursor.getString(1)?.replace(Regex("[\\s\\-()]"), "")?.trim().orEmpty()
                    if (num.isNotBlank()) return Pair(name.ifBlank { clean }, num)
                }
            }
            null
        } catch (e: Exception) {
            Log.w(tag, "Contact query failed", e)
            null
        }
    }

    fun placeDirectCall(numberOrName: String): Pair<Boolean, String> {
        val cleanInput = numberOrName.trim()
        val resolved = if (cleanInput.any { it.isDigit() }) {
            Pair(cleanInput, cleanInput.filter { it.isDigit() || it == '+' || it == '*' || it == '#' })
        } else {
            findContactPhoneNumber(cleanInput)
                ?: return Pair(false, "لم يتم العثور على جهة اتصال باسم '$cleanInput' (أو تأكد من منح إذن جهات الاتصال).")
        }

        val (displayName, phoneNum) = resolved
        if (phoneNum.isBlank()) return Pair(false, "رقم الهاتف غير صالح.")

        val hasCallPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        return try {
            val uri = Uri.parse("tel:${Uri.encode(phoneNum)}")
            val intentAction = if (hasCallPerm) Intent.ACTION_CALL else Intent.ACTION_DIAL
            val intent = Intent(intentAction, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            vibratePulse(60)
            if (hasCallPerm) {
                Pair(true, "تم بدء المكالمة الصوتية مباشرةً مع $displayName ($phoneNum) 📞✓")
            } else {
                Pair(true, "تم تجهيز الاتصال بـ $displayName ($phoneNum) في لوحة الاتصال (امنح إذن الهاتف للاتصال الفوري المباشر) 📞")
            }
        } catch (e: Exception) {
            Pair(false, "تعذر بدء المكالمة: ${e.message}")
        }
    }

    @Suppress("MissingPermission")
    fun answerIncomingCall(): Pair<Boolean, String> {
        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        val hasAnswerPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ANSWER_PHONE_CALLS
            ) == PackageManager.PERMISSION_GRANTED
        } else false

        if (telecom != null && hasAnswerPerm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                @Suppress("DEPRECATION")
                telecom.acceptRingingCall()
                vibratePulse(50)
                return Pair(true, "تم الرد على المكالمة الواردة فوراً عبر نظام الهاتف 📞✓")
            } catch (e: Exception) {
                Log.w(tag, "TelecomManager acceptRingingCall failed, trying Accessibility UI", e)
            }
        }
        return Pair(false, "FALLBACK_ACCESSIBILITY_CALL_ANSWER")
    }

    @Suppress("MissingPermission")
    fun endActiveCall(): Pair<Boolean, String> {
        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        val hasAnswerPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ANSWER_PHONE_CALLS
            ) == PackageManager.PERMISSION_GRANTED
        } else false

        if (telecom != null && hasAnswerPerm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                @Suppress("DEPRECATION")
                val ended = telecom.endCall()
                if (ended) {
                    vibratePulse(50)
                    return Pair(true, "تم إنهاء المكالمة فوراً 📵✓")
                }
            } catch (e: Exception) {
                Log.w(tag, "TelecomManager endCall failed, trying Accessibility UI", e)
            }
        }
        return Pair(false, "FALLBACK_ACCESSIBILITY_CALL_END")
    }

    fun setSpeakerphone(enabled: Boolean): Pair<Boolean, String> {
        val am = audioManager ?: return Pair(false, "تعذر الوصول لنظام الصوت")
        return try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = enabled
            vibratePulse(35)
            Pair(true, if (enabled) "تم تشغيل مكبر الصوت (السبيكر) للمكالمة 🔊✓" else "تم إيقاف مكبر الصوت 🔈✓")
        } catch (e: Exception) {
            Pair(false, "تعذر تبديل مكبر الصوت: ${e.message}")
        }
    }

    fun dialNumber(number: String): Boolean = placeDirectCall(number).first

    fun composeSms(numberOrName: String, message: String): Boolean {
        val cleanNum = if (numberOrName.any { it.isDigit() }) {
            numberOrName
        } else {
            findContactPhoneNumber(numberOrName)?.second ?: numberOrName
        }
        return try {
            val uri = Uri.parse("smsto:${Uri.encode(cleanNum)}")
            val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            vibratePulse(50)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun openWhatsAppChat(phoneOrName: String?, message: String?): Boolean {
        val raw = phoneOrName?.trim().orEmpty()
        val resolvedPhone = if (raw.isNotEmpty() && !raw.any { it.isDigit() }) {
            findContactPhoneNumber(raw)?.second ?: ""
        } else {
            raw
        }
        return try {
            val cleanPhone = resolvedPhone.filter { it.isDigit() }
            val encodedMsg = Uri.encode(message ?: "")
            val url = if (cleanPhone.isNotEmpty()) {
                "https://api.whatsapp.com/send?phone=$cleanPhone&text=$encodedMsg"
            } else {
                "https://api.whatsapp.com/send?text=$encodedMsg"
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            vibratePulse(50)
            true
        } catch (e: Exception) {
            false
        }
    }

    // ==========================================
    // 7. ALARM & TIMER
    // ==========================================
    fun setAlarm(hour: Int, minute: Int, message: String = "منبه مورڤينت"): Boolean {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, message)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            vibratePulse(50)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun setTimer(seconds: Int, message: String = "مؤقت مورڤينت"): Boolean {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, message)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            vibratePulse(50)
            true
        } catch (e: Exception) {
            false
        }
    }

    // ==========================================
    // 8. CLIPBOARD & SEARCH
    // ==========================================
    fun copyToClipboard(label: String, text: String) {
        val clip = ClipData.newPlainText(label, text)
        clipboardManager?.setPrimaryClip(clip)
        vibratePulse(40)
    }

    fun getClipboardText(): String? {
        val clip = clipboardManager?.primaryClip
        if (clip != null && clip.itemCount > 0) {
            return clip.getItemAt(0).text?.toString()
        }
        return null
    }

    fun searchWeb(query: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra("query", query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            vibratePulse(40)
            true
        } catch (e: Exception) {
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(browserIntent)
                true
            } catch (ex: Exception) {
                false
            }
        }
    }

    fun openUrl(rawUrl: String): Boolean {
        return try {
            val url = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://") || rawUrl.contains("://")) {
                rawUrl
            } else {
                "https://$rawUrl"
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            vibratePulse(40)
            true
        } catch (e: Exception) {
            Log.e(tag, "openUrl failed for $rawUrl", e)
            false
        }
    }

    fun shareText(text: String): Boolean {
        return try {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_TEXT, text)
                type = "text/plain"
            }
            val chooser = Intent.createChooser(sendIntent, "مشاركة عبر مورڤينت").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            vibratePulse(40)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun composeEmail(to: String, subject: String = "", body: String = ""): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
                if (to.isNotBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
                if (subject.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, subject)
                if (body.isNotBlank()) putExtra(Intent.EXTRA_TEXT, body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            vibratePulse(40)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun dispatchMediaKey(action: String): Boolean {
        val am = audioManager ?: return false
        val keyCode = when (action.lowercase(Locale.ROOT)) {
            "play", "pause", "play_pause", "toggle" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "prev" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> android.view.KeyEvent.KEYCODE_MEDIA_STOP
            else -> return false
        }
        return try {
            val down = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode)
            val up = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode)
            am.dispatchMediaKeyEvent(down)
            am.dispatchMediaKeyEvent(up)
            vibratePulse(35)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun openAppDetails(appQuery: String): Pair<Boolean, String> {
        val qClean = appQuery.trim().lowercase(Locale.ROOT)
        if (qClean.isEmpty()) {
            return Pair(openSettings(Settings.ACTION_APPLICATION_SETTINGS), "تم فتح إدارة التطبيقات بالنظام")
        }
        val apps = getInstalledApps()
        val match = apps.firstOrNull {
            it.appName.lowercase(Locale.ROOT) == qClean || it.packageName.lowercase(Locale.ROOT) == qClean
        } ?: apps.firstOrNull {
            it.appName.lowercase(Locale.ROOT).contains(qClean) || qClean.contains(it.appName.lowercase(Locale.ROOT))
        }
        if (match == null) {
            return Pair(false, "لم يتم العثور على تطبيق يطابق '$appQuery' لفتح معلومات النظام الخاصة به")
        }
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", match.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            vibratePulse(40)
            Pair(true, "تم فتح إعدادات النظام لتطبيق ${match.appName} (الأذونات، التخزين، الكاش) ⚙️")
        } catch (e: Exception) {
            Pair(false, "تعذر فتح إعدادات التطبيق: ${e.message}")
        }
    }

    fun openMapsSearch(query: String): Boolean {
        return try {
            val uri = Uri.parse("geo:0,0?q=${Uri.encode(query)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            vibratePulse(40)
            true
        } catch (_: Exception) {
            openUrl("https://maps.google.com/?q=${Uri.encode(query)}")
        }
    }

    fun openCamera(videoMode: Boolean = false): Boolean {
        return try {
            val action = if (videoMode) {
                MediaStore.INTENT_ACTION_VIDEO_CAMERA
            } else {
                MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
            }
            val intent = Intent(action).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            vibratePulse(40)
            true
        } catch (_: Exception) {
            launchAppByQuery("كاميرا").first
        }
    }

    fun openFileManager(): Boolean {
        return try {
            val intent = Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            vibratePulse(40)
            true
        } catch (_: Exception) {
            openSpecificSettings("storage")
        }
    }

    fun listManagedFiles(): List<String> {
        val dirs = listOfNotNull(
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
            context.filesDir
        )
        val entries = mutableListOf<String>()
        for (dir in dirs) {
            if (dir.exists()) {
                dir.listFiles()?.sortedByDescending { it.lastModified() }?.take(15)?.forEach { f ->
                    val kb = (f.length() / 1024L).coerceAtLeast(1L)
                    entries.add("${f.name} ($kb KB)")
                }
            }
        }
        return entries
    }

    fun saveNoteFile(fileName: String, contentText: String): Pair<Boolean, String> {
        return try {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
            if (!dir.exists()) dir.mkdirs()
            val safeName = fileName.replace(Regex("[^a-zA-Z0-9\u0600-\u06FF._-]"), "_").ifBlank { "morvent_note.txt" }
            val finalName = if (safeName.contains(".")) safeName else "$safeName.txt"
            val target = File(dir, finalName)
            target.writeText(contentText)
            Pair(true, "تم حفظ الملف '$finalName' في مستندات التطبيق بنجاح 📁✓")
        } catch (e: Exception) {
            Pair(false, "تعذر حفظ الملف: ${e.message}")
        }
    }

    fun openSpecificSettings(section: String): Boolean {
        val action = when (section.lowercase(Locale.ROOT)) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "internet", "mobile_data", "data" -> Settings.ACTION_DATA_ROAMING_SETTINGS
            "hotspot", "tethering", "wireless" -> Settings.ACTION_WIRELESS_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display", "brightness", "dark_mode" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound", "volume" -> Settings.ACTION_SOUND_SETTINGS
            "location", "gps" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "apps", "applications" -> Settings.ACTION_APPLICATION_SETTINGS
            "default_apps" -> Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS
            "battery", "power_save" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "security" -> Settings.ACTION_SECURITY_SETTINGS
            "privacy" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Settings.ACTION_PRIVACY_SETTINGS
            } else Settings.ACTION_SECURITY_SETTINGS
            "date", "time" -> Settings.ACTION_DATE_SETTINGS
            "language" -> Settings.ACTION_LOCALE_SETTINGS
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "nfc" -> Settings.ACTION_NFC_SETTINGS
            "vpn" -> Settings.ACTION_VPN_SETTINGS
            "cast" -> Settings.ACTION_CAST_SETTINGS
            "developer" -> Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
            "about", "system_update" -> Settings.ACTION_DEVICE_INFO_SETTINGS
            "assistant", "voice_input" -> Settings.ACTION_VOICE_INPUT_SETTINGS
            "home_app", "launcher" -> Settings.ACTION_HOME_SETTINGS
            "notifications" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS
            } else Settings.ACTION_SETTINGS
            "dnd" -> Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return openSettings(action)
    }

    fun getInstalledAppsSummaryForAi(limit: Int = 55): String {
        val apps = getInstalledApps()
        val userApps = apps.filter { !it.isSystemApp }
        val sysApps = apps.filter { it.isSystemApp }
        return (userApps + sysApps).take(limit).joinToString(", ") { "${it.appName} (${it.packageName})" }
    }

    // ==========================================
    // 8B. HEADLESS CROSS-APP ACCESS & DEEP PERCEPTION
    // ==========================================
    fun getRecentUsedApps(limit: Int = 8): List<String> {
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager
                ?: return emptyList()
            val end = System.currentTimeMillis()
            val start = end - 24L * 60L * 60L * 1000L
            val stats = usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, start, end)
                ?: return emptyList()
            val pm = context.packageManager
            stats
                .filter { it.totalTimeInForeground > 1000L }
                .sortedByDescending { it.lastTimeUsed }
                .distinctBy { it.packageName }
                .take(limit)
                .map { s ->
                    val label = runCatching {
                        pm.getApplicationLabel(pm.getApplicationInfo(s.packageName, 0)).toString()
                    }.getOrDefault(s.packageName)
                    val mins = (s.totalTimeInForeground / 60000L).coerceAtLeast(1L)
                    "$label (${mins}د)"
                }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun readRecentCalls(limit: Int = 8): List<String> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        return try {
            val list = mutableListOf<String>()
            val uri = android.provider.CallLog.Calls.CONTENT_URI
            val projection = arrayOf(
                android.provider.CallLog.Calls.CACHED_NAME,
                android.provider.CallLog.Calls.NUMBER,
                android.provider.CallLog.Calls.TYPE,
                android.provider.CallLog.Calls.DURATION
            )
            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${android.provider.CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                val nameCol = cursor.getColumnIndex(android.provider.CallLog.Calls.CACHED_NAME)
                val numCol = cursor.getColumnIndex(android.provider.CallLog.Calls.NUMBER)
                val typeCol = cursor.getColumnIndex(android.provider.CallLog.Calls.TYPE)
                val durCol = cursor.getColumnIndex(android.provider.CallLog.Calls.DURATION)
                while (cursor.moveToNext() && list.size < limit) {
                    val name = cursor.getString(nameCol)?.takeIf { it.isNotBlank() }
                    val num = cursor.getString(numCol) ?: ""
                    val type = when (cursor.getInt(typeCol)) {
                        android.provider.CallLog.Calls.INCOMING_TYPE -> "واردة"
                        android.provider.CallLog.Calls.OUTGOING_TYPE -> "صادرة"
                        android.provider.CallLog.Calls.MISSED_TYPE -> "فائتة"
                        else -> "مكالمة"
                    }
                    val dur = cursor.getLong(durCol)
                    val display = name ?: num
                    list.add("• [$type] $display (${dur}ث)")
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun readRecentSms(limit: Int = 8): List<String> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        return try {
            val list = mutableListOf<String>()
            val uri = Uri.parse("content://sms/inbox")
            context.contentResolver.query(
                uri,
                arrayOf("address", "body"),
                null,
                null,
                "date DESC"
            )?.use { cursor ->
                val addrCol = cursor.getColumnIndex("address")
                val bodyCol = cursor.getColumnIndex("body")
                while (cursor.moveToNext() && list.size < limit) {
                    val sender = cursor.getString(addrCol) ?: "غير معروف"
                    val body = (cursor.getString(bodyCol) ?: "").replace("\n", " ").take(90)
                    list.add("• من $sender: $body")
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun readUpcomingCalendarEvents(limit: Int = 6): List<String> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        return try {
            val list = mutableListOf<String>()
            val now = System.currentTimeMillis()
            val projection = arrayOf(
                android.provider.CalendarContract.Events.TITLE,
                android.provider.CalendarContract.Events.DTSTART
            )
            val sdf = java.text.SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
            context.contentResolver.query(
                android.provider.CalendarContract.Events.CONTENT_URI,
                projection,
                "${android.provider.CalendarContract.Events.DTSTART} >= ?",
                arrayOf(now.toString()),
                "${android.provider.CalendarContract.Events.DTSTART} ASC"
            )?.use { cursor ->
                val titleCol = cursor.getColumnIndex(android.provider.CalendarContract.Events.TITLE)
                val startCol = cursor.getColumnIndex(android.provider.CalendarContract.Events.DTSTART)
                while (cursor.moveToNext() && list.size < limit) {
                    val title = cursor.getString(titleCol) ?: "حدث"
                    val dt = cursor.getLong(startCol)
                    list.add("• ${sdf.format(java.util.Date(dt))}: $title")
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addCalendarEvent(title: String, startMillis: Long = System.currentTimeMillis() + 3600_000L): Pair<Boolean, String> {
        return try {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = android.provider.CalendarContract.Events.CONTENT_URI
                putExtra(android.provider.CalendarContract.Events.TITLE, title)
                putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
                putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, startMillis + 3600_000L)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Pair(true, "تم فتح إضافة الموعد '$title' في التقويم 📅✓")
        } catch (e: Exception) {
            Pair(false, "تعذر فتح التقويم: ${e.message}")
        }
    }

    /**
     * Routes cross-app deep actions directly via package deep-links or background notifications
     * without forcing manual app browsing when possible.
     */
    fun executeAppDeepAction(appQuery: String, actionType: String, payload: String): Pair<Boolean, String> {
        val q = appQuery.lowercase(Locale.ROOT)
        val cleanPayload = payload.trim()
        return when {
            q.contains("youtube") || q.contains("يوتيوب") -> {
                val url = if (cleanPayload.isNotBlank()) {
                    "https://www.youtube.com/results?search_query=${Uri.encode(cleanPayload)}"
                } else {
                    "https://www.youtube.com"
                }
                val ok = openUrl(url)
                Pair(ok, if (ok) "تم تنفيذ بحث يوتيوب مباشرة: $cleanPayload ▶️" else "تعذر فتح يوتيوب")
            }
            q.contains("telegram") || q.contains("تيليجرام") || q.contains("تليجرام") -> {
                val uri = if (cleanPayload.startsWith("@") || !cleanPayload.contains(" ")) {
                    Uri.parse("https://t.me/${cleanPayload.removePrefix("@")}")
                } else {
                    Uri.parse("tg://resolve?domain=${Uri.encode(cleanPayload)}")
                }
                val ok = runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    true
                }.getOrDefault(false)
                Pair(ok, if (ok) "تم فتح قناة/محادثة تيليجرام مباشرة: $cleanPayload ✈️" else "تعذر فتح تيليجرام")
            }
            q.contains("spotify") || q.contains("سبوتيفاي") -> {
                val uri = Uri.parse("spotify:search:${Uri.encode(cleanPayload)}")
                val ok = runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    true
                }.getOrElse {
                    openUrl("https://open.spotify.com/search/${Uri.encode(cleanPayload)}")
                }
                Pair(ok, if (ok) "تم تشغيل بحث سبوتيفاي مباشرة: $cleanPayload 🎵" else "تعذر فتح سبوتيفاي")
            }
            q.contains("maps") || q.contains("خرائط") -> {
                val ok = openMapsSearch(cleanPayload)
                Pair(ok, if (ok) "تم فتح خرائط جوجل للموقع: $cleanPayload 🗺️" else "تعذر فتح الخرائط")
            }
            q.contains("gmail") || q.contains("ايميل") || q.contains("بريد") -> {
                val ok = composeEmail("", "رسالة من مورڤينت", cleanPayload)
                Pair(ok, if (ok) "تم تجهيز رسالة البريد الإلكتروني ✉️" else "تعذر فتح البريد")
            }
            actionType.equals("settings", ignoreCase = true) -> openAppDetails(appQuery)
            else -> launchAppByQuery(appQuery)
        }
    }

    fun getUnifiedSystemContextSnapshot(): String {
        val sb = StringBuilder()
        val a11ySummary = com.example.service.MorventAccessibilityService.getCompactLivePerceptionSummary()
        val recentApps = getRecentUsedApps(5)
        val notifList = com.example.service.MorventNotificationListenerService.recentMessages.value
            .take(5)
            .map { "${it.appLabel} (${it.sender}): ${it.text.take(40)}" }
        val batt = getBatteryDetails()
        sb.appendLine("[لقطة النظام الحية الشاملة]:")
        sb.appendLine("- البطارية: ${batt.percentage}% (${if (batt.isCharging) "يشحن" else "غير موصول"}) | السطوع: ${getScreenBrightnessPercent()}%")
        if (a11ySummary.isNotBlank()) {
            sb.appendLine("- الواجهة النشطة حالياً: $a11ySummary")
        }
        if (recentApps.isNotEmpty()) {
            sb.appendLine("- آخر التطبيقات استخداماً في النظام: ${recentApps.joinToString(", ")}")
        }
        if (notifList.isNotEmpty()) {
            sb.appendLine("- أحدث إشعارات الخلفية (${notifList.size}): ${notifList.take(3).joinToString(" | ")}")
        }
        return sb.toString().trim()
    }

    // ==========================================
    // 9. VOICE & HAPTICS
    // ==========================================
    fun speak(text: String) {
        if (!isTtsReady) return
        val cleanText = text.replace(Regex("[\\[\\]*#_]"), "")
        tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "morvent_speech")
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    fun vibratePulse(durationMs: Long = 50) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                v?.vibrate(durationMs)
            }
        } catch (e: Exception) {
            // Ignore if vibration fails
        }
    }

    fun cleanup() {
        try {
            sensorManager?.unregisterListener(this)
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            // Ignore
        }
    }
}
