package com.example.nlp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.example.ai.ApiKeyManager
import com.example.ai.UserLearningManager
import com.example.model.ChatAttachment
import com.example.service.RichScreenSnapshot
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

enum class AgentActionKind {
    TAP_ELEMENT,
    TAP_COORDINATES,
    DOUBLE_TAP,
    LONG_PRESS_ELEMENT,
    LONG_PRESS_COORDINATES,
    SMART_FIND_CLICK,
    TYPE_TEXT,
    SCROLL_DOWN,
    SCROLL_UP,
    SWIPE_LEFT,
    SWIPE_RIGHT,
    DRAG,
    PINCH_IN,
    PINCH_OUT,
    LAUNCH_APP,
    OPEN_URL,
    OPEN_SETTINGS,
    GLOBAL_ACTION,
    MEDIA_CONTROL,
    DEVICE_CONTROL,
    LINUX_EXEC,
    READ_SCREEN,
    DEEP_READ_SCREEN,
    WAIT,
    ASK_USER,
    DONE
}

data class AgentDecision(
    val action: AgentActionKind,
    val thought: String,
    val statusLabel: String,
    val elementId: Int? = null,
    val x: Int = 500,
    val y: Int = 500,
    val endX: Int = 500,
    val endY: Int = 500,
    val text: String = "",
    val submitEnter: Boolean = false,
    val target: String = "",
    val waitMs: Long = 1000L,
    val finalResponse: String = "",
    val steps: List<AgentDecision> = emptyList()
)

data class ChatTurn(
    val isUser: Boolean,
    val text: String
)

data class ApiConnectionTestResult(
    val isSuccess: Boolean,
    val modelUsed: String,
    val message: String,
    val httpCode: Int = 0
)

sealed interface BrainResult {
    data class Success(val decision: AgentDecision) : BrainResult
    data class Failure(
        val errorMessage: String,
        val isCloudUnavailable: Boolean = false
    ) : BrainResult
}

/**
 * Multimodal Vision + UI-Tree + Conversational Brain.
 * Uses direct Gemini v1beta REST via OkHttp with verified active models (gemini-3.5-flash-lite,
 * gemini-flash-lite-latest, gemini-3.8-flash) when an API key is configured, and falls back
 * cleanly to Firebase AI Logic when no custom API key is set.
 */
class GeminiAgentBrain(context: Context? = null) {

    private val tag = "GeminiAgentBrain"
    private val appContext = context?.applicationContext
    private val keyManager = appContext?.let { ApiKeyManager.getInstance(it) }
    private val learningManager = appContext?.let { UserLearningManager.getInstance(it) }

    @Volatile
    private var isCloudProjectBillingBlocked: Boolean = false

    fun isCloudUnavailableForSession(): Boolean = isCloudProjectBillingBlocked

