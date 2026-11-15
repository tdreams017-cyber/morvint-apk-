package com.example.nlp

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.example.ai.ApiKeyManager
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
    READ_SCREEN,
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
    val finalResponse: String = ""
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
    private val keyManager = context?.let { ApiKeyManager.getInstance(it) }

    @Volatile
    private var isCloudProjectBillingBlocked: Boolean = false

    fun isCloudUnavailableForSession(): Boolean = isCloudProjectBillingBlocked

    fun resetCloudAvailability() {
        isCloudProjectBillingBlocked = false
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .build()
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
                "target" to Schema.string(description = "Target value for LAUNCH_APP, OPEN_URL, OPEN_SETTINGS, GLOBAL_ACTION, MEDIA_CONTROL, or DEVICE_CONTROL.", nullable = true),
                "waitMs" to Schema.integer(description = "Milliseconds to wait when action is WAIT (500..4000).", nullable = true),
                "finalResponse" to Schema.string(description = "Clear Arabic message for the user when action is DONE, READ_SCREEN, or ASK_USER.", nullable = true)
            ),
            optionalProperties = listOf(
                "elementId", "x", "y", "endX", "endY", "text",
                "submitEnter", "target", "waitMs", "finalResponse"
            )
        )
    }

    private val config by lazy {
        generationConfig {
            responseMimeType = "application/json"
            responseSchema = decisionSchema
            temperature = 0.2f
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
     * Conversational AI response with real multi-turn memory and zero canned hijacking.
     */
    suspend fun answerGeneralChat(
        question: String,
        recentHistory: List<ChatTurn> = emptyList()
    ): String {
        val apiKey = currentApiKey()
        if (apiKey.isNotBlank()) {
            var lastErrorMsg: String? = null
            for (model in candidateModelsForApiKey()) {
                val res = callDirectGeminiRest(
                    apiKey = apiKey,
                    modelName = model,
                    systemInstruction = CHAT_SYSTEM_PROMPT,
                    historyTurns = recentHistory,
                    userPrompt = question,
                    screenshot = null,
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
            val payload = content { text(question) }
            val res = withTimeout(25_000L) { chatModel.generateContent(payload) }
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
        silentExecutionMode: Boolean = true
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
            "\nوضع التنفيذ الصامت الكامل مفعّل: لا تتوقف أبداً ولا تطلب من المستخدم تنفيذ أي خطوة يدوياً. نفذ عبر DEVICE_CONTROL أو Accessibility Actions مباشرة."
        } else ""

        val promptText = buildString {
            append("الهدف المطلوب من المستخدم: \"$userGoal\"")
            append(silentRuleSection)
            append(queuedSection)
            append(steeringSection)
            append("\nالخطوة الحالية: $stepNumber من $maxSteps")
            append("\nالتطبيق الظاهر حالياً: package=${snapshot.packageName.ifBlank { "unknown" }}, activity=${snapshot.activityName.ifBlank { "unknown" }}")
            append("\nأبعاد الشاشة: ${snapshot.screenWidth}x${snapshot.screenHeight}")
            append("\nالتطبيقات المثبتة على الهاتف: $installedAppsSummary")
            append(historySection)
            append("\n\nشجرة عناصر الواجهة الحالية (الإحداثيات المطبّعة @x,y من 0 إلى 1000):\n")
            append(uiTreeSection)
            append("\n\nأرجع كائن JSON فقط يحدد الخطوة الذكية التالية.")
        }

        val apiKey = currentApiKey()
        if (apiKey.isNotBlank()) {
            var lastFailure: BrainResult = BrainResult.Failure("تعذر الاتصال بـ Gemini عبر مفتاح API.", isCloudUnavailable = true)
            for (model in candidateModelsForApiKey()) {
                val res = callDirectGeminiRest(
                    apiKey = apiKey,
                    modelName = model,
                    systemInstruction = SYSTEM_PROMPT,
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
                        put("temperature", if (expectJson) 0.2 else 0.7)
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

    private fun parseDecisionJson(rawText: String): BrainResult {
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
            val actionStr = json.optString("action", "WAIT")
            val actionKind = runCatching { AgentActionKind.valueOf(actionStr) }
                .getOrDefault(AgentActionKind.WAIT)

            val elementId = if (json.has("elementId") && !json.isNull("elementId")) {
                json.optInt("elementId")
            } else null

            val decision = AgentDecision(
                action = actionKind,
                thought = json.optString("thought", "تحليل الشاشة..."),
                statusLabel = json.optString("statusLabel", "جاري التنفيذ..."),
                elementId = elementId,
                x = json.optInt("x", 500).coerceIn(0, 1000),
                y = json.optInt("y", 500).coerceIn(0, 1000),
                endX = json.optInt("endX", 500).coerceIn(0, 1000),
                endY = json.optInt("endY", 500).coerceIn(0, 1000),
                text = json.optString("text", ""),
                submitEnter = json.optBoolean("submitEnter", false),
                target = json.optString("target", ""),
                waitMs = json.optLong("waitMs", 1000L).coerceIn(300L, 4000L),
                finalResponse = json.optString("finalResponse", "")
            )
            BrainResult.Success(decision)
        } catch (e: Exception) {
            Log.w(tag, "Failed to parse Gemini JSON decision: $rawText", e)
            BrainResult.Failure("استجاب النموذج بتنسيق غير متوقع.", isCloudUnavailable = true)
        }
    }

    private suspend fun invokeFirebaseAiModel(
        model: com.google.firebase.ai.GenerativeModel,
        payload: com.google.firebase.ai.type.Content
    ): BrainResult {
        return try {
            val response = withTimeout(35_000L) {
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

    companion object {
        private const val CHAT_SYSTEM_PROMPT = """
أنت "مورڤينت (Morvent)" — مساعد ذكاء اصطناعي عربي ذكي جداً ومتصل مباشرة بنواة نظام أندرويد ووكيل أتمتة الهاتف (Artemis + Accessibility + Notification Bridge + Telecom).
قواعد الرد والتنفيذ:
1. تحدث بعربية طبيعية ذكية وواضحة، وافهم سياق المحادثة السابقة وتجاوب مع كل ما يقوله المستخدم بدقة.
2. لا ترد أبداً بقوالب ثابتة، ولا تقل أبداً "لا يمكنني تنفيذ ذلك" على أوامر الهاتف الممكنة؛ بل إذا طلب المستخدم منك أي فعل على الهاتف (مثل: الرد على رسالة واتساب، قراءة رسائل أو إشعارات، إجراء مكالمة أو الرد عليها أو إنهائها، فتح تطبيق، النقر، الكتابة، تعديل الإعدادات/الصوت/السطوع، حفظ ملف)، أضف في آخر ردك سطراً مستقلاً بهذه الصيغة الحرفية فقط لتفعيل التنفيذ الفوري في الهاتف:
[[EXECUTE_GOAL: وصف الأمر التنفيذي الكامل بالعربية]]
مثال: إذا قال المستخدم "ممكن تبعت لأحمد على واتساب إني وصلت؟" أجب بجملة قصيرة ثم اكتب في سطر جديد:
[[EXECUTE_GOAL: أرسل رسالة واتساب إلى أحمد محتواها إني وصلت]]
3. أما الأسئلة المعرفية أو الدردشة العامة التي لا تطلب فعلاً على الهاتف فأجب عليها إجابة ذكية كاملة بدون وسم [[EXECUTE_GOAL]].
"""

        private const val SYSTEM_PROMPT = """
أنت "مورڤينت (Morvent)" — وكيل أتمتة هاتف أندرويد متكامل الصلاحيات بمستوى بشري مبني على منهجية Google Artemis.
تعمل بالخلفية بصمت وتلقائية تامة: تتحكم في واجهة النظام، الرد الصامت على الإشعارات وواتساب، المكالمات الصوتية، الإعدادات السريعة، الملفات، وجميع التطبيقات المثبتة.

أجب دائماً بكائن JSON يحتوي على الحقول:
- "action": أحد القيم: TAP_ELEMENT, TAP_COORDINATES, DOUBLE_TAP, LONG_PRESS_ELEMENT, LONG_PRESS_COORDINATES, TYPE_TEXT, SCROLL_DOWN, SCROLL_UP, SWIPE_LEFT, SWIPE_RIGHT, DRAG, PINCH_IN, PINCH_OUT, LAUNCH_APP, OPEN_URL, OPEN_SETTINGS, GLOBAL_ACTION, MEDIA_CONTROL, DEVICE_CONTROL, READ_SCREEN, WAIT, ASK_USER, DONE.
- "thought": سبب اختيار هذه الخطوة بالعربية.
- "statusLabel": حالة قصيرة للظهور في الشريط.
- "elementId": رقم العنصر [id] عند TAP_ELEMENT أو LONG_PRESS_ELEMENT أو TYPE_TEXT.
- "x", "y": إحداثيات مطبعة 0..1000.
- "text": النص عند TYPE_TEXT.
- "submitEnter": true/false.
- "target": اسم التطبيق أو الرابط أو إجراء النظام (back, home, recents, notifications, quick_settings, screenshot, lock, split_screen, all_apps) أو أوامر DEVICE_CONTROL مثل:
  call:<الرقم أو الاسم>, answer_call, end_call, speaker_on, speaker_off, reply_notification:<المستلم أو التطبيق>:<الرسالة>, read_notifications, read_whatsapp, whatsapp_send:<المستلم>:<الرسالة>, adb_toggle:<key>:<on/off>, save_note:<اسم الملف>:<المحتوى>, brightness:<up/down/auto/0..100>, dnd_on, dnd_off, mute, vibrate, normal, flashlight_on, flashlight_off, volume_up, volume_down, qs_toggle:<title>, permission_allow, permission_deny, files.
- "waitMs": 500..4000 عند WAIT.
- "finalResponse": الملخص النهائي للمستخدم عند DONE أو READ_SCREEN أو ASK_USER.

قواعد التنفيذ الصامت:
- لا تستخدم ASK_USER إطلاقاً إلا لخطوة حساسة جداً مثل كلمة مرور أو رمز تحقق OTP أو دفع مالي. فيما عدا ذلك نفذ الخطوات بنفسك كالإنسان حتى تكتمل المهمة ثم أرجع DONE.
"""
    }
}
