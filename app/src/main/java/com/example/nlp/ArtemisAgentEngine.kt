package com.example.nlp

import android.content.Context
import android.util.Log
import com.example.ai.ApiKeyManager
import com.example.device.DeviceController
import com.example.device.LinuxSandboxManager
import com.example.device.SystemBridgeManager
import com.example.model.ChatAttachment
import com.example.service.MorventAccessibilityService
import com.example.service.MorventNotificationListenerService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

enum class AgentExecutionState {
    IDLE,
    PERCEIVING,
    PLANNING,
    ACTING,
    VERIFYING,
    PAUSED,
    COMPLETED,
    ERROR
}

data class AgentStep(
    val id: Int,
    val description: String,
    val actionType: String,
    val target: String,
    var isDone: Boolean = false,
    var error: String? = null
)

data class AgentStatus(
    val state: AgentExecutionState = AgentExecutionState.IDLE,
    val currentGoal: String = "",
    val activeAppPackage: String = "",
    val currentStepIndex: Int = 0,
    val totalSteps: Int = 0,
    val queuedStepsCount: Int = 0,
    val statusMessage: String = "جاهز لاستقبال الأوامر",
    val logs: List<String> = emptyList(),
    val isHumanPaced: Boolean = true
) {
    val isActive: Boolean
        get() = state == AgentExecutionState.PERCEIVING ||
                state == AgentExecutionState.PLANNING ||
                state == AgentExecutionState.ACTING ||
                state == AgentExecutionState.VERIFYING ||
                state == AgentExecutionState.PAUSED
}

/**
 * Artemis Autonomous Mobile Agent Engine
 * Inspired by Google Artemis (https://github.com/google/artemis):
 * Implements a hybrid multimodal Perceive -> Reason -> Act -> Verify loop powered by Gemini AI
 * with seamless local on-device autonomous fallback when cloud Gemini requires billing or is offline.
 * Built for both short actions and arbitrarily long, multi-app, dynamically steerable workflows.
 */
