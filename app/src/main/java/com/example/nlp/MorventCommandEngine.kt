package com.example.nlp

import android.content.Context
import android.provider.Settings
import com.example.device.DeviceController
import com.example.model.ActionCardData
import com.example.model.ActionType
import com.example.model.ChatMessage
import com.example.model.MessageStatus
import com.example.service.MorventAccessibilityService
import com.example.service.MorventFloatingAgentService
import kotlinx.coroutines.delay
import java.util.Locale

class MorventCommandEngine(
    private val context: Context,
    private val controller: DeviceController,
    private val artemisEngine: ArtemisAgentEngine? = null
) {

    fun isFastDeterministicCommand(userInput: String): Boolean {
        val trimmed = userInput.trim()
        val lower = trimmed.lowercase(Locale.ROOT)
        if (lower.isEmpty()) return false

        // Multi-step, multi-line, numbered, punctuated, repeated, or long instructions always go to Artemis Agent
        if (trimmed.contains('\n') || trimmed.contains("،") || trimmed.contains("؛") || trimmed.contains(";") ||
            lower.contains(" ثم ") || lower.contains(" وبعدها ") || lower.contains(" وبعدين ") ||
            lower.contains(" بعد ذلك ") || lower.contains(" ومن ثم ") || lower.contains(" وكذلك ") ||
            lower.contains(" أخيراً ") || lower.contains(" and then ") || lower.contains(" then ") ||
            lower.contains(" after that ") ||
            lower.startsWith("أتمتة:") || lower.startsWith("نفذ:") || lower.startsWith("مهمة:") ||
            Regex("(^|\\s)[\\d\\u0660-\\u0669]+[\\.\\)]\\s+").containsMatchIn(trimmed) ||
            Regex("(?i)\\s+(\\d+|مرتين|ثلاث|أربع|اربع|خمس|ست)\\s*مرات?\\b").containsMatchIn(lower)
        ) return false

        val wordCount = lower.split(Regex("\\s+")).filter { it.isNotBlank() }.size
        if (wordCount > 10) return false

        val hasCompoundConjunction = Regex("(^|\\s)(و|ثم)(افتح|شغل|اضغط|انقر|اكتب|ادخل|ابحث|فعل|غير|حمل|ارسل|ابعت|اقرأ|لخص|شارك|اعمل|انتظر|انسخ|الصق|أغلق|اختر)").containsMatchIn(lower)
        if (hasCompoundConjunction) return false

        if (containsAny(lower, "اشتغل في الخلفية", "شغل الخلفية", "النافذة العائمة", "الفقاعة العائمة", "floating hud", "start background")) return true
        if (containsAny(lower, "كشاف", "فلاش", "flashlight", "torch", "مصباح") && wordCount <= 5) return true
        if (containsAny(lower, "بطارية", "نسبة الشحن", "battery") && wordCount <= 5) return true
        if (containsAny(lower, "فحص الهاتف", "حالة الجهاز", "معلومات الجهاز", "الذاكرة", "الرام", "device info", "system stats") && wordCount <= 5) return true
        if (containsAny(lower, "صوت المنبه", "صوت الرنين", "صوت الإشعارات", "صوت الاشعارات", "صوت", "مستوى الصوت", "volume", "صامت", "اهتزاز", "كتم") && wordCount <= 6) return true
        if (containsAny(lower, "سطوع", "brightness", "تدوير تلقائي", "التدوير التلقائي", "تدوير الشاشة", "auto rotate", "عدم الإزعاج", "عدم الازعاج", "dnd", "مهلة الشاشة", "سكون الشاشة") && wordCount <= 6) return true
        if (containsAny(lower, "تقسيم الشاشة", "قائمة الطاقة", "درج التطبيقات", "كل التطبيقات", "أغلق الإشعارات", "اغلق الاشعارات", "اقرأ الإشعارات", "اقرا الاشعارات", "امسح الإشعارات", "امسح كل الإشعارات", "لوحة الإنترنت", "لوحة الانترنت", "لوحة الصوت", "split screen", "power menu", "all apps", "read notifications", "clear notifications") && wordCount <= 6) return true
        if (containsAny(lower, "اضغط سماح", "اسمح بالإذن", "اسمح بالاذن", "اقبل الإذن", "اقبل الاذن", "ارفض الإذن", "ارفض الاذن", "عدم السماح", "allow permission", "deny permission") && wordCount <= 5) return true
        if (containsAny(lower, "امسح الحقل", "امسح النص", "نظف الحقل", "clear field", "clear text") && wordCount <= 4) return true
        if (containsAny(lower, "إعدادات تطبيق", "اعدادات تطبيق", "معلومات تطبيق", "أذونات تطبيق", "اذونات تطبيق", "صلاحيات تطبيق") && wordCount <= 5) return true
        if (containsAny(lower, "في الخرائط", "خرائط عن", "افتح خريطة", "مدير الملفات", "التنزيلات", "افتح الملفات", "صور فيديو", "التقط صورة", "صور بالكاميرا") && wordCount <= 6) return true
        if (containsAny(lower, "تحديثات النظام", "حول الهاتف", "التاريخ والوقت", "إعدادات المساعد", "اعدادات المساعد", "إعدادات الخصوصية", "اعدادات الخصوصية", "إعدادات المشغل الافتراضي") && wordCount <= 5) return true
        if (containsAny(lower, "نقطة الاتصال", "هوتسبوت", "hotspot", "بيانات الجوال", "بيانات الهاتف", "mobile data", "وضع الطيران", "airplane", "nfc", "مطور", "vpn") && wordCount <= 5) return true
        if (lower in listOf("رجوع", "ارجع", "الرجوع", "back", "الرئيسية", "الشاشة الرئيسية", "home", "التطبيقات الأخيرة", "recents", "لقطة شاشة", "سكرين شوت", "screenshot", "اقفل الشاشة", "lock screen", "لوحة الاشعارات", "اعدادات سريعة", "الملفات")) return true
        if (containsAny(lower, "وضع النوم", "تجهيز النوم", "sleep mode", "وضع الاجتماع", "وضع العمل", "meeting mode", "توفير الطاقة", "توفير البطارية", "power save", "طوارئ", "وضع الطوارئ", "emergency") && wordCount <= 5) return true
        if (containsAny(lower, "من انت", "من أنت", "who are you", "مساعدة", "اوامر", "أوامر", "help") && wordCount <= 4) return true
        if (lower in listOf("افحص الشاشة", "محتوى الشاشة", "inspect screen", "scan screen")) return true
        if (containsAny(lower, "واي فاي", "وايفاي", "wifi", "بلوتوث", "bluetooth", "اعدادات", "الإعدادات", "settings") && wordCount <= 4) return true
        if ((lower.startsWith("افتح ") || lower.startsWith("شغل ") || lower.startsWith("open ")) && wordCount <= 3 && !containsAny(lower, "حساب", "موقع", "فيديو", "أغنية", "رسالة", "الكاميرا وصور")) return true
        if (containsAny(lower, "منبه", "alarm", "مؤقت", "timer") && Regex("\\d+").containsMatchIn(lower) && wordCount <= 6) return true
        if (containsAny(lower, "اتصل", "call") && extractPhoneNumber(lower).isNotEmpty()) return true

        return false
    }

    suspend fun processCommand(userInput: String): ChatMessage {
        val trimmed = userInput.trim()
        val lower = trimmed.lowercase(Locale.ROOT)

        // ========================================================
        // 0A. BACKGROUND FLOATING SERVICE CONTROL VIA CHAT
        // ========================================================
        if (containsAny(lower, "اشتغل في الخلفية", "شغل الخلفية", "النافذة العائمة", "الفقاعة العائمة", "floating hud", "start background")) {
            val stopRequested = containsAny(lower, "اطفئ", "اوقف", "أوقف", "اغلق", "stop", "close", "disable")
            return if (stopRequested) {
                MorventFloatingAgentService.stop(context)
                ChatMessage(
                    text = "تم إيقاف خدمة العمل في الخلفية والنافذة العائمة.",
                    isUser = false,
                    actionType = ActionType.MACRO_SCENARIO
                )
            } else if (!Settings.canDrawOverlays(context)) {
                ChatMessage(
                    text = "لتشغيل مورڤينت في الخلفية فوق جميع التطبيقات، يرجى منح إذن الظهور فوق التطبيقات الأخرى.",
                    isUser = false,
                    status = MessageStatus.INFO,
                    actionType = ActionType.ACCESSIBILITY_NAV,
                    actionDetail = "REQUEST_OVERLAY_PERMISSION"
                )
            } else {
                MorventFloatingAgentService.start(context)
                ChatMessage(
                    text = "تم تشغيل مورڤينت في الخلفية! 🚀 ستظهر الفقاعة العائمة فوق جميع التطبيقات لتتمكن من أمري وإيقافي في أي وقت أثناء العمل.",
                    isUser = false,
                    actionType = ActionType.MACRO_SCENARIO,
                    subSteps = listOf(
                        "بدء خدمة MorventFloatingAgentService في الخلفية",
                        "تفعيل نافذة التحكم العائمة فوق التطبيقات",
                        "تجهيز شريط الإشعار التفاعلي لإدخال الأوامر المباشرة"
                    )
                )
            }
        }

        // ========================================================
        // 0. COMPOUND APPS & IN-APP AUTOMATION: "افتح [تطبيق] واضغط على [زر]"
        // ========================================================
        if ((lower.startsWith("افتح") || lower.startsWith("شغل") || lower.startsWith("open")) &&
            (lower.contains("واضغط") || lower.contains("وانقر") || lower.contains("ثم اضغط") || lower.contains("and click") || lower.contains("and tap"))
        ) {
            val parts = lower.split(Regex("(واضغط على|وانقر على|واضغط|وانقر|ثم اضغط على|ثم اضغط|and click on|and click|and tap)"))
            if (parts.size >= 2) {
                val appPart = extractAppTarget(parts[0])
                val clickTarget = parts[1].trim()

                val (launchOk, launchMsg) = controller.launchAppByQuery(appPart)
                if (launchOk) {
                    delay(1200) // Wait for target app activity to enter foreground and draw UI
                    val (clickOk, clickMsg) = MorventAccessibilityService.clickElementByText(clickTarget)
                    val logs = listOf(
                        launchMsg,
                        "الانتظار حتى ظهور واجهة التطبيق في المقدمة",
                        "فحص شجرة عناصر الشاشة عبر AccessibilityNodeInfo",
                        clickMsg
                    )
                    return ChatMessage(
                        text = if (clickOk) "تم تشغيل $appPart والنقر على '$clickTarget' بنجاح 🎯"
                               else "تم فتح $appPart ولكن: $clickMsg",
                        isUser = false,
                        status = if (clickOk) MessageStatus.SUCCESS else MessageStatus.INFO,
                        actionType = ActionType.IN_APP_CLICK,
                        subSteps = logs,
                        cardData = ActionCardData.InAppActionResultCard(
                            actionTitle = "فتح التطبيق والنقر",
                            target = "$appPart -> $clickTarget",
                            isSuccess = clickOk,
                            logs = logs
                        )
                    )
                }
            }
        }

        // ========================================================
        // 0B. SYSTEM PERMISSION DIALOGS & CLEAR ACTIVE FIELD
        // ========================================================
        if (containsAny(lower, "اضغط سماح", "اسمح بالإذن", "اسمح بالاذن", "اقبل الإذن", "اقبل الاذن", "allow permission")) {
            if (!MorventAccessibilityService.isRunning) {
                return createAccessibilityDisabledMessage("الرد على نافذة إذن النظام")
            }
            val (ok, msg) = MorventAccessibilityService.handleSystemPermissionDialog(true)
            return ChatMessage(msg, isUser = false, status = if (ok) MessageStatus.SUCCESS else MessageStatus.INFO, actionType = ActionType.ACCESSIBILITY_NAV)
        }
        if (containsAny(lower, "ارفض الإذن", "ارفض الاذن", "عدم السماح", "deny permission")) {
            if (!MorventAccessibilityService.isRunning) {
                return createAccessibilityDisabledMessage("رفض نافذة إذن النظام")
            }
            val (ok, msg) = MorventAccessibilityService.handleSystemPermissionDialog(false)
            return ChatMessage(msg, isUser = false, status = if (ok) MessageStatus.SUCCESS else MessageStatus.INFO, actionType = ActionType.ACCESSIBILITY_NAV)
        }
        if (containsAny(lower, "امسح الحقل", "امسح النص", "نظف الحقل", "clear field", "clear text")) {
            if (!MorventAccessibilityService.isRunning) {
                return createAccessibilityDisabledMessage("مسح محتوى الحقل النشط")
            }
            val (ok, msg) = MorventAccessibilityService.clearFocusedField()
            return ChatMessage(msg, isUser = false, status = if (ok) MessageStatus.SUCCESS else MessageStatus.ERROR, actionType = ActionType.IN_APP_TYPE)
        }

        // ========================================================
        // 1. IN-APP ELEMENT CLICKING: "اضغط على [نص]" / "انقر [زر]"
        // ========================================================
        if (lower.startsWith("اضغط على") || lower.startsWith("انقر على") ||
            lower.startsWith("اضغط") || lower.startsWith("انقر") ||
            lower.startsWith("click on") || lower.startsWith("tap on") ||
            lower.startsWith("click") || lower.startsWith("tap")
        ) {
            val target = lower.replace(Regex("^(اضغط على|انقر على|اضغط|انقر|click on|tap on|click|tap)\\s*"), "").trim()
            if (target.isNotEmpty()) {
                val (ok, msg) = MorventAccessibilityService.clickElementByText(target)
                val logs = listOf(
                    "فحص شجرة العناصر للشاشة الحالية",
                    "البحث عن عقدة مطابقة لـ '$target'",
                    msg
                )
                return ChatMessage(
                    text = msg,
                    isUser = false,
                    status = if (ok) MessageStatus.SUCCESS else MessageStatus.ERROR,
                    actionType = ActionType.IN_APP_CLICK,
                    subSteps = logs,
                    cardData = ActionCardData.InAppActionResultCard(
                        actionTitle = "نقر عنصر على الشاشة",
                        target = target,
                        isSuccess = ok,
                        logs = logs
                    )
                )
            }
        }

        // ========================================================
        // 2. IN-APP TEXT TYPING: "اكتب [نص] في حقل [اسم]"
        // ========================================================
        if (lower.startsWith("اكتب") || lower.startsWith("ادخل") || lower.startsWith("أدخل") ||
            lower.startsWith("type") || lower.startsWith("enter")
        ) {
            val (targetField, textToType) = extractTypingTargetAndText(trimmed)
            if (textToType.isNotEmpty()) {
                val (ok, msg) = MorventAccessibilityService.typeTextIntoField(targetField, textToType)
                val logs = listOf(
                    "البحث عن حقل إدخال نشط ${if (targetField != null) "مطابق لـ '$targetField'" else ""}",
                    "تركيز الحقل وتفعيل لوحة المفاتيح",
                    "إدخال النص '$textToType' عبر ACTION_SET_TEXT",
                    msg
                )
                return ChatMessage(
                    text = msg,
                    isUser = false,
                    status = if (ok) MessageStatus.SUCCESS else MessageStatus.ERROR,
                    actionType = ActionType.IN_APP_TYPE,
                    subSteps = logs,
                    cardData = ActionCardData.InAppActionResultCard(
                        actionTitle = "كتابة وإدخال نص",
                        target = textToType,
                        isSuccess = ok,
                        logs = logs
                    )
                )
            }
        }

        // ========================================================
        // 2B. LONG PRESS & HORIZONTAL SWIPES
        // ========================================================
        if (containsAny(lower, "ضغط مطول", "اضغط مطولا", "long press")) {
            val target = trimmed.replace(Regex("(?i).*(ضغط مطول على|اضغط مطولا على|ضغط مطول|long press on|long press)\\s*"), "").trim()
            if (target.isNotEmpty()) {
                val (ok, msg) = MorventAccessibilityService.longClickElementByText(target)
                return ChatMessage(
                    text = msg,
                    isUser = false,
                    status = if (ok) MessageStatus.SUCCESS else MessageStatus.ERROR,
                    actionType = ActionType.IN_APP_CLICK
                )
            }
        }

        if (containsAny(lower, "اسحب يمين", "اسحب لليمين", "swipe right")) {
            val (ok, msg) = MorventAccessibilityService.swipeHorizontal(true)
            return ChatMessage(
                text = msg,
                isUser = false,
                status = if (ok) MessageStatus.SUCCESS else MessageStatus.ERROR,
                actionType = ActionType.IN_APP_SCROLL
            )
        }

        if (containsAny(lower, "اسحب يسار", "اسحب لليسار", "swipe left")) {
            val (ok, msg) = MorventAccessibilityService.swipeHorizontal(false)
            return ChatMessage(
                text = msg,
                isUser = false,
                status = if (ok) MessageStatus.SUCCESS else MessageStatus.ERROR,
                actionType = ActionType.IN_APP_SCROLL
            )
        }

        // ========================================================
        // 3. IN-APP SCROLLING & BROWSING: "انزل لأسفل", "مرر لأعلى"
        // ========================================================
        if (containsAny(lower, "انزل", "مرر لأسفل", "تصفح لأسفل", "scroll down", "اطلع", "مرر لأعلى", "تصفح لأعلى", "scroll up", "تصفح")) {
            val forward = !containsAny(lower, "اطلع", "لأعلى", "up", "فوق")
            val (ok, msg) = MorventAccessibilityService.scrollScreen(forward)
            return ChatMessage(
                text = msg,
                isUser = false,
                status = if (ok) MessageStatus.SUCCESS else MessageStatus.ERROR,
                actionType = ActionType.IN_APP_SCROLL
            )
        }

        // ========================================================
        // 4. AUTONOMOUS ACCOUNT CREATION & LOGIN: "افتح حساب", "تسجيل الدخول", "سجل بواسطة جوجل"
        // ========================================================
        if (containsAny(lower, "افتح حساب", "إنشاء حساب", "سجل دخول", "تسجيل الدخول", "sign in", "login", "create account", "حساب جديد")) {
            val appInQuery = extractAppFromAccountCommand(lower)
            if (appInQuery.isNotEmpty()) {
                val (launchOk, _) = controller.launchAppByQuery(appInQuery)
                if (launchOk) {
                    delay(1200)
                }
            }

            val workflowType = when {
                containsAny(lower, "جوجل", "google") -> "google"
                containsAny(lower, "إنشاء", "جديد", "create", "register", "signup") -> "register"
                else -> "login"
            }

            val (ok, logs) = MorventAccessibilityService.executeAutonomousAccountWorkflow(workflowType)
            val currentPkg = MorventAccessibilityService.currentForegroundPackage.value
            return ChatMessage(
                text = if (ok) "تم إطلاق سير عمل فتح الحساب والمصادقة التلقائي بنجاح 🔐"
                       else "تم فحص الشاشة: لم يتم العثور على زر مصادقة مباشر، يرجى تحديد الخيار أو فتح صفحة التسجيل.",
                isUser = false,
                status = if (ok) MessageStatus.SUCCESS else MessageStatus.INFO,
                actionType = ActionType.ACCOUNT_WORKFLOW,
                subSteps = logs,
                cardData = ActionCardData.AccountWorkflowCard(
                    appName = currentPkg.ifBlank { "التطبيق الحالي" },
                    workflowName = if (workflowType == "google") "المصادقة عبر Google" else if (workflowType == "register") "إنشاء حساب جديد" else "تسجيل الدخول",
                    detectedAuthOptions = listOf("Google Sign-In", "البريد الإلكتروني وكلمة المرور", "متابعة"),
                    stepsExecuted = logs,
                    isSuccess = ok
                )
            )
        }

        // ========================================================
        // 5. SCREEN INSPECTION: "افحص الشاشة", "محتوى الشاشة"
        // ========================================================
        if (containsAny(lower, "افحص الشاشة", "محتوى الشاشة", "ماذا يوجد على الشاشة", "حلل الشاشة", "قراءة الشاشة", "inspect screen", "scan screen")) {
            val report = MorventAccessibilityService.inspectCurrentScreen()
            val logs = listOf(
                "قراءة شجرة العقد النشطة لنظام النافذة",
                "التطبيق النشط حالياً: ${report.packageName}",
                "رصد ${report.elements.size} عنصر واجهة مستخدم",
                "الأزرار المكتشفة: ${report.buttons.joinToString(", ")}",
                "حقول الإدخال المكتشفة: ${report.inputFields.joinToString(", ")}"
            )
            return ChatMessage(
                text = "تقرير فحص الشاشة: التطبيق الحالي هو '${report.packageName}'، تم العثور على ${report.elements.size} عنصر تفاعلي (${report.buttons.size} أزرار تفاعلية). يمكنك الآن طلب النقر على أي منها!",
                isUser = false,
                actionType = ActionType.IN_APP_INSPECT,
                subSteps = logs,
                cardData = ActionCardData.ScreenInspectionCard(
                    packageName = report.packageName,
                    totalElements = report.elements.size,
                    buttonsFound = report.buttons,
                    textInputs = report.inputFields,
                    clickableCount = report.buttons.size
                )
            )
        }

        // ========================================================
        // 6. FLASHLIGHT
        // ========================================================
        if (containsAny(lower, "كشاف", "فلاش", "اضيء", "flashlight", "torch", "مصباح")) {
            val turnOff = containsAny(lower, "اطفي", "طفي", "اغلق", "سكر", "off", "stop", "disable")
            val turnOn = containsAny(lower, "شغل", "افتح", "اضئ", "ولع", "on", "start", "enable")

            val targetState = if (turnOff) false else if (turnOn) true else null
            val isSuccess = controller.toggleFlashlight(targetState)
            val currentState = controller.isFlashlightOn.value

            return if (isSuccess) {
                val stateText = if (currentState) "تشغيل كشاف الهاتف 💡" else "إطفاء كشاف الهاتف 🌑"
                ChatMessage(
                    text = "تم $stateText بنجاح بناءً على أمرك.",
                    isUser = false,
                    actionType = ActionType.FLASHLIGHT,
                    actionDetail = if (currentState) "Flashlight ON" else "Flashlight OFF",
                    subSteps = listOf(
                        "فحص وحدة الكاميرا وحالة الفلاش",
                        "إرسال إشارة التحكم عبر CameraManager",
                        "تأكيد حالة الإضاءة: ${if (currentState) "مضاء" else "مطفأ"}"
                    )
                )
            } else {
                ChatMessage(
                    text = "تعذر التحكم في كشاف الهاتف. تأكد من توفر الفلاش وعدم انشغال الكاميرا بتطبيق آخر.",
                    isUser = false,
                    status = MessageStatus.ERROR,
                    actionType = ActionType.FLASHLIGHT
                )
            }
        }

        // ========================================================
        // 7. BATTERY & POWER
        // ========================================================
        if (containsAny(lower, "بطارية", "شحن", "نسبة الشحن", "battery", "طاقة")) {
            val batteryCard = controller.getBatteryDetails()
            val stateArabic = if (batteryCard.isCharging) "قيد الشحن ⚡" else "يعمل على البطارية 🔋"
            return ChatMessage(
                text = "تقرير حالة الطاقة: نسبة الشحن الحالية ${batteryCard.percentage}%، الحالة: $stateArabic.",
                isUser = false,
                actionType = ActionType.BATTERY,
                cardData = batteryCard,
                subSteps = listOf(
                    "قراءة مستشعر مدير البطارية BatteryManager",
                    "فحص درجة حرارة البطارية: ${batteryCard.temperatureCelsius}°C",
                    "التحقق من مستوى الجهد الكهربائي: ${batteryCard.voltageMv} mV"
                )
            )
        }

        // ========================================================
        // 8. SYSTEM INFO & MEMORY & STORAGE
        // ========================================================
        if (containsAny(lower, "فحص الهاتف", "حالة الجهاز", "معلومات الجهاز", "الذاكرة", "الرام", "تخزين", "storage", "ram", "device info", "system stats")) {
            val deviceInfo = controller.getDeviceInfo()
            return ChatMessage(
                text = "تم فحص موارد الجهاز بنجاح. ذاكرة الوصول العشوائي المتوفرة ${deviceInfo.freeRamGb} GB من أصل ${deviceInfo.totalRamGb} GB.",
                isUser = false,
                actionType = ActionType.DEVICE_INFO,
                cardData = deviceInfo,
                subSteps = listOf(
                    "فحص مساحة التخزين عبر نظام الملفات StatFs",
                    "فحص استهلاك ذاكرة RAM عبر ActivityManager",
                    "التحقق من إصدار النظام: ${deviceInfo.osVersion}"
                )
            )
        }

        // ========================================================
        // 9. SOUND & ALL SYSTEM AUDIO STREAMS
        // ========================================================
        if (containsAny(lower, "صوت المنبه", "صوت الرنين", "صوت الإشعارات", "صوت الاشعارات")) {
            val streamType = when {
                lower.contains("منبه") -> android.media.AudioManager.STREAM_ALARM
                lower.contains("رنين") -> android.media.AudioManager.STREAM_RING
                else -> android.media.AudioManager.STREAM_NOTIFICATION
            }
            val label = when {
                lower.contains("منبه") -> "صوت المنبه"
                lower.contains("رنين") -> "صوت الرنين"
                else -> "صوت الإشعارات"
            }
            val pct = parsePercentageWordOrDigit(lower)
            if (pct != null) {
                controller.setStreamVolumePercent(streamType, pct)
                return ChatMessage(
                    text = "تم ضبط $label في النظام على $pct% 🔊.",
                    isUser = false,
                    actionType = ActionType.VOLUME,
                    cardData = controller.getVolumeCardData()
                )
            } else {
                val raise = !containsAny(lower, "اخفض", "قصر", "نقص", "down", "lower")
                controller.adjustStreamVolume(streamType, raise)
                return ChatMessage(
                    text = "تم ${if (raise) "رفع" else "خفض"} $label بالنظام 🔊.",
                    isUser = false,
                    actionType = ActionType.VOLUME,
                    cardData = controller.getVolumeCardData()
                )
            }
        }

        if (containsAny(lower, "صوت", "مستوى الصوت", "volume", "sound", "صامت", "اهتزاز", "كتم") &&
            !containsAny(lower, "لوحة الصوت")
        ) {
            if (containsAny(lower, "صامت", "كتم", "mute", "silent")) {
                controller.setRingerMode(0) // Silent
                controller.setMediaVolumePercent(0)
                return ChatMessage(
                    text = "تم تفعيل الوضع الصامت وكتم كافة الأصوات 🔇.",
                    isUser = false,
                    actionType = ActionType.VOLUME,
                    cardData = controller.getVolumeCardData(),
                    subSteps = listOf("تغيير وضع الرنين إلى SILENT", "كتم تدفق الوسائط الإعلامية إلى 0%")
                )
            }

            if (containsAny(lower, "اهتزاز", "vibrate")) {
                controller.setRingerMode(1) // Vibrate
                return ChatMessage(
                    text = "تم تفعيل وضع الاهتزاز 📳.",
                    isUser = false,
                    actionType = ActionType.VOLUME,
                    cardData = controller.getVolumeCardData()
                )
            }

            if (containsAny(lower, "عام", "رنين", "عادي", "normal")) {
                controller.setRingerMode(2) // Normal
                controller.setMediaVolumePercent(70)
                return ChatMessage(
                    text = "تم إعادة الصوت إلى الوضع العام الطبيعي 🔊.",
                    isUser = false,
                    actionType = ActionType.VOLUME,
                    cardData = controller.getVolumeCardData()
                )
            }

            if (containsAny(lower, "ارفع", "علي", "زيادة", "up", "raise", "louder")) {
                controller.adjustMediaVolume(true)
                return ChatMessage(
                    text = "تم رفع مستوى صوت الوسائط 🔊.",
                    isUser = false,
                    actionType = ActionType.VOLUME,
                    cardData = controller.getVolumeCardData()
                )
            }

            if (containsAny(lower, "اخفض", "قصر", "نقص", "down", "lower", "quieter")) {
                controller.adjustMediaVolume(false)
                return ChatMessage(
                    text = "تم خفض مستوى صوت الوسائط 🔉.",
                    isUser = false,
                    actionType = ActionType.VOLUME,
                    cardData = controller.getVolumeCardData()
                )
            }

            val digits = Regex("\\d+").find(lower)?.value?.toIntOrNull()
            if (digits != null) {
                controller.setMediaVolumePercent(digits)
                return ChatMessage(
                    text = "تم ضبط مستوى الصوت على $digits% 🎵.",
                    isUser = false,
                    actionType = ActionType.VOLUME,
                    cardData = controller.getVolumeCardData()
                )
            }

            return ChatMessage(
                text = "مستويات الصوت الحالية للهاتف:",
                isUser = false,
                actionType = ActionType.VOLUME,
                cardData = controller.getVolumeCardData()
            )
        }

        // ========================================================
        // 9B. WHOLE-OS SYSTEM HARDWARE SETTINGS & PANELS
        // ========================================================
        if (containsAny(lower, "سطوع", "brightness") && !containsAny(lower, "اعدادات", "إعدادات")) {
            val naturalPct = parsePercentageWordOrDigit(lower)
            val res = when {
                containsAny(lower, "تلقائي", "auto") -> controller.setAutoBrightness(true)
                naturalPct != null -> controller.setScreenBrightnessPercent(naturalPct)
                containsAny(lower, "ارفع", "زد", "علي", "up", "increase") -> controller.adjustScreenBrightness(true)
                containsAny(lower, "اخفض", "قلل", "نقص", "down", "decrease") -> controller.adjustScreenBrightness(false)
                else -> {
                    val current = controller.getScreenBrightnessPercent()
                    return ChatMessage(
                        text = "سطوع شاشة النظام الحالي هو $current% ☀️. يمكنك قول 'اضبط السطوع 80%' أو 'سطوع أقصى' أو 'سطوع تلقائي'.",
                        isUser = false,
                        actionType = ActionType.BRIGHTNESS
                    )
                }
            }
            if (res.second == "REQUIRE_WRITE_SETTINGS") {
                return ChatMessage(
                    text = "لتعديل سطوع الشاشة وتدوير النظام مباشرة، يرجى منح مورڤينت إذن 'تعديل إعدادات النظام' (Modify System Settings).",
                    isUser = false,
                    status = MessageStatus.INFO,
                    actionType = ActionType.BRIGHTNESS,
                    actionDetail = "REQUEST_WRITE_SETTINGS_PERMISSION"
                )
            }
            return ChatMessage(
                text = res.second,
                isUser = false,
                status = if (res.first) MessageStatus.SUCCESS else MessageStatus.ERROR,
                actionType = ActionType.BRIGHTNESS
            )
        }

        if (containsAny(lower, "تدوير تلقائي", "التدوير التلقائي", "تدوير الشاشة", "auto rotate")) {
            val disable = containsAny(lower, "اوقف", "أوقف", "عطل", "اغلق", "ثبت", "off", "disable")
            val res = controller.setAutoRotate(!disable)
            if (res.second == "REQUIRE_WRITE_SETTINGS") {
                return ChatMessage(
                    text = "لتغيير التدوير التلقائي لشاشة النظام، يرجى منح إذن 'تعديل إعدادات النظام'.",
                    isUser = false,
                    status = MessageStatus.INFO,
                    actionType = ActionType.BRIGHTNESS,
                    actionDetail = "REQUEST_WRITE_SETTINGS_PERMISSION"
                )
            }
            return ChatMessage(
                text = res.second,
                isUser = false,
                status = if (res.first) MessageStatus.SUCCESS else MessageStatus.ERROR,
                actionType = ActionType.DEVICE_INFO
            )
        }

        if (containsAny(lower, "مهلة الشاشة", "سكون الشاشة", "screen timeout")) {
            val num = Regex("(\\d+)").find(lower)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 60
            val secs = if (lower.contains("دقيق") || lower.contains("min")) num * 60 else num
            val res = controller.setScreenTimeoutSeconds(secs)
            if (res.second == "REQUIRE_WRITE_SETTINGS") {
                return ChatMessage(
                    text = "لتعديل مهلة سكون الشاشة، يرجى منح إذن 'تعديل إعدادات النظام'.",
                    isUser = false,
                    status = MessageStatus.INFO,
                    actionType = ActionType.BRIGHTNESS,
                    actionDetail = "REQUEST_WRITE_SETTINGS_PERMISSION"
                )
            }
            return ChatMessage(
                text = res.second,
                isUser = false,
                status = if (res.first) MessageStatus.SUCCESS else MessageStatus.ERROR,
                actionType = ActionType.DEVICE_INFO
            )
        }

        if (containsAny(lower, "عدم الإزعاج", "عدم الازعاج", "do not disturb", "dnd") && !containsAny(lower, "اعدادات", "إعدادات")) {
            val disable = containsAny(lower, "اوقف", "أوقف", "عطل", "الغي", "ألغ", "اغلق", "off", "disable")
            val res = controller.setDoNotDisturb(!disable)
            if (res.second == "REQUIRE_DND_PERMISSION") {
                return ChatMessage(
                    text = "للتحكم في وضع عدم الإزعاج (DND) على مستوى النظام، يرجى منح مورڤينت صلاحية الوصول لسياسة الإشعارات.",
                    isUser = false,
                    status = MessageStatus.INFO,
                    actionType = ActionType.ACCESSIBILITY_NAV,
                    actionDetail = "REQUEST_DND_PERMISSION"
                )
            }
            return ChatMessage(
                text = res.second,
                isUser = false,
                status = if (res.first) MessageStatus.SUCCESS else MessageStatus.ERROR,
                actionType = ActionType.MACRO_SCENARIO
            )
        }

        if (containsAny(lower, "اقرأ الإشعارات", "اقرا الاشعارات", "read notifications")) {
            val (ok, list) = MorventAccessibilityService.readSystemNotifications()
            return if (!ok) {
                createAccessibilityDisabledMessage("قراءة إشعارات النظام")
            } else if (list.isEmpty()) {
                ChatMessage("شريط إشعارات النظام فارغ حالياً ولا توجد إشعارات واردة 🔔.", isUser = false, actionType = ActionType.ACCESSIBILITY_NAV)
            } else {
                ChatMessage(
                    text = "🔔 إشعارات النظام الحالية (${list.size}):\n• " + list.joinToString("\n• "),
                    isUser = false,
                    actionType = ActionType.ACCESSIBILITY_NAV,
                    subSteps = list
                )
            }
        }

        if (containsAny(lower, "امسح الإشعارات", "امسح كل الإشعارات", "محو الإشعارات", "clear notifications")) {
            if (!MorventAccessibilityService.isRunning) {
                return createAccessibilityDisabledMessage("مسح إشعارات النظام")
            }
            val (ok, msg) = MorventAccessibilityService.clearAllSystemNotifications()
            return ChatMessage(
                text = msg,
                isUser = false,
                status = if (ok) MessageStatus.SUCCESS else MessageStatus.INFO,
                actionType = ActionType.ACCESSIBILITY_NAV
            )
        }

        if (containsAny(lower, "لوحة الإنترنت", "لوحة الانترنت", "internet panel")) {
            val (ok, msg) = controller.openSystemPanel("internet")
            return ChatMessage(msg, isUser = false, status = if (ok) MessageStatus.SUCCESS else MessageStatus.ERROR, actionType = ActionType.WIFI)
        }

        if (containsAny(lower, "لوحة الصوت", "volume panel")) {
            val (ok, msg) = controller.openSystemPanel("volume")
            return ChatMessage(msg, isUser = false, status = if (ok) MessageStatus.SUCCESS else MessageStatus.ERROR, actionType = ActionType.VOLUME)
        }

        // ========================================================
        // 10. SYSTEM NAVIGATION & ACCESSIBILITY GESTURES
        // ========================================================
        if (containsAny(lower, "رجوع", "ارجع", "الرجوع", "back", "go back")) {
            val executed = MorventAccessibilityService.performBack()
            return if (executed) {
                ChatMessage("تم تنفيذ أمر الرجوع بنجاح ◀️.", isUser = false, actionType = ActionType.ACCESSIBILITY_NAV)
            } else {
                createAccessibilityDisabledMessage("أمر الرجوع")
            }
        }

        if (containsAny(lower, "الرئيسية", "الشاشة الرئيسية", "home", "go home")) {
            val executed = MorventAccessibilityService.performHome()
            return if (executed) {
                ChatMessage("تم الانتقال إلى الشاشة الرئيسية للهاتف 🏠.", isUser = false, actionType = ActionType.ACCESSIBILITY_NAV)
            } else {
                createAccessibilityDisabledMessage("الانتقال للرئيسية")
            }
        }

        if (containsAny(lower, "التطبيقات الأخيرة", "المهام السابقة", "recents", "recent apps")) {
            val executed = MorventAccessibilityService.performRecents()
            return if (executed) {
                ChatMessage("تم فتح شاشة التطبيقات الأخيرة 📱.", isUser = false, actionType = ActionType.ACCESSIBILITY_NAV)
            } else {
                createAccessibilityDisabledMessage("فتح التطبيقات الأخيرة")
            }
        }

        if (containsAny(lower, "أغلق الإشعارات", "اغلق الاشعارات", "dismiss notifications")) {
            val executed = MorventAccessibilityService.performDismissNotifications()
            return if (executed) {
                ChatMessage("تم إغلاق لوحة الإشعارات بالنظام ✓.", isUser = false, actionType = ActionType.ACCESSIBILITY_NAV)
            } else {
                createAccessibilityDisabledMessage("إغلاق الإشعارات")
            }
        }

        if (containsAny(lower, "تقسيم الشاشة", "split screen")) {
            val executed = MorventAccessibilityService.performSplitScreen()
            return if (executed) {
                ChatMessage("تم تفعيل وضع تقسيم الشاشة بالنظام 🪟.", isUser = false, actionType = ActionType.ACCESSIBILITY_NAV)
            } else {
                createAccessibilityDisabledMessage("تقسيم الشاشة")
            }
        }

        if (containsAny(lower, "قائمة الطاقة", "power menu", "power dialog")) {
            val executed = MorventAccessibilityService.performPowerDialog()
            return if (executed) {
                ChatMessage("تم فتح قائمة الطاقة بالنظام ⚡.", isUser = false, actionType = ActionType.ACCESSIBILITY_NAV)
            } else {
                createAccessibilityDisabledMessage("فتح قائمة الطاقة")
            }
        }

        if (containsAny(lower, "درج التطبيقات", "كل التطبيقات", "all apps", "app drawer")) {
            val executed = MorventAccessibilityService.performAllApps()
            return if (executed) {
                ChatMessage("تم فتح درج كافة التطبيقات بالنظام 📱.", isUser = false, actionType = ActionType.ACCESSIBILITY_NAV)
            } else {
                createAccessibilityDisabledMessage("فتح درج التطبيقات")
            }
        }

        if (containsAny(lower, "اعدادات سريعة", "الإعدادات السريعة", "لوحة التحكم السريعة", "quick settings")) {
            val executed = MorventAccessibilityService.performQuickSettings()
            return if (executed) {
                ChatMessage("تم فتح لوحة الإعدادات السريعة ⚙️.", isUser = false, actionType = ActionType.ACCESSIBILITY_NAV)
            } else {
                createAccessibilityDisabledMessage("فتح الإعدادات السريعة")
            }
        }

        if (containsAny(lower, "اشعارات", "إشعارات", "لوحة الاشعارات", "notifications") && !containsAny(lower, "اعدادات", "إعدادات")) {
            val executed = MorventAccessibilityService.performNotifications()
            return if (executed) {
                ChatMessage("تم سحب لوحة الإشعارات لأسفل 🔔.", isUser = false, actionType = ActionType.ACCESSIBILITY_NAV)
            } else {
                createAccessibilityDisabledMessage("سحب الإشعارات")
            }
        }

        if (containsAny(lower, "لقطة شاشة", "صور الشاشة", "سكرين شوت", "screenshot")) {
            val executed = MorventAccessibilityService.performTakeScreenshot()
            return if (executed) {
                ChatMessage("تم التقاط لقطة للشاشة بنجاح 📸.", isUser = false, actionType = ActionType.SCREENSHOT)
            } else {
                createAccessibilityDisabledMessage("التقاط لقطة الشاشة")
            }
        }

        if (containsAny(lower, "اقفل الشاشة", "قفل الشاشة", "اطفئ الشاشة", "lock screen")) {
            val executed = MorventAccessibilityService.performLockScreen()
            return if (executed) {
                ChatMessage("تم قفل شاشة الهاتف 🔒.", isUser = false, actionType = ActionType.LOCK_SCREEN)
            } else {
                createAccessibilityDisabledMessage("قفل الشاشة")
            }
        }

        // ========================================================
        // 11. QUICK SETTINGS TILES & SYSTEM SETTINGS SHORTCUTS
        // ========================================================
        val wantsToggle = containsAny(lower, "فعل", "فعّل", "شغل", "شغّل", "اوقف", "أوقف", "عطل", "تبديل", "toggle", "enable", "disable")
        if (containsAny(lower, "نقطة الاتصال", "هوتسبوت", "hotspot")) {
            if (wantsToggle && MorventAccessibilityService.isRunning) {
                val (ok, msg) = MorventAccessibilityService.performQuickSettingTileToggle("نقطة الاتصال|Hotspot|Tethering", collapseAfter = true)
                if (ok) return ChatMessage(msg, isUser = false, actionType = ActionType.WIFI)
            }
            controller.openSpecificSettings("hotspot")
            return ChatMessage("تم فتح إعدادات نقطة الاتصال والبث السريع 📡.", isUser = false, actionType = ActionType.WIFI)
        }

        if (containsAny(lower, "بيانات الجوال", "بيانات الهاتف", "mobile data")) {
            if (wantsToggle && MorventAccessibilityService.isRunning) {
                val (ok, msg) = MorventAccessibilityService.performQuickSettingTileToggle("بيانات الجوال|بيانات الهاتف|Mobile data|الإنترنت|Internet", collapseAfter = true)
                if (ok) return ChatMessage(msg, isUser = false, actionType = ActionType.WIFI)
            }
            controller.openSpecificSettings("mobile_data")
            return ChatMessage("تم فتح إعدادات بيانات الهاتف والشبكة الخلوية 📶.", isUser = false, actionType = ActionType.WIFI)
        }

        if (containsAny(lower, "وضع الطيران", "airplane")) {
            if (wantsToggle && MorventAccessibilityService.isRunning) {
                val (ok, msg) = MorventAccessibilityService.performQuickSettingTileToggle("وضع الطيران|Airplane mode", collapseAfter = true)
                if (ok) return ChatMessage(msg, isUser = false, actionType = ActionType.WIFI)
            }
            controller.openSettings(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            return ChatMessage("تم فتح إعدادات وضع الطيران بالنظام ✈️.", isUser = false, actionType = ActionType.DEVICE_INFO)
        }

        if (containsAny(lower, "واي فاي", "وايفاي", "wifi", "wi-fi")) {
            if (wantsToggle && MorventAccessibilityService.isRunning) {
                val (ok, msg) = MorventAccessibilityService.performQuickSettingTileToggle("Wi-Fi|Wi‑Fi|الواي فاي|الإنترنت|Internet", collapseAfter = true)
                if (ok) return ChatMessage(msg, isUser = false, actionType = ActionType.WIFI)
            }
            controller.openSettings(Settings.ACTION_WIFI_SETTINGS)
            return ChatMessage(
                text = "تم فتح شاشة إعدادات شبكات Wi-Fi لإدارتها والاتصال 📶.",
                isUser = false,
                actionType = ActionType.WIFI
            )
        }

        if (containsAny(lower, "بلوتوث", "bluetooth")) {
            if (wantsToggle && MorventAccessibilityService.isRunning) {
                val (ok, msg) = MorventAccessibilityService.performQuickSettingTileToggle("البلوتوث|Bluetooth", collapseAfter = true)
                if (ok) return ChatMessage(msg, isUser = false, actionType = ActionType.BLUETOOTH)
            }
            controller.openSettings(Settings.ACTION_BLUETOOTH_SETTINGS)
            return ChatMessage(
                text = "تم فتح شاشة إعدادات البلوتوث لربط الأجهزة أو التبديل ᛒ.",
                isUser = false,
                actionType = ActionType.BLUETOOTH
            )
        }

        if (containsAny(lower, "مطور", "developer")) {
            controller.openSpecificSettings("developer")
            return ChatMessage("تم فتح خيارات المطور في النظام 🛠️.", isUser = false, actionType = ActionType.DEVICE_INFO)
        }

        if (lower.contains("vpn")) {
            controller.openSpecificSettings("vpn")
            return ChatMessage("تم فتح إعدادات شبكات VPN بالنظام 🔒.", isUser = false, actionType = ActionType.WIFI)
        }

        if (lower.contains("nfc")) {
            controller.openSpecificSettings("nfc")
            return ChatMessage("تم فتح إعدادات NFC بالنظام 💳.", isUser = false, actionType = ActionType.DEVICE_INFO)
        }

        if (containsAny(lower, "إعدادات تطبيق", "اعدادات تطبيق", "معلومات تطبيق", "أذونات تطبيق", "اذونات تطبيق", "صلاحيات تطبيق")) {
            val appName = trimmed.replace(
                Regex("(?i).*(?:إعدادات تطبيق|اعدادات تطبيق|معلومات تطبيق|أذونات تطبيق|اذونات تطبيق|صلاحيات تطبيق)\\s*"),
                ""
            ).trim()
            val (ok, msg) = controller.openAppDetails(appName)
            return ChatMessage(msg, isUser = false, status = if (ok) MessageStatus.SUCCESS else MessageStatus.ERROR, actionType = ActionType.DEVICE_INFO)
        }

        if (containsAny(lower, "تحديثات النظام", "حول الهاتف")) {
            controller.openSpecificSettings("system_update")
            return ChatMessage("تم فتح صفحة معلومات النظام والتحديثات ⚙️.", isUser = false, actionType = ActionType.DEVICE_INFO)
        }

        if (containsAny(lower, "التاريخ والوقت")) {
            controller.openSpecificSettings("date")
            return ChatMessage("تم فتح إعدادات التاريخ والوقت بالنظام 🕒.", isUser = false, actionType = ActionType.DEVICE_INFO)
        }

        if (containsAny(lower, "إعدادات المساعد", "اعدادات المساعد")) {
            controller.openSpecificSettings("assistant")
            return ChatMessage("تم فتح إعدادات المساعد الرقمي والإدخال الصوتي بالنظام 🎙️.", isUser = false, actionType = ActionType.DEVICE_INFO)
        }

        if (containsAny(lower, "إعدادات الخصوصية", "اعدادات الخصوصية")) {
            controller.openSpecificSettings("privacy")
            return ChatMessage("تم فتح إعدادات الخصوصية بالنظام 🛡️.", isUser = false, actionType = ActionType.DEVICE_INFO)
        }

        if (containsAny(lower, "إعدادات المشغل الافتراضي", "اعدادات اللانشر الافتراضي")) {
            controller.openSpecificSettings("home_app")
            return ChatMessage("تم فتح إعدادات الشاشة الرئيسية والمشغّل الافتراضي 🏠.", isUser = false, actionType = ActionType.DEVICE_INFO)
        }

        if (containsAny(lower, "في الخرائط", "خرائط عن", "افتح خريطة")) {
            val mapQ = trimmed.replace(Regex("(?i).*(?:ابحث في الخرائط عن|في الخرائط عن|في الخرائط|خرائط عن|افتح خريطة)\\s*"), "").trim()
            val ok = controller.openMapsSearch(mapQ)
            return ChatMessage(
                text = if (ok) "تم فتح الخرائط للبحث عن '$mapQ' 🗺️." else "تعذر فتح تطبيق الخرائط.",
                isUser = false,
                status = if (ok) MessageStatus.SUCCESS else MessageStatus.ERROR,
                actionType = ActionType.WEB_SEARCH
            )
        }

        if (containsAny(lower, "صور فيديو", "سجل فيديو")) {
            val ok = controller.openCamera(videoMode = true)
            return ChatMessage("تم فتح كاميرا النظام على وضع تصوير الفيديو 🎥.", isUser = false, status = if (ok) MessageStatus.SUCCESS else MessageStatus.ERROR, actionType = ActionType.LAUNCH_APP)
        }

        if (containsAny(lower, "التقط صورة", "صور بالكاميرا")) {
            val ok = controller.openCamera(videoMode = false)
            return ChatMessage("تم فتح كاميرا النظام للتصوير الفوتوغرافي 📸.", isUser = false, status = if (ok) MessageStatus.SUCCESS else MessageStatus.ERROR, actionType = ActionType.LAUNCH_APP)
        }

        if (containsAny(lower, "مدير الملفات", "التنزيلات", "افتح الملفات") || lower == "الملفات") {
            val ok = controller.openFileManager()
            return ChatMessage("تم فتح مدير الملفات والتنزيلات بالنظام 📁.", isUser = false, status = if (ok) MessageStatus.SUCCESS else MessageStatus.ERROR, actionType = ActionType.DEVICE_INFO)
        }

        if (containsAny(lower, "سطوع", "شاشة", "السطوع", "brightness", "display")) {
            controller.openSettings(Settings.ACTION_DISPLAY_SETTINGS)
            return ChatMessage(
                text = "تم فتح إعدادات الشاشة والسطوع لضبط الراحة البصرية ☀️.",
                isUser = false,
                actionType = ActionType.BRIGHTNESS
            )
        }

        if (containsAny(lower, "اعدادات", "الإعدادات", "settings")) {
            controller.openSettings(Settings.ACTION_SETTINGS)
            return ChatMessage(
                text = "تم فتح تطبيق الإعدادات العام للهاتف ⚙️.",
                isUser = false,
                actionType = ActionType.DEVICE_INFO
            )
        }

        // ========================================================
        // 12. ALARM & TIMER
        // ========================================================
        if (containsAny(lower, "منبه", "alarm", "صحيني")) {
            val nums = Regex("\\d+").findAll(lower).map { it.value.toInt() }.toList()
            val hour = if (nums.isNotEmpty()) nums[0].coerceIn(0, 23) else 7
            val minute = if (nums.size > 1) nums[1].coerceIn(0, 59) else 0

            val success = controller.setAlarm(hour, minute, "منبه مساعد مورڤينت")
            return if (success) {
                ChatMessage(
                    text = "تم تجهيز المنبه على الساعة $hour:${if (minute < 10) "0$minute" else minute} ⏰.",
                    isUser = false,
                    actionType = ActionType.ALARM
                )
            } else {
                ChatMessage("تعذر الوصول لتطبيق المنبه.", isUser = false, status = MessageStatus.ERROR)
            }
        }

        if (containsAny(lower, "مؤقت", "timer")) {
            val nums = Regex("\\d+").findAll(lower).map { it.value.toInt() }.toList()
            val minutes = if (nums.isNotEmpty()) nums[0] else 5
            val seconds = minutes * 60
            val success = controller.setTimer(seconds, "مؤقت مورڤينت $minutes دقيقة")
            return if (success) {
                ChatMessage("تم تشغيل مؤقت زمني لمدة $minutes دقيقة ⏱️.", isUser = false, actionType = ActionType.TIMER)
            } else {
                ChatMessage("تعذر تفعيل المؤقت الزمني.", isUser = false, status = MessageStatus.ERROR)
            }
        }

        // ========================================================
        // 13. CALL & SMS & WHATSAPP
        // ========================================================
        if (containsAny(lower, "اتصل", "اتصال", "call", "رن على", "خابر")) {
            val phone = extractPhoneNumber(lower)
            return if (phone.isNotEmpty()) {
                val ok = controller.dialNumber(phone)
                if (ok) ChatMessage("تم فتح واجهة الاتصال برقم $phone 📞.", isUser = false, actionType = ActionType.PHONE_CALL)
                else ChatMessage("تعذر بدء الاتصال بالرقم.", isUser = false, status = MessageStatus.ERROR)
            } else {
                ChatMessage(
                    text = "يرجى تحديد الرقم الذي ترغب بالاتصال به. مثال: 'اتصل بـ 0501234567'",
                    isUser = false,
                    actionType = ActionType.PHONE_CALL
                )
            }
        }

        if (containsAny(lower, "رسالة", "sms", "ابعت رسالة", "ارسل رسالة")) {
            val phone = extractPhoneNumber(lower)
            val msgBody = extractMessageBody(trimmed)
            if (phone.isNotEmpty()) {
                controller.composeSms(phone, msgBody)
                return ChatMessage(
                    text = "تم فتح منشئ الرسائل النصية للرقم $phone ✉️.",
                    isUser = false,
                    actionType = ActionType.SEND_SMS
                )
            } else {
                return ChatMessage(
                    text = "لإرسال رسالة، حدد الرقم والرسالة. مثال: 'ارسل رسالة لـ 0551234567 محتواها مرحبا بك'",
                    isUser = false,
                    actionType = ActionType.SEND_SMS
                )
            }
        }

        if (containsAny(lower, "واتساب", "whatsapp", "واتس")) {
            val phone = extractPhoneNumber(lower)
            controller.openWhatsAppChat(if (phone.isNotEmpty()) phone else null, "مرحباً من مورڤينت")
            return ChatMessage("تم فتح تطبيق واتساب لبدء المحادثة 💬.", isUser = false, actionType = ActionType.WHATSAPP)
        }

        // ========================================================
        // 14. AUTOMATED SCENARIOS / MACROS
        // ========================================================
        if (containsAny(lower, "وضع النوم", "تجهيز النوم", "sleep mode", "وقت النوم")) {
            delay(150)
            controller.setRingerMode(0)
            controller.setMediaVolumePercent(0)
            controller.toggleFlashlight(false)
            val executedSteps = listOf(
                "كتم صوت الهاتف وتفعيل الوضع الصامت 🔇",
                "إطفاء كشاف الهاتف في حال كان مشتعلاً 🌑",
                "تجهيز بيئة هادئة لحفظ الطاقة والاسترخاء"
            )
            return ChatMessage(
                text = "تم تفعيل 'وضع النوم' الشامل بنجاح 🌙. ليلة هادئة!",
                isUser = false,
                actionType = ActionType.MACRO_SCENARIO,
                cardData = ActionCardData.MacroResultCard("وضع النوم الهادئ", executedSteps),
                subSteps = executedSteps
            )
        }

        if (containsAny(lower, "وضع الاجتماع", "وضع العمل", "meeting mode", "work mode")) {
            controller.setRingerMode(1)
            controller.setMediaVolumePercent(0)
            val executedSteps = listOf(
                "تحويل رنين الهاتف إلى وضع الاهتزاز 📳",
                "كتم أصوات وسائط الألعاب والتطبيقات 🔇",
                "حفظ التركيز ومنع المقاطعات الصوتية"
            )
            return ChatMessage(
                text = "تم تفعيل 'وضع العمل والاجتماعات' 💼.",
                isUser = false,
                actionType = ActionType.MACRO_SCENARIO,
                cardData = ActionCardData.MacroResultCard("وضع الاجتماعات", executedSteps),
                subSteps = executedSteps
            )
        }

        if (containsAny(lower, "توفير الطاقة", "توفير البطارية", "power save", "battery save")) {
            controller.toggleFlashlight(false)
            val battery = controller.getBatteryDetails()
            val executedSteps = listOf(
                "التأكد من إطفاء الكشاف والمستشعرات النشطة ⚡",
                "فحص صحة البطارية الحالية: ${battery.percentage}%",
                "فتح إعدادات الشاشة لتقليل استهلاك السطوع"
            )
            return ChatMessage(
                text = "تم تشغيل بروتوكول توفير الطاقة الأقصى 🔋.",
                isUser = false,
                actionType = ActionType.MACRO_SCENARIO,
                cardData = ActionCardData.MacroResultCard("توفير الطاقة الأقصى", executedSteps),
                subSteps = executedSteps
            )
        }

        if (containsAny(lower, "طوارئ", "وضع الطوارئ", "emergency")) {
            controller.toggleFlashlight(true)
            controller.setMediaVolumePercent(100)
            controller.setRingerMode(2)
            val executedSteps = listOf(
                "تشغيل إضاءة الكشاف القصوى للرؤية والإشارة 💡",
                "رفع مستوى الصوت إلى 100% للتنبيه 🔊",
                "تجهيز مفكرة الاتصال برقم الطوارئ"
            )
            return ChatMessage(
                text = "تم تفعيل وضع الطوارئ فوراً 🚨. الكشاف مشتعل والصوت على أقصى درجة.",
                isUser = false,
                actionType = ActionType.MACRO_SCENARIO,
                cardData = ActionCardData.MacroResultCard("وضع الطوارئ الفوري", executedSteps),
                subSteps = executedSteps
            )
        }

        // ========================================================
        // 15. LAUNCH APPS
        // ========================================================
        if (containsAny(lower, "افتح", "شغل", "open", "launch")) {
            val appTarget = extractAppTarget(lower)
            if (appTarget.isNotEmpty()) {
                val (success, message) = controller.launchAppByQuery(appTarget)
                return if (success) {
                    ChatMessage(
                        text = message,
                        isUser = false,
                        actionType = ActionType.LAUNCH_APP,
                        cardData = ActionCardData.AppLaunchedCard(appTarget, "")
                    )
                } else {
                    ChatMessage(text = message, isUser = false, status = MessageStatus.ERROR)
                }
            }
        }

        // ========================================================
        // 16. WEB SEARCH
        // ========================================================
        if (containsAny(lower, "ابحث عن", "ابحث في جوجل", "search for", "google")) {
            val q = lower.replace(Regex("(ابحث عن|ابحث في جوجل عن|ابحث|search for|google)"), "").trim()
            if (q.isNotEmpty()) {
                controller.searchWeb(q)
                return ChatMessage("جارٍ البحث في جوجل عن '$q' 🔍...", isUser = false, actionType = ActionType.WEB_SEARCH)
            }
        }

        // ========================================================
        // 17. GREETING & GENERAL HELP
        // ========================================================
        if (containsAny(lower, "من انت", "من أنت", "who are you", "مورفنت", "مورڤينت", "morvent", "مساعدة", "اوامر", "أوامر", "help")) {
            return ChatMessage(
                text = "أنا **مورڤينت (Morvent)**، مساعد الذكاء الاصطناعي والتحكم الشامل بالهاتف والتطبيقات المستوحى من نموذج Artemis.\n\n" +
                        "📱 **قدراتي الكاملة للتحكم في كافة التطبيقات:**\n" +
                        "• **التحكم داخل أي تطبيق:** 'اضغط على [اسم الزر]' أو 'انقر على [بحث/تسجيل/متابعة]'.\n" +
                        "• **الكتابة داخل التطبيقات:** 'اكتب [البريد/النص] في حقل [اسم الحقل]'.\n" +
                        "• **فتح الحسابات والمصادقة:** 'افتح حساب في [اسم التطبيق]' أو 'تسجيل الدخول بواسطة جوجل'.\n" +
                        "• **تصفح التطبيقات:** 'انزل لأسفل' أو 'مرر لأعلى' للتصفح والتمرير التلقائي.\n" +
                        "• **فحص الشاشة الحية:** 'افحص الشاشة' لتحليل وقراءة جميع الأزرار والحقول المتاحة في التطبيق المفتوح.\n" +
                        "• **أوامر مركبة:** 'افتح الإعدادات واضغط على الصوت'.\n" +
                        "• **العتاد والإضاءة:** تشغيل وإطفاء الكشاف، التحكم بالصوت، فحص البطارية والذاكرة.\n" +
                        "• **إيماءات النظام:** الرجوع للخلف، الشاشة الرئيسية، التطبيقات الأخيرة، لقطة شاشة.\n\n" +
                        "اطلب أي أمر تريده وسأنفذه في الحال!",
                isUser = false,
                actionType = ActionType.HELP
            )
        }

        // ========================================================
        // 18. FALLBACK TO APP LAUNCH OR GRACEFUL HANDLING
        // ========================================================
        val words = lower.split(" ").filter { it.isNotBlank() }
        if (words.size in 1..3) {
            val (success, message) = controller.launchAppByQuery(lower)
            if (success) {
                return ChatMessage(message, isUser = false, actionType = ActionType.LAUNCH_APP)
            }
        }

        return ChatMessage(
            text = "فهمت رغبتك: \"$userInput\". يمكنك تفعيل العمل المستقل ليقوم وكيل الذكاء الاصطناعي بتنفيذ المهمة خطوة بخطوة.",
            isUser = false,
            actionType = ActionType.HELP,
            actionDetail = "ROUTE_TO_ARTEMIS_AI",
            subSteps = listOf(
                "تحليل الطلب بواسطة محرك مورڤينت",
                "تحويل المهمة إلى وكيل Artemis متعدد الوسائط"
            )
        )
    }

    private fun createAccessibilityDisabledMessage(actionName: String): ChatMessage {
        val isRunning = MorventAccessibilityService.isRunning
        return if (!isRunning) {
            ChatMessage(
                text = "لتنفيذ $actionName والتحكم داخل التطبيقات، يرجى تفعيل 'خدمة مورڤينت' في إمكانية الوصول (Accessibility) بالنظام.",
                isUser = false,
                status = MessageStatus.INFO,
                actionType = ActionType.ACCESSIBILITY_NAV,
                actionDetail = "REQUEST_ACCESSIBILITY_PERMISSION"
            )
        } else {
            ChatMessage("تم إرسال الأمر للنظام بنجاح.", isUser = false, actionType = ActionType.ACCESSIBILITY_NAV)
        }
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

    private fun containsAny(text: String, vararg keywords: String): Boolean {
        for (kw in keywords) {
            if (text.contains(kw, ignoreCase = true)) return true
        }
        return false
    }

    private fun extractPhoneNumber(text: String): String {
        val match = Regex("(\\+?\\d[\\d\\s\\-]{6,15}\\d)").find(text)
        return match?.value?.replace(" ", "")?.replace("-", "") ?: ""
    }

    private fun extractMessageBody(text: String): String {
        val keywords = listOf("محتواها", "محتوى", "نصها", "رسالة", "body", "message")
        for (kw in keywords) {
            val idx = text.indexOf(kw)
            if (idx != -1) {
                return text.substring(idx + kw.length).trim().removePrefix(":").trim()
            }
        }
        return "مرحباً من مورڤينت"
    }

    private fun extractAppTarget(text: String): String {
        return text.replace(Regex("^(افتح|شغل|ابدأ|open|launch)\\s+(تطبيق|برنامج|app)?"), "").trim()
    }

    private fun extractTypingTargetAndText(text: String): Pair<String?, String> {
        val clean = text.replace(Regex("^(اكتب|ادخل|أدخل|type|enter)\\s*"), "").trim()
        val inFieldRegex = Regex("(?i)(.*)\\s+(في حقل|في|into field|into)\\s+(.*)")
        val match = inFieldRegex.find(clean)
        return if (match != null) {
            val textToType = match.groupValues[1].trim()
            val field = match.groupValues[3].trim()
            Pair(field, textToType)
        } else {
            Pair(null, clean)
        }
    }

    private fun extractAppFromAccountCommand(text: String): String {
        val match = Regex("(?i)(في تطبيق|في|in app|in)\\s+([a-zA-Z\u0600-\u06FF\\s]+)").find(text)
        return match?.groupValues?.getOrNull(2)?.trim() ?: ""
    }
}
