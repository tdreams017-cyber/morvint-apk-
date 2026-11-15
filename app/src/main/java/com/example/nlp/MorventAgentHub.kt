package com.example.nlp

import android.content.Context
import android.provider.Settings
import com.example.ai.ApiKeyManager
import com.example.cloud.MorventCloudRepository
import com.example.device.DeviceController
import com.example.model.ActionType
import com.example.model.ChatMessage
import com.example.model.MessageStatus
import com.example.service.MorventFloatingAgentService
import com.example.service.MorventNotificationListenerService
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Shared singleton coordinator synchronizing Morvent's Chat UI,
 * Background Floating HUD Service, Notification RemoteInput, and
 * the Gemini-powered Artemis Autonomous Mobile Agent.
 */
class MorventAgentHub private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val hubScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    val apiKeyManager = ApiKeyManager.getInstance(appContext)
    val deviceController = DeviceController(appContext)
    val artemisEngine = ArtemisAgentEngine(appContext, deviceController)
    val commandEngine = MorventCommandEngine(appContext, deviceController, artemisEngine)

    private val cloudRepo: MorventCloudRepository? by lazy {
        runCatching {
            if (FirebaseApp.getApps(appContext).isNotEmpty()) {
                MorventCloudRepository(appContext, Firebase.auth)
            } else null
        }.getOrNull()
    }

    private val _messages = MutableStateFlow(
        listOf(
            ChatMessage(
                text = "مرحباً بك! أنا **مورڤينت (Morvent)**، مساعد الذكاء الاصطناعي ووكيل التحكم الشامل بالهاتف المدعوم بـ **Gemini AI + Google Artemis**.\n" +
                        "يمكنك التحدث معي في أي موضوع وسأجيبك بالذكاء الاصطناعي فوراً، أو طلب أي مهمة تحكم على هاتفك وسأنفذها مثل الإنسان!",
                isUser = false,
                actionType = ActionType.HELP,
                subSteps = listOf(
                    "محادثة حرة بالذكاء الاصطناعي وفهم سياق الكلام عبر Gemini",
                    "تحكم بشري كامل: نقر، نقر مزدوج، ضغط مطول، سحب، تكبير، وكتابة",
                    "مزامنة سحابية عبر Firebase وإمكانية تعديل مفتاح Gemini API من الإعدادات 🔑"
                )
            )
        )
    )
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking.asStateFlow()

    private val _isTtsEnabled = MutableStateFlow(true)
    val isTtsEnabled: StateFlow<Boolean> = _isTtsEnabled.asStateFlow()

    private val recentAutoReplyCooldown = java.util.concurrent.ConcurrentHashMap<String, Long>()

    init {
        hubScope.launch(Dispatchers.IO) {
            MorventNotificationListenerService.incomingEvents.collect { event ->
                if (!apiKeyManager.autoReplyWhatsApp.value) return@collect
                if (!event.canDirectReply) return@collect
                val isMessagingApp = event.packageName.contains("whatsapp", ignoreCase = true) ||
                    event.packageName.contains("telegram", ignoreCase = true) ||
                    event.packageName.contains("messaging", ignoreCase = true)
                if (!isMessagingApp) return@collect

                val now = System.currentTimeMillis()
                val key = "${event.packageName}:${event.sender}"
                val last = recentAutoReplyCooldown[key] ?: 0L
                if (now - last < 15_000L) return@collect
                recentAutoReplyCooldown[key] = now

                val replyText = artemisEngine.generateSmartAutoReply(
                    sender = event.sender,
                    incomingText = event.text,
                    fallback = apiKeyManager.autoReplyTemplate.value
                )
                val (ok, msg) = MorventNotificationListenerService.sendSilentReply(
                    context = appContext,
                    recipientOrAppQuery = event.sender,
                    replyText = replyText
                )
                if (ok) {
                    launch(Dispatchers.Main) {
                        appendMessage(
                            ChatMessage(
                                text = "🤖 **رد تلقائي صامت (${event.appLabel})**:\n$msg",
                                isUser = false,
                                actionType = ActionType.WHATSAPP,
                                subSteps = listOf("الرسالة الواردة من ${event.sender}: ${event.text}", "الرد المرسل: $replyText")
                            )
                        )
                    }
                }
            }
        }
    }

    fun setTtsEnabled(enabled: Boolean) {
        _isTtsEnabled.value = enabled
        if (!enabled) deviceController.stopSpeaking()
    }

    fun clearChat() {
        _messages.value = emptyList()
    }

    fun appendMessage(message: ChatMessage) {
        _messages.update { it + message }
        syncMessageToCloud(message)
    }

    private fun syncMessageToCloud(message: ChatMessage) {
        val hasFirebase = runCatching { FirebaseApp.getApps(appContext).isNotEmpty() }.getOrDefault(false)
        if (!hasFirebase) return
        val uid = runCatching { Firebase.auth.currentUser?.uid }.getOrNull() ?: return
        if (uid.isBlank() || message.text == "جاري التفكير والصياغة...") return
        hubScope.launch(Dispatchers.IO) {
            runCatching {
                cloudRepo?.saveChatMessage(
                    text = message.text,
                    isUser = message.isUser,
                    status = message.status.name
                )
            }
        }
    }

    /**
     * Unified entry point for commands coming from ChatScreen, Floating HUD,
     * Notification RemoteInput, or App Shortcuts.
     */
    fun submitCommand(
        commandText: String,
        sourceLabel: String = "الشات",
        onResult: ((Boolean, String) -> Unit)? = null
    ) {
        val trimmed = commandText.trim()
        if (trimmed.isEmpty()) return
        val lower = trimmed.lowercase(Locale.ROOT)

        appendMessage(ChatMessage(text = trimmed, isUser = true))

        // 1. Live Stop / Pause / Resume Commands
        if (lower in listOf("توقف", "اوقف", "ايقاف", "إيقاف", "الغاء", "إلغاء", "stop", "cancel", "abort")) {
            artemisEngine.stop()
            val msg = ChatMessage(
                text = "تم إيقاف مهمة الأتمتة الجارية فوراً بناءً على أمرك ⏹️.",
                isUser = false,
                actionType = ActionType.MACRO_SCENARIO
            )
            appendMessage(msg)
            speakIfEnabled(msg.text)
            onResult?.invoke(true, msg.text)
            return
        }

        if (lower in listOf("وقف مؤقت", "ايقاف مؤقت", "إيقاف مؤقت", "انتظر", "pause", "wait")) {
            artemisEngine.pause()
            val msg = ChatMessage(
                text = "تم إيقاف الوكيل مؤقتاً ⏸️. قل 'كمل' أو 'استئناف' للمتابعة.",
                isUser = false,
                actionType = ActionType.MACRO_SCENARIO
            )
            appendMessage(msg)
            speakIfEnabled(msg.text)
            onResult?.invoke(true, msg.text)
            return
        }

        if (lower in listOf("كمل", "اكمل", "أكمل", "استئناف", "استمر", "تابع", "resume", "continue")) {
            if (artemisEngine.status.value.state == AgentExecutionState.PAUSED) {
                artemisEngine.resume()
                val msg = ChatMessage(
                    text = "تم استئناف تنفيذ المهمة ▶️.",
                    isUser = false,
                    actionType = ActionType.MACRO_SCENARIO
                )
                appendMessage(msg)
                speakIfEnabled(msg.text)
                onResult?.invoke(true, msg.text)
                return
            }
        }

        // 1.8 Direct Execution Trigger ("نفذ", "نفذه", "طبق", "قم بتنفيذ")
        val executionTriggers = listOf("نفذ مباشرة", "نفذه مباشرة", "نفذها مباشرة", "نفذ", "نفذه", "نفذها", "طبق هذا", "طبقها", "طبق", "قم بتنفيذ", "قم بذلك", "افعلها", "افعل", "execute", "do it")
        val hasExecutionTrigger = executionTriggers.any { lower.contains(it) }
        if (hasExecutionTrigger) {
            val coreTask = extractCoreExecutionGoal(trimmed)
            if (commandEngine.isFastDeterministicCommand(coreTask)) {
                _isThinking.value = true
                hubScope.launch {
                    val responseMsg = commandEngine.processCommand(coreTask)
                    _isThinking.value = false
                    appendMessage(responseMsg)
                    speakIfEnabled(responseMsg.text)
                    onResult?.invoke(responseMsg.status != MessageStatus.ERROR, responseMsg.text)
                }
                return
            } else {
                startAutonomousGoal(coreTask, sourceLabel, onResult)
                return
            }
        }

        // 2.5 General AI Chat QA check (answering any general question or conversation via Gemini AI)
        if (isGeneralChatQuestion(trimmed) && !commandEngine.isFastDeterministicCommand(trimmed)) {
            val recentHistory = _messages.value
                .dropLast(1) // exclude current message just appended
                .filter { it.text != "جاري التفكير والصياغة..." && it.actionType != ActionType.HELP || it.isUser }
                .takeLast(8)
                .map { ChatTurn(isUser = it.isUser, text = it.text) }

            _isThinking.value = true
            _messages.update { it + ChatMessage(text = "جاري التفكير والصياغة...", isUser = false, status = MessageStatus.INFO) }
            hubScope.launch {
                val rawAnswer = artemisEngine.answerGeneralChat(trimmed, recentHistory)
                _isThinking.value = false

                val goalRegex = Regex("\\[\\[EXECUTE_GOAL:\\s*(.+?)\\]\\]", RegexOption.DOT_MATCHES_ALL)
                val goalMatch = goalRegex.find(rawAnswer)
                val extractedGoal = goalMatch?.groupValues?.getOrNull(1)?.trim().orEmpty()
                val cleanAnswer = rawAnswer.replace(goalRegex, "").trim()
                    .ifBlank { if (extractedGoal.isNotEmpty()) "حسناً، سأنفذ ذلك فوراً الآن 🤖..." else rawAnswer }

                val replyMsg = ChatMessage(
                    text = cleanAnswer,
                    isUser = false,
                    status = if (cleanAnswer.startsWith("⚠️") || cleanAnswer.startsWith("تعذر الوصول")) MessageStatus.ERROR else MessageStatus.SUCCESS,
                    actionType = ActionType.HELP
                )
                _messages.update { list -> list.dropLast(1) + replyMsg }
                syncMessageToCloud(replyMsg)
                speakIfEnabled(cleanAnswer)

                if (extractedGoal.isNotEmpty()) {
                    executeGoalFromAi(extractedGoal, "توجيه الذكاء الاصطناعي")
                }
                onResult?.invoke(true, cleanAnswer)
            }
            return
        }

        // 2. If Artemis is currently active: handle skip, append, replace, fast toggles, or steer!
        if (artemisEngine.isBusy()) {
            if (lower in listOf("تخطي", "تخطى", "تجاوز", "الخطوة التالية", "skip", "next step")) {
                artemisEngine.skipCurrentStep()
                val skipMsg = ChatMessage(
                    text = "تم تخطي الخطوة الحالية والانتقال للخطوة التالية ⏭️.",
                    isUser = false,
                    status = MessageStatus.INFO,
                    actionType = ActionType.MACRO_SCENARIO
                )
                appendMessage(skipMsg)
                speakIfEnabled("تم تخطي الخطوة")
                onResult?.invoke(true, skipMsg.text)
                return
            }

            val appendPrefixes = listOf("أضف:", "اضف:", "أضف ", "اضف ", "وكمان ", "ثم أضف ", "بعدها أضف ", "add:", "append:")
            val matchedAppendPrefix = appendPrefixes.firstOrNull { lower.startsWith(it) }
            if (matchedAppendPrefix != null) {
                val extraGoal = trimmed.substring(matchedAppendPrefix.length).trim()
                if (extraGoal.isNotEmpty()) {
                    val addedSteps = artemisEngine.appendGoal(extraGoal)
                    val appendMsg = ChatMessage(
                        text = "تمت إضافة ${addedSteps.size} خطوة جديدة لطابور المهمة الطويلة ➕:\n\"$extraGoal\"",
                        isUser = false,
                        status = MessageStatus.SUCCESS,
                        actionType = ActionType.ACCOUNT_WORKFLOW,
                        subSteps = addedSteps.map { it.description }
                    )
                    appendMessage(appendMsg)
                    speakIfEnabled("تمت إضافة الخطوات الجديدة")
                    onResult?.invoke(true, appendMsg.text)
                    return
                }
            }

            val replaceTask = lower.startsWith("بدل:") || lower.startsWith("جديد:")
            if (replaceTask) {
                val newGoal = trimmed.replace(Regex("^(بدل:|جديد:)\\s*"), "").trim()
                startAutonomousGoal(newGoal, sourceLabel, onResult)
                return
            }

            if (commandEngine.isFastDeterministicCommand(trimmed)) {
                hubScope.launch {
                    val responseMsg = commandEngine.processCommand(trimmed)
                    appendMessage(responseMsg)
                    speakIfEnabled(responseMsg.text)
                    onResult?.invoke(responseMsg.status != MessageStatus.ERROR, responseMsg.text)
                }
                return
            }

            // Live Mid-Task Steering: inject the user's new instruction into the running loop
            artemisEngine.steer(trimmed)
            if (artemisEngine.status.value.state == AgentExecutionState.PAUSED) {
                artemisEngine.resume()
            }
            val steerMsg = ChatMessage(
                text = "تم استلام توجيهك الحي أثناء العمل: \"$trimmed\" 🎯. سيتم تطبيقه في الخطوة الحالية فوراً.",
                isUser = false,
                status = MessageStatus.INFO,
                actionType = ActionType.ACCOUNT_WORKFLOW
            )
            appendMessage(steerMsg)
            speakIfEnabled("تم استلام التوجيه")
            onResult?.invoke(true, steerMsg.text)
            return
        }

        // 3. Fast Deterministic Hardware / Utility / Single-Step Path (instant offline response)
        if (commandEngine.isFastDeterministicCommand(trimmed)) {
            _isThinking.value = true
            hubScope.launch {
                val responseMsg = commandEngine.processCommand(trimmed)
                _isThinking.value = false
                if (responseMsg.actionDetail == "ROUTE_TO_ARTEMIS_AI") {
                    startAutonomousGoal(trimmed, sourceLabel, onResult)
                } else {
                    appendMessage(responseMsg)
                    speakIfEnabled(responseMsg.text)
                    onResult?.invoke(responseMsg.status != MessageStatus.ERROR, responseMsg.text)
                }
            }
            return
        }

        // 4. Universal Autonomous Human-Level Execution via Gemini Vision + Artemis Loop
        val cleanGoal = trimmed.replace(Regex("^(أتمتة:|نفذ:|مهمة:)\\s*"), "").trim()
        startAutonomousGoal(cleanGoal, sourceLabel, onResult)
    }

    private fun startAutonomousGoal(
        goalText: String,
        sourceLabel: String,
        onResult: ((Boolean, String) -> Unit)?
    ) {
        val plannedPreview = artemisEngine.planGoal(goalText)
        val isLongWorkflow = plannedPreview.size >= 3

        // Keep long cross-app workflows alive cleanly via Floating HUD when overlay permission is granted
        if (isLongWorkflow && !MorventFloatingAgentService.isRunning && Settings.canDrawOverlays(appContext)) {
            runCatching { MorventFloatingAgentService.start(appContext) }
        }

        val ackText = if (isLongWorkflow) {
            "استلمت مهمة طويلة (${plannedPreview.size} خطوة مبدئية) من ($sourceLabel) 🧩.\nبدأ وكيل مورڤينت التنفيذ المتسلسل — يمكنك التوجيه، إضافة خطوات بـ 'أضف:'، تخطي خطوة، أو الإيقاف في أي لحظة!"
        } else {
            "استلمت المهمة من ($sourceLabel): \"$goalText\" 🤖.\nبدأ وكيل مورڤينت الذكي تحليل الشاشة والعمل مثل الإنسان — يمكنك توجيهي أو إيقافي في أي لحظة!"
        }

        val ackMsg = ChatMessage(
            text = ackText,
            isUser = false,
            status = MessageStatus.INFO,
            actionType = ActionType.ACCOUNT_WORKFLOW,
            subSteps = if (isLongWorkflow) plannedPreview.mapIndexed { i, s -> "${i + 1}. ${s.description}" } else emptyList()
        )
        appendMessage(ackMsg)
        speakIfEnabled(if (isLongWorkflow) "بدأ مورڤينت تنفيذ المهمة الطويلة" else "بدأ مورڤينت تنفيذ المهمة")

        artemisEngine.executeAutonomousGoal(goalText) { success, resultMsg, logs ->
            hubScope.launch(Dispatchers.Main) {
                val needsA11y = !success && artemisEngine.lastErrorRequiresAccessibility
                val finishMsg = ChatMessage(
                    text = resultMsg,
                    isUser = false,
                    status = if (success) MessageStatus.SUCCESS else if (needsA11y) MessageStatus.INFO else MessageStatus.ERROR,
                    actionType = if (needsA11y) ActionType.ACCESSIBILITY_NAV else ActionType.ACCOUNT_WORKFLOW,
                    actionDetail = if (needsA11y) "REQUEST_ACCESSIBILITY_PERMISSION" else null,
                    subSteps = logs
                )
                appendMessage(finishMsg)
                val hasFirebase = runCatching { FirebaseApp.getApps(appContext).isNotEmpty() }.getOrDefault(false)
                if (hasFirebase && runCatching { Firebase.auth.currentUser != null }.getOrDefault(false)) {
                    hubScope.launch(Dispatchers.IO) {
                        runCatching {
                            cloudRepo?.createTaskRecord(
                                goal = goalText,
                                status = if (success) "completed" else "error",
                                summary = resultMsg,
                                stepsCount = logs.size.coerceAtLeast(1),
                                aiProvider = apiKeyManager.getSelectedModelName()
                            )
                        }
                    }
                }
                speakIfEnabled(resultMsg)
                onResult?.invoke(success, resultMsg)
            }
        }
    }

    private fun executeGoalFromAi(goalText: String, sourceLabel: String) {
        val cleanGoal = goalText.trim()
        if (cleanGoal.isEmpty()) return
        if (commandEngine.isFastDeterministicCommand(cleanGoal)) {
            hubScope.launch {
                val responseMsg = commandEngine.processCommand(cleanGoal)
                if (responseMsg.actionDetail == "ROUTE_TO_ARTEMIS_AI") {
                    startAutonomousGoal(cleanGoal, sourceLabel, null)
                } else {
                    appendMessage(responseMsg)
                    speakIfEnabled(responseMsg.text)
                }
            }
        } else {
            startAutonomousGoal(cleanGoal, sourceLabel, null)
        }
    }

    private fun speakIfEnabled(text: String) {
        if (_isTtsEnabled.value && text.isNotBlank()) {
            deviceController.speak(text)
        }
    }

    private fun isGeneralChatQuestion(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        val automationVerbs = listOf(
            "افتح", "شغل", "اضغط", "انقر", "اكتب", "فعّل", "فعل", "عطل", "انزل", "ارفع",
            "اخفض", "صور", "اتصل", "أتمتة", "نفذ", "مهمة", "ابحث عن", "سجل",
            "سحب", "مرر", "اضبط", "امسح", "قفل", "تقسيم", "كشاف",
            "رد على", "ابعت", "ارسل", "راسل", "دق", "كلم", "اقفل", "انهي", "أنهي",
            "سبيكر", "احفظ", "ملاحظة", "واتساب", "واتس", "مكالمة", "تنفيذ صامت",
            "رد تلقائي", "إشعار", "اشعار", "ملفات", "جسر"
        )
        val hasAutomationVerb = automationVerbs.any { lower.contains(it) }
        if (hasAutomationVerb) return false

        val questionStarters = listOf(
            "ما ", "من ", "لماذا", "كيف", "هل", "أين", "متى", "اشرح", "اكتب لي",
            "عرّف", "ما هو", "ما هي", "كم", "ما رأيك", "هل يمكنك", "أعطني",
            "قصه", "قصيدة", "لخص", "ترجم", "اعطني", "لما", "عرف", "متى"
        )
        val isQuestion = questionStarters.any { lower.startsWith(it) || lower.contains(it) }
        val endsWithQuestionMark = text.endsWith("?") || text.endsWith("؟")

        return isQuestion || endsWithQuestionMark || (text.split(Regex("\\s+")).size <= 8 && !hasAutomationVerb)
    }

    private fun extractCoreExecutionGoal(text: String): String {
        var cleaned = text
        val prefixes = listOf(
            "نفذ مباشرة", "نفذه مباشرة", "نفذها مباشرة", "نفذ", "نفذه", "نفذها",
            "طبق هذا", "طبقها", "طبق", "قم بتنفيذ", "قم بذلك", "افعلها", "افعل",
            "execute", "do it", "نفذ لي", "رجاء نفذ"
        )
        for (p in prefixes) {
            if (cleaned.startsWith(p, ignoreCase = true)) {
                cleaned = cleaned.substring(p.length).trim()
            }
        }
        cleaned = cleaned.removePrefix("أن ").removePrefix("ان ").removePrefix("لـ ").removePrefix("لي ").trim()
        return if (cleaned.isNotEmpty()) cleaned else text
    }

    companion object {
        @Volatile
        private var INSTANCE: MorventAgentHub? = null

        fun getInstance(context: Context): MorventAgentHub {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MorventAgentHub(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