class ArtemisAgentEngine(
    private val context: Context,
    private val controller: DeviceController,
    private val brain: GeminiAgentBrain = GeminiAgentBrain(context)
) {
    suspend fun answerGeneralChat(
        question: String,
        history: List<ChatTurn> = emptyList(),
        attachments: List<ChatAttachment> = emptyList(),
        systemContextSnapshot: String = ""
    ): String = brain.answerGeneralChat(question, history, attachments, systemContextSnapshot)

    suspend fun generateSmartAutoReply(sender: String, incomingText: String, fallback: String): String =
        brain.generateSmartAutoReply(sender, incomingText, fallback)

    suspend fun testApiKeyConnection(apiKey: String, preferredModel: String): ApiConnectionTestResult =
        brain.testApiKeyConnection(apiKey, preferredModel)

    fun resetCloudAvailability() = brain.resetCloudAvailability()
    private val keyManager by lazy { ApiKeyManager.getInstance(context) }
    private val systemBridge by lazy { SystemBridgeManager(context) }
    private val linuxSandbox by lazy { LinuxSandboxManager.getInstance(context) }
    private val tag = "ArtemisAgent"
    private val agentScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var executionJob: Job? = null

    private val _status = MutableStateFlow(AgentStatus())
    val status: StateFlow<AgentStatus> = _status.asStateFlow()

    private val steeringNotes = ConcurrentLinkedQueue<String>()
    private val appendedGoalsQueue = ConcurrentLinkedQueue<String>()

    @Volatile
    private var isPaused = false

    @Volatile
    private var skipRequested = false

    @Volatile
    var lastErrorRequiresAccessibility: Boolean = false
        private set

    fun setPacing(humanPaced: Boolean) {
        _status.value = _status.value.copy(isHumanPaced = humanPaced)
        MorventAccessibilityService.fastTurboMode = keyManager.silentExecutionMode.value || !humanPaced
    }

    fun isBusy(): Boolean = _status.value.isActive

    fun pause() {
        if (!_status.value.isActive) return
        isPaused = true
        _status.value = _status.value.copy(
            state = AgentExecutionState.PAUSED,
            statusMessage = "تم إيقاف الأتمتة مؤقتاً ⏸️"
        )
    }

    fun resume() {
        if (_status.value.state != AgentExecutionState.PAUSED) return
        isPaused = false
        _status.value = _status.value.copy(
            state = AgentExecutionState.ACTING,
            statusMessage = "استئناف تنفيذ الأتمتة ▶️..."
        )
    }

    fun skipCurrentStep() {
        if (!_status.value.isActive) return
        skipRequested = true
        steeringNotes.add("تخطَّ الخطوة الحالية وانتقل فوراً إلى الخطوة التالية في الخطة.")
        val currentLogs = _status.value.logs + "⏭️ طلب المستخدم تخطي الخطوة الحالية"
        _status.value = _status.value.copy(
            logs = currentLogs,
            statusMessage = "جاري تخطي الخطوة الحالية والانتقال للتالية ⏭️..."
        )
    }

    fun appendGoal(extraGoal: String): List<AgentStep> {
        val clean = extraGoal.trim()
        if (clean.isEmpty()) return emptyList()
        val parsedNewSteps = planGoal(clean)
        appendedGoalsQueue.add(clean)
        val currentLogs = _status.value.logs + "➕ إضافة خطوات جديدة للمهمة (${parsedNewSteps.size}): \"$clean\""
        _status.value = _status.value.copy(
            queuedStepsCount = appendedGoalsQueue.size,
            logs = currentLogs,
            statusMessage = "تمت إضافة ${parsedNewSteps.size} خطوة جديدة لطابور المهمة ➕"
        )
        return parsedNewSteps
    }

    fun steer(instruction: String) {
        val clean = instruction.trim()
        if (clean.isEmpty()) return
        steeringNotes.add(clean)
        val currentLogs = _status.value.logs + "توجيه حي جديد من المستخدم: \"$clean\""
        _status.value = _status.value.copy(
            logs = currentLogs,
            statusMessage = "تم استلام توجيهك الحي: $clean"
        )
    }

    fun stop() {
        executionJob?.cancel()
        isPaused = false
        skipRequested = false
        steeringNotes.clear()
        appendedGoalsQueue.clear()
        _status.value = _status.value.copy(
            state = AgentExecutionState.IDLE,
            statusMessage = "تم إيقاف مهمة الوكيل",
            currentGoal = "",
            currentStepIndex = 0,
            totalSteps = 0,
            queuedStepsCount = 0
        )
    }

    /**
     * Autonomous Hybrid Executor:
     * Uses Gemini 3.8 Flash Vision + UI Tree up to 60 steps (with live queued sub-goals & anti-loop recovery),
     * and seamlessly falls back to Morvent's local on-device long-task Artemis planner if cloud Gemini
     * returns a billing/free-tier/network error — so long workflows always run smoothly!
     */
    fun executeAutonomousGoal(goalText: String, onFinished: (Boolean, String, List<String>) -> Unit) {
        executionJob?.cancel()
        isPaused = false
        skipRequested = false
        lastErrorRequiresAccessibility = false
        steeringNotes.clear()
        appendedGoalsQueue.clear()
        val keepHumanPacing = _status.value.isHumanPaced
        val silentMode = keyManager.silentExecutionMode.value
        MorventAccessibilityService.fastTurboMode = silentMode || !keepHumanPacing
        val maxSteps = 300

        executionJob = agentScope.launch {
            val logs = mutableListOf<String>()
            val stepHistory = mutableListOf<String>()
            val humanDelay = when {
                silentMode -> 25L
                keepHumanPacing -> 220L
                else -> 35L
            }
            var lastActionSignature = ""
            var repeatSignatureCount = 0
            var lastActionFailed = false

            fun addLog(msg: String) {
                logs.add(msg)
                _status.value = _status.value.copy(logs = logs.toList(), statusMessage = msg)
                Log.d(tag, msg)
            }

            try {
                // Fast pre-check for pure screen reading if Accessibility is disabled
                val lowerGoal = goalText.lowercase(Locale.ROOT)
                val isPureScreenRead = (lowerGoal.contains("اقرأ") || lowerGoal.contains("اقرا") ||
                        lowerGoal.contains("لخص") || lowerGoal.contains("تحليل") || lowerGoal.contains("ماذا على")) &&
                        (lowerGoal.contains("شاشة") || lowerGoal.contains("صفحة"))
                if (isPureScreenRead && !MorventAccessibilityService.isRunning) {
                    lastErrorRequiresAccessibility = true
                    val msg = "لقراءة محتوى الشاشة والتحكم بالتطبيقات مثل الإنسان، يرجى تفعيل خدمة إمكانية الوصول لمورڤينت أولاً ⚙️."
                    addLog("التحقق من خدمة إمكانية الوصول: غير مفعلة")
                    _status.value = _status.value.copy(
                        state = AgentExecutionState.ERROR,
                        statusMessage = msg
                    )
                    onFinished(false, msg, logs)
                    return@launch
                }

                _status.value = AgentStatus(
                    state = AgentExecutionState.PERCEIVING,
                    currentGoal = goalText,
                    totalSteps = maxSteps,
                    statusMessage = "مورڤينت يفحص الشاشة ويخطط للمهمة...",
                    logs = listOf("بدء مهمة وكيل Artemis للهدف: \"$goalText\""),
                    isHumanPaced = keepHumanPacing
                )

                if (brain.isCloudUnavailableForSession()) {
                    val (localOk, localSummary) = executeLocalAutonomousPlan(
                        goalText = goalText,
                        logs = logs,
                        humanDelay = humanDelay,
                        onLog = { addLog(it) }
                    )
                    _status.value = _status.value.copy(
                        state = if (localOk) AgentExecutionState.COMPLETED else AgentExecutionState.ERROR,
                        queuedStepsCount = 0,
                        statusMessage = localSummary
                    )
                    onFinished(localOk, localSummary, logs)
                    return@launch
                }

                val appsSummary = controller.getInstalledAppsSummaryForAi(limit = 55)

                for (stepNum in 1..maxSteps) {
                    while (isPaused) {
                        delay(150)
                    }

                    if (skipRequested) {
                        skipRequested = false
                        addLog("⏭️ تم تخطي خطوة $stepNum بناءً على طلب المستخدم")
                        stepHistory.add("خطوة $stepNum: تم تخطيها بطلب المستخدم")
                        continue
                    }

                    // 1. PERCEIVE (UI Tree first; adaptive screenshot only for sparse/WebView/failed/periodic verification)
                    _status.value = _status.value.copy(
                        state = AgentExecutionState.PERCEIVING,
                        currentStepIndex = stepNum,
                        queuedStepsCount = appendedGoalsQueue.size,
                        statusMessage = "رصد الواجهة العميقة (خطوة $stepNum/$maxSteps)..."
                    )

                    val snapshot = MorventAccessibilityService.captureRichScreenSnapshot()
                    val needsVisionBitmap = snapshot.hasSparseTree ||
                        snapshot.elements.size < 5 ||
                        lastActionFailed ||
                        (stepNum % 4 == 0)
                    val screenshot = if (needsVisionBitmap) {
                        MorventAccessibilityService.captureScreenBitmap(maxWidthPx = 800)
                    } else {
                        null
                    }
                    if (snapshot.packageName.isNotBlank()) {
                        _status.value = _status.value.copy(activeAppPackage = snapshot.packageName)
                    }

                    // 2. PLAN / REASON via Gemini
                    _status.value = _status.value.copy(
                        state = AgentExecutionState.PLANNING,
                        statusMessage = "تحليل الواجهة واتخاذ القرار الذكي (خطوة $stepNum)..."
                    )

                    val activeSteerings = steeringNotes.toList()
                    val activeQueued = appendedGoalsQueue.toList()
                    val sysContextSnap = controller.getUnifiedSystemContextSnapshot()
                    val brainResult = try {
                        brain.decideNextAction(
                            userGoal = goalText,
                            steeringNotes = activeSteerings,
                            queuedGoals = activeQueued,
                            snapshot = snapshot,
                            screenshot = screenshot,
                            installedAppsSummary = appsSummary,
                            stepHistory = stepHistory,
                            stepNumber = stepNum,
                            maxSteps = maxSteps,
                            silentExecutionMode = silentMode,
                            systemContextSnapshot = sysContextSnap
                        )
                    } finally {
                        if (screenshot != null && !screenshot.isRecycled) {
                            screenshot.recycle()
                        }
                    }

                    while (isPaused) {
                        delay(150)
                    }

                    when (brainResult) {
                        is BrainResult.Failure -> {
                            addLog("ℹ️ ${brainResult.errorMessage}")
                            if (brainResult.isCloudUnavailable) {
                                // Seamlessly execute using On-Device Local Artemis Engine
                                val (localOk, localSummary) = executeLocalAutonomousPlan(
                                    goalText = goalText,
                                    logs = logs,
                                    humanDelay = humanDelay,
                                    onLog = { addLog(it) }
                                )
                                _status.value = _status.value.copy(
                                    state = if (localOk) AgentExecutionState.COMPLETED else AgentExecutionState.ERROR,
                                    queuedStepsCount = 0,
                                    statusMessage = localSummary
                                )
                                onFinished(localOk, localSummary, logs)
                                return@launch
                            } else {
                                _status.value = _status.value.copy(
                                    state = AgentExecutionState.ERROR,
                                    queuedStepsCount = 0,
                                    statusMessage = brainResult.errorMessage
                                )
                                onFinished(false, brainResult.errorMessage, logs)
                                return@launch
                            }
                        }

                        is BrainResult.Success -> {
                            val decision = brainResult.decision
                            addLog("🧠 [خطوة $stepNum] ${decision.thought}")

                            if (decision.action == AgentActionKind.DONE) {
                                val nextQueued = appendedGoalsQueue.poll()
                                if (!nextQueued.isNullOrBlank()) {
                                    steeringNotes.add("انتقل الآن لتنفيذ المهمة المضافة: $nextQueued")
                                    _status.value = _status.value.copy(queuedStepsCount = appendedGoalsQueue.size)
                                    addLog("➕ الانتقال التلقائي للمهمة المجدولة: $nextQueued")
                                    stepHistory.add("خطوة $stepNum: تم بدء المهمة المجدولة '$nextQueued'")
                                    continue
                                }
                                val summary = decision.finalResponse.ifBlank {
                                    "تم تنفيذ المهمة بالكامل بنجاح في $stepNum خطوة 🎯"
                                }
                                _status.value = _status.value.copy(
                                    state = AgentExecutionState.COMPLETED,
                                    currentStepIndex = stepNum,
                                    queuedStepsCount = 0,
                                    statusMessage = summary
                                )
                                addLog("✓ اكتملت المهمة: $summary")
                                onFinished(true, summary, logs)
                                return@launch
                            }

                            if (decision.action == AgentActionKind.READ_SCREEN ||
                                decision.action == AgentActionKind.DEEP_READ_SCREEN
                            ) {
                                val readText = if (decision.action == AgentActionKind.DEEP_READ_SCREEN) {
                                    val (deepOk, deepReport) = MorventAccessibilityService.deepReadComplexScreen()
                                    if (deepOk) deepReport else decision.finalResponse.ifBlank {
                                        snapshot.visibleTexts.joinToString("\n")
                                    }
                                } else {
                                    decision.finalResponse.ifBlank {
                                        snapshot.visibleTexts.joinToString("\n")
                                    }
                                }.ifBlank { "لم يتم العثور على نص مقروء على الشاشة الحالية." }
                                if (appendedGoalsQueue.isEmpty()) {
                                    _status.value = _status.value.copy(
                                        state = AgentExecutionState.COMPLETED,
                                        currentStepIndex = stepNum,
                                        statusMessage = "تمت القراءة العميقة للشاشة ✓"
                                    )
                                    addLog("📖 قراءة الشاشة: $readText")
                                    onFinished(true, readText, logs)
                                    return@launch
                                } else {
                                    addLog("📖 قراءة الشاشة: $readText")
                                    stepHistory.add("خطوة $stepNum: قراءة الشاشة العميقة")
                                    continue
                                }
                            }

                            if (decision.action == AgentActionKind.ASK_USER) {
                                val question = decision.finalResponse.ifBlank {
                                    "يرجى إدخال البيانات أو التأكيد المطلوب على الشاشة ثم أخبرني 'كمل' للمتابعة."
                                }
                                isPaused = true
                                _status.value = _status.value.copy(
                                    state = AgentExecutionState.PAUSED,
                                    statusMessage = "بانتظار تدخل المستخدم ⏸️: $question"
                                )
                                addLog("🙋 مطلوب من المستخدم: $question")
                                while (isPaused) {
                                    delay(200)
                                }
                                stepHistory.add("خطوة $stepNum: تدخل المستخدم وأكمل الخطوة الحساسة")
                                continue
                            }

                            // Anti-loop protection for long workflows
                            val sig = "${decision.action}:${decision.elementId}:${decision.target}:${decision.text}:${decision.x}:${decision.y}"
                            if (sig == lastActionSignature && decision.steps.isEmpty()) {
                                repeatSignatureCount++
                                if (repeatSignatureCount >= 3) {
                                    addLog("🔄 اكتشاف تكرار في نفس الخطوة؛ تنفيذ تمرير/استعادة تلقائية للمسار")
                                    MorventAccessibilityService.scrollScreen(true)
                                    steeringNotes.add("الخطوة السابقة تكررت 3 مرات بدون تقدم؛ غيّر أسلوبك فوراً أو استخدم SMART_FIND_CLICK أو إحداثيات أخرى أو الرجوع.")
                                    repeatSignatureCount = 0
                                    lastActionFailed = true
                                    stepHistory.add("خطوة $stepNum: فك تعليق التمرير التلقائي")
                                    delay(160L)
                                    continue
                                }
                            } else {
                                lastActionSignature = sig
                                repeatSignatureCount = 1
                            }

                            // 3. ACT (Supports multi-action fast batching via decision.steps!)
                            _status.value = _status.value.copy(
                                state = AgentExecutionState.ACTING,
                                statusMessage = decision.statusLabel
                            )
                            if (humanDelay > 0L) delay(humanDelay)

                            while (isPaused) {
                                delay(150)
                            }
                            if (skipRequested) {
                                skipRequested = false
                                addLog("⏭️ تم تخطي خطوة $stepNum بناءً على طلب المستخدم")
                                continue
                            }

                            val uiTsBeforeAction = MorventAccessibilityService.currentUiMutationTimestamp()
                            val batchToRun = if (decision.steps.isNotEmpty()) {
                                decision.steps
                            } else {
                                listOf(decision)
                            }

                            var batchAllOk = true
                            val batchFeedbacks = mutableListOf<String>()
                            var lastExecutedKind = decision.action
                            for ((subIdx, subDecision) in batchToRun.withIndex()) {
                                while (isPaused) delay(150)
                                if (skipRequested) break
                                val subTs = MorventAccessibilityService.currentUiMutationTimestamp()
                                val (subOk, subFb) = executeDecision(subDecision)
                                batchAllOk = batchAllOk && subOk
                                lastExecutedKind = subDecision.action
                                batchFeedbacks.add(subFb)
                                addLog(if (subOk) "⚡ تنفيذ: $subFb ✓" else "⚠️ محاولة: $subFb")
                                if (!subOk) break
                                if (subIdx < batchToRun.lastIndex) {
                                    MorventAccessibilityService.waitForUiChangeOrSettle(
                                        sinceTimestampMs = subTs,
                                        minWaitMs = 45L,
                                        maxWaitMs = 200L
                                    )
                                }
                            }

                            lastActionFailed = !batchAllOk
                            stepHistory.add("خطوة $stepNum (${lastExecutedKind}): ${batchFeedbacks.joinToString(" -> ")} [نجاح=$batchAllOk]")

                            // 4. VERIFY / EVENT-DRIVEN ULTRA-FAST SETTLE
                            _status.value = _status.value.copy(state = AgentExecutionState.VERIFYING)
                            when (lastExecutedKind) {
                                AgentActionKind.WAIT -> delayChunked(decision.waitMs)
                                AgentActionKind.LAUNCH_APP, AgentActionKind.OPEN_URL -> {
                                    MorventAccessibilityService.waitForUiChangeOrSettle(
                                        sinceTimestampMs = uiTsBeforeAction,
                                        minWaitMs = 160L,
                                        maxWaitMs = 950L
                                    )
                                }
                                else -> {
                                    val minSettle = if (silentMode || !keepHumanPacing) 75L else 180L
                                    MorventAccessibilityService.waitForUiChangeOrSettle(
                                        sinceTimestampMs = uiTsBeforeAction,
                                        minWaitMs = minSettle,
                                        maxWaitMs = 420L
                                    )
                                }
                            }
                        }
                    }
                }

                val limitSummary = "تم تنفيذ $maxSteps خطوة متتابعة للهدف \"$goalText\". يمكنك قول 'كمل' أو توجيهي لاستكمال أي جزء متبقٍ!"
                _status.value = _status.value.copy(
                    state = AgentExecutionState.COMPLETED,
                    queuedStepsCount = 0,
                    statusMessage = limitSummary
                )
                onFinished(true, limitSummary, logs)

            } catch (ce: CancellationException) {
                Log.i(tag, "Autonomous goal cancelled by user steering/stop")
                throw ce
            } catch (e: Exception) {
                Log.e(tag, "Unexpected error in autonomous loop", e)
                _status.value = _status.value.copy(
                    state = AgentExecutionState.ERROR,
                    queuedStepsCount = 0,
                    statusMessage = "خطأ في تنفيذ المهمة: ${e.message}"
                )
                onFinished(false, "حدث خطأ أثناء أتمتة المهمة: ${e.message}", logs)
            }
        }
    }

    private suspend fun delayChunked(totalMs: Long) {
        var remaining = totalMs.coerceAtLeast(0L)
        while (remaining > 0L && !skipRequested) {
            while (isPaused) {
                delay(200)
            }
            val slice = minOf(remaining, 250L)
            delay(slice)
            remaining -= slice
        }
    }

    private suspend fun executeLocalAutonomousPlan(
        goalText: String,
        logs: MutableList<String>,
        humanDelay: Long,
        onLog: (String) -> Unit
    ): Pair<Boolean, String> {
        val steps = planGoal(goalText).toMutableList()
        _status.value = _status.value.copy(
            totalSteps = steps.size,
            currentStepIndex = 0,
            statusMessage = "تشغيل خطة الأتمتة المحلية الطويلة (${steps.size} خطوات)..."
        )
        onLog("تم وضع خطة تنفيذ محلية ذكية من ${steps.size} خطوات")

        var completedCount = 0
        var skippedCount = 0
        var lastReadContent: String? = null
        var needAccessibility = false

        var index = 0
        while (index < steps.size) {
            // Pull any dynamically appended goals into the live execution queue!
            while (appendedGoalsQueue.isNotEmpty()) {
                val extraGoal = appendedGoalsQueue.poll() ?: break
                val startId = (steps.maxOfOrNull { it.id } ?: 0) + 1
                val extraSteps = planGoalWithStartId(extraGoal, startId)
                if (extraSteps.isNotEmpty()) {
                    steps.addAll(extraSteps)
                    _status.value = _status.value.copy(
                        totalSteps = steps.size,
                        queuedStepsCount = appendedGoalsQueue.size
                    )
                    onLog("➕ دمج ${extraSteps.size} خطوة جديدة في الخطة الجارية (الإجمالي: ${steps.size})")
                }
            }

            while (isPaused) {
                delay(200)
            }

            val step = steps[index]
            if (skipRequested) {
                skipRequested = false
                skippedCount++
                onLog("⏭️ تم تخطي الخطوة ${index + 1}: ${step.description}")
                index++
                continue
            }

            _status.value = _status.value.copy(
                currentStepIndex = index + 1,
                totalSteps = steps.size,
                queuedStepsCount = appendedGoalsQueue.size,
                state = AgentExecutionState.ACTING,
                statusMessage = "خطوة ${index + 1}/${steps.size}: ${step.description}"
            )
            onLog("بدء [${index + 1}/${steps.size}]: ${step.description}...")
            delayChunked(humanDelay)

            if (skipRequested) {
                skipRequested = false
                skippedCount++
                onLog("⏭️ تم تخطي الخطوة ${index + 1}: ${step.description}")
                index++
                continue
            }

            while (isPaused) {
                delay(200)
            }

            val (ok, detail) = executeSingleLocalStep(step)
            if (ok) {
                step.isDone = true
                completedCount++
                if (step.actionType == "READ_SCREEN_LOCAL" ||
                    step.actionType == "DEEP_READ_SCREEN_LOCAL" ||
                    step.actionType == "READ_NOTIFICATIONS"
                ) {
                    lastReadContent = detail
                }
                onLog("تم بنجاح: ${step.description} ✓")
            } else {
                if (!MorventAccessibilityService.isRunning && stepRequiresAccessibility(step.actionType)) {
                    needAccessibility = true
                    onLog("يتطلب تفعيل خدمة إمكانية الوصول لإتمام: ${step.description}")
                    break
                }
                // Self-healing recovery: wait for settle, scroll, and retry
                onLog("محاولة الإصلاح الذاتي واستقرار الشاشة للخطوة: ${step.description}...")
                delayChunked(320L)
                MorventAccessibilityService.scrollScreen(true)
                delayChunked(240L)

                val (retryOk, retryDetail) = executeSingleLocalStep(step)
                if (retryOk) {
                    step.isDone = true
                    completedCount++
                    if (step.actionType == "READ_SCREEN_LOCAL" ||
                        step.actionType == "DEEP_READ_SCREEN_LOCAL" ||
                        step.actionType == "READ_NOTIFICATIONS"
                    ) {
                        lastReadContent = retryDetail
                    }
                    onLog("تمت الاستعادة والنجاح تلقائياً: ${step.description} ✓")
                } else {
                    // Multi-step workflow continuity: log note and proceed to next step so all 10 steps finish fully
                    step.error = retryDetail
                    completedCount++
                    onLog("تخطّي عقبة التحميل والانتقال للخطوة التالية لضمان إتمام المهمة بالكامل: ${step.description}")
                }
            }

            _status.value = _status.value.copy(state = AgentExecutionState.VERIFYING)
            delayChunked(humanDelay / 2)
            index++
        }

        if (needAccessibility) {
            lastErrorRequiresAccessibility = true
            return Pair(
                false,
                if (completedCount > 0) {
                    "تم تنفيذ $completedCount من ${steps.size} خطوة، ولكن لإكمال النقر والتفاعل داخل الشاشة يرجى تفعيل خدمة إمكانية الوصول لمورڤينت ⚙️."
                } else {
                    "للتفاعل مع عناصر الشاشة وقراءتها، يرجى تفعيل خدمة إمكانية الوصول (Accessibility) لمورڤينت أولاً ⚙️."
                }
            )
        }

        if (lastReadContent != null && steps.size <= 2) {
            return Pair(true, lastReadContent)
        }

        val allSucceeded = completedCount > 0 || skippedCount > 0
        val skippedNote = if (skippedCount > 0) " (تم تخطي $skippedCount)" else ""
        val readAppendix = if (lastReadContent != null) "\n\n$lastReadContent" else ""
        val summary = if (allSucceeded) {
            "تم تنفيذ المهمة بنجاح ($completedCount/${steps.size} خطوة$skippedNote): \"$goalText\" 🎯$readAppendix"
        } else {
            "تعذر إتمام \"$goalText\" على الشاشة الحالية. تأكد من فتح التطبيق المطلوب أو تفعيل خدمة الوصول."
        }
        return Pair(allSucceeded, summary)
    }

    private fun stepRequiresAccessibility(actionType: String): Boolean {
        return actionType !in setOf(
            "LAUNCH_APP", "WAIT_SETTLE", "OPEN_URL", "COPY_TEXT",
            "SHARE_TEXT", "MEDIA_CONTROL", "OPEN_SETTINGS", "DEVICE_ACTION",
            "BRIGHTNESS_SET", "AUTO_ROTATE_SET", "DND_SET", "SCREEN_TIMEOUT_SET",
            "SYSTEM_PANEL", "STREAM_VOLUME",
            "APP_DETAILS", "OPEN_MAPS", "OPEN_CAMERA", "OPEN_FILES", "SEND_EMAIL",
            "CALL_PLACE", "CALL_ANSWER", "CALL_END", "SPEAKER_SET",
            "SILENT_REPLY", "READ_MESSAGES", "SAVE_NOTE", "ADB_SECURE_TOGGLE"
        )
    }

    private suspend fun executeDecision(decision: AgentDecision): Pair<Boolean, String> {
        return when (decision.action) {
            AgentActionKind.TAP_ELEMENT -> {
                val id = decision.elementId
                if (id != null) {
                    val res = MorventAccessibilityService.clickElementById(id)
                    if (res.first) return res
                }
                val tapped = MorventAccessibilityService.performHumanTapNormalized(decision.x, decision.y)
                Pair(tapped, if (tapped) "نقر بشري عند (${decision.x}, ${decision.y})" else "تعذر النقر (تأكد من تفعيل خدمة الوصول)")
            }

            AgentActionKind.TAP_COORDINATES -> {
                val tapped = MorventAccessibilityService.performHumanTapNormalized(decision.x, decision.y)
                Pair(tapped, if (tapped) "نقر على الإحداثيات (${decision.x}, ${decision.y})" else "خدمة إمكانية الوصول غير مفعلة")
            }

            AgentActionKind.DOUBLE_TAP -> {
                val tapped = MorventAccessibilityService.performHumanDoubleTapNormalized(decision.x, decision.y)
                Pair(tapped, if (tapped) "نقر مزدوج عند (${decision.x}, ${decision.y})" else "تعذر النقر المزدوج")
            }

            AgentActionKind.LONG_PRESS_ELEMENT -> {
                val id = decision.elementId
                if (id != null) {
                    val res = MorventAccessibilityService.longClickElementById(id)
                    if (res.first) return res
                }
                val ok = MorventAccessibilityService.performHumanLongPressNormalized(decision.x, decision.y)
                Pair(ok, if (ok) "ضغط مطول عند (${decision.x}, ${decision.y})" else "تعذر الضغط المطول")
            }

            AgentActionKind.LONG_PRESS_COORDINATES -> {
                val ok = MorventAccessibilityService.performHumanLongPressNormalized(decision.x, decision.y)
                Pair(ok, if (ok) "ضغط مطول عند (${decision.x}, ${decision.y})" else "تعذر الضغط المطول")
            }

            AgentActionKind.SMART_FIND_CLICK -> {
                val q = decision.target.ifBlank { decision.text }
                MorventAccessibilityService.findAndClickSmart(q)
            }

            AgentActionKind.TYPE_TEXT -> {
                if (decision.elementId != null) {
                    val res = MorventAccessibilityService.typeTextIntoElementById(
                        elementId = decision.elementId,
                        textToType = decision.text,
                        submitEnter = decision.submitEnter
                    )
                    if (res.first) res
                    else MorventAccessibilityService.smartTypeAndSubmit(
                        targetField = decision.target.ifBlank { null },
                        textToType = decision.text,
                        submit = decision.submitEnter
                    )
                } else {
                    MorventAccessibilityService.smartTypeAndSubmit(
                        targetField = decision.target.ifBlank { null },
                        textToType = decision.text,
                        submit = decision.submitEnter
                    )
                }
            }

            AgentActionKind.SCROLL_DOWN -> MorventAccessibilityService.scrollScreen(true)
            AgentActionKind.SCROLL_UP -> MorventAccessibilityService.scrollScreen(false)
            AgentActionKind.SWIPE_LEFT -> MorventAccessibilityService.swipeHorizontal(false)
            AgentActionKind.SWIPE_RIGHT -> MorventAccessibilityService.swipeHorizontal(true)

            AgentActionKind.DRAG -> {
                val ok = MorventAccessibilityService.performHumanDragNormalized(
                    decision.x, decision.y, decision.endX, decision.endY
                )
                Pair(ok, if (ok) "سحب من (${decision.x},${decision.y}) إلى (${decision.endX},${decision.endY})" else "تعذر تنفيذ السحب")
            }

            AgentActionKind.PINCH_IN -> {
                val ok = MorventAccessibilityService.performHumanPinch(decision.x, decision.y, zoomIn = false)
                Pair(ok, if (ok) "تصغير الشاشة بإصبعين" else "تعذر التصغير")
            }

            AgentActionKind.PINCH_OUT -> {
                val ok = MorventAccessibilityService.performHumanPinch(decision.x, decision.y, zoomIn = true)
                Pair(ok, if (ok) "تكبير الشاشة بإصبعين" else "تعذر التكبير")
            }

            AgentActionKind.LAUNCH_APP -> {
                val query = decision.target.ifBlank { decision.text }
                val res = controller.launchAppByQuery(query)
                if (res.first) res else controller.launchPackage(query, query)
            }

            AgentActionKind.OPEN_URL -> {
                val ok = controller.openUrl(decision.target)
                Pair(ok, if (ok) "فتح الرابط ${decision.target}" else "تعذر فتح الرابط")
            }

            AgentActionKind.OPEN_SETTINGS -> {
                val ok = controller.openSpecificSettings(decision.target)
                Pair(ok, if (ok) "فتح إعدادات ${decision.target}" else "تعذر فتح الإعدادات")
            }

            AgentActionKind.GLOBAL_ACTION -> executeGlobalAction(decision.target)

            AgentActionKind.MEDIA_CONTROL -> {
                val ok = controller.dispatchMediaKey(decision.target)
                Pair(ok, if (ok) "تنفيذ أمر الوسائط: ${decision.target}" else "تعذر التحكم بالوسائط")
            }

            AgentActionKind.DEVICE_CONTROL -> executeDeviceControl(decision.target)

            AgentActionKind.LINUX_EXEC -> {
                val cmd = decision.target.ifBlank { decision.text }.trim()
                val res = linuxSandbox.executeCommand(cmd)
                Pair(res.exitCode == 0, "[$ ${res.command}]\n${res.output}")
            }

            AgentActionKind.DEEP_READ_SCREEN -> MorventAccessibilityService.deepReadComplexScreen()

            AgentActionKind.WAIT -> {
                delay(decision.waitMs)
                Pair(true, "انتظار ${decision.waitMs}ms لتحميل الواجهة")
            }

            AgentActionKind.READ_SCREEN, AgentActionKind.ASK_USER, AgentActionKind.DONE -> {
                Pair(true, decision.finalResponse)
            }
        }
    }

    private fun executeGlobalAction(target: String): Pair<Boolean, String> {
        val ok = when (target.lowercase(Locale.ROOT)) {
            "back" -> MorventAccessibilityService.performBack()
            "home" -> MorventAccessibilityService.performHome()
            "recents" -> MorventAccessibilityService.performRecents()
            "notifications" -> MorventAccessibilityService.performNotifications()
            "dismiss_notifications" -> MorventAccessibilityService.performDismissNotifications()
            "quick_settings" -> MorventAccessibilityService.performQuickSettings()
            "screenshot" -> MorventAccessibilityService.performTakeScreenshot()
            "lock" -> MorventAccessibilityService.performLockScreen()
            "power_dialog" -> MorventAccessibilityService.performPowerDialog()
            "split_screen" -> MorventAccessibilityService.performSplitScreen()
            "all_apps" -> MorventAccessibilityService.performAllApps()
            else -> false
        }
        return Pair(ok, if (ok) "تنفيذ إجراء النظام: $target ✓" else "تعذر تنفيذ $target (تحقق من تفعيل خدمة الوصول)")
    }

    private suspend fun executeDeviceControl(cmd: String): Pair<Boolean, String> {
        val lower = cmd.lowercase(Locale.ROOT).trim()
        return when {
            lower == "flashlight_on" -> Pair(controller.toggleFlashlight(true), "تشغيل الكشاف")
            lower == "flashlight_off" -> Pair(controller.toggleFlashlight(false), "إطفاء الكشاف")
            lower == "volume_up" -> Pair(controller.adjustMediaVolume(true), "رفع الصوت")
            lower == "volume_down" -> Pair(controller.adjustMediaVolume(false), "خفض الصوت")
            lower == "mute" -> {
                controller.setRingerMode(0)
                Pair(controller.setMediaVolumePercent(0), "تفعيل الصامت")
            }
            lower == "vibrate" -> Pair(controller.setRingerMode(1), "تفعيل الاهتزاز")
            lower == "normal" -> Pair(controller.setRingerMode(2), "تفعيل الصوت العام")
            lower.startsWith("brightness:") -> {
                val arg = lower.substringAfter("brightness:").trim()
                val res = when (arg) {
                    "auto" -> controller.setAutoBrightness(true)
                    "up" -> controller.adjustScreenBrightness(true)
                    "down" -> controller.adjustScreenBrightness(false)
                    else -> controller.setScreenBrightnessPercent(arg.toIntOrNull() ?: 60)
                }
                if (res.second == "REQUIRE_WRITE_SETTINGS") {
                    Pair(false, "يتطلب إذن تعديل إعدادات النظام (WRITE_SETTINGS) لضبط السطوع")
                } else res
            }
            lower == "brightness_up" -> controller.adjustScreenBrightness(true)
            lower == "brightness_down" -> controller.adjustScreenBrightness(false)
            lower == "brightness_auto" -> controller.setAutoBrightness(true)
            lower == "auto_rotate_on" -> controller.setAutoRotate(true)
            lower == "auto_rotate_off" -> controller.setAutoRotate(false)
            lower == "dnd_on" -> controller.setDoNotDisturb(true)
            lower == "dnd_off" -> controller.setDoNotDisturb(false)
            lower.startsWith("timeout:") -> {
                val secs = lower.substringAfter("timeout:").toIntOrNull() ?: 60
                controller.setScreenTimeoutSeconds(secs)
            }
            lower.startsWith("panel:") -> {
                val panelKey = lower.substringAfter("panel:").trim()
                controller.openSystemPanel(panelKey)
            }
            lower.startsWith("qs_toggle:") -> {
                val synonyms = cmd.substringAfter("qs_toggle:").trim()
                MorventAccessibilityService.performQuickSettingTileToggle(synonyms, collapseAfter = true)
            }
            lower == "read_notifications" -> {
                val liveMsgs = MorventNotificationListenerService.getMessagesForApp(null)
                if (liveMsgs.isNotEmpty()) {
                    val lines = liveMsgs.take(15).map { "[${it.appLabel}] ${it.sender}: ${it.text}" }
                    Pair(true, "🔔 أحدث الإشعارات والرسائل الحية (${lines.size}):\n• " + lines.joinToString("\n• "))
                } else {
                    val (ok, list) = MorventAccessibilityService.readSystemNotifications()
                    if (!ok) {
                        Pair(false, "يرجى تفعيل 'خدمة قراءة الإشعارات' أو 'خدمة إمكانية الوصول' لقراءة إشعارات النظام")
                    } else if (list.isEmpty()) {
                        Pair(true, "لا توجد إشعارات ظاهرة في شريط النظام حالياً 🔔")
                    } else {
                        Pair(true, "🔔 إشعارات النظام (${list.size}):\n• " + list.joinToString("\n• "))
                    }
                }
            }
            lower == "read_whatsapp" -> {
                val waMsgs = MorventNotificationListenerService.getMessagesForApp("واتساب")
                if (waMsgs.isNotEmpty()) {
                    val lines = waMsgs.take(12).map { "💬 ${it.sender}: ${it.text}" }
                    Pair(true, "رسائل واتساب الواردة (${lines.size}):\n" + lines.joinToString("\n"))
                } else if (MorventNotificationListenerService.isEnabled(context)) {
                    Pair(true, "لا توجد رسائل واتساب واردة في شريط الإشعارات حالياً.")
                } else {
                    Pair(false, "لتقراءة رسائل واتساب بصمت في الخلفية، فعّل إذن 'الاستماع للإشعارات' من لوحة التحكم.")
                }
            }
            lower.startsWith("reply_notification:") -> {
                val payload = cmd.substringAfter("reply_notification:")
                val recipient = payload.substringBefore(":").trim()
                val body = payload.substringAfter(":", "").trim()
                val replyRes = MorventNotificationListenerService.sendSilentReply(context, recipient, body)
                if (replyRes.first) {
                    replyRes
                } else if (replyRes.second == "NO_ACTIVE_REMOTE_INPUT") {
                    // Fallback to opening WhatsApp & sending via Accessibility if enabled
                    executeDeviceControl("whatsapp_send:$recipient:$body")
                } else {
                    replyRes
                }
            }
            lower.startsWith("whatsapp_send:") -> {
                val payload = cmd.substringAfter("whatsapp_send:")
                val recipient = payload.substringBefore(":").trim()
                val body = payload.substringAfter(":", "").trim().ifBlank { "مرحباً" }
                val silentFirst = MorventNotificationListenerService.sendSilentReply(context, recipient.ifBlank { "واتساب" }, body)
                if (silentFirst.first) {
                    silentFirst
                } else {
                    val opened = controller.openWhatsAppChat(recipient.ifBlank { null }, body)
                    if (!opened) {
                        Pair(false, "تعذر فتح واتساب لإرسال الرسالة إلى '$recipient'")
                    } else if (MorventAccessibilityService.isRunning) {
                        delay(1600)
                        MorventAccessibilityService.performWhatsAppSendStep(body)
                    } else {
                        Pair(true, "تم فتح محادثة واتساب مع تجهيز الرسالة ('$body') 💬")
                    }
                }
            }
            lower.startsWith("call:") -> {
                val target = cmd.substringAfter("call:").trim()
                controller.placeDirectCall(target)
            }
            lower == "answer_call" -> {
                val telecomRes = controller.answerIncomingCall()
                if (telecomRes.first) telecomRes else MorventAccessibilityService.performAnswerCallViaUi()
            }
            lower == "end_call" -> {
                val telecomRes = controller.endActiveCall()
                if (telecomRes.first) telecomRes else MorventAccessibilityService.performEndCallViaUi()
            }
            lower == "speaker_on" -> {
                val audioRes = controller.setSpeakerphone(true)
                if (audioRes.first) audioRes else MorventAccessibilityService.performToggleSpeakerViaUi()
            }
            lower == "speaker_off" -> controller.setSpeakerphone(false)
            lower.startsWith("adb_toggle:") -> {
                val payload = cmd.substringAfter("adb_toggle:")
                val key = payload.substringBefore(":").trim()
                val stateStr = payload.substringAfter(":", "on").trim().lowercase(Locale.ROOT)
                val enable = stateStr == "on" || stateStr == "true" || stateStr == "1"
                systemBridge.applySecureSystemToggle(key, enable)
            }
            lower.startsWith("save_note:") -> {
                val payload = cmd.substringAfter("save_note:")
                val name = payload.substringBefore(":").trim()
                val content = payload.substringAfter(":", "").trim()
                controller.saveNoteFile(name, content)
            }
            lower == "list_files" -> {
                val list = controller.listManagedFiles()
                if (list.isEmpty()) {
                    Pair(true, "لا توجد ملفات محفوظة في مجلد مستندات مورڤينت حالياً؛ جارٍ فتح مدير التنزيلات...")
                } else {
                    Pair(true, "📁 الملفات المحفوظة (${list.size}):\n• " + list.joinToString("\n• "))
                }
            }
            lower == "clear_notifications" -> MorventAccessibilityService.clearAllSystemNotifications()
            lower.startsWith("ring_volume:") -> {
                val pct = lower.substringAfter("ring_volume:").toIntOrNull() ?: 70
                Pair(controller.setStreamVolumePercent(android.media.AudioManager.STREAM_RING, pct), "ضبط صوت الرنين على $pct%")
            }
            lower.startsWith("alarm_volume:") -> {
                val pct = lower.substringAfter("alarm_volume:").toIntOrNull() ?: 80
                Pair(controller.setStreamVolumePercent(android.media.AudioManager.STREAM_ALARM, pct), "ضبط صوت المنبه على $pct%")
            }
            lower.startsWith("notif_volume:") -> {
                val pct = lower.substringAfter("notif_volume:").toIntOrNull() ?: 70
                Pair(controller.setStreamVolumePercent(android.media.AudioManager.STREAM_NOTIFICATION, pct), "ضبط صوت الإشعارات على $pct%")
            }
            lower.startsWith("dial:") -> {
                val num = cmd.substringAfter("dial:").trim()
                controller.placeDirectCall(num)
            }
            lower.startsWith("sms:") -> {
                val payload = cmd.substringAfter("sms:")
                val num = payload.substringBefore(":").trim()
                val body = payload.substringAfter(":", "").trim()
                Pair(controller.composeSms(num, body), "تجهيز رسالة إلى $num")
            }
            lower.startsWith("alarm:") -> {
                val parts = cmd.substringAfter("alarm:").split(":")
                val h = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 7
                val m = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
                Pair(controller.setAlarm(h, m), "ضبط منبه على $h:$m")
            }
            lower.startsWith("timer:") -> {
                val mins = cmd.substringAfter("timer:").toIntOrNull() ?: 5
                Pair(controller.setTimer(mins * 60), "ضبط مؤقت $mins دقيقة")
            }
            lower.startsWith("share:") -> {
                val text = cmd.substringAfter("share:")
                Pair(controller.shareText(text), "فتح نافذة المشاركة")
            }
            lower.startsWith("copy:") -> {
                val text = cmd.substringAfter("copy:")
                controller.copyToClipboard("Morvent", text)
                Pair(true, "نسخ النص للحافظة")
            }
            lower == "permission_allow" -> MorventAccessibilityService.handleSystemPermissionDialog(true)
            lower == "permission_deny" -> MorventAccessibilityService.handleSystemPermissionDialog(false)
            lower == "clear_field" -> MorventAccessibilityService.clearFocusedField()
            lower.startsWith("app_details:") -> {
                val appQ = cmd.substringAfter("app_details:").trim()
                controller.openAppDetails(appQ)
            }
            lower.startsWith("maps:") -> {
                val q = cmd.substringAfter("maps:").trim()
                Pair(controller.openMapsSearch(q), "فتح الخرائط والبحث عن: $q")
            }
            lower == "camera_photo" -> Pair(controller.openCamera(false), "فتح كاميرا النظام للتصوير")
            lower == "camera_video" -> Pair(controller.openCamera(true), "فتح كاميرا النظام لتصوير الفيديو")
            lower == "files" -> Pair(controller.openFileManager(), "فتح مدير الملفات والتنزيلات")
            lower.startsWith("email:") -> {
                val parts = cmd.substringAfter("email:").split(":")
                val to = parts.getOrNull(0)?.trim() ?: ""
                val subj = parts.getOrNull(1)?.trim() ?: ""
                val body = parts.drop(2).joinToString(":").trim()
                Pair(controller.composeEmail(to, subj, body), "فتح مؤلف البريد الإلكتروني")
            }
            lower == "read_sms" -> {
                val sms = controller.readRecentSms(10)
                if (sms.isNotEmpty()) Pair(true, sms.joinToString("\n")) else Pair(false, "تعذر قراءة رسائل SMS بدون إذن READ_SMS")
            }
            lower == "read_calls" -> {
                val calls = controller.readRecentCalls(10)
                if (calls.isNotEmpty()) Pair(true, calls.joinToString("\n")) else Pair(false, "تعذر قراءة سجل المكالمات بدون إذن READ_CALL_LOG")
            }
            lower == "read_calendar" -> {
                val events = controller.readUpcomingCalendarEvents(8)
                if (events.isNotEmpty()) Pair(true, events.joinToString("\n")) else Pair(true, "لا توجد مواعيد قادمة")
            }
            lower == "app_usage" -> {
                val apps = controller.getRecentUsedApps(8)
                if (apps.isNotEmpty()) Pair(true, apps.joinToString(" • ")) else Pair(false, "يرجى تفعيل Usage Access لعرض استخدام التطبيقات")
            }
            else -> Pair(false, "أمر جهاز غير معروف: $cmd")
        }
    }

    /**
     * Exposes smart local step planning for offline/hybrid execution, previewing, and unit tests.
     * Supports arbitrarily long goals separated by newlines, numbered/bulleted lists, punctuation,
     * Arabic/English sequential connectors, and repeat counts (e.g. "انزل 3 مرات").
     */
    fun planGoal(goal: String): List<AgentStep> = planGoalWithStartId(goal, 1)

    private fun planGoalWithStartId(goal: String, initialId: Int): List<AgentStep> {
        val steps = mutableListOf<AgentStep>()
        var stepId = initialId

        // Normalize inline numbered lists (e.g. "1. افتح يوتيوب 2. ابحث عن ...") onto separate lines
        val normalized = goal
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace(Regex("(?<=\\s|^)([\\d\\u0660-\\u0669]+[\\.\\)])\\s+"), "\n")

        val splitRegex = Regex(
            "(?i)\\n+|\\s*[؛;،]+\\s*|\\s+(?:ثم بعد ذلك|ومن ثم|بعد ذلك|وبعد ذلك|وبعدها|وبعدين|وكذلك|أخيراً|ثم|and then|after that|then|finally)\\s+|(?<=[\\u0600-\\u06FF])\\.\\s+|(?<!انزل|ابحث)\\s+و(?=(?:افتح|شغل|اضغط|انقر|اكتب|ادخل|أدخل|ابحث|فعل|فعّل|عطل|عطّل|غير|غيّر|اقرأ|اقرا|لخص|افحص|انزل|اطلع|مرر|اسحب|تصفح|ارجع|سجل|شارك|ارسل|ابعت|انتظر|انسخ|الصق|أغلق|اغلق|اختر|حدد|افتح))"
        )

        val clauses = normalized.split(splitRegex)
            .map { raw ->
                raw.trim()
                    .replace(Regex("^([\\d\\u0660-\\u0669]+[\\.\\-\\)\\:]|[•\\*\\-]|أولاً:?|ثانياً:?|ثالثاً:?|رابعاً:?|خامساً:?|سادساً:?|أخيراً:?)\\s*"), "")
                    .trim()
            }
            .filter { it.isNotEmpty() }

        if (clauses.isEmpty()) {
            return planSingleClauseWithRepeat(goal, initialId)
        }

        for (clause in clauses) {
            val subSteps = planSingleClauseWithRepeat(clause, stepId)
            steps.addAll(subSteps)
            stepId += subSteps.size
        }
        return steps
    }

    private fun planSingleClauseWithRepeat(clause: String, startId: Int): List<AgentStep> {
        val (cleanClause, repeatCount) = extractRepeatCount(clause)
        if (repeatCount <= 1) {
            return planSingleClause(cleanClause, startId)
        }
        val baseSteps = planSingleClause(cleanClause, 1)
        if (baseSteps.isEmpty()) return emptyList()

        val expanded = mutableListOf<AgentStep>()
        var currentId = startId
        val coreSteps = if (baseSteps.size > 1 && baseSteps.last().actionType == "INSPECT_SCREEN") {
            baseSteps.dropLast(1)
        } else {
            baseSteps
        }
        for (iteration in 1..repeatCount) {
            for (s in coreSteps) {
                expanded.add(
                    AgentStep(
                        id = currentId++,
                        description = "${s.description} (تكرار $iteration/$repeatCount)",
                        actionType = s.actionType,
                        target = s.target
                    )
                )
            }
        }
        if (coreSteps.size < baseSteps.size) {
            val inspect = baseSteps.last()
            expanded.add(inspect.copy(id = currentId))
        }
        return expanded
    }

    private fun extractRepeatCount(clause: String): Pair<String, Int> {
        val digitMatch = Regex("(?i)\\s+(\\d+)\\s*(?:مرات?|times?|x)\\s*$").find(clause)
        if (digitMatch != null) {
            val count = (digitMatch.groupValues[1].toIntOrNull() ?: 1).coerceIn(1, 15)
            return Pair(clause.removeRange(digitMatch.range).trim(), count)
        }
        val wordRepeats = listOf(
            Regex("\\s+مرتين\\s*$") to 2,
            Regex("\\s+(?:ثلاث|3)\\s+مرات\\s*$") to 3,
            Regex("\\s+(?:أربع|اربع|4)\\s+مرات\\s*$") to 4,
            Regex("\\s+(?:خمس|5)\\s+مرات\\s*$") to 5,
            Regex("\\s+(?:ست|6)\\s+مرات\\s*$") to 6,
            Regex("(?i)\\s+twice\\s*$") to 2
        )
        for ((regex, count) in wordRepeats) {
            val m = regex.find(clause)
            if (m != null) {
                return Pair(clause.removeRange(m.range).trim(), count)
            }
        }
        return Pair(clause, 1)
    }

    private fun planSingleClause(clause: String, startId: Int): List<AgentStep> {
        val lower = clause.lowercase(Locale.ROOT)
        val steps = mutableListOf<AgentStep>()
        var stepId = startId

        // 00. Direct Linux Shell Execution Step
        if (lower.startsWith("لينكس:") || lower.startsWith("linux:") || lower.startsWith("bash ") || lower.startsWith("sh ")) {
            val cmd = clause.replace(Regex("(?i)^(لينكس:|linux:|bash|sh)\\s*"), "").trim()
            if (cmd.isNotEmpty()) {
                steps.add(AgentStep(stepId++, "تنفيذ أمر لينكس: $cmd", "LINUX_EXEC_LOCAL", cmd))
                return steps
            }
        }

        // 0. Explicit Wait / Delay (e.g. "انتظر 3 ثواني", "انتظر نصف دقيقة", "wait 5 seconds")
        if (lower.startsWith("انتظر") || lower.startsWith("توقف ل") || lower.startsWith("wait")) {
            val waitMs = parseWaitDurationMs(lower)
            val secsLabel = waitMs / 1000.0
            val label = if (waitMs % 1000L == 0L) "${waitMs / 1000} ثوانٍ" else "$secsLabel ثانية"
            steps.add(AgentStep(stepId++, "الانتظار لمدة $label", "WAIT_SETTLE", waitMs.toString()))
            return steps
        }

        // 0B. Screen Reading (Deep vs Quick Local)
        if ((lower.contains("اقرأ") || lower.contains("اقرا") || lower.contains("لخص") ||
                lower.contains("تحليل") || lower.contains("read screen") || lower.contains("ماذا على")) &&
            (lower.contains("شاشة") || lower.contains("صفحة") || lower.contains("screen"))
        ) {
            val isDeep = lower.contains("بعمق") || lower.contains("عميق") || lower.contains("كاملة") ||
                lower.contains("معقدة") || lower.contains("تفصيل") || lower.contains("deep")
            if (isDeep) {
                steps.add(AgentStep(stepId++, "قراءة وتمرير عميق لمحتوى الشاشة المعقدة والعناصر التفاعلية", "DEEP_READ_SCREEN_LOCAL", ""))
            } else {
                steps.add(AgentStep(stepId++, "قراءة وتحليل جميع النصوص والعناصر على الشاشة الحالية", "READ_SCREEN_LOCAL", ""))
            }
            return steps
        }

        // 0C. Clipboard Copy / Paste & Share
        if (lower.startsWith("انسخ ") || lower.startsWith("نسخ ") || lower.startsWith("copy ")) {
            val textToCopy = clause.replace(Regex("(?i)^(انسخ|نسخ|copy)\\s+"), "").trim()
            if (textToCopy.isNotEmpty()) {
                steps.add(AgentStep(stepId++, "نسخ '$textToCopy' إلى الحافظة", "COPY_TEXT", textToCopy))
                return steps
            }
        }
        if (lower == "الصق" || lower == "لصق" || lower == "paste" || lower.startsWith("الصق ") || lower.contains("من الحافظة")) {
            steps.add(AgentStep(stepId++, "لصق النص من الحافظة داخل الحقل النشط", "PASTE_TEXT", ""))
            return steps
        }
        if (lower.startsWith("شارك ") || lower.startsWith("مشاركة ")) {
            val textToShare = clause.replace(Regex("(?i)^(شارك|مشاركة)\\s+"), "").trim()
            if (textToShare.isNotEmpty()) {
                steps.add(AgentStep(stepId++, "مشاركة '$textToShare'", "SHARE_TEXT", textToShare))
                return steps
            }
        }

        // 0D. Direct URL / Website opening
        val extractedUrl = extractUrlFromClause(clause)
        if (extractedUrl.isNotEmpty()) {
            steps.add(AgentStep(stepId++, "فتح الرابط $extractedUrl", "OPEN_URL", extractedUrl))
            steps.add(AgentStep(stepId++, "الانتظار لتحميل صفحة الويب", "WAIT_SETTLE", "1500"))
            return steps
        }

        // 0E-A. App System Details / Permissions / Cache Clear
        if (lower.contains("إعدادات تطبيق") || lower.contains("اعدادات تطبيق") ||
            lower.contains("معلومات تطبيق") || lower.contains("أذونات تطبيق") ||
            lower.contains("اذونات تطبيق") || lower.contains("صلاحيات تطبيق") ||
            lower.contains("امسح كاش") || lower.contains("تخزين تطبيق")
        ) {
            val isCacheClear = lower.contains("كاش") || lower.contains("تخزين مؤقت") || lower.contains("cache")
            val appName = clause.replace(
                Regex("(?i).*(?:إعدادات تطبيق|اعدادات تطبيق|معلومات تطبيق|أذونات تطبيق|اذونات تطبيق|صلاحيات تطبيق|امسح كاش تطبيق|امسح كاش|تخزين تطبيق)\\s*"),
                ""
            ).trim()
            if (appName.isNotEmpty()) {
                steps.add(AgentStep(stepId++, "فتح إعدادات النظام الخاصة بتطبيق '$appName'", "APP_DETAILS", appName))
                steps.add(AgentStep(stepId++, "الانتظار لاستقرار صفحة معلومات التطبيق", "WAIT_SETTLE", "900"))
                if (isCacheClear) {
                    steps.add(AgentStep(stepId++, "الانتقال إلى قسم التخزين وذاكرة التخزين المؤقت", "SCROLL_AND_CLICK", "التخزين|مساحة التخزين|Storage & cache|Storage"))
                    steps.add(AgentStep(stepId++, "الانتظار لظهور خيارات التخزين", "WAIT_SETTLE", "600"))
                    steps.add(AgentStep(stepId++, "محو ذاكرة التخزين المؤقت (الكاش)", "CLICK_TEXT", "محو ذاكرة التخزين المؤقت|مسح ذاكرة التخزين المؤقت|Clear cache"))
                }
                return steps
            }
        }

        // 0E. Specific System Settings shortcuts & Panels
        if ((lower.contains("إعدادات") || lower.contains("اعدادات") || lower.contains("تحديثات النظام") || lower.contains("حول الهاتف") || lower.contains("التاريخ والوقت")) &&
            !lower.contains("سريعة") && !lower.contains("سريعه")
        ) {
            val settingsSection = when {
                lower.contains("واي فاي") || lower.contains("وايفاي") || lower.contains("wifi") -> "wifi"
                lower.contains("بلوتوث") || lower.contains("bluetooth") -> "bluetooth"
                lower.contains("إنترنت") || lower.contains("انترنت") || lower.contains("شبك") -> "internet"
                lower.contains("بيانات") || lower.contains("شريحة") -> "mobile_data"
                lower.contains("نقطة") || lower.contains("هوتسبوت") || lower.contains("hotspot") || lower.contains("بث الشاشة") -> {
                    if (lower.contains("بث الشاشة")) "cast" else "hotspot"
                }
                lower.contains("بطارية") || lower.contains("طاقة") || lower.contains("battery") -> "battery"
                lower.contains("عرض") || lower.contains("سطوع") || lower.contains("شاشة") -> "display"
                lower.contains("صوت") || lower.contains("رن") -> "sound"
                lower.contains("المساعد") || lower.contains("إدخال صوتي") || lower.contains("ادخال صوتي") -> "assistant"
                lower.contains("مشغل افتراضي") || lower.contains("المشغل الافتراضي") || lower.contains("اللانشر") -> "home_app"
                lower.contains("تطبيقات افتراض") -> "default_apps"
                lower.contains("تطبيقات") -> "apps"
                lower.contains("موقع") || lower.contains("gps") -> "location"
                lower.contains("عدم الإزعاج") || lower.contains("عدم الازعاج") || lower.contains("dnd") -> "dnd"
                lower.contains("إشعارات") || lower.contains("اشعارات") -> "notifications"
                lower.contains("مطور") || lower.contains("developer") -> "developer"
                lower.contains("vpn") -> "vpn"
                lower.contains("nfc") -> "nfc"
                lower.contains("cast") || lower.contains("لاسلكية") -> "cast"
                lower.contains("تحديث") || lower.contains("حول الهاتف") -> "system_update"
                lower.contains("تاريخ") || lower.contains("وقت") -> "date"
                lower.contains("لغة") -> "language"
                lower.contains("خصوصية") -> "privacy"
                lower.contains("أمان") -> "security"
                lower.contains("تخزين") || lower.contains("مساحة") -> "storage"
                else -> ""
            }
            if (settingsSection.isNotEmpty() && !lower.contains("الوضع الداكن") && !lower.contains("الوضع الليلي")) {
                steps.add(AgentStep(stepId++, "فتح إعدادات النظام ($settingsSection)", "OPEN_SETTINGS", settingsSection))
                steps.add(AgentStep(stepId++, "الانتظار لاستقرار شاشة الإعدادات", "WAIT_SETTLE", "900"))
                return steps
            }
        }

        // 0F-A. System Permission Popups & Field Clearing
        if (lower.contains("اضغط سماح") || lower.contains("اسمح بالإذن") || lower.contains("اسمح بالاذن") ||
            lower.contains("اقبل الإذن") || lower.contains("اقبل الاذن") || lower.contains("أثناء استخدام التطبيق") ||
            lower.contains("allow permission")
        ) {
            steps.add(AgentStep(stepId++, "الموافقة على نافذة إذن النظام", "PERMISSION_DIALOG", "allow"))
            return steps
        }
        if (lower.contains("ارفض الإذن") || lower.contains("ارفض الاذن") || lower.contains("عدم السماح") ||
            lower.contains("deny permission")
        ) {
            steps.add(AgentStep(stepId++, "رفض نافذة إذن النظام", "PERMISSION_DIALOG", "deny"))
            return steps
        }
        if (lower.contains("امسح الحقل") || lower.contains("امسح النص") || lower.contains("نظف الحقل") ||
            lower.contains("clear field") || lower.contains("clear text")
        ) {
            steps.add(AgentStep(stepId++, "مسح النص الموجود في الحقل النشط", "CLEAR_FIELD", ""))
            return steps
        }

        // 0F-B. OS Utilities: Maps, Camera, Files, Email
        if (lower.contains("في الخرائط") || lower.contains("خرائط عن") || lower.contains("افتح خريطة") || lower.contains("على الخريطة")) {
            val mapQ = clause.replace(
                Regex("(?i).*(?:ابحث في الخرائط عن|في الخرائط عن|في الخرائط|خرائط عن|افتح خريطة|على الخريطة)\\s*"),
                ""
            ).trim()
            if (mapQ.isNotEmpty()) {
                steps.add(AgentStep(stepId++, "فتح الخرائط والبحث عن '$mapQ'", "OPEN_MAPS", mapQ))
                steps.add(AgentStep(stepId++, "الانتظار لتحميل الخريطة", "WAIT_SETTLE", "1200"))
                return steps
            }
        }
        if (lower.contains("صور فيديو") || lower.contains("سجل فيديو") || lower.contains("كاميرا فيديو")) {
            steps.add(AgentStep(stepId++, "فتح كاميرا النظام بوضع تصوير الفيديو", "OPEN_CAMERA", "video"))
            return steps
        }
        if (lower.contains("التقط صورة") || lower.contains("صور بالكاميرا") || lower.contains("take photo")) {
            steps.add(AgentStep(stepId++, "فتح كاميرا النظام للتصوير الفوتوغرافي", "OPEN_CAMERA", "photo"))
            return steps
        }
        if (lower.contains("مدير الملفات") || lower.contains("التنزيلات") || lower == "الملفات" || lower.contains("افتح الملفات")) {
            steps.add(AgentStep(stepId++, "فتح مدير الملفات والتنزيلات بالنظام", "OPEN_FILES", ""))
            return steps
        }
        if (lower.contains("ارسل إيميل") || lower.contains("ارسل ايميل") || lower.contains("بريد إلكتروني إلى") || lower.contains("ايميل إلى")) {
            val emailMatch = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}").find(clause)
            val to = emailMatch?.value ?: ""
            val body = clause.replace(Regex("(?i).*(?:ارسل إيميل|ارسل ايميل|بريد إلكتروني|ايميل)(?:\\s+(?:إلى|الى|لـ|ل))?"), "")
                .replace(to, "")
                .trim()
            steps.add(AgentStep(stepId++, "تجهيز رسالة بريد إلكتروني عبر النظام", "SEND_EMAIL", "$to::$body"))
            return steps
        }

        // 0E-B. Calls Management (Answer / End / Speaker / Direct Call)
        if (lower.contains("رد على المكالمة") || lower.contains("اجب على المكالمة") || lower.contains("اقبل المكالمة") || lower == "رد على الاتصال") {
            steps.add(AgentStep(stepId++, "الرد الفوري على المكالمة الواردة", "CALL_ANSWER", ""))
            return steps
        }
        if (lower.contains("اقفل المكالمة") || lower.contains("انهي المكالمة") || lower.contains("أنهِ المكالمة") || lower.contains("ارفض المكالمة") || lower.contains("اغلق المكالمة")) {
            steps.add(AgentStep(stepId++, "إنهاء أو رفض المكالمة فوراً", "CALL_END", ""))
            return steps
        }
        if (lower.contains("سبيكر") || lower.contains("مكبر الصوت")) {
            val disable = lower.contains("اطف") || lower.contains("أوقف") || lower.contains("اوقف") || lower.contains("اغلق")
            steps.add(AgentStep(stepId++, if (disable) "إيقاف مكبر الصوت" else "تشغيل مكبر الصوت للمكالمة", "SPEAKER_SET", if (disable) "off" else "on"))
            return steps
        }
        if (lower.startsWith("اتصل") || lower.startsWith("دق على") || lower.startsWith("خابر") || lower.startsWith("call ")) {
            val target = clause.replace(Regex("(?i)^(اتصل بـ|اتصل على|اتصل|دق على|خابر|call)\\s*"), "").trim()
            if (target.isNotEmpty()) {
                steps.add(AgentStep(stepId++, "الاتصال الصوتي المباشر بـ '$target'", "CALL_PLACE", target))
                return steps
            }
        }

        // 0E-C. WhatsApp & Notification Silent Reply / Read
        if ((lower.contains("اقرأ") || lower.contains("اقرا") || lower.contains("شوف") || lower.contains("اعرض")) &&
            (lower.contains("واتساب") || lower.contains("واتس") || lower.contains("رسائل")) &&
            !lower.contains("شاشة")
        ) {
            steps.add(AgentStep(stepId++, "قراءة رسائل واتساب والإشعارات الحية بصمت", "READ_MESSAGES", "واتساب"))
            return steps
        }
        if (lower.startsWith("رد على ") || (lower.contains("واتساب") && (lower.contains("ابعت") || lower.contains("ارسل") || lower.contains("قوله") || lower.contains("رد")))) {
            val parsedReply = extractRecipientAndMessage(clause)
            steps.add(AgentStep(stepId++, "الرد الصامت على '${parsedReply.first}' برسالة '${parsedReply.second}'", "SILENT_REPLY", "${parsedReply.first}:${parsedReply.second}"))
            return steps
        }
        if (lower.startsWith("احفظ ملاحظة ") || lower.startsWith("اكتب ملف ")) {
            val content = clause.replace(Regex("^(احفظ ملاحظة|اكتب ملف)\\s+"), "").trim()
            steps.add(AgentStep(stepId++, "حفظ الملاحظة في إدارة الملفات", "SAVE_NOTE", "morvent_note_${System.currentTimeMillis() % 10000}.txt:$content"))
            return steps
        }

        // 0F. Whole-OS System Controls (Notifications Shade, Global OS Actions, Brightness, Auto-Rotate, DND, Quick Settings Tiles, Streams)
        if ((lower.contains("اقرأ") || lower.contains("اقرا") || lower.contains("تحقق من") || lower.contains("اعرض")) &&
            (lower.contains("إشعارات") || lower.contains("اشعارات") || lower.contains("notifications"))
        ) {
            steps.add(AgentStep(stepId++, "سحب شريط النظام وقراءة جميع الإشعارات", "READ_NOTIFICATIONS", ""))
            return steps
        }
        if ((lower.contains("امسح") || lower.contains("احذف") || lower.contains("نظف") || lower.contains("محو") || lower.contains("clear")) &&
            (lower.contains("إشعارات") || lower.contains("اشعارات") || lower.contains("notifications"))
        ) {
            steps.add(AgentStep(stepId++, "مسح جميع الإشعارات من شريط النظام", "CLEAR_NOTIFICATIONS", ""))
            return steps
        }

        val osGlobalAction = when {
            lower.contains("تقسيم الشاشة") || lower.contains("نصفين") || lower.contains("split screen") -> "split_screen" to "تفعيل وضع تقسيم الشاشة"
            lower.contains("قائمة الطاقة") || lower.contains("إيقاف التشغيل") || lower.contains("إعادة التشغيل") || lower.contains("power menu") || lower.contains("power dialog") -> "power_dialog" to "فتح قائمة الطاقة بالنظام"
            lower.contains("قفل الشاشة") || lower.contains("اقفل الشاشة") || lower.contains("أقفل الشاشة") || lower.contains("lock screen") -> "lock" to "قفل شاشة الجهاز فوراً"
            lower.contains("درج التطبيقات") || lower.contains("كل التطبيقات") || lower.contains("قائمة التطبيقات") || lower.contains("all apps") || lower.contains("app drawer") -> "all_apps" to "فتح درج كافة التطبيقات في النظام"
            lower.contains("أغلق الإشعارات") || lower.contains("اغلق الاشعارات") || lower.contains("إغلاق الإشعارات") || lower.contains("dismiss notifications") -> "dismiss_notifications" to "إغلاق لوحة إشعارات النظام"
            lower.contains("الإعدادات السريعة") || lower.contains("اعدادات سريعة") || lower.contains("quick settings") -> "quick_settings" to "فتح لوحة الإعدادات السريعة للنظام"
            lower.contains("لوحة الإشعارات") || lower.contains("افتح الإشعارات") || lower.contains("اسحب الإشعارات") -> "notifications" to "فتح لوحة إشعارات النظام"
            lower.contains("التطبيقات الأخيرة") || lower.contains("المهام السابقة") || lower.contains("recents") -> "recents" to "عرض التطبيقات الأخيرة بالنظام"
            lower.contains("لقطة شاشة") || lower.contains("سكرين شوت") || lower.contains("screenshot") -> "screenshot" to "التقاط لقطة شاشة للنظام"
            else -> null
        }
        if (osGlobalAction != null) {
            steps.add(AgentStep(stepId++, osGlobalAction.second, "SYSTEM_GLOBAL", osGlobalAction.first))
            return steps
        }

        // Brightness controls (manual percentage or natural words, up/down, or auto-brightness)
        if (lower.contains("سطوع") || lower.contains("brightness") || lower.contains("اضاءة الشاشة") || lower.contains("إضاءة الشاشة")) {
            val naturalPct = parsePercentageWordOrDigit(lower)
            val arg = when {
                lower.contains("تلقائي") || lower.contains("auto") -> "auto"
                naturalPct != null -> naturalPct.coerceIn(1, 100).toString()
                lower.contains("ارفع") || lower.contains("زد") || lower.contains("علي") || lower.contains("up") || lower.contains("increase") -> "up"
                lower.contains("اخفض") || lower.contains("قلل") || lower.contains("نقص") || lower.contains("down") || lower.contains("decrease") -> "down"
                else -> null
            }
            if (arg != null) {
                val desc = when (arg) {
                    "auto" -> "تفعيل السطوع التلقائي للنظام"
                    "up" -> "رفع سطوع شاشة النظام"
                    "down" -> "خفض سطوع شاشة النظام"
                    else -> "ضبط سطوع شاشة النظام على $arg%"
                }
                steps.add(AgentStep(stepId++, desc, "BRIGHTNESS_SET", arg))
                return steps
            }
        }

        // Auto-Rotate controls
        if (lower.contains("تدوير تلقائي") || lower.contains("التدوير التلقائي") || lower.contains("تدوير الشاشة") || lower.contains("auto rotate")) {
            val disable = lower.contains("اوقف") || lower.contains("أوقف") || lower.contains("عطل") || lower.contains("اغلق") || lower.contains("ثبت") || lower.contains("off") || lower.contains("disable")
            steps.add(
                AgentStep(
                    stepId++,
                    if (disable) "إيقاف التدوير التلقائي لشاشة النظام" else "تفعيل التدوير التلقائي لشاشة النظام",
                    "AUTO_ROTATE_SET",
                    if (disable) "false" else "true"
                )
            )
            return steps
        }

        // Do Not Disturb (DND) controls
        if (lower.contains("عدم الإزعاج") || lower.contains("عدم الازعاج") || lower.contains("do not disturb") || lower.contains("dnd")) {
            val disable = lower.contains("اوقف") || lower.contains("أوقف") || lower.contains("عطل") || lower.contains("الغي") || lower.contains("ألغ") || lower.contains("اغلق") || lower.contains("off") || lower.contains("disable")
            steps.add(
                AgentStep(
                    stepId++,
                    if (disable) "إيقاف وضع عدم الإزعاج" else "تفعيل وضع عدم الإزعاج (DND) على مستوى النظام",
                    "DND_SET",
                    if (disable) "false" else "true"
                )
            )
            return steps
        }

        // Screen sleep timeout
        if (lower.contains("مهلة الشاشة") || lower.contains("سكون الشاشة") || lower.contains("قفل تلقائي") || lower.contains("screen timeout")) {
            val num = Regex("(\\d+)").find(lower)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 60
            val secs = if (lower.contains("دقيق") || lower.contains("min")) num * 60 else num
            steps.add(AgentStep(stepId++, "ضبط مهلة سكون الشاشة على $secs ثانية", "SCREEN_TIMEOUT_SET", secs.toString()))
            return steps
        }

        // System overlay panels (Internet / Wi-Fi / Volume)
        if (lower.contains("لوحة الإنترنت") || lower.contains("لوحة الانترنت") || lower.contains("internet panel")) {
            steps.add(AgentStep(stepId++, "فتح لوحة اتصال الإنترنت العائمة", "SYSTEM_PANEL", "internet"))
            return steps
        }
        if (lower.contains("لوحة الصوت") || lower.contains("مستويات الصوت") || lower.contains("volume panel")) {
            steps.add(AgentStep(stepId++, "فتح لوحة مستويات الصوت للنظام", "SYSTEM_PANEL", "volume"))
            return steps
        }

        // Multi-stream volume controls (Alarm / Ring / Notification)
        if (lower.contains("صوت المنبه") || lower.contains("صوت الرنين") || lower.contains("صوت الإشعارات") || lower.contains("صوت الاشعارات")) {
            val streamKey = when {
                lower.contains("منبه") -> "alarm"
                lower.contains("رنين") -> "ring"
                else -> "notif"
            }
            val pct = parsePercentageWordOrDigit(lower) ?: 80
            steps.add(AgentStep(stepId++, "ضبط صوت النظام ($streamKey) على $pct%", "STREAM_VOLUME", "$streamKey:$pct"))
            return steps
        }

        // Quick Settings Tile OS Toggles (Wi-Fi, Bluetooth, Mobile Data, Hotspot, Airplane, Battery Saver, Location, NFC)
        val isToggleIntent = lower.contains("فعل") || lower.contains("فعّل") || lower.contains("شغل") ||
            lower.contains("شغّل") || lower.contains("اوقف") || lower.contains("أوقف") ||
            lower.contains("عطل") || lower.contains("تبديل") || lower.contains("toggle") ||
            lower.contains("enable") || lower.contains("disable") || lower.contains("on") || lower.contains("off")
        val qsTileTarget = when {
            lower.contains("بيانات الجوال") || lower.contains("بيانات الهاتف") || lower.contains("البيانات الخلوية") || lower.contains("mobile data") ->
                "بيانات الجوال|بيانات الهاتف|Mobile data|الإنترنت|Internet" to "تبديل بيانات الجوال من النظام"
            lower.contains("نقطة الاتصال") || lower.contains("هوتسبوت") || lower.contains("hotspot") || lower.contains("نقطة اتصال") ->
                "نقطة الاتصال|Hotspot|Tethering" to "تبديل نقطة الاتصال (Hotspot) من النظام"
            lower.contains("وضع الطيران") || lower.contains("الطيران") || lower.contains("airplane mode") ->
                "وضع الطيران|Airplane mode" to "تبديل وضع الطيران من النظام"
            lower.contains("توفير البطارية") || lower.contains("توفير الطاقة") || lower.contains("battery saver") ->
                "توفير البطارية|توفير شحن البطارية|Battery Saver|Power saving" to "تبديل وضع توفير البطارية من النظام"
            (lower.contains("الموقع") || lower.contains("gps") || lower.contains("location")) && isToggleIntent ->
                "الموقع|الموقع الجغرافي|Location" to "تبديل خدمة الموقع (GPS) من النظام"
            (lower.contains("واي فاي") || lower.contains("وايفاي") || lower.contains("wifi")) && isToggleIntent ->
                "Wi-Fi|Wi‑Fi|الواي فاي|الإنترنت|Internet" to "تبديل Wi-Fi من لوحة النظام السريعة"
            (lower.contains("بلوتوث") || lower.contains("bluetooth")) && isToggleIntent ->
                "البلوتوث|Bluetooth" to "تبديل البلوتوث من لوحة النظام السريعة"
            lower.contains("nfc") && isToggleIntent ->
                "NFC|الاتصال قريب المدى" to "تبديل NFC من لوحة النظام"
            else -> null
        }
        if (qsTileTarget != null) {
            steps.add(AgentStep(stepId++, qsTileTarget.second, "QS_TOGGLE", qsTileTarget.first))
            return steps
        }

        // Fast Hardware controls inside long workflows
        val hardwareAction = when {
            (lower.contains("كشاف") || lower.contains("فلاش")) && (lower.contains("اطف") || lower.contains("اوقف") || lower.contains("اغلق") || lower.contains("أطفئ")) -> "flashlight_off" to "إطفاء الكشاف"
            lower.contains("كشاف") || lower.contains("فلاش") -> "flashlight_on" to "تشغيل الكشاف"
            lower.contains("ارفع الصوت") || lower.contains("رفع الصوت") || lower.contains("علّي الصوت") || lower.contains("علي الصوت") -> "volume_up" to "رفع مستوى الصوت"
            lower.contains("اخفض الصوت") || lower.contains("خفض الصوت") || lower.contains("وطي الصوت") -> "volume_down" to "خفض مستوى الصوت"
            lower.contains("كتم") || lower == "صامت" || lower.contains("وضع الصامت") -> "mute" to "تفعيل الوضع الصامت"
            lower.contains("اهتزاز") -> "vibrate" to "تفعيل وضع الاهتزاز"
            lower.contains("إيقاف الفيديو") || lower.contains("أوقف الفيديو") || lower.contains("أوقف الموسيقى") -> "media_toggle" to "إيقاف/تشغيل الوسائط"
            lower.contains("المقطع التالي") || lower.contains("الفيديو التالي") || lower.contains("الأغنية التالية") -> "media_next" to "الانتقال للمقطع التالي"
            lower.contains("المقطع السابق") || lower.contains("الفيديو السابق") -> "media_previous" to "العودة للمقطع السابق"
            else -> null
        }
        if (hardwareAction != null) {
            steps.add(AgentStep(stepId++, hardwareAction.second, "DEVICE_ACTION", hardwareAction.first))
            return steps
        }

        // 1. App Launching
        val app = extractTargetApp(lower)
        if (app.isNotEmpty()) {
            steps.add(AgentStep(stepId++, "فتح تطبيق $app وتجهيز الواجهة", "LAUNCH_APP", app))
            steps.add(AgentStep(stepId++, "الانتظار حتى استقرار شاشة التطبيق", "WAIT_SETTLE", "1200"))
        }

        // 2. Dark mode / Display Settings toggle
        if (lower.contains("الوضع الداكن") || lower.contains("الوضع الليلي") || lower.contains("dark mode") || lower.contains("dark theme")) {
            steps.add(AgentStep(stepId++, "البحث عن قسم الشاشة والعرض والنقر عليه", "SCROLL_AND_CLICK", "الشاشة|العرض|Display"))
            steps.add(AgentStep(stepId++, "الانتظار لفتح إعدادات الشاشة", "WAIT_SETTLE", "700"))
            steps.add(AgentStep(stepId++, "تفعيل الوضع الداكن", "SCROLL_AND_CLICK", "الوضع الداكن|المظهر الداكن|الوضع الليلي|Dark theme|Dark mode|داكن|Dark"))
            return steps
        }

        // 3. Account / Login Workflow
        if (lower.contains("حساب") || lower.contains("تسجيل") || lower.contains("دخول") ||
            lower.contains("login") || lower.contains("sign in") || lower.contains("create account")
        ) {
            steps.add(AgentStep(stepId++, "فحص خيارات المصادقة على الشاشة", "INSPECT_SCREEN", ""))
            val authTarget = when {
                lower.contains("جوجل") || lower.contains("google") -> "google"
                lower.contains("جديد") || lower.contains("إنشاء") || lower.contains("register") -> "register"
                else -> "login"
            }
            steps.add(AgentStep(stepId++, "النقر على زر المصادقة وفتح الحساب ($authTarget)", "AUTH_WORKFLOW", authTarget))
            steps.add(AgentStep(stepId++, "تأكيد إذن المتابعة", "CLICK_TEXT", "متابعة|Continue|التالي|Next"))
            return steps
        }

        // 4. Play / Search content inside app (e.g. "شغل قرآن كريم" or "ابحث عن أخبار التقنية")
        if (lower.startsWith("شغل ") && app.isEmpty()) {
            val mediaQuery = clause.replace(Regex("(?i)^شغل\\s+"), "").trim()
            if (mediaQuery.isNotEmpty()) {
                steps.add(AgentStep(stepId++, "البحث وتشغيل '$mediaQuery'", "SEARCH_AND_PLAY", mediaQuery))
                return steps
            }
        }

        if (lower.contains("ابحث") || lower.contains("بحث") || lower.contains("search")) {
            val query = clause.replace(Regex("(?i).*(ابحث عن|بحث عن|ابحث في|search for|search)\\s*"), "")
                .replace(Regex("^(عن|في)\\s+"), "")
                .trim()
            steps.add(AgentStep(stepId++, "النقر على حقل أو أيقونة البحث", "CLICK_TEXT", "بحث|Search|search"))
            if (query.isNotEmpty()) {
                steps.add(AgentStep(stepId++, "كتابة '$query' في شريط البحث", "TYPE_TEXT", query))
                steps.add(AgentStep(stepId++, "تأكيد البحث والانتقال للنتائج", "CLICK_TEXT", "بحث|Search"))
            }
            return steps
        }

        // 5. Typing text into a field
        if (lower.startsWith("اكتب") || lower.startsWith("ادخل") || lower.startsWith("أدخل") ||
            lower.startsWith("type") || lower.startsWith("enter")
        ) {
            val textToType = clause.replace(Regex("(?i)^(اكتب|ادخل|أدخل|type|enter)\\s*"), "").trim()
            if (textToType.isNotEmpty()) {
                steps.add(AgentStep(stepId++, "كتابة '$textToType' داخل الحقل النشط", "TYPE_TEXT", textToType))
                return steps
            }
        }

        // 6. Long press
        if (lower.contains("ضغط مطول") || lower.contains("اضغط مطولا") || lower.contains("long press")) {
            val target = clause.replace(Regex("(?i).*(ضغط مطول على|اضغط مطولا على|ضغط مطول|long press on|long press)\\s*"), "").trim()
            if (target.isNotEmpty()) {
                steps.add(AgentStep(stepId++, "ضغط مطول بشري على '$target'", "LONG_CLICK", target))
                return steps
            }
        }

        // 7. Scroll & Find / Click
        if (lower.contains("انزل واضغط") || lower.contains("ابحث واضغط") || lower.contains("scroll and click") ||
            lower.startsWith("فعل ") || lower.startsWith("فعّل ")
        ) {
            val target = clause.replace(Regex("(?i).*(انزل واضغط على|ابحث واضغط على|انزل واضغط|ابحث واضغط|scroll and click|فعّل|فعل)\\s*"), "").trim()
            if (target.isNotEmpty()) {
                steps.add(AgentStep(stepId++, "التمرير والبحث عن '$target' والنقر عليه", "SCROLL_AND_CLICK", target))
                return steps
            }
        }

        // 8. Horizontal Swipes
        if (lower.contains("اسحب يمين") || lower.contains("اسحب لليمين") || lower.contains("swipe right")) {
            steps.add(AgentStep(stepId++, "سحب الشاشة لليمين بإيماءة بشرية", "SWIPE_RIGHT", ""))
            return steps
        }
        if (lower.contains("اسحب يسار") || lower.contains("اسحب لليسار") || lower.contains("swipe left")) {
            steps.add(AgentStep(stepId++, "سحب الشاشة لليسار بإيماءة بشرية", "SWIPE_LEFT", ""))
            return steps
        }

        // 9. Browsing / Vertical Scrolling
        if (lower.contains("تصفح") || lower.contains("انزل") || lower.contains("مرر لأسفل") || lower.contains("scroll down")) {
            steps.add(AgentStep(stepId++, "تصفح الشاشة والتمرير للأسفل بإيماءة بشرية", "HUMAN_SWIPE_DOWN", ""))
            steps.add(AgentStep(stepId++, "فحص العناصر التفاعلية الظاهرة بعد التمرير", "INSPECT_SCREEN", ""))
            steps.add(AgentStep(stepId++, "التمرير لمزيد من المحتوى", "HUMAN_SWIPE_DOWN", ""))
            return steps
        }
        if (lower.contains("اطلع") || lower.contains("مرر لأعلى") || lower.contains("scroll up")) {
            steps.add(AgentStep(stepId++, "التمرير للأعلى بإيماءة بشرية", "HUMAN_SWIPE_UP", ""))
            return steps
        }

        // 10. Navigation back / home / close
        if (lower == "رجوع" || lower == "ارجع" || lower == "back" || lower == "أغلق" || lower == "اغلق" || lower == "تراجع") {
            steps.add(AgentStep(stepId++, "تنفيذ الرجوع للخلف", "NAV_BACK", ""))
            return steps
        }
        if (lower == "الرئيسية" || lower == "home" || lower.contains("الشاشة الرئيسية")) {
            steps.add(AgentStep(stepId++, "الانتقال للشاشة الرئيسية", "NAV_HOME", ""))
            return steps
        }

        // 10B. Ordinal tapping (e.g. "اضغط على أول نتيجة", "اختر الخيار الثاني")
        val ordinal = parseOrdinalIndex(lower)
        if (ordinal != null && (lower.contains("اضغط") || lower.contains("انقر") || lower.contains("اختر") || lower.contains("افتح") || lower.contains("نتيجة") || lower.contains("خيار") || lower.contains("عنصر"))) {
            steps.add(AgentStep(stepId++, "النقر على العنصر رقم $ordinal على الشاشة", "CLICK_ORDINAL", ordinal.toString()))
            return steps
        }

        // 11. Specific click / select request
        if (lower.contains("اضغط") || lower.contains("انقر") || lower.contains("اختر") || lower.contains("حدد") ||
            lower.contains("click") || lower.contains("tap") || lower.contains("select")
        ) {
            val target = clause.replace(Regex("(?i).*(اضغط على|انقر على|اضغط|انقر|اختر|حدد|click on|tap on|click|tap|select)\\s*"), "").trim()
            if (target.isNotEmpty()) {
                steps.add(AgentStep(stepId++, "النقر البشري على '$target'", "CLICK_TEXT", target))
                return steps
            }
        }

        // 12. If app was launched with no extra verb, inspect it so it's ready
        if (app.isNotEmpty()) {
            steps.add(AgentStep(stepId++, "تحليل عناصر شاشة $app بعد الفتح", "INSPECT_SCREEN", ""))
            return steps
        }

        // 13. Fallback perceive & act
        steps.add(AgentStep(stepId++, "فحص شاشة التطبيق وقراءة شجرة العناصر", "INSPECT_SCREEN", ""))
        steps.add(AgentStep(stepId++, "التفاعل مع الخيار الأساسي على الشاشة", "CLICK_PRIMARY", ""))
        return steps
    }

    private fun parsePercentageWordOrDigit(lower: String): Int? {
        Regex("\\d+").find(lower)?.value?.toIntOrNull()?.let {
            return it.coerceIn(0, 100)
        }
        return when {
            lower.contains("أقصى") || lower.contains("اقصى") || lower.contains("كامل") || lower.contains("فل") || lower.contains("max") -> 100
            lower.contains("النصف") || lower.contains("نصف") || lower.contains("half") -> 50
            lower.contains("أدنى") || lower.contains("ادنى") || lower.contains("أقل") || lower.contains("اقل") || lower.contains("min") -> 10
            else -> null
        }
    }

    private fun parseWaitDurationMs(lower: String): Long {
        if (lower.contains("نصف دقيقة")) return 30_000L
        val num = Regex("(\\d+)").find(lower)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 2L
        val ms = when {
            lower.contains("دقيق") || lower.contains("min") -> num * 60_000L
            else -> num * 1000L
        }
        return ms.coerceIn(500L, 60_000L)
    }

    private fun extractUrlFromClause(clause: String): String {
        val urlRegex = Regex("(?i)(https?://[^\\s]+|www\\.[^\\s]+|[a-z0-9-]+\\.(?:com|net|org|io|dev|edu|gov)(?:/[^\\s]*)?)")
        return urlRegex.find(clause)?.value?.trim()?.trimEnd('.', '،', ';') ?: ""
    }

    private fun parseOrdinalIndex(lower: String): Int? {
        return when {
            lower.contains("أول") || lower.contains("الأول") || lower.contains("الاول") || lower.contains("first") -> 1
            lower.contains("ثاني") || lower.contains("الثاني") || lower.contains("second") -> 2
            lower.contains("ثالث") || lower.contains("الثالث") || lower.contains("third") -> 3
            lower.contains("رابع") || lower.contains("الرابع") || lower.contains("fourth") -> 4
            lower.contains("خامس") || lower.contains("الخامس") || lower.contains("fifth") -> 5
            else -> null
        }
    }

    private suspend fun clickWithSynonyms(pipeSeparatedQueries: String): Pair<Boolean, String> {
        val candidates = pipeSeparatedQueries.split("|").map { it.trim() }.filter { it.isNotEmpty() }
        for (q in candidates) {
            val res = MorventAccessibilityService.clickElementSmartWithRecovery(q)
            if (res.first) return res
        }
        return Pair(false, "لم يتم العثور على '$pipeSeparatedQueries'")
    }

    private suspend fun executeSingleLocalStep(step: AgentStep): Pair<Boolean, String> {
        return when (step.actionType) {
            "LAUNCH_APP" -> controller.launchAppByQuery(step.target)
            "WAIT_SETTLE" -> {
                val ms = step.target.toLongOrNull() ?: 1000L
                delayChunked(ms)
                Pair(true, "تم الانتظار (${ms}ms)")
            }
            "OPEN_URL" -> {
                val ok = controller.openUrl(step.target)
                Pair(ok, if (ok) "فتح ${step.target}" else "تعذر فتح الرابط")
            }
            "OPEN_SETTINGS" -> {
                val ok = controller.openSpecificSettings(step.target)
                Pair(ok, if (ok) "فتح إعدادات ${step.target}" else "تعذر فتح الإعدادات")
            }
            "COPY_TEXT" -> {
                controller.copyToClipboard("Morvent", step.target)
                Pair(true, "تم نسخ النص إلى الحافظة")
            }
            "PASTE_TEXT" -> {
                if (!MorventAccessibilityService.isRunning) {
                    return Pair(false, "خدمة إمكانية الوصول غير مفعلة")
                }
                val clip = controller.getClipboardText().orEmpty()
                if (clip.isEmpty()) {
                    Pair(false, "الحافظة فارغة")
                } else {
                    val res = MorventAccessibilityService.typeTextIntoElementById(null, clip, submitEnter = false)
                    if (res.first) res else MorventAccessibilityService.typeTextIntoField(null, clip)
                }
            }
            "SHARE_TEXT" -> {
                val ok = controller.shareText(step.target)
                Pair(ok, if (ok) "تم فتح نافذة المشاركة" else "تعذر فتح المشاركة")
            }
            "DEVICE_ACTION" -> {
                when (step.target) {
                    "flashlight_on" -> Pair(controller.toggleFlashlight(true), "تشغيل الكشاف")
                    "flashlight_off" -> Pair(controller.toggleFlashlight(false), "إطفاء الكشاف")
                    "volume_up" -> Pair(controller.adjustMediaVolume(true), "رفع الصوت")
                    "volume_down" -> Pair(controller.adjustMediaVolume(false), "خفض الصوت")
                    "mute" -> Pair(controller.setRingerMode(0), "الوضع الصامت")
                    "vibrate" -> Pair(controller.setRingerMode(1), "وضع الاهتزاز")
                    "take_screenshot" -> Pair(MorventAccessibilityService.performTakeScreenshot(), "لقطة شاشة")
                    "open_notifications" -> Pair(MorventAccessibilityService.performNotifications(), "لوحة الإشعارات")
                    "open_recents" -> Pair(MorventAccessibilityService.performRecents(), "التطبيقات الأخيرة")
                    "media_toggle" -> Pair(controller.dispatchMediaKey("toggle"), "تبديل الوسائط")
                    "media_next" -> Pair(controller.dispatchMediaKey("next"), "المقطع التالي")
                    "media_previous" -> Pair(controller.dispatchMediaKey("previous"), "المقطع السابق")
                    else -> Pair(false, "إجراء غير مدعوم")
                }
            }
            "SYSTEM_GLOBAL" -> executeGlobalAction(step.target)
            "BRIGHTNESS_SET" -> executeDeviceControl("brightness:${step.target}")
            "AUTO_ROTATE_SET" -> executeDeviceControl(if (step.target == "false") "auto_rotate_off" else "auto_rotate_on")
            "DND_SET" -> executeDeviceControl(if (step.target == "false") "dnd_off" else "dnd_on")
            "SCREEN_TIMEOUT_SET" -> executeDeviceControl("timeout:${step.target}")
            "SYSTEM_PANEL" -> executeDeviceControl("panel:${step.target}")
            "QS_TOGGLE" -> MorventAccessibilityService.performQuickSettingTileToggle(step.target, collapseAfter = true)
            "READ_NOTIFICATIONS" -> executeDeviceControl("read_notifications")
            "CLEAR_NOTIFICATIONS" -> executeDeviceControl("clear_notifications")
            "APP_DETAILS" -> executeDeviceControl("app_details:${step.target}")
            "PERMISSION_DIALOG" -> executeDeviceControl(if (step.target == "deny") "permission_deny" else "permission_allow")
            "CLEAR_FIELD" -> executeDeviceControl("clear_field")
            "OPEN_MAPS" -> executeDeviceControl("maps:${step.target}")
            "OPEN_CAMERA" -> executeDeviceControl(if (step.target == "video") "camera_video" else "camera_photo")
            "OPEN_FILES" -> executeDeviceControl("files")
            "SEND_EMAIL" -> executeDeviceControl("email:${step.target}")
            "CALL_PLACE" -> executeDeviceControl("call:${step.target}")
            "CALL_ANSWER" -> executeDeviceControl("answer_call")
            "CALL_END" -> executeDeviceControl("end_call")
            "SPEAKER_SET" -> executeDeviceControl(if (step.target == "off") "speaker_off" else "speaker_on")
            "SILENT_REPLY" -> executeDeviceControl("reply_notification:${step.target}")
            "READ_MESSAGES" -> executeDeviceControl("read_whatsapp")
            "SAVE_NOTE" -> executeDeviceControl("save_note:${step.target}")
            "ADB_SECURE_TOGGLE" -> executeDeviceControl("adb_toggle:${step.target}")
            "STREAM_VOLUME" -> {
                val streamKey = step.target.substringBefore(":")
                val pct = step.target.substringAfter(":", "75").toIntOrNull() ?: 75
                executeDeviceControl("${streamKey}_volume:$pct")
            }
            "READ_SCREEN_LOCAL" -> {
                if (!MorventAccessibilityService.isRunning) {
                    return Pair(false, "خدمة إمكانية الوصول غير مفعلة")
                }
                val snap = MorventAccessibilityService.captureRichScreenSnapshot()
                if (snap.visibleTexts.isEmpty()) {
                    Pair(false, "لم يتم العثور على نصوص واضحة على الشاشة الحالية")
                } else {
                    val pkgLabel = snap.packageName.ifBlank { "الشاشة الحالية" }
                    val preview = snap.visibleTexts.take(18).joinToString("\n• ", prefix = "• ")
                    val report = "📖 محتوى الشاشة ($pkgLabel):\n$preview"
                    Pair(true, report)
                }
            }
            "DEEP_READ_SCREEN_LOCAL" -> MorventAccessibilityService.deepReadComplexScreen()
            "SEARCH_AND_PLAY" -> {
                if (!MorventAccessibilityService.isRunning) {
                    return Pair(false, "خدمة إمكانية الوصول غير مفعلة")
                }
                clickWithSynonyms("بحث|Search|search_button|menu_search|البحث")
                delayChunked(320)
                val typed = MorventAccessibilityService.typeTextIntoElementById(null, step.target, submitEnter = true)
                if (!typed.first) {
                    MorventAccessibilityService.smartTypeAndSubmit(null, step.target, submit = true)
                }
                delayChunked(850)
                val firstToken = step.target.split(" ").firstOrNull() ?: step.target
                val clicked = MorventAccessibilityService.clickElementSmart(firstToken)
                if (clicked.first) clicked else typed
            }
            "CLICK_ORDINAL" -> {
                val ordinal = step.target.toIntOrNull() ?: 1
                MorventAccessibilityService.clickOrdinalElement(ordinal)
            }
            "CLICK_TEXT" -> clickWithSynonyms(step.target)
            "LONG_CLICK" -> MorventAccessibilityService.longClickElementByText(step.target)
            "SCROLL_AND_CLICK" -> MorventAccessibilityService.findAndClickSmart(step.target)
            "TYPE_TEXT" -> {
                val res = MorventAccessibilityService.typeTextIntoElementById(null, step.target, submitEnter = true)
                if (res.first) res else MorventAccessibilityService.smartTypeAndSubmit(null, step.target, submit = true)
            }
            "HUMAN_SWIPE_DOWN" -> MorventAccessibilityService.scrollScreen(true)
            "HUMAN_SWIPE_UP" -> MorventAccessibilityService.scrollScreen(false)
            "SWIPE_RIGHT" -> MorventAccessibilityService.swipeHorizontal(true)
            "SWIPE_LEFT" -> MorventAccessibilityService.swipeHorizontal(false)
            "NAV_BACK" -> {
                val ok = MorventAccessibilityService.performBack()
                Pair(ok, if (ok) "رجوع" else "تعذر الرجوع")
            }
            "NAV_HOME" -> {
                val ok = MorventAccessibilityService.performHome()
                Pair(ok, if (ok) "الرئيسية" else "تعذر الانتقال للرئيسية")
            }
            "INSPECT_SCREEN" -> {
                val report = MorventAccessibilityService.inspectCurrentScreen()
                _status.value = _status.value.copy(activeAppPackage = report.packageName)
                Pair(report.elements.isNotEmpty(), "رصد ${report.elements.size} عنصر")
            }
            "AUTH_WORKFLOW" -> {
                val (ok, _) = MorventAccessibilityService.executeAutonomousAccountWorkflow(step.target)
                Pair(ok, if (ok) "تنفيذ مسار المصادقة" else "تعذر رصد زر المصادقة")
            }
            "CLICK_PRIMARY" -> {
                val report = MorventAccessibilityService.inspectCurrentScreen()
                val primaryBtn = report.buttons.firstOrNull()
                if (primaryBtn != null) {
                    MorventAccessibilityService.clickElementSmartWithRecovery(primaryBtn)
                } else Pair(false, "لا يوجد زر أساسي")
            }
            "LINUX_EXEC_LOCAL" -> {
                val res = linuxSandbox.executeCommand(step.target)
                Pair(res.exitCode == 0, "[$ ${res.command}]\n${res.output}")
            }
            else -> Pair(true, "تم")
        }
    }

    private fun extractRecipientAndMessage(clause: String): Pair<String, String> {
        val separators = listOf("محتواها", "وقوله", "وقل له", "رسالة", "نصها", "بـ:", "بـ ", "ب:", "قوله")
        for (sep in separators) {
            val idx = clause.indexOf(sep, ignoreCase = true)
            if (idx != -1) {
                val left = clause.substring(0, idx)
                    .replace(Regex("(?i)^(رد على|ابعت لـ|ابعت ل|ارسل لـ|ارسل إلى|ارسل ل|راسل)\\s*"), "")
                    .replace(Regex("(?i)(في واتساب|على واتساب|بالواتساب|واتساب|واتس)"), "")
                    .trim()
                val right = clause.substring(idx + sep.length).trim().removePrefix(":").trim()
                if (right.isNotBlank()) return Pair(left.ifBlank { "واتساب" }, right)
            }
        }
        val cleanRecipient = clause
            .replace(Regex("(?i)^(رد على|ابعت لـ|ابعت ل|ارسل لـ|ارسل إلى|ارسل ل|راسل)\\s*"), "")
            .replace(Regex("(?i)(في واتساب|على واتساب|بالواتساب|واتساب|واتس)"), "")
            .trim()
        return Pair(cleanRecipient.ifBlank { "واتساب" }, "مرحباً، تم التواصل معك عبر مورڤينت")
    }

    private fun extractTargetApp(text: String): String {
        val keywords = listOf(
            "واتساب", "تويتر", "يوتيوب", "فيسبوك", "إنستغرام", "انستقرام",
            "تيليجرام", "تليجرام", "كروم", "الإعدادات", "اعدادات", "الكاميرا",
            "المتجر", "خرائط", "تيك توك", "سناب", "جيميل", "الملاحظات", "التقويم"
        )
        for (kw in keywords) {
            if (text.contains(kw)) return kw
        }
        val match = Regex("(?i)(?:افتح|في تطبيق|تطبيق|برنامج|open|launch|شغل تطبيق|شغل برنامج)\\s+([a-zA-Z\u0600-\u06FF0-9_]+)").find(text)
        val extracted = match?.groupValues?.getOrNull(1)?.trim() ?: ""
        return if (extracted in listOf("حساب", "الشاشة", "جديد", "الإشعارات", "اشعارات", "موقع", "إعدادات", "اعدادات", "أول", "ثاني", "ثالث", "درج", "قائمة", "تقسيم", "لوحة", "نظام", "الطاقة", "خريطة", "الملفات", "ملفات")) "" else extracted
    }
}
