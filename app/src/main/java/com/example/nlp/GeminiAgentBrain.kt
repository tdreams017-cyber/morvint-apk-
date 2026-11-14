package com.example.nlp

import android.graphics.Bitmap
import android.util.Log
import com.example.service.RichScreenSnapshot
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
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

sealed interface BrainResult {
    data class Success(val decision: AgentDecision) : BrainResult
    data class Failure(
        val errorMessage: String,
        val isCloudUnavailable: Boolean = false
    ) : BrainResult
}

/**
 * Multimodal Vision + UI-Tree Autonomous Brain powered by Firebase AI Logic.
 * Uses gemini-3.8-flash with automatic fallback to gemini-3.5-flash-lite,
 * and signals cleanly when cloud billing/quota/connectivity requires local Artemis fallback.
 */
class GeminiAgentBrain {

    private val tag = "GeminiAgentBrain"

    @Volatile
    private var isCloudProjectBillingBlocked: Boolean = false

    fun isCloudUnavailableForSession(): Boolean = isCloudProjectBillingBlocked

    fun resetCloudAvailability() {
        isCloudProjectBillingBlocked = false
    }

    private val decisionSchema = Schema.obj(
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

    private val config by lazy {
        generationConfig {
            responseMimeType = "application/json"
            responseSchema = decisionSchema
            temperature = 0.2f
        }
    }

    private val primaryModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = config,
            systemInstruction = content {
                text(SYSTEM_PROMPT)
            }
        )
    }

    private val fallbackModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.5-flash-lite",
            generationConfig = config,
            systemInstruction = content {
                text(SYSTEM_PROMPT)
            }
        )
    }

    private val chatModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            systemInstruction = content {
                text("أنت مساعد ذكاء اصطناعي عربي ذكي ومفيد جداً (مورڤينت). تجيب عن أي سؤال أو استفسار يطرحه المستخدم بدقة واحترافية ووضوح تام باللغة العربية.")
            }
        )
    }

    private val chatFallbackModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.5-flash-lite",
            systemInstruction = content {
                text("أنت مساعد ذكاء اصطناعي عربي ذكي ومفيد جداً (مورڤينت). تجيب عن أي سؤال أو استفسار يطرحه المستخدم بدقة واحترافية ووضوح تام باللغة العربية.")
            }
        )
    }

    fun getSmartLocalChatResponse(input: String): String? {
        val trimmed = input.trim()
        val lower = trimmed.lowercase(Locale.ROOT)

        // Greetings & Warm Welcome
        if (lower in listOf("السلام عليكم", "سلام عليكم", "السلام عليكم ورحمة الله", "سلام", "السلام عليكم ورحمة الله وبركاته", "وعليكم السلام")) {
            return "وعليكم السلام ورحمة الله وبركاته! 🌸\nمرحباً بك! أنا **مورڤينت (Morvent)**، وكيل الأتمتة والذكاء الاصطناعي الشامل لهاتفك ونظام أندرويد.\n\nأنا جاهز للإجابة وتنفيذ أي مهمة ترغب بها على هاتفك وتطبيقاتك! كيف أساعدك الآن؟"
        }
        if (lower in listOf("مرحبا", "مرحباً", "أهلاً", "اهلا", "أهلا", "أهلاً وسهلاً", "اهلا وسهلا", "هلا", "مرحبا بك", "hi", "hello", "hey")) {
            return "أهلاً وسهلاً بك! 👋 أنا **مورڤينت**، مساعدك الذكي ووكيل التحكم بالهاتف والتطبيقات. اسألني أي سؤال أو اطلب أي مهمة في هاتفك!"
        }
        if (lower in listOf("صباح الخير", "صباح النور", "صباح الورد")) {
            return "صباح الخير والبركة! ☀️ أتمنى لك يوماً رائعاً ومثمراً. كيف يمكنني خدمتك اليوم؟"
        }
        if (lower in listOf("مساء الخير", "مساء النور", "مساء الورد")) {
            return "مساء الخير والسرور! 🌙 جاهز لمساعدتك في أي استفسار أو مهمة في هاتفك."
        }
        // Friendly check-ins ("كيف الحال")
        if (lower in listOf("كيف الحال", "كيفك", "شخبارك", "عساك بخير", "شلونك", "أخبارك", "اخبارك", "كيف حالك", "how are you")) {
            return "أنا بخير وبأتم الجاهزية والنشاط لخدمتك! 😊 شكراً لسؤالك يا طيب.\nكيف حالك أنت؟ أنا جاهز فوراً لتنفيذ أي أمر تريده على هاتفك والنظام والتطبيقات، أو الإجابة عن أي استفسار!"
        }
        if (lower in listOf("شكرا", "شكراً", "تسلم", "يعطيك العافية", "مشكور", "جزاك الله خير", "thank you", "thanks")) {
            return "العفو، على الرحب والسعة دائماً! 🌹 أنا في خدمتك دائماً لأي أمر أو استفسار."
        }
        // Core Identity & Capabilities ("هل تنفذ؟", "المهمة الأصلية")
        val isExecutionQuestion = lower in listOf(
            "هل تنفذ", "هل تستطيع التنفيذ", "هل تقدر تنفذ", "هل تنفذ اوامر", "هل تنفذ أوامر",
            "هل انت فقط شات", "انت شات بس", "أنت شات بس", "مش شات", "لا أريدك فقط شات", "لا اريدك فقط شات",
            "ما هي مهمتك الاصليه", "ما هي مهمتك الأصلية", "مهمتك الاصليه", "المهمة الاصلية", "المهمة الأصلية",
            "ما وظيفتك", "ما هي قدراتك", "قدراتك", "ماذا تفعل", "ماذا تستطيع ان تفعل", "ماذا تستطيع أن تفعل",
            "من أنت", "من انت", "عرف عن نفسك", "عرف بنفسك", "who are you"
        ) || (lower.contains("تنفذ") && (lower.contains("هل") || lower.contains("تقدر") || lower.contains("شات")))
        if (isExecutionQuestion) {
            return "نعم بالتأكيد! أنا **مورڤينت (Morvent)** — وكيل أندرويد تنفيذي متكامل (Autonomous Mobile Agent) مبني على منهجية **Google Artemis** ولست مجرد روبوت محادثة! 🤖📱\n\n" +
                    "⚡ **أنا أتحكم بهاتفك فعلياً:**\n" +
                    "1️⃣ **التحكم بالنظام والعتاد:** تشغيل الكشاف، السطوع، الصوت، الواي فاي، البلوتوث، البيانات، عدم الإزعاج، قفل الشاشة، ولقطة الشاشة.\n" +
                    "2️⃣ **التحكم داخل كل التطبيقات:** فتح أي تطبيق (يوتيوب، واتساب، الإعدادات، المتصفح)، اللمس، التمرير، والكتابة والبحث.\n" +
                    "3️⃣ **المهام متعددة الخطوات:** تنفيذ مسارات معقدة خطوة بخطوة مثل الإنسان، مع إمكانية توجيهي أو إيقافي أثناء العمل.\n\n" +
                    "⚙️ **ملاحظة هامة:** تأكد من الضغط على **'انقر لتفعيل خدمة الوصول ⚙️'** في الشريط العلوي لتمنحني صلاحية التحكم واللمس على الشاشة، ثم اطلب مني أي مهمة وسأنفذها فوراً! (مثال: *'شغل الكشاف'* أو *'افتح الإعدادات'* أو *'اقرأ الإشعارات'*)."
        }
        return null
    }

    fun getHelpfulConversationalFallback(question: String): String {
        val lower = question.lowercase(Locale.ROOT)
        return when {
            lower.contains("تطبيق") || lower.contains("برنامج") || lower.contains("يوتيوب") || lower.contains("واتساب") -> {
                "أستطيع فتح وتصفح أي تطبيق على هاتفك والتحكم بداخله! اطلب مني مثلاً: 'افتح يوتيوب وابحث عن سورة الكهف' أو 'افتح الإعدادات' وسأبدأ التنفيذ فوراً 🚀."
            }
            lower.contains("كشاف") || lower.contains("فلاش") -> {
                "يمكنك التحكم بالكشاف مباشرة بقول: 'شغل الكشاف' أو 'أطفئ الكشاف'."
            }
            lower.contains("صوت") || lower.contains("رنين") -> {
                "يمكنك التحكم بالصوت: 'ارفع الصوت'، 'اخفض الصوت'، 'صامت'، أو 'اضبط صوت المنبه 80%'."
            }
            lower.contains("سطوع") || lower.contains("شاشة") -> {
                "لضبط إضاءة الشاشة: 'اضبط السطوع 80%' أو 'فعل التدوير التلقائي'."
            }
            lower.contains("اشعار") || lower.contains("إشعار") -> {
                "أستطيع قراءة إشعارات هاتفك ومسحها! اطلب مني: 'اقرأ الإشعارات'."
            }
            lower.contains("وقت") || lower.contains("ساعة") || lower.contains("تاريخ") -> {
                "لضبط المنبه أو المؤقت، قل لي: 'اضبط منبه 7:00' أو 'مؤقت 10 دقائق'."
            }
            lower.contains("كيف") && lower.contains("حال") -> {
                "أنا بخير وبأتم الجاهزية والنشاط لخدمتك! 😊 شكراً لسؤالك. جاهز فوراً لتنفيذ أي أمر أو مهمة على هاتفك!"
            }
            else -> {
                "أهلاً بك! أنا وكيلك الذكي مورڤينت (Morvent) 🤖.\nأنا جاهز تماماً لتنفيذ أي مهمة أتمتة أو تحكم على هاتفك (فتح التطبيقات، ضبط الإعدادات، الكشاف، الصوت، قراءة الإشعارات).\n\nجرّب أن تطلب مني أي أمر تنفيذي الآن مثل: 'شغل الكشاف' أو 'افتح الإعدادات واضبط السطوع' أو 'التقط لقطة شاشة'!"
            }
        }
    }

    suspend fun answerGeneralChat(question: String): String {
        // 1. Instant friendly local answer for greetings & execution queries
        val quickResponse = getSmartLocalChatResponse(question)
        if (quickResponse != null) {
            return quickResponse
        }

        val payload = content {
            text(question)
        }

        // 2. Query Gemini 3.8 Flash (Primary cloud model)
        return try {
            val res = withTimeout(25_000L) {
                chatModel.generateContent(payload)
            }
            val text = res.text?.trim()
            if (!text.isNullOrBlank()) {
                return text
            }
            // Fallback to Gemini 3.5 Flash Lite if empty
            val fallbackRes = withTimeout(20_000L) {
                chatFallbackModel.generateContent(payload)
            }
            fallbackRes.text?.trim() ?: getHelpfulConversationalFallback(question)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(tag, "Primary chatModel failed (${e.message}); attempting fallback model...")
            try {
                val fallbackRes = withTimeout(20_000L) {
                    chatFallbackModel.generateContent(payload)
                }
                val fallbackText = fallbackRes.text?.trim()
                if (!fallbackText.isNullOrBlank()) {
                    return fallbackText
                }
            } catch (fallbackEx: CancellationException) {
                throw fallbackEx
            } catch (fallbackEx: Exception) {
                Log.w(tag, "Fallback chatModel also encountered error (${fallbackEx.message}); using intelligent conversational fallback.")
            }
            // Provide a rich, helpful conversational answer instead of throwing raw billing errors to the user
            getHelpfulConversationalFallback(question)
        }
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
        maxSteps: Int
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
                "\nملخص موجز لأول ${stepHistory.size - 10} خطوات: تم تنفيذها ضمن تسلسل المهمة الطويلة بنجاح/محاولة."
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

        val promptText = buildString {
            append("الهدف المطلوب من المستخدم: \"$userGoal\"")
            append(queuedSection)
            append(steeringSection)
            append("\nالخطوة الحالية: $stepNumber من $maxSteps (تدعم المهام الطويلة متعددة المراحل)")
            append("\nالتطبيق الظاهر حالياً: package=${snapshot.packageName.ifBlank { "unknown" }}, activity=${snapshot.activityName.ifBlank { "unknown" }}")
            append("\nأبعاد الشاشة: ${snapshot.screenWidth}x${snapshot.screenHeight}")
            append("\nالتطبيقات المثبتة على الهاتف: $installedAppsSummary")
            append(historySection)
            append("\n\nشجرة عناصر الواجهة الحالية (الإحداثيات المطبّعة @x,y من 0 إلى 1000):\n")
            append(uiTreeSection)
            append("\n\nحدد الخطوة الذكية التالية لإنجاز كل أجزاء المهمة بالكامل كإنسان، ولا تختر DONE إلا بعد التأكد من اكتمال جميع الخطوات والمهام المجدولة.")
        }

        val payload = content {
            if (screenshot != null && !screenshot.isRecycled) {
                image(screenshot)
            }
            text(promptText)
        }

        val firstAttempt = invokeModel(primaryModel, payload)
        if (firstAttempt is BrainResult.Success) {
            return firstAttempt
        }

        // Try secondary lightweight model once if primary failed
        val secondAttempt = invokeModel(fallbackModel, payload)
        if (secondAttempt is BrainResult.Success) {
            return secondAttempt
        }

        return firstAttempt
    }

    private suspend fun invokeModel(
        model: com.google.firebase.ai.GenerativeModel,
        payload: com.google.firebase.ai.type.Content
    ): BrainResult {
        return try {
            val response = withTimeout(35_000L) {
                model.generateContent(payload)
            }
            val jsonText = response.text?.trim()
                ?: return BrainResult.Failure("لم يرد نموذج الذكاء الاصطناعي بقرار.", isCloudUnavailable = true)

            val json = JSONObject(jsonText)
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
        } catch (e: TimeoutCancellationException) {
            Log.w(tag, "Gemini cloud reasoning timed out; falling back to local Artemis engine.")
            BrainResult.Failure(
                errorMessage = "انتهت مهلة الاتصال السحابي بـ Gemini، تم التحويل للأتمتة المحلية.",
                isCloudUnavailable = true
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val raw = e.localizedMessage ?: e.message ?: ""
            Log.w(tag, "Gemini cloud reasoning unavailable ($raw); switching step to local Artemis engine.")
            val isBillingBlock = raw.contains("free tier is not available", ignoreCase = true) ||
                raw.contains("billing", ignoreCase = true)
            val friendlyMsg = when {
                isBillingBlock ->
                    "الطبقة السحابية لـ Gemini تتطلب تفعيل الفوترة لهذا المشروع — جاري المتابعة بالأتمتة المحلية."
                raw.contains("403") || raw.contains("App Check", ignoreCase = true) ->
                    "تعذر التحقق السحابي (App Check) — جاري المتابعة بالأتمتة المحلية."
                raw.contains("quota", ignoreCase = true) || raw.contains("429") ->
                    "تم تجاوز حد الطلبات السحابي مؤقتاً — جاري المتابعة بالأتمتة المحلية."
                else ->
                    "تعذر الاتصال السحابي بـ Gemini — جاري المتابعة بالأتمتة المحلية."
            }
            BrainResult.Failure(
                errorMessage = friendlyMsg,
                isCloudUnavailable = true
            )
        }
    }

    companion object {
        private const val SYSTEM_PROMPT = """
أنت "مورڤينت (Morvent)" — وكيل أتمتة هاتف أندرويد متكامل بمستوى بشري مبني على منهجية Google Artemis.
تعمل على النظام كله وليس داخل التطبيقات فقط: تتحكم في واجهة النظام (SystemUI)، الإعدادات السريعة، شريط الإشعارات، نوافذ الأذونات والحوارات المنبثقة، الشاشة الرئيسية، درج التطبيقات، تقسيم الشاشة، قائمة الطاقة، قفل الشاشة، السطوع، التدوير التلقائي، عدم الإزعاج، الصوت، الواي فاي، البلوتوث، بيانات الجوال، نقطة الاتصال، الوضع الداكن، الكاميرا، الخرائط، مدير الملفات، إعدادات كل تطبيق، وجميع التطبيقات المثبتة.

قواعد العمل الدقيقة:
1. للتحكم الشامل بالنظام استخدم:
   - "GLOBAL_ACTION" مع target من: back, home, recents, notifications, dismiss_notifications, quick_settings, screenshot, lock, power_dialog, split_screen, all_apps.
   - "OPEN_SETTINGS" لفتح أي صفحة إعدادات (wifi, internet, mobile_data, hotspot, bluetooth, display, sound, battery, location, airplane, nfc, vpn, developer, apps, default_apps, security, privacy, storage, notifications, date, language, cast, about, system_update, assistant, home_app).
   - "DEVICE_CONTROL" مع target مثل: flashlight_on/off, volume_up/down, mute, vibrate, normal, ring_volume:80, alarm_volume:90, notif_volume:70, brightness:80, brightness_auto, auto_rotate_on/off, dnd_on/off, qs_toggle:الواي فاي|Wi-Fi, panel:internet/wifi/volume/nfc, timeout:120, read_notifications, clear_notifications, permission_allow, permission_deny, clear_field, app_details:<اسم_التطبيق>, maps:<بحث>, camera_photo, camera_video, files, email:<to>:<subject>:<body>.
2. استخدم "LAUNCH_APP" لفتح التطبيقات المثبتة مباشرة بالاسم أو الحزمة.
3. فضّل "TAP_ELEMENT" مع elementId من شجرة العناصر (التي تشمل نوافذ النظام والتطبيقات والحوارات)؛ وإذا كان العنصر رسومياً أو داخل WebView/خريطة/لعبة، استخدم "TAP_COORDINATES" بالإحداثيات المطبّعة (x, y من 0 إلى 1000).
4. للكتابة في أي حقل (شريط البحث، تسجيل الدخول، إعدادات النظام)، استخدم "TYPE_TEXT" مع submitEnter=true عند الإرسال.
5. إذا لم يظهر العنصر على الشاشة، استخدم "SCROLL_DOWN" أو "SCROLL_UP" أو "SWIPE_LEFT"/"SWIPE_RIGHT". لتبديل إعداد نظام سريع مثل الواي فاي أو نقطة الاتصال أو البيانات، افتح "quick_settings" أو استخدم qs_toggle أو panel.
6. إذا تكررت نفس الخطوة مرتين دون تغير الشاشة، غيّر أسلوبك فوراً.
7. عند وجود خطوة حساسة (كلمة مرور، OTP، دفع مالي)، لا تخمن — استخدم "ASK_USER".
8. فور التأكد من اكتمال جميع الخطوات والمهام المجدولة بالكامل، اختر "DONE" مع ملخص عربي واضح ومفيد في finalResponse.
"""
    }
}