    fun resetCloudAvailability() {
        isCloudProjectBillingBlocked = false
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    private val subStepSchema by lazy {
        Schema.obj(
            properties = mapOf(
                "action" to Schema.enumeration(
                    values = AgentActionKind.entries.map { it.name },
                    description = "Sub-step action kind."
                ),
                "elementId" to Schema.integer(description = "UI tree [id] if known.", nullable = true),
                "x" to Schema.integer(description = "Normalized X 0..1000.", nullable = true),
                "y" to Schema.integer(description = "Normalized Y 0..1000.", nullable = true),
                "endX" to Schema.integer(description = "End normalized X 0..1000.", nullable = true),
                "endY" to Schema.integer(description = "End normalized Y 0..1000.", nullable = true),
                "text" to Schema.string(description = "Text to type.", nullable = true),
                "submitEnter" to Schema.boolean(description = "Press Enter/Search after typing.", nullable = true),
                "target" to Schema.string(description = "Text query for SMART_FIND_CLICK or target argument.", nullable = true),
                "waitMs" to Schema.integer(description = "Delay milliseconds.", nullable = true)
            ),
            optionalProperties = listOf(
                "elementId", "x", "y", "endX", "endY", "text", "submitEnter", "target", "waitMs"
            )
        )
    }

    private val decisionSchema by lazy {
        Schema.obj(
            properties = mapOf(
                "thought" to Schema.string(description = "Brief Arabic reasoning about what is currently visible on the phone screen and why this action is next."),
                "statusLabel" to Schema.string(description = "Short 3-6 word Arabic status for the Floating HUD (e.g. أضغط على زر البحث...)."),
                "action" to Schema.enumeration(
                    values = AgentActionKind.entries.map { it.name },
                    description = "Exact next action to execute on the Android device."
                ),
                "elementId" to Schema.integer(description = "Target [id] from the UI tree when using TAP_ELEMENT, LONG_PRESS_ELEMENT, or TYPE_TEXT.", nullable = true),
                "x" to Schema.integer(description = "Normalized X coordinate 0..1000 (0=left edge, 1000=right edge).", nullable = true),
                "y" to Schema.integer(description = "Normalized Y coordinate 0..1000 (0=top edge, 1000=bottom edge).", nullable = true),
                "endX" to Schema.integer(description = "End normalized X 0..1000 for DRAG.", nullable = true),
                "endY" to Schema.integer(description = "End normalized Y 0..1000 for DRAG.", nullable = true),
                "text" to Schema.string(description = "Text to type when action is TYPE_TEXT or text to copy.", nullable = true),
                "submitEnter" to Schema.boolean(description = "Whether to press Enter/Search/Send IME action after typing.", nullable = true),
                "target" to Schema.string(description = "Target value for SMART_FIND_CLICK, LAUNCH_APP, OPEN_URL, OPEN_SETTINGS, GLOBAL_ACTION, MEDIA_CONTROL, or DEVICE_CONTROL.", nullable = true),
                "waitMs" to Schema.integer(description = "Milliseconds to wait when action is WAIT (300..4000).", nullable = true),
                "finalResponse" to Schema.string(description = "Clear Arabic message for the user when action is DONE, READ_SCREEN, DEEP_READ_SCREEN, or ASK_USER.", nullable = true),
                "steps" to Schema.array(
                    items = subStepSchema,
                    description = "Optional batch of up to 4 deterministic immediate UI actions to execute sequentially in one fast turn (e.g. tap search box + type text + submit).",
                    nullable = true
                )
            ),
            optionalProperties = listOf(
                "elementId", "x", "y", "endX", "endY", "text",
                "submitEnter", "target", "waitMs", "finalResponse", "steps"
            )
        )
    }

    private val config by lazy {
        generationConfig {
            responseMimeType = "application/json"
            responseSchema = decisionSchema
            temperature = 0.1f
        }
    }

    private val primaryModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.5-flash-lite",
            generationConfig = config,
            systemInstruction = content { text(SYSTEM_PROMPT) }
        )
    }

    private val chatModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.5-flash-lite",
            systemInstruction = content { text(CHAT_SYSTEM_PROMPT) }
        )
    }

    private fun currentApiKey(): String {
        val dynamic = keyManager?.getEffectiveApiKey()?.trim().orEmpty()
        if (dynamic.isNotBlank()) return dynamic
        val builtIn = BuildConfig.GEMINI_API_KEY.trim()
        return if (builtIn.isNotBlank() && !builtIn.startsWith("AIzaSy...Placeholder")) builtIn else ""
    }

    private fun candidateModelsForApiKey(): List<String> {
        val preferred = keyManager?.getSelectedModelName() ?: ApiKeyManager.DEFAULT_MODEL
        val ordered = linkedSetOf(preferred)
        ordered.add("gemini-3.5-flash-lite")
        ordered.add("gemini-flash-lite-latest")
        ordered.add("gemini-3.8-flash")
        return ordered.toList()
    }

    internal fun buildEffectiveChatPrompt(systemContextSnapshot: String = ""): String {
        val sb = StringBuilder(CHAT_SYSTEM_PROMPT.trim())
        val strict = keyManager?.strictObedienceMode?.value ?: true
        if (strict) {
            sb.appendLine()
            sb.appendLine()
            sb.appendLine("[أمر تشغيل إلزامي — الطاعة الحرفية والتنفيذ المباشر]:")
            sb.appendLine("- التزم بطلبات المستخدم بحذافيرها وحول أوامره فوراً إلى أفعال عبر [[EXECUTE_GOAL: ...]] أو أوامر شل لينكس عبر [[LINUX_EXEC: ...]].")
            sb.appendLine("- لا تعتذر ولا ترفض ولا تقل 'لا أستطيع' أبداً؛ قدم أفضل مسار عملي وتنفيذي مباشر على الفور.")
        }
        val custom = keyManager?.getCustomInstructions().orEmpty()
        if (custom.isNotBlank()) {
            sb.appendLine()
            sb.appendLine("[التعليمات الخاصة المحددة من المالك (أولوية قصوى)]:")
            sb.appendLine(custom)
        }
        val learned = learningManager?.buildLearningPromptContext().orEmpty()
        if (learned.isNotBlank()) {
            sb.appendLine()
            sb.appendLine(learned)
        }
        if (systemContextSnapshot.isNotBlank()) {
            sb.appendLine()
            sb.appendLine(systemContextSnapshot)
        }
        return sb.toString().trim()
    }

    internal fun buildEffectiveAgentPrompt(systemContextSnapshot: String = ""): String {
        val sb = StringBuilder(SYSTEM_PROMPT.trim())
        val strict = keyManager?.strictObedienceMode?.value ?: true
        if (strict) {
            sb.appendLine()
            sb.appendLine("- القاعدة الصارمة: نفذ تعليمات المستخدم بالحرف دون رفض أو تردد أو أسئلة متكررة.")
        }
        val custom = keyManager?.getCustomInstructions().orEmpty()
        if (custom.isNotBlank()) {
            sb.appendLine()
            sb.appendLine("[تعليمات خاصة من المستخدم]: $custom")
        }
        val learned = learningManager?.buildLearningPromptContext().orEmpty()
        if (learned.isNotBlank()) {
            sb.appendLine()
            sb.appendLine(learned)
        }
        if (systemContextSnapshot.isNotBlank()) {
            sb.appendLine()
            sb.appendLine(systemContextSnapshot)
        }
        return sb.toString().trim()
    }

    fun getSmartLocalChatResponse(input: String): String? {
        val trimmed = input.trim()
        val lower = trimmed.lowercase(Locale.ROOT)
        if (lower in listOf("السلام عليكم", "سلام عليكم", "السلام عليكم ورحمة الله", "سلام", "السلام عليكم ورحمة الله وبركاته", "وعليكم السلام")) {
            return "وعليكم السلام ورحمة الله وبركاته! 🌸\nمرحباً بك! أنا **مورڤينت (Morvent)**، وكيل الأتمتة والذكاء الاصطناعي الشامل لهاتفك ونظام أندرويد."
        }
        if (lower in listOf("مرحبا", "مرحباً", "أهلاً", "اهلا", "أهلا", "أهلاً وسهلاً", "اهلا وسهلا", "هلا", "مرحبا بك", "hi", "hello", "hey")) {
            return "أهلاً وسهلاً بك! 👋 أنا **مورڤينت**، مساعدك الذكي ووكيل التحكم بالهاتف والتطبيقات."
        }
        if (lower in listOf("من أنت", "من انت", "عرف عن نفسك", "who are you")) {
            return "أنا **مورڤينت (Morvent)** — وكيل أندرويد ذكي للتحدث وتنفيذ المهام والأتمتة الحقيقية على الهاتف."
        }
        return null
    }

    fun getHelpfulConversationalFallback(question: String): String {
        val trimmed = question.trim()
        return "تعذر الوصول إلى خادم الذكاء الاصطناعي Gemini حالياً للرد على: \"$trimmed\".\n" +
            "تأكد من اتصال الإنترنت أو اضغط على أيقونة المفتاح 🔑 في الشريط العلوي للتحقق من مفتاح Gemini API واختبار الاتصال."
    }

    /**
     * Direct connectivity test invoked from the UI Settings sheet ("اختبار الاتصال الآن").
     */
    suspend fun testApiKeyConnection(
        apiKey: String,
        preferredModel: String = ApiKeyManager.DEFAULT_MODEL
    ): ApiConnectionTestResult {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty()) {
            return ApiConnectionTestResult(
                isSuccess = false,
                modelUsed = preferredModel,
                message = "يرجى إدخال مفتاح Gemini API أولاً."
            )
        }

        val models = linkedSetOf(preferredModel.ifBlank { ApiKeyManager.DEFAULT_MODEL }, "gemini-3.5-flash-lite", "gemini-flash-lite-latest")
        var lastError = ApiConnectionTestResult(false, preferredModel, "لم يرد الخادم")

        for (model in models) {
            val res = callDirectGeminiRest(
                apiKey = cleanKey,
                modelName = model,
                systemInstruction = "أنت مساعد ذكي اسمه مورڤينت. أجب باختصار شديد بالعربية.",
                historyTurns = emptyList(),
                userPrompt = "أرسل جملة ترحيب قصيرة من 4 كلمات لتأكيد الاتصال بنجاح.",
                screenshot = null,
                expectJson = false
            )
            if (res.isSuccess && !res.text.isNullOrBlank()) {
                keyManager?.updateConnectionStatus("متصل بـ $model ✓")
                resetCloudAvailability()
                return ApiConnectionTestResult(
                    isSuccess = true,
                    modelUsed = model,
                    message = "تم الاتصال بنجاح عبر نموذج ($model):\n\"${res.text.trim()}\"",
                    httpCode = 200
                )
            } else {
                lastError = ApiConnectionTestResult(
                    isSuccess = false,
                    modelUsed = model,
                    message = res.friendlyError ?: "فشل الاتصال (HTTP ${res.httpCode})",
                    httpCode = res.httpCode
                )
                // If invalid API key (400/401/403 API_KEY_INVALID), don't retry other models with same bad key
                if (res.httpCode == 400 || res.httpCode == 401 || res.httpCode == 403) {
                    break
                }
            }
        }
        keyManager?.updateConnectionStatus("خطأ اتصال: ${lastError.message.take(60)}")
        return lastError
    }

    /**
     * Conversational & Multimodal AI response supporting uploaded images/files, custom instructions,
     * user style learning, and full system snapshot awareness.
     */
    suspend fun answerGeneralChat(
        question: String,
        recentHistory: List<ChatTurn> = emptyList(),
        attachments: List<ChatAttachment> = emptyList(),
        systemContextSnapshot: String = ""
    ): String {
        val effectivePrompt = buildEffectiveChatPrompt(systemContextSnapshot)
        val finalQuestion = buildString {
            append(question.ifBlank { "حلل المرفقات التالية بدقة ونفذ المطلوب." })
            if (attachments.isNotEmpty()) {
                append("\n\n[مرفقات من المستخدم (${attachments.size})]:")
                attachments.forEach { att ->
                    append("\n• ${att.displayName} (${att.mimeType})")
                    if (!att.localLinuxPath.isNullOrBlank()) {
                        append(" | مسار لينكس: ${att.localLinuxPath}")
                    }
                }
            }
        }

        val apiKey = currentApiKey()
        if (apiKey.isNotBlank()) {
            var lastErrorMsg: String? = null
            for (model in candidateModelsForApiKey()) {
                val res = callDirectGeminiRest(
                    apiKey = apiKey,
                    modelName = model,
                    systemInstruction = effectivePrompt,
                    historyTurns = recentHistory,
                    userPrompt = finalQuestion,
                    screenshot = null,
                    attachments = attachments,
                    expectJson = false
                )
                if (res.isSuccess && !res.text.isNullOrBlank()) {
                    keyManager?.updateConnectionStatus("متصل بـ $model ✓")
                    resetCloudAvailability()
                    return res.text.trim()
                }
                lastErrorMsg = res.friendlyError
                if (res.httpCode == 400 || res.httpCode == 401 || res.httpCode == 403) {
                    break
                }
            }
            if (!lastErrorMsg.isNullOrBlank()) {
                return "⚠️ $lastErrorMsg\n\n💡 يمكنك تعديل أو اختبار مفتاح API من أيقونة المفتاح 🔑 في أعلى الشاشة."
            }
        }

        // Firebase AI Logic path when no API key is configured
        return try {
            val dynamicModel = Firebase.ai.generativeModel(
                modelName = "gemini-3.5-flash-lite",
                systemInstruction = content { text(effectivePrompt) }
            )
            val payload = content {
                attachments.forEach { att ->
                    if (att.isImage) {
                        loadScaledBitmap(att.uriString)?.let { bmp -> image(bmp) }
                    } else {
                        readAttachmentTextPreview(att)?.let { txt -> text(txt) }
                    }
                }
                text(finalQuestion)
            }
            val res = withTimeout(25_000L) { dynamicModel.generateContent(payload) }
            val reply = res.text?.trim()
            if (!reply.isNullOrBlank()) reply else getHelpfulConversationalFallback(question)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.w(tag, "Firebase AI chat generation failed: ${e.message}", e)
            getHelpfulConversationalFallback(question)
        }
    }

    suspend fun generateSmartAutoReply(sender: String, incomingText: String, fallbackTemplate: String): String {
        val apiKey = currentApiKey()
        if (apiKey.isNotBlank()) {
            val prompt = "وصلك الآن إشعار رسالة من \"$sender\" نصها:\n\"$incomingText\"\n" +
                "اكتب رداً عربياً مختصراً ومهذباً نيابة عن مالك الهاتف (أقل من 18 كلمة)."
            for (model in candidateModelsForApiKey()) {
                val res = callDirectGeminiRest(
                    apiKey = apiKey,
                    modelName = model,
                    systemInstruction = "أنت الرد التلقائي الذكي لمساعد مورڤينت. اكتب رداً طبيعياً قصيراً ومباشراً فقط بدون علامات اقتباس.",
                    historyTurns = emptyList(),
                    userPrompt = prompt,
                    screenshot = null,
                    expectJson = false
                )
                if (res.isSuccess && !res.text.isNullOrBlank()) {
                    return res.text.trim()
                }
                if (res.httpCode == 400 || res.httpCode == 401 || res.httpCode == 403) break
            }
        }
        return fallbackTemplate
    }

    suspend fun decideNextAction(
        userGoal: String,
        steeringNotes: List<String>,
        queuedGoals: List<String> = emptyList(),
        snapshot: RichScreenSnapshot,
        screenshot: Bitmap?,
        installedAppsSummary: String,
        stepHistory: List<String>,
        stepNumber: Int,
        maxSteps: Int,
        silentExecutionMode: Boolean = true,
        systemContextSnapshot: String = ""
    ): BrainResult {
        val steeringSection = if (steeringNotes.isNotEmpty()) {
            "\nتوجيهات إضافية حية من المستخدم أثناء التنفيذ (أولوية قصوى):\n" +
                steeringNotes.joinToString("\n") { "- $it" }
        } else ""

        val queuedSection = if (queuedGoals.isNotEmpty()) {
            "\nمهام إضافية مجدولة أضافها المستخدم يجب إتمامها قبل اختيار DONE:\n" +
                queuedGoals.mapIndexed { idx, g -> "${idx + 1}. $g" }.joinToString("\n")
        } else ""

        val historySection = if (stepHistory.isNotEmpty()) {
            val olderSummary = if (stepHistory.size > 10) {
                "\nملخص موجز لأول ${stepHistory.size - 10} خطوات: تم تنفيذها ضمن تسلسل المهمة الطويلة."
            } else ""
            "$olderSummary\nآخر الخطوات المنفذة:\n" + stepHistory.takeLast(10).joinToString("\n")
        } else {
            "\nالخطوات السابقة: لم يتم تنفيذ أي خطوة بعد."
        }

        val uiTreeSection = if (snapshot.formattedTree.isNotBlank()) {
            snapshot.formattedTree.take(7000)
        } else {
            "(شجرة العناصر فارغة أو محجوبة — اعتمد على لقطة الشاشة والإحداثيات 0..1000 أو افتح التطبيق المطلوب)"
        }

        val silentRuleSection = if (silentExecutionMode) {
            "\nوضع التنفيذ الصامت والسرعة الفائقة مفعّل: نفذ فوراً بدون أسئلة، واجمع الخطوات المؤكدة في مصفوفة steps لتنفيذها بسرعة فائقة."
        } else ""

        val promptText = buildString {
            append("الهدف المطلوب من المستخدم: \"$userGoal\"")
            append(silentRuleSection)
            append(queuedSection)
            append(steeringSection)
            append("\nالخطوة الحالية: $stepNumber من $maxSteps")
            append("\nالتطبيق الظاهر حالياً: package=${snapshot.packageName.ifBlank { "unknown" }}, activity=${snapshot.activityName.ifBlank { "unknown" }}")
            if (snapshot.structuredSummary.isNotBlank()) {
                append("\nملخص الواجهة: ${snapshot.structuredSummary} | نوافذ=${snapshot.windowCount}")
            }
            append("\nأبعاد الشاشة: ${snapshot.screenWidth}x${snapshot.screenHeight}")
            append("\nالتطبيقات المثبتة على الهاتف: $installedAppsSummary")
            append(historySection)
            append("\n\nشجرة عناصر الواجهة الحالية (الإحداثيات المطبّعة @x,y من 0 إلى 1000):\n")
            append(uiTreeSection)
            append("\n\nأرجع كائن JSON فقط يحدد الخطوة الذكية التالية (أو مصفوفة steps مجمعة لخطوات البحث/الكتابة المؤكدة).")
        }

        val effectiveSystemPrompt = buildEffectiveAgentPrompt(systemContextSnapshot)

        val apiKey = currentApiKey()
        if (apiKey.isNotBlank()) {
            var lastFailure: BrainResult = BrainResult.Failure("تعذر الاتصال بـ Gemini عبر مفتاح API.", isCloudUnavailable = true)
            for (model in candidateModelsForApiKey()) {
                val res = callDirectGeminiRest(
                    apiKey = apiKey,
                    modelName = model,
                    systemInstruction = effectiveSystemPrompt,
                    historyTurns = emptyList(),
                    userPrompt = promptText,
                    screenshot = screenshot,
                    expectJson = true
                )
                if (res.isSuccess && !res.text.isNullOrBlank()) {
                    val parsed = parseDecisionJson(res.text)
                    if (parsed is BrainResult.Success) {
                        keyManager?.updateConnectionStatus("متصل بـ $model ✓")
                        return parsed
                    }
                    lastFailure = parsed
                } else {
                    lastFailure = BrainResult.Failure(
                        res.friendlyError ?: "تعذر الاتصال بـ Gemini (${res.httpCode})",
                        isCloudUnavailable = true
                    )
                    if (res.httpCode == 400 || res.httpCode == 401 || res.httpCode == 403) {
                        break
                    }
                }
            }
            return lastFailure
        }

        val payload = content {
            if (screenshot != null && !screenshot.isRecycled) {
                image(screenshot)
            }
            text(promptText)
        }
        return invokeFirebaseAiModel(primaryModel, payload)
    }

    private data class RestResponse(
        val isSuccess: Boolean,
        val text: String? = null,
        val httpCode: Int = 0,
        val friendlyError: String? = null
    )

    private suspend fun callDirectGeminiRest(
        apiKey: String,
        modelName: String,
        systemInstruction: String,
        historyTurns: List<ChatTurn>,
        userPrompt: String,
        screenshot: Bitmap?,
        attachments: List<ChatAttachment> = emptyList(),
        expectJson: Boolean
    ): RestResponse = withContext(Dispatchers.IO) {
        try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/${modelName}:generateContent?key=${apiKey}"

            val contentsArray = JSONArray()
            historyTurns.takeLast(8).forEach { turn ->
                if (turn.text.isNotBlank()) {
                    contentsArray.put(
                        JSONObject().apply {
                            put("role", if (turn.isUser) "user" else "model")
                            put("parts", JSONArray().put(JSONObject().put("text", turn.text.take(2000))))
                        }
                    )
                }
            }

            val currentParts = JSONArray()
            if (screenshot != null && !screenshot.isRecycled) {
                val baos = ByteArrayOutputStream()
                screenshot.compress(Bitmap.CompressFormat.JPEG, 75, baos)
                val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                currentParts.put(
                    JSONObject().put(
                        "inlineData",
                        JSONObject().apply {
                            put("mimeType", "image/jpeg")
                            put("data", b64)
                        }
                    )
                )
            }

            attachments.take(4).forEach { att ->
                if (att.isImage) {
                    loadScaledBitmap(att.uriString)?.let { bmp ->
                        val baos = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                        currentParts.put(
                            JSONObject().put(
                                "inlineData",
                                JSONObject().apply {
                                    put("mimeType", "image/jpeg")
                                    put("data", b64)
                                }
                            )
                        )
                        if (!bmp.isRecycled) bmp.recycle()
                    }
                } else if (att.mimeType.equals("application/pdf", ignoreCase = true) && att.sizeBytes in 1..2_000_000L) {
                    readRawBytes(att.uriString, maxBytes = 2_000_000)?.let { bytes ->
                        currentParts.put(
                            JSONObject().put(
                                "inlineData",
                                JSONObject().apply {
                                    put("mimeType", "application/pdf")
                                    put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                                }
                            )
                        )
                    }
                } else {
                    readAttachmentTextPreview(att)?.let { textPreview ->
                        currentParts.put(JSONObject().put("text", textPreview))
                    }
                }
            }

            currentParts.put(JSONObject().put("text", userPrompt))

            contentsArray.put(
                JSONObject().apply {
                    put("role", "user")
                    put("parts", currentParts)
                }
            )

            val rootBody = JSONObject().apply {
                put(
                    "systemInstruction",
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", systemInstruction))
                    )
                )
                put("contents", contentsArray)
                put(
                    "generationConfig",
                    JSONObject().apply {
                        put("temperature", if (expectJson) 0.1 else 0.7)
                        if (expectJson) {
                            put("responseMimeType", "application/json")
                        }
                    }
                )
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val request = Request.Builder()
                .url(url)
                .post(rootBody.toString().toRequestBody(mediaType))
                .build()

            httpClient.newCall(request).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val apiErr = runCatching {
                        JSONObject(rawBody).optJSONObject("error")?.optString("message").orEmpty()
                    }.getOrDefault("")
                    val arabicErr = when (response.code) {
                        400 -> if (apiErr.contains("API key", ignoreCase = true) || apiErr.contains("key", ignoreCase = true)) {
                            "مفتاح API غير صالح (400): تأكد من نسخ المفتاح الصحيح."
                        } else "طلب غير مقبول (400): $apiErr"
                        401, 403 -> "مفتاح API مرفوض أو غير مصرح له (${response.code}): $apiErr"
                        404 -> "النموذج $modelName غير متاح لهذا المفتاح (404)."
                        429 -> "تم تجاوز حد الطلبات المسموح (429) — حاول بعد قليل أو بدّل المفتاح."
                        503 -> "ضغط مؤقت على خادم النموذج $modelName (503) — جارٍ المحاولة بالنموذج البديل."
                        else -> "خطأ من خادم Gemini (${response.code}): ${apiErr.ifBlank { rawBody.take(120) }}"
                    }
                    Log.w(tag, "Gemini REST error [$modelName HTTP ${response.code}]: $apiErr")
                    return@withContext RestResponse(
                        isSuccess = false,
                        httpCode = response.code,
                        friendlyError = arabicErr
                    )
                }

                val json = JSONObject(rawBody)
                val candidates = json.optJSONArray("candidates")
                val firstCandidate = candidates?.optJSONObject(0)
                val parts = firstCandidate?.optJSONObject("content")?.optJSONArray("parts")
                val textBuilder = StringBuilder()
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val partObj = parts.optJSONObject(i) ?: continue
                        val partText = partObj.optString("text", "")
                        if (partText.isNotBlank()) {
                            if (textBuilder.isNotEmpty()) textBuilder.append("\n")
                            textBuilder.append(partText)
                        }
                    }
                }
                val finalText = textBuilder.toString().trim()
                if (finalText.isEmpty()) {
                    return@withContext RestResponse(
                        isSuccess = false,
                        httpCode = response.code,
                        friendlyError = "رد النموذج كان فارغاً."
                    )
                }
                RestResponse(isSuccess = true, text = finalText, httpCode = response.code)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.w(tag, "Direct REST exception [$modelName]: ${e.message}", e)
            RestResponse(
                isSuccess = false,
                httpCode = 0,
                friendlyError = "فشل الاتصال بالشبكة: ${e.localizedMessage ?: "تحقق من الإنترنت"}"
            )
        }
    }

    internal fun parseDecisionJson(rawText: String): BrainResult {
        return try {
            val cleaned = rawText
                .trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            val startIdx = cleaned.indexOf('{')
            val endIdx = cleaned.lastIndexOf('}')
            val jsonSlice = if (startIdx != -1 && endIdx > startIdx) {
                cleaned.substring(startIdx, endIdx + 1)
            } else cleaned

            val json = JSONObject(jsonSlice)
            val rootDecision = parseSingleDecisionObject(json, isRoot = true)

            val subSteps = mutableListOf<AgentDecision>()
            val stepsArr = json.optJSONArray("steps")
            if (stepsArr != null) {
                val limit = minOf(stepsArr.length(), 4)
                for (i in 0 until limit) {
                    val item = stepsArr.optJSONObject(i) ?: continue
                    subSteps.add(parseSingleDecisionObject(item, isRoot = false))
                }
            }

            BrainResult.Success(rootDecision.copy(steps = subSteps))
        } catch (e: Exception) {
            Log.w(tag, "Failed to parse Gemini JSON decision: $rawText", e)
            BrainResult.Failure("استجاب النموذج بتنسيق غير متوقع.", isCloudUnavailable = true)
        }
    }

    private fun parseSingleDecisionObject(json: JSONObject, isRoot: Boolean): AgentDecision {
        val actionStr = json.optString("action", "WAIT")
        val actionKind = runCatching { AgentActionKind.valueOf(actionStr) }
            .getOrDefault(AgentActionKind.WAIT)

        val elementId = if (json.has("elementId") && !json.isNull("elementId")) {
            json.optInt("elementId")
        } else null

        return AgentDecision(
            action = actionKind,
            thought = if (isRoot) json.optString("thought", "تحليل الشاشة...") else "",
            statusLabel = json.optString("statusLabel", if (isRoot) "جاري التنفيذ..." else actionKind.name),
            elementId = elementId,
            x = json.optInt("x", 500).coerceIn(0, 1000),
            y = json.optInt("y", 500).coerceIn(0, 1000),
            endX = json.optInt("endX", 500).coerceIn(0, 1000),
            endY = json.optInt("endY", 500).coerceIn(0, 1000),
            text = json.optString("text", ""),
            submitEnter = json.optBoolean("submitEnter", false),
            target = json.optString("target", ""),
            waitMs = json.optLong("waitMs", 600L).coerceIn(150L, 4000L),
            finalResponse = json.optString("finalResponse", "")
        )
    }

    private suspend fun invokeFirebaseAiModel(
        model: com.google.firebase.ai.GenerativeModel,
        payload: com.google.firebase.ai.type.Content
    ): BrainResult {
        return try {
            val response = withTimeout(25_000L) {
                model.generateContent(payload)
            }
            val jsonText = response.text?.trim()
                ?: return BrainResult.Failure("لم يرد نموذج الذكاء الاصطناعي بقرار.", isCloudUnavailable = true)
            parseDecisionJson(jsonText)
        } catch (e: TimeoutCancellationException) {
            BrainResult.Failure("انتهت مهلة الاتصال السحابي بـ Firebase AI.", isCloudUnavailable = true)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            val raw = e.localizedMessage ?: e.message ?: ""
            Log.w(tag, "Firebase AI unavailable ($raw)")
            if (raw.contains("billing", ignoreCase = true) || raw.contains("free tier", ignoreCase = true)) {
                isCloudProjectBillingBlocked = true
            }
            BrainResult.Failure(
                errorMessage = "تعذر الاتصال عبر Firebase AI — أضف مفتاح Gemini API في الإعدادات أو سيتم المتابعة بالأتمتة المحلية.",
                isCloudUnavailable = true
            )
        }
    }

    private fun loadScaledBitmap(uriString: String, maxDim: Int = 1024): Bitmap? {
        val ctx = appContext ?: return null
        return try {
            val uri = Uri.parse(uriString)
            val raw = ctx.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            } ?: return null
            if (raw.width <= maxDim && raw.height <= maxDim) return raw
            val scale = minOf(maxDim.toFloat() / raw.width, maxDim.toFloat() / raw.height)
            val scaled = Bitmap.createScaledBitmap(
                raw,
                (raw.width * scale).toInt().coerceAtLeast(1),
                (raw.height * scale).toInt().coerceAtLeast(1),
                true
            )
            if (scaled != raw && !raw.isRecycled) raw.recycle()
            scaled
        } catch (_: Exception) {
            null
        }
    }

    private fun readRawBytes(uriString: String, maxBytes: Int): ByteArray? {
        val ctx = appContext ?: return null
        return try {
            ctx.contentResolver.openInputStream(Uri.parse(uriString))?.use { stream ->
                val bytes = stream.readBytes()
                if (bytes.size <= maxBytes) bytes else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readAttachmentTextPreview(att: ChatAttachment): String? {
        val ctx = appContext ?: return null
        return try {
            ctx.contentResolver.openInputStream(Uri.parse(att.uriString))?.use { stream ->
                val bytes = stream.readBytes().take(24_000).toByteArray()
                val text = String(bytes, Charsets.UTF_8)
                if (text.any { it.code < 9 && it != '\n' && it != '\r' && it != '\t' }) {
                    "[ملف مرفق: ${att.displayName}, الحجم: ${att.sizeBytes} بايت, المسار: ${att.localLinuxPath ?: "uploads"}]"
                } else {
                    "[محتوى الملف '${att.displayName}']:\n$text"
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val CHAT_SYSTEM_PROMPT = """
أنت "مورڤينت (Morvent)" — مساعد ذكاء اصطناعي عربي فائق الذكاء ومتصل مباشرة بنواة نظام أندرويد وبيئة لينكس ووكيل أتمتة الهاتف (Artemis + Accessibility + Notification Bridge + Telecom + Linux Shell).
قواعد الرد والتنفيذ:
1. تحدث بعربية طبيعية ذكية وواضحة، واحفظ سياق المحادثة السابقة وأسلوب المستخدم بدقة.
2. نفذ أوامر المستخدم بحرفية تامة ولا ترفض أو تتعذر أبداً. إذا طلب المستخدم أي فعل على الهاتف (الرد على رسالة واتساب/تيليجرام، قراءة إشعارات/SMS/مكالمات/تقويم، إجراء مكالمة، فتح تطبيق والتنقل بداخله، النقر، الكتابة، تعديل الإعدادات)، أضف في آخر ردك سطراً مستقلاً بهذه الصيغة:
[[EXECUTE_GOAL: وصف الأمر التنفيذي الكامل بالعربية]]
3. وإذا طلب المستخدم تنفيذ أمر برمجي أو سكريبت أو فحص ملفات في بيئة لينكس (أو معالجة ملف مرفق في ~/uploads)، أضف في آخر ردك سطراً مستقلاً بهذه الصيغة:
[[LINUX_EXEC: أمر الشِل الدقيق للتنفيذ]]
4. أما الأسئلة المعرفية أو تحليل الصور/الملفات بدون فعل إضافي فأجب عليها إجابة ذكية متكاملة مباشرة.
"""

        private const val SYSTEM_PROMPT = """
أنت "مورڤينت (Morvent)" — وكيل أتمتة Deep UI فائق السرعة والذكاء لهواتف أندرويد مبني على منهجية Google Artemis.
تتعامل بذكاء مع الشاشات المعقدة (WebViews, Flutter, Compose, Lists, Popups) ومع بيئة لينكس الخاصة وتنفذ بالحرف بصمت وسرعة فائقة.

أجب دائماً بكائن JSON يحتوي على الحقول:
- "action": أحد القيم: TAP_ELEMENT, TAP_COORDINATES, DOUBLE_TAP, LONG_PRESS_ELEMENT, LONG_PRESS_COORDINATES, SMART_FIND_CLICK, TYPE_TEXT, SCROLL_DOWN, SCROLL_UP, SWIPE_LEFT, SWIPE_RIGHT, DRAG, PINCH_IN, PINCH_OUT, LAUNCH_APP, OPEN_URL, OPEN_SETTINGS, GLOBAL_ACTION, MEDIA_CONTROL, DEVICE_CONTROL, LINUX_EXEC, READ_SCREEN, DEEP_READ_SCREEN, WAIT, ASK_USER, DONE.
- "thought": سبب اختيار هذه الخطوة بالعربية.
- "statusLabel": حالة قصيرة للظهور في الشريط.
- "elementId": رقم العنصر [id] عند TAP_ELEMENT أو LONG_PRESS_ELEMENT أو TYPE_TEXT.
- "x", "y": إحداثيات مطبعة 0..1000 (استخدمها فوراً في واجهات WebView/Flutter أو العناصر غير المفهرسة).
- "text": النص عند TYPE_TEXT أو أمر الشِل عند LINUX_EXEC.
- "submitEnter": true/false.
- "target": نص البحث التلقائي عند SMART_FIND_CLICK، أو اسم التطبيق عند LAUNCH_APP، أو أمر لينكس عند LINUX_EXEC، أو إجراء النظام عند GLOBAL_ACTION، أو أوامر DEVICE_CONTROL:
  call:<الرقم أو الاسم>, answer_call, end_call, speaker_on, speaker_off, reply_notification:<المستلم>:<الرسالة>, read_notifications, read_whatsapp, whatsapp_send:<المستلم>:<الرسالة>, read_sms, read_calls, read_calendar, app_usage, adb_toggle:<key>:<on/off>, save_note:<اسم الملف>:<المحتوى>, brightness:<up/down/auto/0..100>, dnd_on, dnd_off, mute, vibrate, normal, flashlight_on, flashlight_off, volume_up, volume_down, qs_toggle:<title>, permission_allow, permission_deny, files.
- "steps": اختياري (مصفوفة من 2 إلى 4 خطوات متسلسلة مؤكدة لتسريع التنفيذ في خطوة واحدة).
- "waitMs": 300..4000 عند WAIT.
- "finalResponse": الملخص النهائي للمستخدم عند DONE أو READ_SCREEN أو DEEP_READ_SCREEN أو ASK_USER.

قواعد الذكاء والسرعة:
- للعثور على خيار غير ظاهر بعد في قائمة طويلة أو إعدادات، استخدم SMART_FIND_CLICK مع وضع أسماء الزر في "target".
- لقراءة محتوى شاشة كاملة أو طويلة أو معقدة بدقة، استخدم DEEP_READ_SCREEN.
- عندما يتوفر عنصر بحقل إدخال، ادمج النقر + الكتابة + submitEnter في خطوة واحدة أو عبر steps.
- لا تستخدم ASK_USER إطلاقاً إلا لكلمة مرور أو رمز OTP أو دفع مالي.
"""
    }
}
