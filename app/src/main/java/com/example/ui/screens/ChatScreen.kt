package com.example.ui.screens

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ChatMessage
import com.example.model.MessageStatus
import com.example.nlp.AgentExecutionState
import com.example.nlp.AgentStatus
import com.example.ui.components.ActionCardContainer
import com.example.ui.components.SubStepsExpander
import com.example.ui.theme.MorventAmber
import com.example.ui.theme.MorventCyan
import com.example.ui.theme.MorventDarkBorder
import com.example.ui.theme.MorventDarkSurface
import com.example.ui.theme.MorventDarkSurfaceElevated
import com.example.ui.theme.MorventEmerald
import com.example.ui.theme.MorventPurple
import com.example.ui.theme.MorventRose

@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    isThinking: Boolean,
    agentStatus: AgentStatus,
    onSendCommand: (String) -> Unit,
    onPauseAgent: () -> Unit,
    onResumeAgent: () -> Unit,
    onSkipAgent: () -> Unit = {},
    onStopAgent: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenWriteSettings: () -> Unit = {},
    onOpenDndSettings: () -> Unit = {},
    onOpenNotificationListenerSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
            if (!spoken.isNullOrEmpty()) {
                onSendCommand(spoken)
            }
        }
    }

    val quickChips = listOf(
        "💬 اقرأ رسائل واتساب",
        "📞 رد على المكالمة",
        "📵 اقفل المكالمة",
        "🤖 تفعيل التنفيذ الصامت",
        "🚀 اشتغل في الخلفية",
        "🪟 تقسيم الشاشة",
        "🔔 اقرأ الإشعارات",
        "🔕 تفعيل عدم الإزعاج",
        "☀️ اضبط السطوع 80%",
        "⚡ قائمة الطاقة",
        "🧩 افتح يوتيوب ثم ابحث عن القرآن الكريم ثم انتظر 2 ثانية ثم انزل لأسفل",
        "📖 اقرأ لي ما على الشاشة",
        "📺 افتح يوتيوب وشغل قرآن كريم",
        "🌙 افتح الإعدادات وفعل الوضع الداكن",
        "🔍 افحص الشاشة",
        "🔐 فتح حساب جديد",
        "🔑 تسجيل الدخول بجوجل",
        "⬇️ انزل لأسفل 3 مرات",
        "💡 تشغيل الكشاف",
        "🔋 فحص البطارية",
        "🔊 رفع الصوت",
        "🔇 كتم الصوت",
        "📊 فحص الهاتف",
        "🏠 الشاشة الرئيسية",
        "◀️ الرجوع للخلف",
        "📸 لقطة شاشة",
        "💼 وضع الاجتماع",
        "⚙️ الإعدادات"
    )

    LaunchedEffect(messages.size, isThinking) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MorventDarkSurface)
    ) {
        // Live Artemis Background Execution Banner (command, skip, append & steer while working)
        AnimatedVisibility(visible = agentStatus.isActive) {
            LiveArtemisBanner(
                agentStatus = agentStatus,
                onPause = onPauseAgent,
                onResume = onResumeAgent,
                onSkip = onSkipAgent,
                onStop = onStopAgent
            )
        }

        // Chat messages LazyColumn
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                ChatBubble(
                    message = msg,
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                    onOpenOverlaySettings = onOpenOverlaySettings,
                    onOpenWriteSettings = onOpenWriteSettings,
                    onOpenDndSettings = onOpenDndSettings,
                    onOpenNotificationListenerSettings = onOpenNotificationListenerSettings
                )
            }

            if (isThinking) {
                item {
                    ThinkingIndicator()
                }
            }
        }

        // Quick command chips row
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(quickChips) { chip ->
                val cleanCmd = chip.replace(Regex("^[\\p{So}\\p{Sk}]+\\s*"), "")
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MorventDarkSurfaceElevated,
                    modifier = Modifier
                        .border(1.dp, MorventDarkBorder, RoundedCornerShape(20.dp))
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { onSendCommand(cleanCmd) }
                ) {
                    Text(
                        text = chip,
                        style = MaterialTheme.typography.bodySmall,
                        color = MorventCyan,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // Command Input Bar
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            color = MorventDarkSurfaceElevated,
            shape = RoundedCornerShape(26.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, MorventDarkBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        try {
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(
                                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                                )
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA")
                                putExtra(RecognizerIntent.EXTRA_PROMPT, "تحدث الآن لتوجيه مورڤينت...")
                            }
                            speechLauncher.launch(intent)
                        } catch (_: Exception) {
                            inputText = "فحص شامل للجهاز"
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("voice_input_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "أمر صوتي",
                        tint = MorventPurple
                    )
                }

                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = {
                        Text(
                            text = if (agentStatus.isActive) "وجّه الوكيل، أضف خطوات بـ 'أضف:'، أو قل 'تخطي'..."
                            else "اطلب من مورڤينت: مهمة طويلة، أتمتة، تحكم...",
                            color = Color.Gray,
                            fontSize = 13.sp
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        cursorColor = MorventCyan,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("chat_input_field"),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (inputText.isNotBlank()) {
                            onSendCommand(inputText)
                            inputText = ""
                        }
                    })
                )

                IconButton(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            onSendCommand(inputText)
                            inputText = ""
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(listOf(MorventCyan, MorventPurple))
                        )
                        .testTag("send_command_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "إرسال",
                        tint = Color.Black,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveArtemisBanner(
    agentStatus: AgentStatus,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSkip: () -> Unit,
    onStop: () -> Unit
) {
    val isPaused = agentStatus.state == AgentExecutionState.PAUSED
    val queuedBadge = if (agentStatus.queuedStepsCount > 0) " • +${agentStatus.queuedStepsCount} مهام بالانتظار" else ""
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .border(1.dp, if (isPaused) MorventAmber else MorventCyan, RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF0E1B30)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isPaused) "⏸️ وكيل Artemis متوقف مؤقتاً$queuedBadge"
                        else "⚡ وكيل Artemis ينفذ (${agentStatus.currentStepIndex}/${agentStatus.totalSteps})$queuedBadge",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = if (isPaused) MorventAmber else MorventCyan
                    )
                    Text(
                        text = agentStatus.statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.LightGray,
                        fontSize = 11.sp,
                        maxLines = 2
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = if (isPaused) onResume else onPause,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MorventAmber.copy(alpha = 0.2f))
                            .testTag("agent_pause_resume_btn")
                    ) {
                        Icon(
                            imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = if (isPaused) "استئناف" else "إيقاف مؤقت",
                            tint = MorventAmber,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    IconButton(
                        onClick = onSkip,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MorventPurple.copy(alpha = 0.2f))
                            .testTag("agent_skip_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "تخطي الخطوة",
                            tint = MorventPurple,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    IconButton(
                        onClick = onStop,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MorventRose.copy(alpha = 0.2f))
                            .testTag("agent_stop_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "إيقاف",
                            tint = MorventRose,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            if (agentStatus.totalSteps > 0) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { (agentStatus.currentStepIndex.toFloat() / agentStatus.totalSteps).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = if (isPaused) MorventAmber else MorventCyan,
                    trackColor = Color(0xFF1E2D47)
                )
            }

            val recentLogs = agentStatus.logs.takeLast(2)
            if (recentLogs.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                recentLogs.forEach { logLine ->
                    Text(
                        text = "• $logLine",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8),
                        fontSize = 10.sp,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
fun ChatBubble(
    message: ChatMessage,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenWriteSettings: () -> Unit = {},
    onOpenDndSettings: () -> Unit = {},
    onOpenNotificationListenerSettings: () -> Unit = {}
) {
    val isUser = message.isUser

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (!isUser) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(MorventCyan.copy(alpha = 0.2f))
                    .border(1.dp, MorventCyan.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.SmartToy,
                    contentDescription = "Morvent",
                    tint = MorventCyan,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(
            modifier = Modifier.widthIn(max = 320.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (isUser) 16.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 16.dp
                ),
                color = if (isUser) Color(0xFF005662) else MorventDarkSurfaceElevated,
                border = if (isUser) null else androidx.compose.foundation.BorderStroke(1.dp, MorventDarkBorder),
                modifier = Modifier.widthIn(min = 60.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (message.status == MessageStatus.ERROR) MorventRose else Color.White,
                        lineHeight = 20.sp
                    )

                    if (message.actionDetail == "REQUEST_ACCESSIBILITY_PERMISSION") {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MorventPurple.copy(alpha = 0.2f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onOpenAccessibilitySettings() }
                                .padding(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Accessibility,
                                    contentDescription = null,
                                    tint = MorventPurple,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "فتح إعدادات إمكانية الوصول لتفعيل مورڤينت",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MorventPurple,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    if (message.actionDetail == "REQUEST_OVERLAY_PERMISSION") {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MorventCyan.copy(alpha = 0.2f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onOpenOverlaySettings() }
                                .padding(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Layers,
                                    contentDescription = null,
                                    tint = MorventCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "منح إذن الظهور فوق التطبيقات الأخرى",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MorventCyan,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    if (message.actionDetail == "REQUEST_WRITE_SETTINGS_PERMISSION") {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MorventAmber.copy(alpha = 0.2f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onOpenWriteSettings() }
                                .padding(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Settings,
                                    contentDescription = null,
                                    tint = MorventAmber,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "منح إذن تعديل إعدادات النظام (السطوع والتدوير)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MorventAmber,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    if (message.actionDetail == "REQUEST_DND_PERMISSION") {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MorventEmerald.copy(alpha = 0.2f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onOpenDndSettings() }
                                .padding(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.DoNotDisturbOn,
                                    contentDescription = null,
                                    tint = MorventEmerald,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "منح صلاحية وضع عدم الإزعاج بالنظام (DND)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MorventEmerald,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    if (message.actionDetail == "REQUEST_NOTIFICATION_LISTENER") {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MorventCyan.copy(alpha = 0.2f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onOpenNotificationListenerSettings() }
                                .padding(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.SmartToy,
                                    contentDescription = null,
                                    tint = MorventCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "تفعيل قراءة رسائل واتساب والرد التلقائي بالخلفية",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MorventCyan,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    message.cardData?.let { card ->
                        ActionCardContainer(cardData = card)
                    }

                    if (message.subSteps.isNotEmpty()) {
                        SubStepsExpander(steps = message.subSteps)
                    }
                }
            }
        }
    }
}

@Composable
fun ThinkingIndicator() {
    Row(
        modifier = Modifier.padding(start = 42.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = MorventCyan
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "مورڤينت يحلل الأمر وينفذ الإجراء...",
            style = MaterialTheme.typography.bodySmall,
            color = MorventCyan,
            fontSize = 12.sp
        )
    }
}
