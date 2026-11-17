package com.example.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MarkChatRead
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Splitscreen
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.DeviceStatus
import com.example.ui.theme.MorventAmber
import com.example.ui.theme.MorventCyan
import com.example.ui.theme.MorventDarkBorder
import com.example.ui.theme.MorventDarkSurface
import com.example.ui.theme.MorventDarkSurfaceElevated
import com.example.ui.theme.MorventEmerald
import com.example.ui.theme.MorventPurple
import com.example.ui.theme.MorventRose

@Composable
fun ControlHubScreen(
    deviceStatus: DeviceStatus,
    onToggleFlashlight: () -> Unit,
    onSetVolume: (Int) -> Unit,
    onSetRingerMode: (Int) -> Unit,
    onCommand: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    isFloatingRunning: Boolean = false,
    isHumanPaced: Boolean = true,
    onToggleFloatingService: () -> Unit = {},
    onToggleHumanPacing: () -> Unit = {},
    onSetBrightness: (Int) -> Unit = {},
    onSetAutoRotate: (Boolean) -> Unit = {},
    onSetDoNotDisturb: (Boolean) -> Unit = {},
    onOpenWriteSettings: () -> Unit = {},
    onOpenDndSettings: () -> Unit = {},
    onOpenOverlaySettings: () -> Unit = {},
    silentExecutionMode: Boolean = true,
    autoReplyWhatsApp: Boolean = false,
    adbBridgeCommands: String = "",
    onToggleSilentExecution: () -> Unit = {},
    onToggleAutoReplyWhatsApp: () -> Unit = {},
    onOpenNotificationListenerSettings: () -> Unit = {},
    onOpenBatteryOptimizationSettings: () -> Unit = {},
    onRequestPhoneContactsPermissions: () -> Unit = {},
    onOpenUsageAccess: () -> Unit = {},
    onOpenTerminalTab: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val clipboardManager = LocalClipboardManager.current
    var showAdbBridge by remember { mutableStateOf(false) }
    var adbCopied by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MorventDarkSurface)
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top status header with refresh
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "لوحة التحكم السريعة",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
                Text(
                    text = "تحكم مباشر بكافة وظائف ومستشعرات الهاتف",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
            IconButton(
                onClick = onRefresh,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MorventDarkSurfaceElevated)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "تحديث", tint = MorventCyan)
            }
        }

        // ========================================================
        // Hardware-Level OS Bridge & Silent Permissions Card
        // ========================================================
        val activePerms = listOf(
            deviceStatus.accessibilityServiceActive,
            deviceStatus.notificationListenerActive,
            deviceStatus.batteryOptimizationIgnored,
            deviceStatus.overlayPermissionGranted,
            deviceStatus.canWriteSystemSettings,
            deviceStatus.canManageDnd,
            deviceStatus.callPermissionsGranted && deviceStatus.contactsPermissionGranted,
            deviceStatus.writeSecureSettingsGranted,
            deviceStatus.usageStatsGranted,
            deviceStatus.smsPermissionGranted && deviceStatus.callLogPermissionGranted && deviceStatus.calendarPermissionGranted
        ).count { it }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MorventEmerald.copy(alpha = 0.55f), RoundedCornerShape(20.dp)),
            colors = CardDefaults.cardColors(containerColor = MorventDarkSurfaceElevated)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MorventEmerald.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Security, contentDescription = null, tint = MorventEmerald, modifier = Modifier.size(22.dp))
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "جسر التحكم الكامل بالهاتف (OS Bridge)",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                            Text(
                                text = "مفعّل $activePerms من 10 صلاحيات للتحكم الصامت والنواة",
                                style = MaterialTheme.typography.labelSmall,
                                color = MorventEmerald,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (activePerms / 10f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = MorventEmerald,
                    trackColor = Color(0xFF1E2D47)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Grid of 1-tap OS permission pills
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OsPermissionPill(
                        label = "إمكانية الوصول",
                        granted = deviceStatus.accessibilityServiceActive,
                        onClick = onOpenAccessibilitySettings,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("bridge_accessibility_pill")
                    )
                    OsPermissionPill(
                        label = "واتساب/إشعارات",
                        granted = deviceStatus.notificationListenerActive,
                        onClick = onOpenNotificationListenerSettings,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("bridge_notification_pill")
                    )
                    OsPermissionPill(
                        label = "البطارية (خلفية)",
                        granted = deviceStatus.batteryOptimizationIgnored,
                        onClick = onOpenBatteryOptimizationSettings,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("bridge_battery_pill")
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OsPermissionPill(
                        label = "مكالمات/أسماء",
                        granted = deviceStatus.callPermissionsGranted && deviceStatus.contactsPermissionGranted,
                        onClick = onRequestPhoneContactsPermissions,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("bridge_calls_contacts_pill")
                    )
                    OsPermissionPill(
                        label = "فوق التطبيقات",
                        granted = deviceStatus.overlayPermissionGranted,
                        onClick = onOpenOverlaySettings,
                        modifier = Modifier.weight(1f)
                    )
                    OsPermissionPill(
                        label = "النواة (ADB)",
                        granted = deviceStatus.writeSecureSettingsGranted,
                        onClick = { showAdbBridge = !showAdbBridge },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OsPermissionPill(
                        label = "نشاط التطبيقات",
                        granted = deviceStatus.usageStatsGranted,
                        onClick = onOpenUsageAccess,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("bridge_usage_stats_pill")
                    )
                    OsPermissionPill(
                        label = "SMS/سجل/تقويم",
                        granted = deviceStatus.smsPermissionGranted && deviceStatus.callLogPermissionGranted && deviceStatus.calendarPermissionGranted,
                        onClick = onRequestPhoneContactsPermissions,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("bridge_sms_calendar_pill")
                    )
                    OsPermissionPill(
                        label = "طرفية لينكس 🐧",
                        granted = true,
                        onClick = onOpenTerminalTab,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("bridge_linux_terminal_pill")
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Silent Execution Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "التنفيذ التلقائي الصامت (Silent Execution)",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                        Text(
                            text = "تنفيذ فوري في الخلفية كالإنسان دون طلب تأكيدات أو خطوات يدوية",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = silentExecutionMode,
                        onCheckedChange = { onToggleSilentExecution() },
                        modifier = Modifier.testTag("silent_execution_switch"),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = MorventEmerald,
                            uncheckedThumbColor = Color.Gray,
                            uncheckedTrackColor = Color(0xFF1E2B42)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Auto-Reply WhatsApp & Messaging Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "الرد التلقائي الذكي على واتساب والرسائل",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                        Text(
                            text = "قراءة الرسائل الواردة والرد عليها بالخلفية بصمت عبر Gemini + RemoteInput",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = autoReplyWhatsApp,
                        onCheckedChange = { onToggleAutoReplyWhatsApp() },
                        modifier = Modifier.testTag("auto_reply_wa_switch"),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = MorventCyan,
                            uncheckedThumbColor = Color.Gray,
                            uncheckedTrackColor = Color(0xFF1E2B42)
                        )
                    )
                }

                // Expandable ADB Bridge Panel
                if (adbBridgeCommands.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF0E1728),
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, MorventAmber.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAdbBridge = !showAdbBridge }
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "⚡ أوامر جسر ADB لمنح كامل صلاحيات النواة بضغطة",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MorventAmber
                                )
                                Icon(
                                    imageVector = if (showAdbBridge) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = "توسيع",
                                    tint = MorventAmber,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            if (showAdbBridge) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = adbBridgeCommands,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = Color(0xFFBAE6FD),
                                    fontSize = 10.sp
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MorventAmber.copy(alpha = 0.2f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            clipboardManager.setText(AnnotatedString(adbBridgeCommands))
                                            adbCopied = true
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(vertical = 8.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = null, tint = MorventAmber, modifier = Modifier.size(15.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (adbCopied) "تم نسخ أوامر ADB بنجاح ✓" else "نسخ أوامر التفعيل السريع (ADB)",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = MorventAmber
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ========================================================
        // Artemis Autonomous Background Agent & Human Touch Card
        // ========================================================
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MorventCyan.copy(alpha = 0.5f), RoundedCornerShape(20.dp)),
            colors = CardDefaults.cardColors(containerColor = MorventDarkSurfaceElevated)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MorventCyan.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Layers, contentDescription = null, tint = MorventCyan, modifier = Modifier.size(22.dp))
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "نظام الأتمتة البشري والعمل في الخلفية",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White
                                )
                            }
                            Text(
                                text = "Artemis Autonomous Mobile Agent",
                                style = MaterialTheme.typography.labelSmall,
                                color = MorventCyan,
                                fontSize = 10.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Toggle 1: Background Floating Overlay HUD
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "تشغيل مورڤينت فوق كافة التطبيقات",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                        Text(
                            text = "فقاعة ذكية عائمة للتحكم وإعطاء الأوامر أثناء استخدام أي تطبيق آخر",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = isFloatingRunning,
                        onCheckedChange = { onToggleFloatingService() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = MorventCyan,
                            uncheckedThumbColor = Color.Gray,
                            uncheckedTrackColor = Color(0xFF1E2B42)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Toggle 2: Human-like Touch & Trajectory Simulation
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "محاكاة اللمس البشري الطبيعي",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                        Text(
                            text = "إيماءات لمس منحنية (Bézier Curves) وفترات استقرار طبيعية كاللمس البشري",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = isHumanPaced,
                        onCheckedChange = { onToggleHumanPacing() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = MorventEmerald,
                            uncheckedThumbColor = Color.Gray,
                            uncheckedTrackColor = Color(0xFF1E2B42)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Quick autonomous action chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF111E33),
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, MorventCyan.copy(alpha = 0.3f)),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onCommand("سجل دخول بواسطة جوجل") }
                    ) {
                        Text(
                            text = "🔐 فتح حساب",
                            style = MaterialTheme.typography.labelSmall,
                            color = MorventCyan,
                            modifier = Modifier.padding(vertical = 8.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF111E33),
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, MorventEmerald.copy(alpha = 0.3f)),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onCommand("تصفح لأسفل") }
                    ) {
                        Text(
                            text = "⬇️ تصفح بشري",
                            style = MaterialTheme.typography.labelSmall,
                            color = MorventEmerald,
                            modifier = Modifier.padding(vertical = 8.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF111E33),
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, MorventPurple.copy(alpha = 0.3f)),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onCommand("افحص الشاشة") }
                    ) {
                        Text(
                            text = "🔍 فحص الشاشة",
                            style = MaterialTheme.typography.labelSmall,
                            color = MorventPurple,
                            modifier = Modifier.padding(vertical = 8.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        }

        // Flashlight & Battery Duo Cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Flashlight Giant Toggle
            val flashColor by animateColorAsState(
                targetValue = if (deviceStatus.flashlightOn) MorventEmerald else MorventDarkSurfaceElevated,
                label = "flashColor"
            )
            Card(
                modifier = Modifier
                    .weight(1f)
                    .height(130.dp)
                    .border(1.dp, if (deviceStatus.flashlightOn) MorventEmerald else MorventDarkBorder, RoundedCornerShape(18.dp))
                    .clip(RoundedCornerShape(18.dp))
                    .clickable { onToggleFlashlight() }
                    .testTag("flashlight_toggle_card"),
                colors = CardDefaults.cardColors(containerColor = flashColor)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(if (deviceStatus.flashlightOn) Color.White.copy(alpha = 0.3f) else Color(0xFF1E2B42)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (deviceStatus.flashlightOn) Icons.Default.FlashlightOn else Icons.Default.FlashlightOff,
                            contentDescription = "كشاف",
                            tint = if (deviceStatus.flashlightOn) Color.White else MorventCyan
                        )
                    }
                    Column {
                        Text(
                            text = if (deviceStatus.flashlightOn) "الكشاف مشتعل 💡" else "كشاف الفلاش",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = if (deviceStatus.flashlightOn) Color.Black else Color.White
                        )
                        Text(
                            text = if (deviceStatus.flashlightOn) "انقر للإطفاء" else "انقر للتشغيل",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (deviceStatus.flashlightOn) Color.DarkGray else Color.Gray,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // Battery Level Card
            Card(
                modifier = Modifier
                    .weight(1f)
                    .height(130.dp)
                    .border(1.dp, MorventDarkBorder, RoundedCornerShape(18.dp)),
                colors = CardDefaults.cardColors(containerColor = MorventDarkSurfaceElevated)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(MorventEmerald.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.BatteryChargingFull,
                                contentDescription = "بطارية",
                                tint = MorventEmerald
                            )
                        }
                        Text(
                            text = "${deviceStatus.batteryLevel}%",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MorventEmerald
                        )
                    }
                    Column {
                        LinearProgressIndicator(
                            progress = { deviceStatus.batteryLevel / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = MorventEmerald,
                            trackColor = Color(0xFF1E3247)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (deviceStatus.isCharging) "قيد الشحن ⚡ (${deviceStatus.batteryTemp}°C)" else "حرارة ${deviceStatus.batteryTemp}°C",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.LightGray,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        // Sound & Volume Controls
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MorventDarkBorder, RoundedCornerShape(18.dp)),
            colors = CardDefaults.cardColors(containerColor = MorventDarkSurfaceElevated)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "التحكم في الصوت والرنين",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(10.dp))

                // Mode Toggles (Normal, Vibrate, Silent)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SoundModePill(
                        icon = Icons.AutoMirrored.Filled.VolumeUp,
                        label = "عام",
                        isSelected = deviceStatus.ringerMode == 2,
                        onClick = { onSetRingerMode(2) },
                        modifier = Modifier.weight(1f)
                    )
                    SoundModePill(
                        icon = Icons.Default.Vibration,
                        label = "اهتزاز",
                        isSelected = deviceStatus.ringerMode == 1,
                        onClick = { onSetRingerMode(1) },
                        modifier = Modifier.weight(1f)
                    )
                    SoundModePill(
                        icon = Icons.AutoMirrored.Filled.VolumeOff,
                        label = "صامت",
                        isSelected = deviceStatus.ringerMode == 0,
                        onClick = { onSetRingerMode(0) },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("مستوى صوت الوسائط", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Text("${deviceStatus.mediaVolumePercent}%", style = MaterialTheme.typography.bodySmall, color = MorventCyan)
                }
                Slider(
                    value = deviceStatus.mediaVolumePercent.toFloat(),
                    onValueChange = { onSetVolume(it.toInt()) },
                    valueRange = 0f..100f,
                    colors = SliderDefaults.colors(
                        thumbColor = MorventCyan,
                        activeTrackColor = MorventCyan,
                        inactiveTrackColor = Color(0xFF1E2D47)
                    )
                )
            }
        }

        // System Navigation & Gestures via Morvent Accessibility Service
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MorventDarkBorder, RoundedCornerShape(18.dp)),
            colors = CardDefaults.cardColors(containerColor = MorventDarkSurfaceElevated)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "إيماءات التحكم بنظام الهاتف",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (deviceStatus.accessibilityServiceActive) MorventEmerald.copy(alpha = 0.2f) else MorventRose.copy(alpha = 0.2f),
                        modifier = Modifier.clickable { onOpenAccessibilitySettings() }
                    ) {
                        Text(
                            text = if (deviceStatus.accessibilityServiceActive) "الخدمة مفعلة ✓" else "تفعيل الخدمة ⚙️",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (deviceStatus.accessibilityServiceActive) MorventEmerald else MorventRose,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            fontSize = 11.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    NavActionButton(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") { onCommand("الرجوع للخلف") }
                    NavActionButton(Icons.Default.Home, "الرئيسية") { onCommand("الشاشة الرئيسية") }
                    NavActionButton(Icons.Default.ViewCarousel, "المهام") { onCommand("التطبيقات الأخيرة") }
                    NavActionButton(Icons.Default.Notifications, "الإشعارات") { onCommand("افتح الإشعارات") }
                    NavActionButton(Icons.Default.Screenshot, "لقطة") { onCommand("لقطة شاشة") }
                }
            }
        }

        // Whole-OS Android System Controls Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MorventPurple.copy(alpha = 0.45f), RoundedCornerShape(18.dp)),
            colors = CardDefaults.cardColors(containerColor = MorventDarkSurfaceElevated)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "التحكم الكامل بنظام أندرويد (Whole OS)",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                    Text(
                        text = if (deviceStatus.powerSaveMode) "توفير الطاقة نشط 🔋" else "وضع قياسي",
                        style = MaterialTheme.typography.labelSmall,
                        color = MorventCyan,
                        fontSize = 10.sp
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Permissions Status Strip (1-tap grant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OsPermissionPill(
                        label = "الوصول",
                        granted = deviceStatus.accessibilityServiceActive,
                        onClick = onOpenAccessibilitySettings,
                        modifier = Modifier.weight(1f)
                    )
                    OsPermissionPill(
                        label = "الطفو",
                        granted = deviceStatus.overlayPermissionGranted,
                        onClick = onOpenOverlaySettings,
                        modifier = Modifier.weight(1f)
                    )
                    OsPermissionPill(
                        label = "النظام",
                        granted = deviceStatus.canWriteSystemSettings,
                        onClick = onOpenWriteSettings,
                        modifier = Modifier.weight(1f)
                    )
                    OsPermissionPill(
                        label = "عدم الإزعاج",
                        granted = deviceStatus.canManageDnd,
                        onClick = onOpenDndSettings,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Brightness Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.BrightnessHigh, contentDescription = null, tint = MorventCyan, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("سطوع شاشة النظام", style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${deviceStatus.brightnessPercent}%", style = MaterialTheme.typography.bodySmall, color = MorventCyan)
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MorventCyan.copy(alpha = 0.15f),
                            modifier = Modifier.clickable { onCommand("سطوع تلقائي") }
                        ) {
                            Text(
                                text = "تلقائي",
                                style = MaterialTheme.typography.labelSmall,
                                color = MorventCyan,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                fontSize = 10.sp
                            )
                        }
                    }
                }
                Slider(
                    value = deviceStatus.brightnessPercent.toFloat().coerceIn(1f, 100f),
                    onValueChange = { onSetBrightness(it.toInt()) },
                    valueRange = 5f..100f,
                    modifier = Modifier.testTag("brightness_slider"),
                    colors = SliderDefaults.colors(
                        thumbColor = MorventPurple,
                        activeTrackColor = MorventPurple,
                        inactiveTrackColor = Color(0xFF1E2D47)
                    )
                )

                // Auto-Rotate & Do Not Disturb Switches
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF131D31),
                        modifier = Modifier
                            .weight(1f)
                            .border(1.dp, MorventDarkBorder, RoundedCornerShape(12.dp))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.ScreenRotation, contentDescription = null, tint = MorventCyan, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("التدوير", style = MaterialTheme.typography.labelMedium, color = Color.White)
                            }
                            Switch(
                                checked = deviceStatus.autoRotateEnabled,
                                onCheckedChange = { onSetAutoRotate(it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = MorventCyan)
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF131D31),
                        modifier = Modifier
                            .weight(1f)
                            .border(1.dp, MorventDarkBorder, RoundedCornerShape(12.dp))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.DoNotDisturbOn, contentDescription = null, tint = MorventRose, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("عدم إزعاج", style = MaterialTheme.typography.labelMedium, color = Color.White)
                            }
                            Switch(
                                checked = deviceStatus.dndEnabled,
                                onCheckedChange = { onSetDoNotDisturb(it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = MorventRose)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Whole-OS Action Grid Row 1
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    NavActionButton(Icons.Default.Splitscreen, "تقسيم") { onCommand("تقسيم الشاشة") }
                    NavActionButton(Icons.Default.PowerSettingsNew, "الطاقة") { onCommand("قائمة الطاقة") }
                    NavActionButton(Icons.Default.Apps, "التطبيقات") { onCommand("درج التطبيقات") }
                    NavActionButton(Icons.Default.Tune, "سريعة") { onCommand("الإعدادات السريعة") }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Whole-OS Action Grid Row 2
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    NavActionButton(Icons.Default.Public, "الإنترنت") { onCommand("لوحة الإنترنت") }
                    NavActionButton(Icons.Default.WifiTethering, "هوتسبوت") { onCommand("تبديل نقطة الاتصال") }
                    NavActionButton(Icons.Default.ClearAll, "مسح إشعار") { onCommand("امسح الإشعارات") }
                    NavActionButton(Icons.Default.Lock, "قفل") { onCommand("اقفل الشاشة") }
                }
            }
        }

        // System Settings Hub
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MorventDarkBorder, RoundedCornerShape(18.dp)),
            colors = CardDefaults.cardColors(containerColor = MorventDarkSurfaceElevated)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "اختصارات إعدادات النظام",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    QuickLauncherItem(Icons.Default.Wifi, "الواي فاي") { onCommand("افتح الواي فاي") }
                    QuickLauncherItem(Icons.Default.Bluetooth, "البلوتوث") { onCommand("افتح البلوتوث") }
                    QuickLauncherItem(Icons.Default.BrightnessMedium, "السطوع") { onCommand("اعدادات الشاشة") }
                    QuickLauncherItem(Icons.Default.Alarm, "المنبه") { onCommand("اضبط منبه على الساعة 7") }
                    QuickLauncherItem(Icons.Default.Settings, "الإعدادات") { onCommand("افتح الإعدادات") }
                }
            }
        }

        // Hardware Storage & RAM
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MorventDarkBorder, RoundedCornerShape(18.dp)),
            colors = CardDefaults.cardColors(containerColor = MorventDarkSurfaceElevated)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "موارد العتاد والتخزين",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("الذاكرة العشوائية RAM", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                        Text("${deviceStatus.freeRamGb} GB حرة من ${deviceStatus.totalRamGb} GB", style = MaterialTheme.typography.bodySmall, color = MorventPurple)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("سعة التخزين الداخلي", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                        Text("${deviceStatus.freeStorageGb} GB متوفرة", style = MaterialTheme.typography.bodySmall, color = MorventCyan)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
private fun SoundModePill(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) MorventCyan else Color(0xFF131D31),
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) Color.Black else Color.LightGray,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = if (isSelected) Color.Black else Color.LightGray,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun NavActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color(0xFF162238))
                .border(1.dp, MorventDarkBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = MorventCyan, modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = Color.LightGray, fontSize = 11.sp)
    }
}

@Composable
private fun QuickLauncherItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MorventPurple.copy(alpha = 0.15f))
                .border(1.dp, MorventPurple.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = MorventPurple, modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = Color.LightGray, fontSize = 11.sp)
    }
}

@Composable
private fun OsPermissionPill(
    label: String,
    granted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (granted) MorventEmerald.copy(alpha = 0.15f) else MorventRose.copy(alpha = 0.15f),
        border = androidx.compose.foundation.BorderStroke(
            0.5.dp,
            if (granted) MorventEmerald.copy(alpha = 0.4f) else MorventRose.copy(alpha = 0.4f)
        ),
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        Text(
            text = if (granted) "$label ✓" else "$label !",
            style = MaterialTheme.typography.labelSmall,
            color = if (granted) MorventEmerald else MorventRose,
            fontSize = 10.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(vertical = 5.dp, horizontal = 4.dp)
        )
    }
}
