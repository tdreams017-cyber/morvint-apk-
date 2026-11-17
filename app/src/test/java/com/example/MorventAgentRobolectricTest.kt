package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.device.DeviceController
import com.example.model.ActionCardData
import com.example.model.ActionType
import com.example.model.ChatMessage
import com.example.model.MessageStatus
import com.example.nlp.AgentExecutionState
import com.example.nlp.ArtemisAgentEngine
import com.example.nlp.MorventAgentHub
import com.example.nlp.MorventCommandEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MorventAgentRobolectricTest {

    private lateinit var context: Context
    private lateinit var controller: DeviceController
    private lateinit var artemisEngine: ArtemisAgentEngine
    private lateinit var commandEngine: MorventCommandEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        controller = DeviceController(context)
        artemisEngine = ArtemisAgentEngine(context, controller)
        commandEngine = MorventCommandEngine(context, controller, artemisEngine)
    }

    @Test
    fun `planGoal splits multi-clause Arabic chained commands accurately`() {
        val steps = artemisEngine.planGoal("افتح يوتيوب ثم ابحث عن أخبار التقنية ثم انزل لأسفل")

        val actionTypes = steps.map { it.actionType }
        assertTrue(actionTypes.contains("LAUNCH_APP"))
        assertTrue(actionTypes.contains("WAIT_SETTLE"))
        assertTrue(actionTypes.contains("CLICK_TEXT"))
        assertTrue(actionTypes.contains("TYPE_TEXT"))
        assertTrue(actionTypes.contains("HUMAN_SWIPE_DOWN"))
        assertTrue(actionTypes.contains("INSPECT_SCREEN"))

        val launchStep = steps.first { it.actionType == "LAUNCH_APP" }
        assertEquals("يوتيوب", launchStep.target)

        val typeStep = steps.first { it.actionType == "TYPE_TEXT" }
        assertEquals("أخبار التقنية", typeStep.target)
    }

    @Test
    fun `planGoal splits natural Arabic action conjunctions and screen reading`() {
        val ytSteps = artemisEngine.planGoal("افتح يوتيوب وشغل قرآن كريم")
        assertTrue(ytSteps.any { it.actionType == "LAUNCH_APP" && it.target == "يوتيوب" })
        assertTrue(ytSteps.any { it.actionType == "SEARCH_AND_PLAY" && it.target == "قرآن كريم" })

        val darkSteps = artemisEngine.planGoal("افتح الإعدادات وفعل الوضع الداكن")
        assertTrue(darkSteps.any { it.actionType == "LAUNCH_APP" && it.target == "الإعدادات" })
        assertTrue(darkSteps.any { it.actionType == "SCROLL_AND_CLICK" && it.target.contains("الوضع الداكن") })

        val readSteps = artemisEngine.planGoal("اقرأ لي ما على الشاشة")
        assertEquals(1, readSteps.size)
        assertEquals("READ_SCREEN_LOCAL", readSteps.first().actionType)
    }

    @Test
    fun `planGoal detects autonomous account registration workflow`() {
        val steps = artemisEngine.planGoal("افتح حساب جديد في فيسبوك")
        val actionTypes = steps.map { it.actionType }

        assertTrue(actionTypes.contains("LAUNCH_APP"))
        assertTrue(actionTypes.contains("INSPECT_SCREEN"))
        assertTrue(actionTypes.contains("AUTH_WORKFLOW"))

        val authStep = steps.first { it.actionType == "AUTH_WORKFLOW" }
        assertEquals("register", authStep.target)
    }

    @Test
    fun `planGoal handles scroll-and-click, long-press, and horizontal swipes`() {
        val scrollClick = artemisEngine.planGoal("انزل واضغط على الخصوصية")
        assertEquals("SCROLL_AND_CLICK", scrollClick.first().actionType)
        assertEquals("الخصوصية", scrollClick.first().target)

        val longPress = artemisEngine.planGoal("ضغط مطول على الرسالة")
        assertEquals("LONG_CLICK", longPress.first().actionType)
        assertEquals("الرسالة", longPress.first().target)

        val swipeRight = artemisEngine.planGoal("اسحب لليمين")
        assertEquals("SWIPE_RIGHT", swipeRight.first().actionType)

        val swipeLeft = artemisEngine.planGoal("اسحب يسار")
        assertEquals("SWIPE_LEFT", swipeLeft.first().actionType)
    }

    @Test
    fun `commandEngine differentiates fast local commands from open-ended human AI goals`() {
        assertTrue(commandEngine.isFastDeterministicCommand("شغل الكشاف"))
        assertTrue(commandEngine.isFastDeterministicCommand("كم نسبة البطارية"))
        assertTrue(commandEngine.isFastDeterministicCommand("وضع النوم"))
        assertTrue(commandEngine.isFastDeterministicCommand("افتح واتساب"))

        // Open-ended / multi-step human commands must route to Gemini Artemis AI
        assertFalse(commandEngine.isFastDeterministicCommand("افتح يوتيوب وشغل قرآن كريم"))
        assertFalse(commandEngine.isFastDeterministicCommand("روح الإعدادات وفعل الوضع الداكن"))
        assertFalse(commandEngine.isFastDeterministicCommand("اقرأ لي الرسالة الأخيرة ولخصها"))
        assertFalse(commandEngine.isFastDeterministicCommand("افتح تطبيق المصرف ثم سجل الدخول"))
    }

    @Test
    fun `artemisEngine pacing, steering, and stop controls work cleanly`() {
        artemisEngine.setPacing(false)
        assertFalse(artemisEngine.status.value.isHumanPaced)

        artemisEngine.setPacing(true)
        assertTrue(artemisEngine.status.value.isHumanPaced)

        artemisEngine.steer("اضغط على الفيديو الثاني")
        assertTrue(artemisEngine.status.value.logs.any { it.contains("اضغط على الفيديو الثاني") })

        artemisEngine.stop()
        assertEquals(AgentExecutionState.IDLE, artemisEngine.status.value.state)
        assertFalse(artemisEngine.isBusy())
    }

    @Test
    fun `commandEngine processes battery, device info, help, sleep macro, and background overlay request`() = runBlocking {
        val batteryMsg = commandEngine.processCommand("كم نسبة البطارية")
        assertEquals(ActionType.BATTERY, batteryMsg.actionType)
        assertTrue(batteryMsg.cardData is ActionCardData.BatteryCard)

        val deviceMsg = commandEngine.processCommand("معلومات الجهاز")
        assertEquals(ActionType.DEVICE_INFO, deviceMsg.actionType)
        assertTrue(deviceMsg.cardData is ActionCardData.DeviceInfoCard)

        val helpMsg = commandEngine.processCommand("من أنت")
        assertEquals(ActionType.HELP, helpMsg.actionType)

        val sleepMsg = commandEngine.processCommand("تفعيل وضع النوم")
        assertEquals(ActionType.MACRO_SCENARIO, sleepMsg.actionType)
        assertTrue(sleepMsg.cardData is ActionCardData.MacroResultCard)

        val bgMsg = commandEngine.processCommand("اشتغل في الخلفية")
        assertEquals("REQUEST_OVERLAY_PERMISSION", bgMsg.actionDetail)
    }

    @Test
    fun `agentHub handles live steering commands cleanly`() {
        val hub = MorventAgentHub.getInstance(context)
        hub.setTtsEnabled(false)
        hub.clearChat()

        var callbackResult = ""
        hub.submitCommand("من أنت", sourceLabel = "الاختبار") { _, text ->
            callbackResult = text
        }
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        Thread.sleep(100)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertTrue(hub.messages.value.any { it.isUser && it.text == "من أنت" })
        assertTrue(callbackResult.contains("مورڤينت") || hub.messages.value.any { !it.isUser })

        var stopCalled = false
        hub.submitCommand("توقف", sourceLabel = "الفقاعة العائمة") { ok, _ ->
            stopCalled = ok
        }
        assertTrue(stopCalled)
        assertEquals(AgentExecutionState.IDLE, hub.artemisEngine.status.value.state)
    }

    @Test
    fun `planGoal decomposes long multi-line and numbered workflows into 10 plus steps`() {
        val longGoal = """
            1. افتح يوتيوب
            2. انتظر 3 ثواني
            3. ابحث عن البرمجة بلغة كوتلن
            4. اضغط على أول نتيجة
            5. انزل لأسفل 3 مرات
            6. افتح موقع https://developer.android.com
            7. انسخ ملخص البحث
            8. افتح الإعدادات ثم افتح إعدادات الواي فاي
            9. شغل الكشاف
            10. لقطة شاشة
        """.trimIndent()

        val steps = artemisEngine.planGoal(longGoal)
        val types = steps.map { it.actionType }

        assertTrue("Expected at least 12 expanded steps, got ${steps.size}", steps.size >= 12)
        assertTrue(types.contains("LAUNCH_APP"))
        assertTrue(types.contains("WAIT_SETTLE"))
        assertTrue(types.contains("CLICK_ORDINAL"))
        assertTrue(types.contains("OPEN_URL"))
        assertTrue(types.contains("COPY_TEXT"))
        assertTrue(types.contains("OPEN_SETTINGS"))
        assertTrue(types.contains("DEVICE_ACTION"))

        val waitStep = steps.first { it.actionType == "WAIT_SETTLE" && it.description.contains("3 ثوان") }
        assertEquals("3000", waitStep.target)

        val urlStep = steps.first { it.actionType == "OPEN_URL" }
        assertEquals("https://developer.android.com", urlStep.target)

        val copyStep = steps.first { it.actionType == "COPY_TEXT" }
        assertEquals("ملخص البحث", copyStep.target)

        val ordinalStep = steps.first { it.actionType == "CLICK_ORDINAL" }
        assertEquals("1", ordinalStep.target)
    }

    @Test
    fun `planGoal expands repeat multipliers cleanly`() {
        val steps = artemisEngine.planGoal("انزل لأسفل 3 مرات ثم اسحب لليسار مرتين")
        val swipeDownCount = steps.count { it.actionType == "HUMAN_SWIPE_DOWN" }
        val swipeLeftCount = steps.count { it.actionType == "SWIPE_LEFT" }

        assertEquals(6, swipeDownCount) // 2 HUMAN_SWIPE_DOWN per browse cycle x 3 repeats
        assertEquals(2, swipeLeftCount)
    }

    @Test
    fun `commandEngine routes long, numbered, punctuated, and repeated goals to Artemis`() {
        assertFalse(commandEngine.isFastDeterministicCommand("1. افتح كروم\n2. ابحث عن الأخبار\n3. اقرأ الشاشة"))
        assertFalse(commandEngine.isFastDeterministicCommand("افتح يوتيوب، ابحث عن كوتلن؛ انتظر 2 ثانية"))
        assertFalse(commandEngine.isFastDeterministicCommand("انزل لأسفل 4 مرات"))
        assertFalse(commandEngine.isFastDeterministicCommand("ارفع الصوت بعد ذلك افتح تطبيق الإعدادات وافحص الشاشة بالكامل وتأكد من النتيجة"))
    }

    @Test
    fun `artemisEngine appendGoal and skipCurrentStep manage dynamic long queues`() {
        val appended = artemisEngine.appendGoal("انتظر 2 ثانية ثم لقطة شاشة")
        assertEquals(2, appended.size)
        assertEquals(1, artemisEngine.status.value.queuedStepsCount)
        assertTrue(artemisEngine.status.value.logs.any { it.contains("إضافة خطوات جديدة") })

        artemisEngine.stop()
        assertEquals(0, artemisEngine.status.value.queuedStepsCount)
    }

    @Test
    fun `planGoal decomposes whole-OS system workflow accurately`() {
        val osWorkflow = """
            1. اضبط السطوع 85%
            2. فعل التدوير التلقائي
            3. فعل عدم الإزعاج
            4. اقرأ الإشعارات
            5. امسح الإشعارات
            6. تقسيم الشاشة
            7. قائمة الطاقة
            8. درج التطبيقات
            9. تبديل نقطة الاتصال
            10. اضبط صوت المنبه 90%
            11. افتح لوحة الإنترنت
        """.trimIndent()

        val steps = artemisEngine.planGoal(osWorkflow)
        val types = steps.map { it.actionType }

        assertTrue(types.contains("BRIGHTNESS_SET"))
        assertTrue(types.contains("AUTO_ROTATE_SET"))
        assertTrue(types.contains("DND_SET"))
        assertTrue(types.contains("READ_NOTIFICATIONS"))
        assertTrue(types.contains("CLEAR_NOTIFICATIONS"))
        assertTrue(types.contains("SYSTEM_GLOBAL"))
        assertTrue(types.contains("QS_TOGGLE"))
        assertTrue(types.contains("STREAM_VOLUME"))
        assertTrue(types.contains("SYSTEM_PANEL"))

        assertEquals("85", steps.first { it.actionType == "BRIGHTNESS_SET" }.target)
        assertEquals("true", steps.first { it.actionType == "AUTO_ROTATE_SET" }.target)
        assertEquals("true", steps.first { it.actionType == "DND_SET" }.target)
        assertEquals("alarm:90", steps.first { it.actionType == "STREAM_VOLUME" }.target)
        assertEquals("internet", steps.first { it.actionType == "SYSTEM_PANEL" }.target)

        val globals = steps.filter { it.actionType == "SYSTEM_GLOBAL" }.map { it.target }
        assertTrue(globals.containsAll(listOf("split_screen", "power_dialog", "all_apps")))
    }

    @Test
    fun `commandEngine handles whole-OS commands and routes multi-step OS workflows to Artemis`() = runBlocking {
        assertTrue(commandEngine.isFastDeterministicCommand("اضبط السطوع 80%"))
        assertTrue(commandEngine.isFastDeterministicCommand("تفعيل عدم الإزعاج"))
        assertTrue(commandEngine.isFastDeterministicCommand("تقسيم الشاشة"))
        assertTrue(commandEngine.isFastDeterministicCommand("اقرأ الإشعارات"))
        assertTrue(commandEngine.isFastDeterministicCommand("صوت المنبه 75%"))
        assertFalse(commandEngine.isFastDeterministicCommand("اضبط السطوع 80% ثم فعل عدم الإزعاج ثم افتح تقسيم الشاشة"))

        // Verify whole-OS brightness and auto-rotate flows cleanly
        val brightnessMsg = commandEngine.processCommand("اضبط السطوع 80%")
        if (controller.canWriteSystemSettings()) {
            assertEquals(ActionType.BRIGHTNESS, brightnessMsg.actionType)
            assertTrue(brightnessMsg.text.contains("80%"))
            assertEquals(80, controller.getScreenBrightnessPercent())
        } else {
            assertEquals("REQUEST_WRITE_SETTINGS_PERMISSION", brightnessMsg.actionDetail)
        }

        val autoRotateMsg = commandEngine.processCommand("تفعيل التدوير التلقائي")
        if (controller.canWriteSystemSettings()) {
            assertTrue(controller.isAutoRotateEnabled())
            assertTrue(autoRotateMsg.text.contains("التدوير التلقائي"))
        } else {
            assertEquals("REQUEST_WRITE_SETTINGS_PERMISSION", autoRotateMsg.actionDetail)
        }

        val dndMsg = commandEngine.processCommand("تفعيل عدم الإزعاج")
        assertTrue(
            dndMsg.actionDetail == "REQUEST_DND_PERMISSION" || controller.isDoNotDisturbEnabled()
        )

        // Multi-stream alarm volume executes deterministically without extra permission
        val alarmVolMsg = commandEngine.processCommand("صوت المنبه 90%")
        assertEquals(ActionType.VOLUME, alarmVolMsg.actionType)
        assertTrue(alarmVolMsg.text.contains("90%"))

        // Global accessibility action requests accessibility when service not running
        val splitMsg = commandEngine.processCommand("تقسيم الشاشة")
        assertEquals("REQUEST_ACCESSIBILITY_PERMISSION", splitMsg.actionDetail)

        // System settings & panel shortcuts launch cleanly
        val devMsg = commandEngine.processCommand("إعدادات المطور")
        assertEquals(ActionType.DEVICE_INFO, devMsg.actionType)
    }

    @Test
    fun `planGoal decomposes literal OS workflows including app details, permissions, maps, files, camera, and natural brightness`() {
        val workflow = """
            1. إعدادات تطبيق واتساب
            2. اضغط سماح
            3. امسح الحقل
            4. ابحث في الخرائط عن مطاعم قريبة
            5. التقط صورة بالكاميرا
            6. افتح مدير الملفات
            7. اضبط السطوع أقصى درجة
            8. ارفع صوت الرنين النصف
            9. افتح إعدادات التحديثات النظام
        """.trimIndent()

        val steps = artemisEngine.planGoal(workflow)
        val types = steps.map { it.actionType }

        assertTrue(types.contains("APP_DETAILS"))
        assertTrue(types.contains("PERMISSION_DIALOG"))
        assertTrue(types.contains("CLEAR_FIELD"))
        assertTrue(types.contains("OPEN_MAPS"))
        assertTrue(types.contains("OPEN_CAMERA"))
        assertTrue(types.contains("OPEN_FILES"))
        assertTrue(types.contains("BRIGHTNESS_SET"))
        assertTrue(types.contains("STREAM_VOLUME"))

        assertEquals("واتساب", steps.first { it.actionType == "APP_DETAILS" }.target)
        assertEquals("allow", steps.first { it.actionType == "PERMISSION_DIALOG" }.target)
        assertEquals("مطاعم قريبة", steps.first { it.actionType == "OPEN_MAPS" }.target)
        assertEquals("photo", steps.first { it.actionType == "OPEN_CAMERA" }.target)
        assertEquals("100", steps.first { it.actionType == "BRIGHTNESS_SET" }.target)
        assertEquals("ring:50", steps.first { it.actionType == "STREAM_VOLUME" }.target)

        // Cache clear expands into Storage & Cache + Clear Cache steps
        val cacheSteps = artemisEngine.planGoal("امسح كاش تطبيق يوتيوب")
        assertTrue(cacheSteps.any { it.actionType == "APP_DETAILS" && it.target == "يوتيوب" })
        assertTrue(cacheSteps.any { it.actionType == "SCROLL_AND_CLICK" && it.target.contains("Storage") })
        assertTrue(cacheSteps.any { it.actionType == "CLICK_TEXT" && it.target.contains("Clear cache") })
    }

    @Test
    fun `commandEngine handles new OS-wide commands deterministically`() = runBlocking {
        assertTrue(commandEngine.isFastDeterministicCommand("اضغط سماح"))
        assertTrue(commandEngine.isFastDeterministicCommand("امسح الحقل"))
        assertTrue(commandEngine.isFastDeterministicCommand("مدير الملفات"))
        assertTrue(commandEngine.isFastDeterministicCommand("تحديثات النظام"))
        assertTrue(commandEngine.isFastDeterministicCommand("صور فيديو"))

        val permMsg = commandEngine.processCommand("اضغط سماح")
        assertEquals("REQUEST_ACCESSIBILITY_PERMISSION", permMsg.actionDetail)

        val clearMsg = commandEngine.processCommand("امسح الحقل")
        assertEquals("REQUEST_ACCESSIBILITY_PERMISSION", clearMsg.actionDetail)

        val filesMsg = commandEngine.processCommand("مدير الملفات")
        assertEquals(ActionType.DEVICE_INFO, filesMsg.actionType)

        val updatesMsg = commandEngine.processCommand("تحديثات النظام")
        assertEquals(ActionType.DEVICE_INFO, updatesMsg.actionType)

        val camMsg = commandEngine.processCommand("صور فيديو")
        assertEquals(ActionType.LAUNCH_APP, camMsg.actionType)
    }

    @Test
    fun `chat responses handle greetings and questions smoothly without AppCheck errors`() = runBlocking {
        val brain = com.example.nlp.GeminiAgentBrain(context)
        val salamResponse = brain.getSmartLocalChatResponse("السلام عليكم")
        assertTrue(salamResponse != null && salamResponse.contains("وعليكم السلام ورحمة الله وبركاته"))

        val marhabaResponse = brain.getSmartLocalChatResponse("مرحبا")
        assertTrue(marhabaResponse != null && marhabaResponse.contains("أهلاً وسهلاً"))

        val whoResponse = brain.getSmartLocalChatResponse("من أنت")
        assertTrue(whoResponse != null && whoResponse.contains("مورڤينت"))

        // Even with network/AppCheck unavailable, answerGeneralChat returns a non-blank response without throwing
        val answer = brain.answerGeneralChat("السلام عليكم")
        assertTrue(answer.isNotBlank())
    }

    @Test
    fun `apiKeyManager stores, updates, selects model, and resets keys cleanly`() {
        val manager = com.example.ai.ApiKeyManager.getInstance(context)
        val defaultKey = manager.resetToDefaultKey()
        assertTrue(defaultKey.isNotBlank())

        manager.saveApiKey("AIzaSyCustomTestKey12345")
        assertEquals("AIzaSyCustomTestKey12345", manager.getEffectiveApiKey())

        manager.setSelectedModel("gemini-flash-lite-latest")
        assertEquals("gemini-flash-lite-latest", manager.getSelectedModelName())

        manager.saveApiKey("")
        assertEquals("", manager.getEffectiveApiKey())

        manager.resetToDefaultKey()
        assertEquals(defaultKey, manager.getEffectiveApiKey())
        manager.setSelectedModel(com.example.ai.ApiKeyManager.DEFAULT_MODEL)
    }

    @Test
    fun `systemBridge inspects capabilities, builds ADB grant script, and gates secure kernel writes`() {
        val bridge = com.example.device.SystemBridgeManager(context)
        val caps = bridge.inspectCapabilities(controller)
        assertTrue(caps.totalCount >= 9)

        val adbCmd = bridge.getAdbGrantCommand()
        assertTrue(adbCmd.contains("WRITE_SECURE_SETTINGS"))
        assertTrue(adbCmd.contains("enabled_accessibility_services"))
        assertTrue(adbCmd.contains("allow_listener"))

        val secureRes = bridge.applySecureSystemToggle("battery_saver", true)
        assertFalse(secureRes.first)
        assertEquals("NEED_WRITE_SECURE_SETTINGS", secureRes.second)
    }

    @Test
    fun `commandEngine handles silent execution, note files, WhatsApp listener prompts, calls, and speakerphone`() = runBlocking {
        val keyMgr = com.example.ai.ApiKeyManager.getInstance(context)
        val silentOn = commandEngine.processCommand("تفعيل التنفيذ الصامت")
        assertTrue(silentOn.text.contains("التنفيذ التلقائي الصامت"))
        assertTrue(keyMgr.silentExecutionMode.value)

        val waRead = commandEngine.processCommand("اقرأ رسائل واتساب")
        assertEquals("REQUEST_NOTIFICATION_LISTENER", waRead.actionDetail)

        val waReply = commandEngine.processCommand("رد على أحمد بـ سأتواصل معك قريباً")
        assertEquals("REQUEST_NOTIFICATION_LISTENER", waReply.actionDetail)

        val noteSave = commandEngine.processCommand("احفظ ملاحظة موعد الاجتماع الساعة 5")
        assertTrue(noteSave.text.contains("تم حفظ الملف"))

        val fileList = commandEngine.processCommand("عرض الملفات")
        assertTrue(fileList.subSteps.isNotEmpty())

        val bridgeReport = commandEngine.processCommand("جسر النظام")
        assertEquals(ActionType.DIAGNOSTICS, bridgeReport.actionType)
        assertTrue(bridgeReport.text.contains("adb shell"))

        val speakerRes = commandEngine.processCommand("شغل السبيكر")
        assertEquals(ActionType.PHONE_CALL, speakerRes.actionType)

        val answerRes = commandEngine.processCommand("رد على المكالمة")
        assertEquals("REQUEST_ACCESSIBILITY_PERMISSION", answerRes.actionDetail)

        val endRes = commandEngine.processCommand("اقفل المكالمة")
        assertEquals("REQUEST_ACCESSIBILITY_PERMISSION", endRes.actionDetail)
    }

    @Test
    fun `planGoal decomposes hardware-level call, speaker, WhatsApp read, and silent reply steps`() {
        val workflow = """
            1. اقرأ رسائل واتساب
            2. رد على خالد بـ تم استلام الرسالة
            3. اتصل بـ 0501234567
            4. شغل السبيكر
            5. اقفل المكالمة
            6. احفظ ملاحظة مكالمة خالد تمت
        """.trimIndent()

        val steps = artemisEngine.planGoal(workflow)
        val types = steps.map { it.actionType }

        assertTrue(types.contains("READ_MESSAGES"))
        assertTrue(types.contains("SILENT_REPLY"))
        assertTrue(types.contains("CALL_PLACE"))
        assertTrue(types.contains("SPEAKER_SET"))
        assertTrue(types.contains("CALL_END"))
        assertTrue(types.contains("SAVE_NOTE"))
    }

    @Test
    fun `deep ui normalization, batch decision parsing, and deep screen reading work cleanly`() = runBlocking {
        // Arabic normalization handles hamza, teh marbuta, alef maqsura, tashkeel, and tatweel
        val norm = com.example.service.MorventAccessibilityService.Companion
        assertEquals(
            norm.normalizeArabicForMatch("إرسالًـ"),
            norm.normalizeArabicForMatch("ارسال")
        )
        assertEquals(
            norm.normalizeArabicForMatch("الإعداداتُ المتقدمة"),
            norm.normalizeArabicForMatch("الاعدادات المتقدمه")
        )
        assertEquals(
            norm.normalizeArabicForMatch("مفعلّ"),
            norm.normalizeArabicForMatch("مفعل")
        )

        // Disabled-service guards return clean Arabic diagnostics without crashing
        val deepReadOff = norm.deepReadComplexScreen()
        assertFalse(deepReadOff.first)
        assertTrue(deepReadOff.second.contains("إمكانية الوصول"))

        val smartFindOff = norm.findAndClickSmart("الوضع الداكن")
        assertFalse(smartFindOff.first)
        assertTrue(smartFindOff.second.contains("إمكانية الوصول"))

        // GeminiAgentBrain parses SMART_FIND_CLICK, DEEP_READ_SCREEN, and multi-action batch steps
        val brain = com.example.nlp.GeminiAgentBrain(context)
        val batchJson = """
            {
              "thought": "الضغط على البحث ثم الكتابة والإرسال فوراً",
              "statusLabel": "بحث سريع",
              "action": "TAP_ELEMENT",
              "elementId": 3,
              "steps": [
                {"action": "TAP_ELEMENT", "elementId": 3, "x": 500, "y": 120},
                {"action": "TYPE_TEXT", "text": "قرآن كريم", "submitEnter": true},
                {"action": "SMART_FIND_CLICK", "target": "أول نتيجة"}
              ]
            }
        """.trimIndent()
        val parsed = brain.parseDecisionJson(batchJson)
        assertTrue(parsed is com.example.nlp.BrainResult.Success)
        val decision = (parsed as com.example.nlp.BrainResult.Success).decision
        assertEquals(3, decision.steps.size)
        assertEquals(com.example.nlp.AgentActionKind.TAP_ELEMENT, decision.steps[0].action)
        assertEquals(com.example.nlp.AgentActionKind.TYPE_TEXT, decision.steps[1].action)
        assertTrue(decision.steps[1].submitEnter)
        assertEquals(com.example.nlp.AgentActionKind.SMART_FIND_CLICK, decision.steps[2].action)
        assertEquals("أول نتيجة", decision.steps[2].target)

        // Planner & command engine deep screen read routes cleanly
        assertTrue(commandEngine.isFastDeterministicCommand("اقرأ الشاشة بعمق"))
        val deepCmdRes = commandEngine.processCommand("اقرأ الشاشة بعمق")
        assertEquals("REQUEST_ACCESSIBILITY_PERMISSION", deepCmdRes.actionDetail)

        val deepSteps = artemisEngine.planGoal("اقرأ الشاشة بعمق")
        assertEquals("DEEP_READ_SCREEN_LOCAL", deepSteps.first().actionType)

        val quickReadSteps = artemisEngine.planGoal("اقرأ لي ما على الشاشة")
        assertEquals("READ_SCREEN_LOCAL", quickReadSteps.first().actionType)
    }

    @Test
    fun `hydrogen model, custom instructions, and strict obedience mode persist and inject into brain prompts`() {
        val keyMgr = com.example.ai.ApiKeyManager.getInstance(context)
        assertTrue(com.example.ai.ApiKeyManager.SUPPORTED_MODELS.contains(com.example.ai.ApiKeyManager.MODEL_HYDROGEN))
        assertTrue(com.example.ai.ApiKeyManager.modelDisplayLabel(com.example.ai.ApiKeyManager.MODEL_HYDROGEN).contains("هيدروجين"))

        keyMgr.setSelectedModel(com.example.ai.ApiKeyManager.MODEL_HYDROGEN)
        assertEquals("gemini-4-hydrogen", keyMgr.getSelectedModelName())

        keyMgr.setCustomInstructions("أجب دائماً باللغة العربية وبأقصى سرعة")
        assertEquals("أجب دائماً باللغة العربية وبأقصى سرعة", keyMgr.getCustomInstructions())

        keyMgr.setStrictObedienceMode(true)
        assertTrue(keyMgr.strictObedienceMode.value)

        val learner = com.example.ai.UserLearningManager.getInstance(context)
        learner.teachRule("أسلوب المستخدم: مقتضب")

        val brain = com.example.nlp.GeminiAgentBrain(context)
        val chatPrompt = brain.buildEffectiveChatPrompt(
            systemContextSnapshot = "البطارية: 90%"
        )
        assertTrue(chatPrompt.contains("أجب دائماً باللغة العربية وبأقصى سرعة"))
        assertTrue(chatPrompt.contains("أسلوب المستخدم: مقتضب"))
        assertTrue(chatPrompt.contains("البطارية: 90%"))

        val agentPrompt = brain.buildEffectiveAgentPrompt("لقطة النظام: البطارية: 90%")
        assertTrue(agentPrompt.contains("لقطة النظام: البطارية: 90%"))
        assertTrue(agentPrompt.contains("LINUX_EXEC"))

        // Clean up model preference and learning rule
        keyMgr.setSelectedModel(com.example.ai.ApiKeyManager.DEFAULT_MODEL)
        learner.clearLearnedData()
    }

    @Test
    fun `userLearningManager teaches rules, records commands, and produces pre-typing and live predictions`() {
        val learner = com.example.ai.UserLearningManager.getInstance(context)
        learner.clearLearnedData()

        val rule = learner.teachRule("استخدم يوتيوب دائماً للمقاطع")
        assertNotNull(rule)
        assertEquals(1, learner.learnedRules.value.size)

        learner.recordUserCommand("افتح يوتيوب وشغل قرآن كريم", targetAppName = "YouTube")
        learner.recordUserCommand("افتح يوتيوب وشغل قرآن كريم", targetAppName = "YouTube")
        learner.recordUserCommand("لينكس: ls -la")

        val preTyping = learner.getPredictiveSuggestions(prefix = "", limit = 5)
        assertTrue(preTyping.isNotEmpty())
        assertTrue(preTyping.any { it.contains("افتح يوتيوب") })

        val liveTyping = learner.getPredictiveSuggestions(prefix = "لين", limit = 5)
        assertTrue(liveTyping.any { it.startsWith("لين") })

        val promptCtx = learner.buildLearningPromptContext()
        assertTrue(promptCtx.contains("استخدم يوتيوب دائماً للمقاطع"))

        learner.removeRule(rule!!.id)
        assertTrue(learner.learnedRules.value.isEmpty())
    }

    @Test
    fun `chatHistoryManager creates, saves, switches, and deletes multi-session history`() {
        val history = com.example.ai.ChatHistoryManager.getInstance(context)
        val s1 = history.createNewSession(
            listOf(ChatMessage(text = "أهلاً من الجلسة الأولى", isUser = true))
        )
        val s2 = history.createNewSession(
            listOf(ChatMessage(text = "اختبار الجلسة الثانية", isUser = true))
        )

        assertTrue(history.sessions.value.any { it.id == s1 })
        assertTrue(history.sessions.value.any { it.id == s2 })

        val loaded1 = history.switchSession(s1)
        assertEquals(1, loaded1.size)
        assertEquals("أهلاً من الجلسة الأولى", loaded1.first().text)

        history.deleteSession(s2)
        assertFalse(history.sessions.value.any { it.id == s2 })
    }

    @Test
    fun `linuxSandboxManager executes shell, cd, calc, json, and morvent-info commands`() = runBlocking {
        val sandbox = com.example.device.LinuxSandboxManager.getInstance(context)
        assertTrue(sandbox.uploadsDir.exists())

        val pwdRes = sandbox.executeCommand("pwd")
        assertEquals(0, pwdRes.exitCode)
        assertTrue(pwdRes.output.contains("morvent_linux"))

        val calcRes = sandbox.executeCommand("calc (25*4)+15")
        assertEquals(0, calcRes.exitCode)
        assertEquals("115", calcRes.output)

        val jsonRes = sandbox.executeCommand("""json {"agent":"morvent","ok":true}""")
        assertEquals(0, jsonRes.exitCode)
        assertTrue(jsonRes.output.contains("morvent"))

        val echoRes = sandbox.executeCommand("echo hello_morvent_linux")
        assertEquals(0, echoRes.exitCode)
        assertTrue(echoRes.output.contains("hello_morvent_linux"))

        val mkdirRes = sandbox.executeCommand("mkdir -p workspace_test && cd workspace_test")
        assertEquals(0, mkdirRes.exitCode)

        val cdBack = sandbox.executeCommand("cd ~")
        assertEquals(0, cdBack.exitCode)
        assertEquals("~", cdBack.workingDir)
    }

    @Test
    fun `commandEngine routes linux terminal, user learning, and gemini brain parses LINUX_EXEC`() = runBlocking {
        assertTrue(commandEngine.isFastDeterministicCommand("لينكس: calc 12*12"))
        val linuxMsg = commandEngine.processCommand("لينكس: calc 12*12")
        assertEquals(ActionType.LINUX_TERMINAL, linuxMsg.actionType)
        assertTrue(linuxMsg.cardData is ActionCardData.LinuxCommandCard)
        assertEquals("144", (linuxMsg.cardData as ActionCardData.LinuxCommandCard).output)

        assertTrue(commandEngine.isFastDeterministicCommand("علم مورڤينت أجب باختصار دائماً"))
        val learnMsg = commandEngine.processCommand("علم مورڤينت أجب باختصار دائماً")
        assertEquals(MessageStatus.SUCCESS, learnMsg.status)
        assertTrue(learnMsg.text.contains("أجب باختصار دائماً"))

        assertTrue(commandEngine.isFastDeterministicCommand("سجل المكالمات"))
        val callLogMsg = commandEngine.processCommand("سجل المكالمات")
        assertEquals("REQUEST_EXPANDED_PERMISSIONS", callLogMsg.actionDetail)

        val brain = com.example.nlp.GeminiAgentBrain(context)
        val linuxDecisionJson = """
            {
              "thought": "تشغيل فحص لينكس في البيئة الخاصة",
              "statusLabel": "تشغيل لينكس",
              "action": "LINUX_EXEC",
              "target": "morvent-info",
              "steps": [
                {"action": "LINUX_EXEC", "text": "calc 10+20"}
              ]
            }
        """.trimIndent()
        val parsed = brain.parseDecisionJson(linuxDecisionJson)
        assertTrue(parsed is com.example.nlp.BrainResult.Success)
        val decision = (parsed as com.example.nlp.BrainResult.Success).decision
        assertEquals(com.example.nlp.AgentActionKind.LINUX_EXEC, decision.action)
        assertEquals("morvent-info", decision.target)
        assertEquals(com.example.nlp.AgentActionKind.LINUX_EXEC, decision.steps.first().action)
        assertEquals("calc 10+20", decision.steps.first().text)
    }
}
