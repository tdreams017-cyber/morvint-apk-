package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.model.ScreenInspectionReport
import com.example.model.UiElementInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

data class IndexedUiElement(
    val id: Int,
    val label: String,
    val viewId: String,
    val className: String,
    val bounds: Rect,
    val normX: Int,
    val normY: Int,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val isCheckable: Boolean,
    val isChecked: Boolean,
    val isFocused: Boolean,
    val isEnabled: Boolean = true,
    val isSelected: Boolean = false,
    val stateDescription: String = "",
    val windowType: String = "APP"
)

data class RichScreenSnapshot(
    val packageName: String = "",
    val activityName: String = "",
    val screenWidth: Int = 1080,
    val screenHeight: Int = 2400,
    val elements: List<IndexedUiElement> = emptyList(),
    val formattedTree: String = "",
    val visibleTexts: List<String> = emptyList(),
    val windowCount: Int = 1,
    val hasWebView: Boolean = false,
    val hasSparseTree: Boolean = false,
    val structuredSummary: String = ""
)

/**
 * Morvent Accessibility Service enables full human-level Android interaction:
 * multi-window UI tree perception, live screenshot capture (API 30+),
 * coordinate & element-ID clicking, double-tap, long-press, curved swipes,
 * drag-and-drop, two-finger pinch zoom, IME text submission, and global actions.
 */
class MorventAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceRef = WeakReference(this)
        Log.d(TAG, "MorventAccessibilityService connected successfully")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString()
        val now = System.currentTimeMillis()
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                lastUiMutationTimeMs.set(now)
                val cls = event.className?.toString()
                if (!pkg.isNullOrBlank() && pkg != packageName) {
                    _currentForegroundPackage.value = pkg
                }
                if (!cls.isNullOrBlank()) {
                    _currentForegroundActivity.value = cls
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                lastUiMutationTimeMs.set(now)
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                if (!pkg.isNullOrBlank() && pkg != packageName) {
                    val msg = event.text.mapNotNull { it?.toString()?.trim() }
                        .filter { it.isNotBlank() }
                        .joinToString(" - ")
                    if (msg.isNotBlank()) {
                        synchronized(recentNotificationsLock) {
                            val entry = "[$pkg] $msg"
                            recentCapturedNotifications.remove(entry)
                            recentCapturedNotifications.add(0, entry)
                            if (recentCapturedNotifications.size > 25) {
                                recentCapturedNotifications.removeAt(recentCapturedNotifications.lastIndex)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "MorventAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceRef?.get() == this) {
            serviceRef = null
        }
    }

    companion object {
        private const val TAG = "MorventA11y"
        private var serviceRef: WeakReference<MorventAccessibilityService>? = null

        private val _currentForegroundPackage = MutableStateFlow("")
        val currentForegroundPackage: StateFlow<String> = _currentForegroundPackage.asStateFlow()

        private val _currentForegroundActivity = MutableStateFlow("")
        val currentForegroundActivity: StateFlow<String> = _currentForegroundActivity.asStateFlow()

        private val indexedBoundsMap = ConcurrentHashMap<Int, Rect>()
        private val recentNotificationsLock = Any()
        private val recentCapturedNotifications = mutableListOf<String>()
        private val lastUiMutationTimeMs = java.util.concurrent.atomic.AtomicLong(0L)

        @Volatile
        var fastTurboMode: Boolean = true

        val isRunning: Boolean
            get() = serviceRef?.get() != null

        fun currentUiMutationTimestamp(): Long = lastUiMutationTimeMs.get()

        suspend fun waitForUiChangeOrSettle(
            sinceTimestampMs: Long,
            minWaitMs: Long = 100L,
            maxWaitMs: Long = 850L
        ): Boolean {
            delay(minWaitMs)
            val deadline = System.currentTimeMillis() + (maxWaitMs - minWaitMs).coerceAtLeast(0L)
            var mutated = lastUiMutationTimeMs.get() > sinceTimestampMs
            while (!mutated && System.currentTimeMillis() < deadline) {
                delay(45L)
                if (lastUiMutationTimeMs.get() > sinceTimestampMs) {
                    mutated = true
                    delay(90L) // Brief settle after mutation burst
                    break
                }
            }
            return mutated
        }

        fun normalizeArabicForMatch(raw: String): String {
            return raw.trim()
                .replace(Regex("[\\u064B-\\u065F\\u0670\\u0640]"), "") // Strip Arabic tashkeel & tatweel
                .replace('أ', 'ا')
                .replace('إ', 'ا')
                .replace('آ', 'ا')
                .replace('ى', 'ي')
                .replace('ة', 'ه')
                .replace(Regex("\\s+"), " ")
                .lowercase(Locale.ROOT)
        }

        fun isServiceEnabled(context: Context): Boolean {
            val expectedServiceName = "${context.packageName}/${MorventAccessibilityService::class.java.canonicalName}"
            val enabledServicesSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServicesSetting)
            while (colonSplitter.hasNext()) {
                val componentName = colonSplitter.next()
                if (componentName.equals(expectedServiceName, ignoreCase = true)) {
                    return true
                }
            }
            return false
        }

        fun openAccessibilitySettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        fun getScreenDimensions(): Pair<Int, Int> {
            val service = serviceRef?.get() ?: return Pair(1080, 2400)
            val dm = service.resources.displayMetrics
            return Pair(dm.widthPixels.coerceAtLeast(1), dm.heightPixels.coerceAtLeast(1))
        }

        // ========================================================
        // 1. GLOBAL SYSTEM NAVIGATION & HARDWARE ACTIONS
        // ========================================================
        fun performBack(): Boolean = serviceRef?.get()?.performGlobalAction(GLOBAL_ACTION_BACK) == true
        fun performHome(): Boolean = serviceRef?.get()?.performGlobalAction(GLOBAL_ACTION_HOME) == true
        fun performRecents(): Boolean = serviceRef?.get()?.performGlobalAction(GLOBAL_ACTION_RECENTS) == true
        fun performNotifications(): Boolean = serviceRef?.get()?.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) == true
        fun performQuickSettings(): Boolean = serviceRef?.get()?.performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS) == true
        fun performPowerDialog(): Boolean = serviceRef?.get()?.performGlobalAction(GLOBAL_ACTION_POWER_DIALOG) == true
        fun performSplitScreen(): Boolean = serviceRef?.get()?.performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN) == true

        fun performDismissNotifications(): Boolean {
            val service = serviceRef?.get() ?: return false
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                service.performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            } else {
                performBack()
            }
        }

        fun performLockScreen(): Boolean {
            val service = serviceRef?.get() ?: return false
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                service.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            } else false
        }

        fun performTakeScreenshot(): Boolean {
            val service = serviceRef?.get() ?: return false
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                service.performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            } else false
        }

        fun performAllApps(): Boolean {
            val service = serviceRef?.get() ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (service.performGlobalAction(GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS)) return true
            }
            val homeOk = performHome()
            if (homeOk) {
                val (w, h) = getScreenDimensions()
                dispatchSwipeGesture(w / 2f, h * 0.85f, w / 2f, h * 0.25f, 340L)
            }
            return homeOk
        }

        private fun getAllActiveWindowRoots(service: AccessibilityService): List<AccessibilityNodeInfo> {
            val roots = mutableListOf<AccessibilityNodeInfo>()
            val ownPackage = service.packageName
            service.rootInActiveWindow?.let { roots.add(it) }
            try {
                val allWindows = service.windows
                if (!allWindows.isNullOrEmpty()) {
                    for (window in allWindows) {
                        val root = window.root ?: continue
                        val pkg = root.packageName?.toString() ?: ""
                        val isOwnOverlay = pkg == ownPackage &&
                            window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                        if (!isOwnOverlay && roots.none { it === root }) {
                            roots.add(root)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "getAllActiveWindowRoots fallback", e)
            }
            return roots
        }

        // ========================================================
        // 2. LIVE SCREEN VISION (API 30+ SCREENSHOT) & RICH MULTI-WINDOW UI TREE
        // ========================================================
        suspend fun captureScreenBitmap(maxWidthPx: Int = 720): Bitmap? {
            val service = serviceRef?.get() ?: return null
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

            return withTimeoutOrNull(3500L) {
                suspendCancellableCoroutine { cont ->
                    try {
                        service.takeScreenshot(
                            Display.DEFAULT_DISPLAY,
                            service.mainExecutor,
                            object : TakeScreenshotCallback {
                                override fun onSuccess(screenshot: ScreenshotResult) {
                                    val buffer = screenshot.hardwareBuffer
                                    try {
                                        val hwBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                                        if (hwBitmap == null) {
                                            if (cont.isActive) cont.resume(null)
                                            return
                                        }
                                        val softBitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                        hwBitmap.recycle()
                                        if (softBitmap == null) {
                                            if (cont.isActive) cont.resume(null)
                                            return
                                        }
                                        val finalBitmap = if (softBitmap.width > maxWidthPx) {
                                            val ratio = maxWidthPx.toFloat() / softBitmap.width.toFloat()
                                            val targetH = (softBitmap.height * ratio).toInt().coerceAtLeast(1)
                                            val scaled = Bitmap.createScaledBitmap(softBitmap, maxWidthPx, targetH, true)
                                            if (scaled !== softBitmap) softBitmap.recycle()
                                            scaled
                                        } else {
                                            softBitmap
                                        }
                                        if (cont.isActive) cont.resume(finalBitmap)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Screenshot conversion failed", e)
                                        if (cont.isActive) cont.resume(null)
                                    } finally {
                                        buffer.close()
                                    }
                                }

                                override fun onFailure(errorCode: Int) {
                                    Log.w(TAG, "takeScreenshot failed with errorCode=$errorCode")
                                    if (cont.isActive) cont.resume(null)
                                }
                            }
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "takeScreenshot threw exception", e)
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        }

        private fun extractNodeOwnLabel(node: AccessibilityNodeInfo): String {
            val text = node.text?.toString()?.trim()?.replace("\n", " ").orEmpty()
            val desc = node.contentDescription?.toString()?.trim()?.replace("\n", " ").orEmpty()
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                node.hintText?.toString()?.trim()?.replace("\n", " ").orEmpty()
            } else ""
            val stateDesc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                node.stateDescription?.toString()?.trim()?.replace("\n", " ").orEmpty()
            } else ""

            val base = when {
                text.isNotBlank() && desc.isNotBlank() && !text.equals(desc, true) -> "$text ($desc)"
                text.isNotBlank() -> text
                desc.isNotBlank() -> desc
                hint.isNotBlank() -> "[تلميح: $hint]"
                else -> ""
            }
            return if (stateDesc.isNotBlank() && !base.contains(stateDesc, true)) {
                if (base.isNotBlank()) "$base [$stateDesc]" else stateDesc
            } else base
        }

        private fun collectCompositeChildLabels(node: AccessibilityNodeInfo, maxItems: Int = 3, maxDepth: Int = 3): String {
            val parts = LinkedHashSet<String>()
            fun walk(cur: AccessibilityNodeInfo?, depth: Int) {
                if (cur == null || depth > maxDepth || parts.size >= maxItems) return
                val lbl = extractNodeOwnLabel(cur)
                if (lbl.isNotBlank() && lbl.length <= 60) {
                    parts.add(lbl)
                }
                for (i in 0 until cur.childCount) {
                    walk(cur.getChild(i), depth + 1)
                    if (parts.size >= maxItems) break
                }
            }
            for (i in 0 until node.childCount) {
                walk(node.getChild(i), 1)
                if (parts.size >= maxItems) break
            }
            return parts.joinToString(" | ")
        }

        fun captureRichScreenSnapshot(): RichScreenSnapshot {
            val service = serviceRef?.get() ?: return RichScreenSnapshot()
            val (screenW, screenH) = getScreenDimensions()
            val ownPackage = service.packageName

            val windowRoots = mutableListOf<Pair<AccessibilityNodeInfo, String>>()
            try {
                val allWindows = service.windows
                if (!allWindows.isNullOrEmpty()) {
                    // Prioritize active/topmost application & dialog windows first
                    val sortedWindows = allWindows.sortedByDescending { it.layer }
                    for (window in sortedWindows) {
                        val root = window.root ?: continue
                        val pkg = root.packageName?.toString() ?: ""
                        val isOwnOverlay = pkg == ownPackage &&
                            window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                        if (!isOwnOverlay) {
                            val winTag = when (window.type) {
                                android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM -> "SYSTEM_DIALOG"
                                android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "KEYBOARD"
                                else -> "APP"
                            }
                            windowRoots.add(root to winTag)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed reading windows list, using rootInActiveWindow", e)
            }

            if (windowRoots.isEmpty()) {
                service.rootInActiveWindow?.let { windowRoots.add(it to "APP") }
            }

            if (windowRoots.isEmpty()) {
                return RichScreenSnapshot(
                    packageName = _currentForegroundPackage.value,
                    activityName = _currentForegroundActivity.value,
                    screenWidth = screenW,
                    screenHeight = screenH,
                    hasSparseTree = true
                )
            }

            indexedBoundsMap.clear()
            val elements = mutableListOf<IndexedUiElement>()
            val visibleTexts = mutableListOf<String>()
            val treeLines = mutableListOf<String>()
            var nextId = 0
            val maxIndexedElements = 220
            var detectedPkg = ""
            var detectedWebView = false
            var totalTraversed = 0

            fun traverse(node: AccessibilityNodeInfo?, depth: Int, winTag: String) {
                if (node == null || nextId >= maxIndexedElements || depth > 42 || totalTraversed > 950) return
                totalTraversed++

                val pkg = node.packageName?.toString() ?: ""
                if (detectedPkg.isBlank() && pkg.isNotBlank() && pkg != ownPackage && winTag == "APP") {
                    detectedPkg = pkg
                }

                val clsFull = node.className?.toString() ?: ""
                val clsShort = clsFull.substringAfterLast('.')
                if (!detectedWebView && (clsFull.contains("WebView", ignoreCase = true) ||
                        clsFull.contains("Flutter", ignoreCase = true) ||
                        clsFull.contains("SurfaceView", ignoreCase = true))
                ) {
                    detectedWebView = true
                }

                val viewIdFull = node.viewIdResourceName ?: ""
                val shortViewId = viewIdFull.substringAfterLast('/')

                val rect = Rect()
                node.getBoundsInScreen(rect)
                val validBounds = rect.width() > 2 && rect.height() > 2 &&
                        rect.right > 0 && rect.bottom > 0 &&
                        rect.left < screenW && rect.top < screenH

                val ownLabel = extractNodeOwnLabel(node)
                val isInteractive = node.isClickable || node.isLongClickable ||
                        node.isEditable || node.isScrollable || node.isCheckable

                // For clickable/checkable containers without direct text (e.g. list cards, Compose rows, WebView buttons),
                // aggregate inner text/contentDescription from children cleanly!
                val label = if (ownLabel.isNotBlank()) {
                    ownLabel
                } else if ((node.isClickable || node.isCheckable || node.isLongClickable) && node.childCount in 1..12) {
                    collectCompositeChildLabels(node, maxItems = 3, maxDepth = 3)
                } else ""

                if (node.isVisibleToUser && ownLabel.isNotBlank()) {
                    visibleTexts.add(ownLabel)
                }

                val stateDesc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    node.stateDescription?.toString()?.trim().orEmpty()
                } else ""

                val rangeTag = node.rangeInfo?.let {
                    if (it.max > it.min) {
                        val pct = (((it.current - it.min) / (it.max - it.min)) * 100f).toInt().coerceIn(0, 100)
                        "$pct%"
                    } else ""
                }.orEmpty()

                val shouldIndex = validBounds && (node.isVisibleToUser || isInteractive) &&
                        (isInteractive || ownLabel.isNotBlank())

                if (shouldIndex) {
                    val normX = ((rect.centerX().toFloat() / screenW) * 1000f).toInt().coerceIn(0, 1000)
                    val normY = ((rect.centerY().toFloat() / screenH) * 1000f).toInt().coerceIn(0, 1000)
                    val id = nextId++
                    indexedBoundsMap[id] = Rect(rect)

                    val el = IndexedUiElement(
                        id = id,
                        label = label,
                        viewId = shortViewId,
                        className = clsShort,
                        bounds = Rect(rect),
                        normX = normX,
                        normY = normY,
                        isClickable = node.isClickable,
                        isEditable = node.isEditable,
                        isScrollable = node.isScrollable,
                        isCheckable = node.isCheckable,
                        isChecked = node.isChecked,
                        isFocused = node.isFocused,
                        isEnabled = node.isEnabled,
                        isSelected = node.isSelected,
                        stateDescription = stateDesc,
                        windowType = winTag
                    )
                    elements.add(el)

                    val flags = buildList {
                        if (winTag != "APP") add(winTag)
                        if (!node.isEnabled) add("DISABLED")
                        if (node.isClickable) add("CLICK")
                        if (node.isLongClickable) add("LONG")
                        if (node.isEditable) add("EDIT")
                        if (node.isScrollable) add("SCROLL")
                        if (node.isSelected) add("SELECTED")
                        if (node.isCheckable) add(if (node.isChecked) "CHECKED" else "UNCHECKED")
                        if (node.isFocused) add("FOCUSED")
                        if (rangeTag.isNotBlank()) add("VAL:$rangeTag")
                    }.joinToString(",")

                    val idTag = if (shortViewId.isNotBlank()) " #$shortViewId" else ""
                    val labelTag = if (label.isNotBlank()) " \"${label.take(80)}\"" else ""
                    val flagsTag = if (flags.isNotBlank()) " [$flags]" else ""
                    treeLines.add("[$id] $clsShort$idTag$labelTag$flagsTag @($normX,$normY)")
                }

                for (i in 0 until node.childCount) {
                    traverse(node.getChild(i), depth + 1, winTag)
                    if (nextId >= maxIndexedElements) break
                }
            }

            for ((root, winTag) in windowRoots) {
                traverse(root, 0, winTag)
            }

            val finalPkg = detectedPkg.ifBlank {
                _currentForegroundPackage.value.ifBlank {
                    windowRoots.firstOrNull()?.first?.packageName?.toString() ?: ""
                }
            }
            val uniqueTexts = visibleTexts.distinct().take(80)
            val isSparse = elements.size < 5 || detectedWebView

            val summary = buildString {
                append("التطبيق: ${finalPkg.ifBlank { "غير محدد" }}")
                append(" | العناصر التفاعلية: ${elements.count { it.isClickable || it.isEditable }}")
                append(" | حقول الإدخال: ${elements.count { it.isEditable }}")
                if (detectedWebView) append(" | محتوى ويب/مرسوم معقد (WebView/Canvas)")
            }

            return RichScreenSnapshot(
                packageName = finalPkg,
                activityName = _currentForegroundActivity.value,
                screenWidth = screenW,
                screenHeight = screenH,
                elements = elements,
                formattedTree = treeLines.joinToString("\n"),
                visibleTexts = uniqueTexts,
                windowCount = windowRoots.size,
                hasWebView = detectedWebView,
                hasSparseTree = isSparse,
                structuredSummary = summary
            )
        }

        suspend fun deepReadComplexScreen(maxScrolls: Int = 3): Pair<Boolean, String> {
            if (serviceRef?.get() == null) {
                return Pair(false, "خدمة إمكانية الوصول غير مفعلة لقراءة الشاشة المعقدة")
            }
            val collectedTexts = LinkedHashSet<String>()
            var lastSnap = captureRichScreenSnapshot()
            lastSnap.visibleTexts.forEach { collectedTexts.add(it) }

            val interactiveButtons = LinkedHashSet<String>()
            val editableFields = LinkedHashSet<String>()
            val switchesState = LinkedHashSet<String>()

            fun collectInteractiveStats(snap: RichScreenSnapshot) {
                for (el in snap.elements) {
                    if (el.label.isBlank()) continue
                    when {
                        el.isCheckable -> switchesState.add("${el.label}: ${if (el.isChecked) "مفعّل ✓" else "معطّل"}")
                        el.isEditable -> editableFields.add(el.label)
                        el.isClickable -> interactiveButtons.add(el.label.take(45))
                    }
                }
            }
            collectInteractiveStats(lastSnap)

            val hasScrollable = lastSnap.elements.any { it.isScrollable }
            var pagesRead = 1
            if (hasScrollable && maxScrolls > 0) {
                var prevSig = lastSnap.visibleTexts.joinToString("|")
                for (i in 0 until maxScrolls) {
                    val scrolled = scrollScreen(true).first
                    if (!scrolled) break
                    delay(260L)
                    val nextSnap = captureRichScreenSnapshot()
                    val newSig = nextSnap.visibleTexts.joinToString("|")
                    if (newSig == prevSig) break
                    prevSig = newSig
                    pagesRead++
                    nextSnap.visibleTexts.forEach { collectedTexts.add(it) }
                    collectInteractiveStats(nextSnap)
                    lastSnap = nextSnap
                }
            }

            if (collectedTexts.isEmpty() && lastSnap.elements.isEmpty()) {
                return Pair(false, "الشاشة الحالية لا تحتوي على نصوص مقروءة مباشرة (تعمل كواجهة رسومية كاملة).")
            }

            val report = buildString {
                appendLine("📖 **القراءة العميقة للشاشة المعقدة** (${lastSnap.packageName.ifBlank { "التطبيق الحالي" }} • $pagesRead صفحات):")
                if (switchesState.isNotEmpty()) {
                    appendLine("• **حالة المفاتيح والخيارات:** " + switchesState.take(8).joinToString(" | "))
                }
                if (editableFields.isNotEmpty()) {
                    appendLine("• **حقول الإدخال:** " + editableFields.take(6).joinToString(" | "))
                }
                if (interactiveButtons.isNotEmpty()) {
                    appendLine("• **أبرز الأزرار والعناصر التفاعلية:** " + interactiveButtons.take(12).joinToString(" • "))
                }
                if (collectedTexts.isNotEmpty()) {
                    appendLine("\n📝 **المحتوى النصي المقروء:**")
                    collectedTexts.take(45).forEach { line ->
                        appendLine("– $line")
                    }
                }
            }.trim()

            return Pair(true, report)
        }

        fun inspectCurrentScreen(): ScreenInspectionReport {
            val rich = captureRichScreenSnapshot()
            val elementsList = rich.elements.map {
                UiElementInfo(
                    text = it.label,
                    viewId = it.viewId,
                    className = it.className,
                    isClickable = it.isClickable,
                    isEditable = it.isEditable,
                    contentDescription = it.label,
                    centerX = it.bounds.centerX(),
                    centerY = it.bounds.centerY()
                )
            }
            val buttons = rich.elements
                .filter { (it.isClickable || it.className.contains("Button", ignoreCase = true)) && it.label.isNotBlank() }
                .map { it.label }
                .distinct()
                .take(20)
            val inputFields = rich.elements
                .filter { it.isEditable || it.className.contains("EditText", ignoreCase = true) }
                .map { it.label.ifBlank { it.viewId.ifBlank { "حقل إدخال" } } }
                .distinct()
                .take(12)

            return ScreenInspectionReport(
                packageName = rich.packageName,
                activityName = rich.activityName,
                elements = elementsList,
                buttons = buttons,
                inputFields = inputFields
            )
        }

        // ========================================================
        // 3. ELEMENT-ID & TEXT CLICKING
        // ========================================================
        fun clickElementById(elementId: Int): Pair<Boolean, String> {
            val service = serviceRef?.get()
                ?: return Pair(false, "خدمة إمكانية الوصول غير مفعلة")

            val rect = indexedBoundsMap[elementId]
            if (rect != null && rect.width() > 0 && rect.height() > 0) {
                val roots = getAllActiveWindowRoots(service)
                var exactNode: AccessibilityNodeInfo? = null
                var clickableAncestor: AccessibilityNodeInfo? = null
                val nodeRect = Rect()
                fun findAtRect(node: AccessibilityNodeInfo?) {
                    if (node == null || exactNode != null) return
                    node.getBoundsInScreen(nodeRect)
                    if (nodeRect == rect) {
                        exactNode = node
                        var anc: AccessibilityNodeInfo? = node
                        var hop = 0
                        while (anc != null && hop < 4) {
                            if (anc.isClickable && anc.isEnabled) {
                                clickableAncestor = anc
                                break
                            }
                            anc = anc.parent
                            hop++
                        }
                        return
                    }
                    for (i in 0 until node.childCount) {
                        findAtRect(node.getChild(i))
                    }
                }
                for (root in roots) {
                    findAtRect(root)
                    if (exactNode != null) break
                }

                // Check sibling switch/checkbox if matched row contains one
                exactNode?.let { matched ->
                    val siblingToggle = findCheckableInAncestors(matched)
                    if (siblingToggle != null && siblingToggle.isEnabled && siblingToggle.isClickable) {
                        if (siblingToggle.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            return Pair(true, "تم تبديل الخيار [$elementId] بنجاح ✓")
                        }
                    }
                }

                if (clickableAncestor?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                    return Pair(true, "تم النقر على العنصر [$elementId] بنجاح")
                }

                val tapped = performHumanTap(rect.centerX().toFloat(), rect.centerY().toFloat())
                if (tapped) {
                    return Pair(true, "تم النقر البشري الذكي على العنصر [$elementId] عند (${rect.centerX()}, ${rect.centerY()})")
                }
            }
            return Pair(false, "تعذر العثور على العنصر [$elementId]")
        }

        fun longClickElementById(elementId: Int): Pair<Boolean, String> {
            val rect = indexedBoundsMap[elementId]
                ?: return Pair(false, "تعذر العثور على العنصر [$elementId]")
            val ok = performHumanLongPress(rect.centerX().toFloat(), rect.centerY().toFloat())
            return if (ok) {
                Pair(true, "تم الضغط المطول البشري على [$elementId]")
            } else {
                Pair(false, "تعذر الضغط المطول على [$elementId]")
            }
        }

        private data class ScoredNodeCandidate(
            val node: AccessibilityNodeInfo,
            val score: Int,
            val bounds: Rect,
            val label: String
        )

        fun clickElementByText(query: String): Pair<Boolean, String> = clickElementSmart(query)

        fun clickElementSmart(query: String): Pair<Boolean, String> {
            val service = serviceRef?.get()
                ?: return Pair(false, "خدمة إمكانية الوصول غير مفعلة")
            val roots = getAllActiveWindowRoots(service)
            if (roots.isEmpty()) {
                return Pair(false, "تعذر قراءة محتوى الشاشة النشطة")
            }

            val rawClean = query.trim().lowercase(Locale.ROOT)
            val normQuery = normalizeArabicForMatch(query)
            if (normQuery.isBlank() && rawClean.isBlank()) {
                return Pair(false, "لم يتم تحديد نص العنصر المطلوب")
            }
            val candidates = mutableListOf<ScoredNodeCandidate>()
            val wordBoundaryRegex = runCatching {
                Regex("(^|\\s)${Regex.escape(normQuery)}(\\s|$)")
            }.getOrNull()

            fun scoreNode(node: AccessibilityNodeInfo?, winIdx: Int, depth: Int) {
                if (node == null || depth > 42) return
                val rect = Rect()
                node.getBoundsInScreen(rect)

                if (node.isVisibleToUser && rect.width() > 2 && rect.height() > 2) {
                    val ownRaw = extractNodeOwnLabel(node)
                    val ownNorm = normalizeArabicForMatch(ownRaw)
                    val idRaw = (node.viewIdResourceName ?: "").substringAfterLast('/').lowercase(Locale.ROOT)
                    val hintNorm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        normalizeArabicForMatch(node.hintText?.toString() ?: "")
                    } else ""

                    var score = 0
                    when {
                        ownRaw.lowercase(Locale.ROOT) == rawClean && rawClean.isNotEmpty() -> score += 100
                        ownNorm == normQuery && normQuery.isNotEmpty() -> score += 85
                        ownNorm.startsWith(normQuery) && normQuery.isNotEmpty() -> score += 60
                        normQuery.isNotEmpty() && wordBoundaryRegex?.containsMatchIn(ownNorm) == true -> score += 45
                        normQuery.isNotEmpty() && ownNorm.contains(normQuery) -> score += 30
                        normQuery.isNotEmpty() && hintNorm.contains(normQuery) -> score += 26
                        rawClean.isNotEmpty() && idRaw.contains(rawClean) -> score += 22
                    }

                    if (score > 0) {
                        score += if (node.isEnabled) 30 else -80
                        val hasClickableAnc = run {
                            var anc: AccessibilityNodeInfo? = node
                            var hop = 0
                            var ok = false
                            while (anc != null && hop < 4) {
                                if (anc.isClickable) {
                                    ok = true
                                    break
                                }
                                anc = anc.parent
                                hop++
                            }
                            ok
                        }
                        if (hasClickableAnc) score += 50
                        if (node.isEditable) score += 20
                        if (winIdx == 0) score += 25 // Frontmost popup/dialog/active window priority
                        if (findCheckableInAncestors(node) != null) score += 40
                        if (rect.width() >= 20 && rect.height() >= 20) score += 20

                        candidates.add(
                            ScoredNodeCandidate(
                                node = node,
                                score = score,
                                bounds = rect,
                                label = ownRaw.ifBlank { idRaw.ifBlank { query } }
                            )
                        )
                    }
                }

                for (i in 0 until node.childCount) {
                    scoreNode(node.getChild(i), winIdx, depth + 1)
                }
            }

            roots.forEachIndexed { winIdx, root ->
                scoreNode(root, winIdx, 0)
            }

            if (candidates.isEmpty()) {
                return Pair(false, "لم يتم العثور على عنصر بالشاشة يطابق '$query'")
            }

            val topCandidates = candidates.sortedByDescending { it.score }.take(3)
            for (cand in topCandidates) {
                val matched = cand.node

                // Tier 1: Sibling Switch/CheckBox inside same settings/card row
                val siblingToggle = findCheckableInAncestors(matched)
                if (siblingToggle != null && siblingToggle.isEnabled && siblingToggle.isClickable) {
                    if (siblingToggle.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return Pair(true, "تم تبديل خيار '${cand.label.take(40)}' بنجاح ✓")
                    }
                }

                // Tier 2: Nearest enabled clickable ancestor
                var clickTarget: AccessibilityNodeInfo? = matched
                while (clickTarget != null && !clickTarget.isClickable) {
                    clickTarget = clickTarget.parent
                }
                if (clickTarget != null && clickTarget.isClickable && clickTarget.isEnabled) {
                    if (clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return Pair(true, "تم النقر على '${cand.label.take(40)}' بنجاح ✓")
                    }
                }

                // Tier 3: Hardware gesture tap directly on bounding box center (works for WebView, Flutter, Canvas, custom views)
                if (cand.bounds.width() > 0 && cand.bounds.height() > 0) {
                    val tapped = dispatchTapGesture(cand.bounds.centerX().toFloat(), cand.bounds.centerY().toFloat())
                    if (tapped) {
                        return Pair(
                            true,
                            "تم النقر بإيماءة اللمس الذكية على '${cand.label.take(40)}' عند (${cand.bounds.centerX()}, ${cand.bounds.centerY()})"
                        )
                    }
                }
            }

            return Pair(false, "تعذر تنفيذ النقر على '$query'")
        }

        suspend fun findAndClickSmart(query: String, maxScrolls: Int = 5): Pair<Boolean, String> {
            if (serviceRef?.get() == null) {
                return Pair(false, "خدمة إمكانية الوصول غير مفعلة")
            }
            val queries = query.split("|").map { it.trim() }.filter { it.isNotEmpty() }
            if (queries.isEmpty()) return Pair(false, "لم يتم تحديد العنصر المطلوب")

            var prevSignature = ""
            for (attempt in 0..maxScrolls) {
                for (q in queries) {
                    val res = clickElementSmart(q)
                    if (res.first) return res
                }
                if (attempt == maxScrolls) break

                val snap = captureRichScreenSnapshot()
                val curSig = snap.visibleTexts.joinToString("|")
                if (attempt > 0 && curSig == prevSignature) {
                    break // Reached bottom / scrollable end cleanly without wasted loops
                }
                prevSignature = curSig

                val tsBefore = currentUiMutationTimestamp()
                val scrolled = scrollScreen(true).first
                if (!scrolled) break
                waitForUiChangeOrSettle(tsBefore, minWaitMs = if (fastTurboMode) 110L else 240L, maxWaitMs = 450L)
            }
            return Pair(false, "لم يتم العثور على '$query' بعد البحث والتمرير الذكي")
        }

        fun clickRelativeToLabel(anchorText: String, directionOrType: String = "next"): Pair<Boolean, String> {
            val service = serviceRef?.get()
                ?: return Pair(false, "خدمة إمكانية الوصول غير مفعلة")
            val snap = captureRichScreenSnapshot()
            val normAnchor = normalizeArabicForMatch(anchorText)
            val anchorEl = snap.elements.firstOrNull {
                val n = normalizeArabicForMatch(it.label)
                n == normAnchor || n.contains(normAnchor)
            } ?: return Pair(false, "لم يتم العثور على النص المرجعي '$anchorText'")

            val interactiveCandidates = snap.elements.filter {
                it.id != anchorEl.id && (it.isClickable || it.isCheckable || it.isEditable) && it.isEnabled
            }
            val dir = directionOrType.lowercase(Locale.ROOT)
            val matchingDir = interactiveCandidates.filter { cand ->
                when (dir) {
                    "below", "under", "تحت" -> cand.bounds.centerY() > anchorEl.bounds.bottom - 10
                    "above", "فوق" -> cand.bounds.centerY() < anchorEl.bounds.top + 10
                    else -> kotlin.math.abs(cand.bounds.centerY() - anchorEl.bounds.centerY()) <= (anchorEl.bounds.height() + 90)
                }
            }.ifEmpty { interactiveCandidates }

            val nearest = matchingDir.minByOrNull { cand ->
                val dx = (cand.bounds.centerX() - anchorEl.bounds.centerX()).toLong()
                val dy = (cand.bounds.centerY() - anchorEl.bounds.centerY()).toLong()
                dx * dx + dy * dy
            } ?: return Pair(false, "لم يتم العثور على عنصر تفاعلي مجاور لـ '$anchorText'")

            return clickElementById(nearest.id)
        }

        private fun findCheckableInAncestors(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isCheckable) return node
            var parent = node.parent
            var depth = 0
            while (parent != null && depth < 3) {
                for (i in 0 until parent.childCount) {
                    val sibling = parent.getChild(i) ?: continue
                    if (sibling.isCheckable) return sibling
                    for (j in 0 until sibling.childCount) {
                        val grand = sibling.getChild(j)
                        if (grand != null && grand.isCheckable) return grand
                    }
                }
                parent = parent.parent
                depth++
            }
            return null
        }

        fun handleSystemPermissionDialog(allow: Boolean): Pair<Boolean, String> {
            if (serviceRef?.get() == null) {
                return Pair(false, "خدمة إمكانية الوصول غير مفعلة")
            }
            val candidates = if (allow) {
                listOf(
                    "سماح أثناء استخدام التطبيق", "السماح عند استخدام التطبيق",
                    "While using the app", "Allow only while using the app",
                    "سماح دائماً", "السماح", "سماح", "مرة واحدة فقط",
                    "Allow", "Only this time", "قبول", "موافق", "OK", "Accept"
                )
            } else {
                listOf(
                    "عدم السماح", "رفض", "لا تسمح", "إلغاء",
                    "Don't allow", "Deny", "Cancel"
                )
            }
            for (candidate in candidates) {
                val res = clickElementByText(candidate)
                if (res.first) {
                    return Pair(true, if (allow) "تمت الموافقة على نافذة إذن النظام ('$candidate') ✓" else "تم رفض نافذة إذن النظام ('$candidate') ✓")
                }
            }
            return Pair(false, "لم يتم العثور على نافذة إذن نظام ظاهرة حالياً")
        }

        fun clickOrdinalElement(ordinalIndex: Int): Pair<Boolean, String> {
            val service = serviceRef?.get()
                ?: return Pair(false, "خدمة إمكانية الوصول غير مفعلة")
            val roots = getAllActiveWindowRoots(service)
            if (roots.isEmpty()) {
                return Pair(false, "تعذر قراءة محتوى الشاشة النشطة")
            }

            val clickableTargets = mutableListOf<Pair<AccessibilityNodeInfo, Rect>>()
            fun collect(node: AccessibilityNodeInfo?) {
                if (node == null) return
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val hasContent = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
                if (node.isVisibleToUser && node.isClickable && rect.width() > 36 && rect.height() > 36 && rect.top > 80 && hasContent) {
                    clickableTargets.add(node to rect)
                }
                for (i in 0 until node.childCount) {
                    collect(node.getChild(i))
                }
            }
            for (root in roots) {
                collect(root)
            }

            val sorted = clickableTargets.sortedWith(compareBy({ it.second.top }, { it.second.left }))
            val zeroBased = (ordinalIndex - 1).coerceAtLeast(0)
            val chosen = sorted.getOrNull(zeroBased)
                ?: return Pair(false, "لم يتم العثور على العنصر رقم $ordinalIndex على الشاشة")

            val (node, rect) = chosen
            val label = node.text?.toString()?.take(30) ?: node.contentDescription?.toString()?.take(30) ?: "#$ordinalIndex"
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return Pair(true, "تم النقر على العنصر رقم $ordinalIndex ($label) بنجاح")
            }
            val tapped = dispatchTapGesture(rect.centerX().toFloat(), rect.centerY().toFloat())
            return if (tapped) {
                Pair(true, "تم لمس العنصر رقم $ordinalIndex ($label) عند (${rect.centerX()}, ${rect.centerY()})")
            } else {
                Pair(false, "تعذر النقر على العنصر رقم $ordinalIndex")
            }
        }

        suspend fun performQuickSettingTileToggle(tileSynonyms: String, collapseAfter: Boolean = false): Pair<Boolean, String> {
            val service = serviceRef?.get()
                ?: return Pair(false, "خدمة إمكانية الوصول غير مفعلة")
            val candidates = tileSynonyms.split("|").map { it.trim() }.filter { it.isNotEmpty() }
            if (candidates.isEmpty()) return Pair(false, "لم يتم تحديد بلاطة الإعدادات السريعة")

            performQuickSettings()
            delay(650)

            for (pageAttempt in 0 until 2) {
                for (q in candidates) {
                    val res = clickElementByText(q)
                    if (res.first) {
                        if (collapseAfter) {
                            delay(400)
                            performDismissNotifications()
                        }
                        return Pair(true, "تم تبديل '$q' مباشرة من لوحة الإعدادات السريعة للنظام ✓")
                    }
                }
                if (pageAttempt == 0) {
                    swipeHorizontal(toRight = false)
                    delay(450)
                }
            }

            performDismissNotifications()
            return Pair(false, "لم يتم العثور على زر '$tileSynonyms' في لوحة الإعدادات السريعة")
        }

        suspend fun readSystemNotifications(): Pair<Boolean, List<String>> {
            if (serviceRef?.get() == null) return Pair(false, emptyList())
            performNotifications()
            delay(650)

            val snap = captureRichScreenSnapshot()
            val ignoredLabels = setOf(
                "محو الكل", "مسح الكل", "Clear all", "Manage", "إدارة",
                "History", "السجل", "No notifications", "لا توجد إشعارات"
            )
            val shadeLines = snap.visibleTexts
                .filter { it.length > 2 && it !in ignoredLabels }
                .take(20)

            performDismissNotifications()

            val combined = buildList {
                addAll(shadeLines)
                synchronized(recentNotificationsLock) {
                    for (entry in recentCapturedNotifications) {
                        if (size >= 25) break
                        if (none { it.contains(entry.substringAfter("] ").trim()) }) {
                            add(entry)
                        }
                    }
                }
            }
            return Pair(true, combined.take(20))
        }

        suspend fun clearAllSystemNotifications(): Pair<Boolean, String> {
            if (serviceRef?.get() == null) return Pair(false, "خدمة إمكانية الوصول غير مفعلة")
            performNotifications()
            delay(650)

            val clearKeywords = listOf("محو الكل", "مسح الكل", "إزالة الكل", "Clear all", "Dismiss all")
            for (kw in clearKeywords) {
                val res = clickElementByText(kw)
                if (res.first) {
                    delay(350)
                    performDismissNotifications()
                    return Pair(true, "تم مسح جميع الإشعارات من شريط النظام ✓")
                }
            }

            performDismissNotifications()
            return Pair(false, "لا توجد إشعارات قابلة للمسح أو لم يظهر زر 'مسح الكل'")
        }

        // ========================================================
        // 4. IN-APP & SYSTEM-WIDE TEXT TYPING & IME SUBMISSION
        // ========================================================
        private fun setTextWithClipboardFallback(
            service: MorventAccessibilityService,
            editNode: AccessibilityNodeInfo,
            textToType: String,
            submitEnter: Boolean
        ): Boolean {
            editNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            editNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
            }
            var success = editNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

            if (!success && textToType.isNotEmpty()) {
                // Fallback for custom Canvas/WebView/Flutter inputs: select existing text + ClipboardManager paste
                runCatching {
                    val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("MorventInput", textToType))
                        val existingLen = editNode.text?.length ?: 0
                        if (existingLen > 0) {
                            val selArgs = Bundle().apply {
                                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, existingLen)
                            }
                            editNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
                        }
                        success = editNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                    }
                }
            }

            if (success && submitEnter && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                editNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
            return success
        }

        fun typeTextIntoElementById(elementId: Int?, textToType: String, submitEnter: Boolean = false): Pair<Boolean, String> {
            val service = serviceRef?.get()
                ?: return Pair(false, "خدمة الوصول غير مفعلة")

            // Tap target field first if elementId is supplied so focus/keyboard triggers naturally
            if (elementId != null) {
                val rect = indexedBoundsMap[elementId]
                if (rect != null && rect.width() > 0 && rect.height() > 0) {
                    performHumanTap(rect.centerX().toFloat(), rect.centerY().toFloat())
                }
            }

            val roots = getAllActiveWindowRoots(service)
            if (roots.isEmpty()) return Pair(false, "تعذر قراءة نوافذ النظام النشطة")

            var targetNode: AccessibilityNodeInfo? = null
            val targetRect = if (elementId != null) indexedBoundsMap[elementId] else null

            fun findBestEditable(node: AccessibilityNodeInfo?, requireFocused: Boolean, matchRect: Rect?) {
                if (node == null || targetNode != null) return
                val isEdit = node.isEditable || node.className?.toString()?.contains("EditText", ignoreCase = true) == true
                if (isEdit && node.isEnabled) {
                    val r = Rect()
                    node.getBoundsInScreen(r)
                    val rectMatches = matchRect == null || r == matchRect || r.contains(matchRect.centerX(), matchRect.centerY())
                    if (rectMatches && (!requireFocused || node.isFocused)) {
                        targetNode = node
                        return
                    }
                }
                for (i in 0 until node.childCount) {
                    findBestEditable(node.getChild(i), requireFocused, matchRect)
                    if (targetNode != null) return
                }
            }

            for (root in roots) {
                if (targetRect != null) findBestEditable(root, requireFocused = false, matchRect = targetRect)
                if (targetNode != null) break
            }
            if (targetNode == null) {
                for (root in roots) {
                    findBestEditable(root, requireFocused = true, matchRect = null)
                    if (targetNode != null) break
                }
            }
            if (targetNode == null) {
                for (root in roots) {
                    findBestEditable(root, requireFocused = false, matchRect = null)
                    if (targetNode != null) break
                }
            }

            val editNode = targetNode
                ?: return Pair(false, "لم يتم العثور على حقل إدخال نشط على الشاشة")

            val success = setTextWithClipboardFallback(service, editNode, textToType, submitEnter)

            return if (success) {
                Pair(true, "تمت كتابة '$textToType' في الحقل بنجاح")
            } else {
                Pair(false, "تعذر إدخال النص في الحقل المطلوب")
            }
        }

        fun clearFocusedField(): Pair<Boolean, String> {
            return typeTextIntoElementById(null, "", submitEnter = false).let {
                if (it.first) Pair(true, "تم مسح محتوى الحقل النشط ✓")
                else Pair(false, "لم يتم العثور على حقل إدخال نشط لمسحه")
            }
        }

        fun typeTextIntoField(targetField: String?, textToType: String, submitEnter: Boolean = false): Pair<Boolean, String> {
            val service = serviceRef?.get()
                ?: return Pair(false, "خدمة الوصول غير مفعلة")
            val roots = getAllActiveWindowRoots(service)
            if (roots.isEmpty()) return Pair(false, "تعذر قراءة نافذة التطبيق النشطة")

            val normTarget = normalizeArabicForMatch(targetField ?: "")
            var targetNode: AccessibilityNodeInfo? = null
            val editNodes = mutableListOf<Pair<AccessibilityNodeInfo, Rect>>()
            val labelNodes = mutableListOf<Pair<String, Rect>>()

            fun collectNodes(node: AccessibilityNodeInfo?) {
                if (node == null) return
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val isEdit = node.isEditable || node.className?.toString()?.contains("EditText", ignoreCase = true) == true
                if (isEdit && node.isEnabled) {
                    editNodes.add(node to rect)
                    if (normTarget.isNotEmpty()) {
                        val own = normalizeArabicForMatch(extractNodeOwnLabel(node))
                        val id = (node.viewIdResourceName ?: "").lowercase(Locale.ROOT)
                        if (own.contains(normTarget) || id.contains(normTarget)) {
                            targetNode = node
                            return
                        }
                    }
                } else {
                    val txt = normalizeArabicForMatch(extractNodeOwnLabel(node))
                    if (txt.isNotBlank() && node.isVisibleToUser) {
                        labelNodes.add(txt to rect)
                    }
                }
                for (i in 0 until node.childCount) {
                    collectNodes(node.getChild(i))
                    if (targetNode != null) return
                }
            }

            for (root in roots) {
                collectNodes(root)
                if (targetNode != null) break
            }

            // Spatial matching: find EditText directly below/next to a matching TextView label
            if (targetNode == null && normTarget.isNotEmpty() && editNodes.isNotEmpty()) {
                val matchedLabelRect = labelNodes.firstOrNull { it.first.contains(normTarget) }?.second
                if (matchedLabelRect != null) {
                    targetNode = editNodes.minByOrNull { (_, r) ->
                        val dx = (r.centerX() - matchedLabelRect.centerX()).toLong()
                        val dy = (r.centerY() - matchedLabelRect.centerY()).toLong()
                        dx * dx + dy * dy
                    }?.first
                }
            }

            val editNode = targetNode
                ?: editNodes.firstOrNull { it.first.isFocused }?.first
                ?: editNodes.firstOrNull()?.first
                ?: return Pair(false, "لم يتم العثور على حقل إدخال نشط على الشاشة")

            val success = setTextWithClipboardFallback(service, editNode, textToType, submitEnter)

            return if (success) {
                Pair(true, "تمت كتابة '$textToType' في الحقل بنجاح")
            } else {
                Pair(false, "تعذر إدخال النص في الحقل المطلوب")
            }
        }

        suspend fun smartTypeAndSubmit(
            targetField: String?,
            textToType: String,
            submit: Boolean = true
        ): Pair<Boolean, String> {
            val tsBefore = currentUiMutationTimestamp()
            val typed = typeTextIntoField(targetField, textToType, submitEnter = submit)
            if (!typed.first) return typed
            if (!submit) return typed

            val mutatedByIme = waitForUiChangeOrSettle(tsBefore, minWaitMs = 90L, maxWaitMs = 220L)
            if (!mutatedByIme) {
                val submitKeywords = listOf("إرسال", "ارسال", "بحث", "Send", "Search", "Go", "تم", "Done", "Submit")
                for (kw in submitKeywords) {
                    val res = clickElementSmart(kw)
                    if (res.first) {
                        return Pair(true, "تمت كتابة '$textToType' وتأكيد الإرسال عبر '$kw' ✓")
                    }
                }
            }
            return typed
        }

        // ========================================================
        // 5. IN-APP & SYSTEM SCROLLING
        // ========================================================
        fun scrollScreen(forward: Boolean): Pair<Boolean, String> {
            val service = serviceRef?.get()
                ?: return Pair(false, "خدمة إمكانية الوصول غير مفعلة")
            val roots = getAllActiveWindowRoots(service)
            if (roots.isEmpty()) return Pair(false, "تعذر الوصول للشاشة النشطة")

            var scrollableNode: AccessibilityNodeInfo? = null

            fun findScrollable(node: AccessibilityNodeInfo?) {
                if (node == null || scrollableNode != null) return
                if (node.isScrollable) {
                    scrollableNode = node
                    return
                }
                for (i in 0 until node.childCount) {
                    findScrollable(node.getChild(i))
                    if (scrollableNode != null) return
                }
            }

            for (root in roots) {
                findScrollable(root)
                if (scrollableNode != null) break
            }

            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (scrollableNode != null) {
                val ok = scrollableNode!!.performAction(action)
                if (ok) return Pair(true, if (forward) "تم التمرير للأسفل ⬇️" else "تم التمرير للأعلى ⬆️")
            }

            val width = service.resources.displayMetrics.widthPixels.toFloat()
            val height = service.resources.displayMetrics.heightPixels.toFloat()
            val startX = width / 2f
            val startY = if (forward) height * 0.75f else height * 0.25f
            val endY = if (forward) height * 0.25f else height * 0.75f

            val gestured = dispatchSwipeGesture(startX, startY, startX, endY, if (fastTurboMode) 220L else 340L)
            return if (gestured) {
                Pair(true, if (forward) "تم التمرير لأسفل بإيماءة السحب" else "تم التمرير لأعلى بإيماءة السحب")
            } else {
                Pair(false, "تعذر التمرير على هذه الشاشة")
            }
        }

        // ========================================================
        // 6. FULL HUMAN GESTURE MOTOR SUITE (TAP, DOUBLE-TAP, LONG-PRESS, SWIPE, DRAG, PINCH)
        // ========================================================
        private fun normToPx(normX: Int, normY: Int): Pair<Float, Float> {
            val (w, h) = getScreenDimensions()
            val px = ((normX.coerceIn(0, 1000) / 1000f) * w).coerceIn(1f, (w - 1).toFloat())
            val py = ((normY.coerceIn(0, 1000) / 1000f) * h).coerceIn(1f, (h - 1).toFloat())
            return Pair(px, py)
        }

        fun dispatchTapGesture(x: Float, y: Float): Boolean = performHumanTap(x, y)

        fun performHumanTapNormalized(normX: Int, normY: Int): Boolean {
            val (px, py) = normToPx(normX, normY)
            return performHumanTap(px, py)
        }

        fun performHumanTap(x: Float, y: Float): Boolean {
            val service = serviceRef?.get() ?: return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

            val jitterX = ((-2..2).random()).toFloat()
            val jitterY = ((-2..2).random()).toFloat()
            val tapX = (x + jitterX).coerceAtLeast(1f)
            val tapY = (y + jitterY).coerceAtLeast(1f)

            val path = Path().apply {
                moveTo(tapX, tapY)
                lineTo(tapX + 0.5f, tapY + 0.7f)
            }
            val duration = if (fastTurboMode) (45L..68L).random() else (75L..115L).random()
            val stroke = GestureDescription.StrokeDescription(path, 0, duration)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            return service.dispatchGesture(gesture, null, null)
        }

        fun performHumanDoubleTapNormalized(normX: Int, normY: Int): Boolean {
            val (px, py) = normToPx(normX, normY)
            return performHumanDoubleTap(px, py)
        }

        fun performHumanDoubleTap(x: Float, y: Float): Boolean {
            val service = serviceRef?.get() ?: return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

            val p1 = Path().apply { moveTo(x, y); lineTo(x + 0.5f, y + 0.5f) }
            val p2 = Path().apply { moveTo(x + 1f, y - 1f); lineTo(x + 1.5f, y - 0.5f) }
            val tapDur = if (fastTurboMode) 50L else 75L
            val gapStart = if (fastTurboMode) 105L else 145L
            val stroke1 = GestureDescription.StrokeDescription(p1, 0, tapDur)
            val stroke2 = GestureDescription.StrokeDescription(p2, gapStart, tapDur)
            val gesture = GestureDescription.Builder().addStroke(stroke1).addStroke(stroke2).build()
            return service.dispatchGesture(gesture, null, null)
        }

        fun performHumanLongPressNormalized(normX: Int, normY: Int): Boolean {
            val (px, py) = normToPx(normX, normY)
            return performHumanLongPress(px, py)
        }

        fun performHumanLongPress(x: Float, y: Float): Boolean {
            val service = serviceRef?.get() ?: return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

            val path = Path().apply {
                moveTo(x, y)
                lineTo(x + 0.3f, y + 0.3f)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, 680L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            return service.dispatchGesture(gesture, null, null)
        }

        fun performHumanDragNormalized(
            startNormX: Int,
            startNormY: Int,
            endNormX: Int,
            endNormY: Int,
            durationMs: Long = 650L
        ): Boolean {
            val (sx, sy) = normToPx(startNormX, startNormY)
            val (ex, ey) = normToPx(endNormX, endNormY)
            return dispatchSwipeGesture(sx, sy, ex, ey, durationMs.coerceIn(250L, 2000L))
        }

        fun performHumanPinch(centerNormX: Int, centerNormY: Int, zoomIn: Boolean): Boolean {
            val service = serviceRef?.get() ?: return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

            val (cx, cy) = normToPx(centerNormX, centerNormY)
            val (w, h) = getScreenDimensions()
            val span = (minOf(w, h) * 0.22f).coerceAtLeast(80f)
            val inner = 30f

            val f1StartX = cx - (if (zoomIn) inner else span)
            val f1StartY = cy - (if (zoomIn) inner else span)
            val f1EndX = cx - (if (zoomIn) span else inner)
            val f1EndY = cy - (if (zoomIn) span else inner)

            val f2StartX = cx + (if (zoomIn) inner else span)
            val f2StartY = cy + (if (zoomIn) inner else span)
            val f2EndX = cx + (if (zoomIn) span else inner)
            val f2EndY = cy + (if (zoomIn) span else inner)

            val path1 = Path().apply { moveTo(f1StartX, f1StartY); lineTo(f1EndX, f1EndY) }
            val path2 = Path().apply { moveTo(f2StartX, f2StartY); lineTo(f2EndX, f2EndY) }

            val s1 = GestureDescription.StrokeDescription(path1, 0, 420L)
            val s2 = GestureDescription.StrokeDescription(path2, 0, 420L)
            val gesture = GestureDescription.Builder().addStroke(s1).addStroke(s2).build()
            return service.dispatchGesture(gesture, null, null)
        }

        fun longClickElementByText(query: String): Pair<Boolean, String> {
            val service = serviceRef?.get()
                ?: return Pair(false, "خدمة إمكانية الوصول غير مفعلة")
            val roots = getAllActiveWindowRoots(service)
            if (roots.isEmpty()) return Pair(false, "تعذر قراءة محتوى الشاشة النشطة")

            val cleanQuery = query.trim().lowercase(Locale.ROOT)
            var targetNode: AccessibilityNodeInfo? = null

            fun findMatchingNode(node: AccessibilityNodeInfo?) {
                if (node == null || targetNode != null) return
                val text = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""
                val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
                val id = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
                if (text.contains(cleanQuery) || desc.contains(cleanQuery) || id.contains(cleanQuery)) {
                    targetNode = node
                    return
                }
                for (i in 0 until node.childCount) {
                    findMatchingNode(node.getChild(i))
                    if (targetNode != null) return
                }
            }

            for (root in roots) {
                findMatchingNode(root)
                if (targetNode != null) break
            }
            val matched = targetNode ?: return Pair(false, "لم يتم العثور على '$query'")

            var clickTarget: AccessibilityNodeInfo? = matched
            while (clickTarget != null && !clickTarget.isLongClickable) {
                clickTarget = clickTarget.parent
            }
            if (clickTarget != null && clickTarget.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                return Pair(true, "تم الضغط المطول على '$query' بنجاح")
            }

            val rect = Rect()
            matched.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0) {
                if (performHumanLongPress(rect.centerX().toFloat(), rect.centerY().toFloat())) {
                    return Pair(true, "تم الضغط المطول بإيماءة بشرية على '$query'")
                }
            }
            return Pair(false, "تعذر الضغط المطول على '$query'")
        }

        fun swipeHorizontal(toRight: Boolean): Pair<Boolean, String> {
            val service = serviceRef?.get()
                ?: return Pair(false, "خدمة إمكانية الوصول غير مفعلة")
            val width = service.resources.displayMetrics.widthPixels.toFloat()
            val height = service.resources.displayMetrics.heightPixels.toFloat()
            val startX = if (toRight) width * 0.2f else width * 0.8f
            val endX = if (toRight) width * 0.8f else width * 0.2f
            val y = height * 0.5f

            val ok = dispatchSwipeGesture(startX, y, endX, y, if (fastTurboMode) 210L else 340L)
            return if (ok) {
                Pair(true, if (toRight) "تم السحب لليمين 👉" else "تم السحب لليسار 👈")
            } else {
                Pair(false, "تعذر تنفيذ السحب الأفقي")
            }
        }

        fun dispatchSwipeGesture(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 260): Boolean {
            val service = serviceRef?.get() ?: return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

            val effectiveDuration = if (fastTurboMode) durationMs.coerceIn(140L, 280L) else durationMs.coerceIn(220L, 900L)
            val path = Path().apply {
                moveTo(startX, startY)
                val midX = (startX + endX) / 2f + (if (startX < endX) 18f else -18f)
                val midY = (startY + endY) / 2f + ((-12..12).random()).toFloat()
                quadTo(midX, midY, endX, endY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, effectiveDuration)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            return service.dispatchGesture(gesture, null, null)
        }

        // ========================================================
        // 7. AUTONOMOUS ACCOUNT & LOGIN WORKFLOW
        // ========================================================
        fun executeAutonomousAccountWorkflow(actionType: String): Pair<Boolean, List<String>> {
            val logs = mutableListOf<String>()
            val service = serviceRef?.get()
            if (service == null) {
                logs.add("خدمة إمكانية الوصول غير مفعلة لمتابعة الأتمتة")
                return Pair(false, logs)
            }

            val inspection = inspectCurrentScreen()
            logs.add("فحص الشاشة الحالية للتطبيق: ${inspection.packageName}")
            logs.add("تم العثور على ${inspection.elements.size} عنصر تفاعلي (${inspection.buttons.size} أزرار)")

            val targetKeywords = when (actionType.lowercase(Locale.ROOT)) {
                "google" -> listOf("google", "جوجل", "sign in with google", "تسجيل الدخول باستخدام google", "المتابعة باستخدام google")
                "register", "signup", "حساب جديد" -> listOf("إنشاء حساب", "حساب جديد", "create account", "sign up", "register", "تسجيل جديد", "اشتراك")
                else -> listOf("تسجيل الدخول", "دخول", "sign in", "log in", "login", "متابعة", "continue", "next", "التالي")
            }

            var clicked = false
            for (kw in targetKeywords) {
                val (ok, msg) = clickElementByText(kw)
                if (ok) {
                    logs.add("تم العثور على خيار المصادقة والنقر عليه: $msg")
                    clicked = true
                    break
                }
            }

            if (!clicked) {
                if (inspection.inputFields.isNotEmpty()) {
                    logs.add("تم رصد حقول إدخال بيانات الحساب: ${inspection.inputFields.joinToString(", ")}")
                    logs.add("جاهز لإدخال البريد الإلكتروني أو كلمة المرور بناءً على أمرك")
                    return Pair(true, logs)
                } else {
                    logs.add("لم يتم العثور على أزرار تسجيل دخول مباشرة على الشاشة الحالية")
                    return Pair(false, logs)
                }
            }

            logs.add("تم إرسال إشارة فتح الحساب والمصادقة للنظام")
            return Pair(true, logs)
        }

        // ========================================================
        // 8. CALL & WHATSAPP BACKGROUND UI AUTOMATION
        // ========================================================
        fun performAnswerCallViaUi(): Pair<Boolean, String> {
            if (serviceRef?.get() == null) {
                return Pair(false, "خدمة إمكانية الوصول غير مفعلة للرد التلقائي على المكالمة")
            }
            val answerKeywords = listOf("رد", "قبول", "إجابة", "اجابة", "Answer", "Accept", "Pickup")
            for (kw in answerKeywords) {
                val res = clickElementByText(kw)
                if (res.first) return Pair(true, "تم الرد على المكالمة تلقائياً عبر واجهة النظام ($kw) 📞✓")
            }
            return Pair(false, "لم يظهر زر الرد على المكالمة على الشاشة حالياً")
        }

        fun performEndCallViaUi(): Pair<Boolean, String> {
            if (serviceRef?.get() == null) {
                return Pair(false, "خدمة إمكانية الوصول غير مفعلة لإنهاء المكالمة")
            }
            val endKeywords = listOf("إنهاء", "انهاء", "رفض", "إغلاق", "End call", "End", "Decline", "Hang up", "Reject")
            for (kw in endKeywords) {
                val res = clickElementByText(kw)
                if (res.first) return Pair(true, "تم إنهاء المكالمة تلقائياً ($kw) 📵✓")
            }
            return Pair(false, "لم يتم العثور على زر إنهاء المكالمة على الشاشة الحالية")
        }

        fun performToggleSpeakerViaUi(): Pair<Boolean, String> {
            if (serviceRef?.get() == null) {
                return Pair(false, "خدمة إمكانية الوصول غير مفعلة")
            }
            val speakerKeywords = listOf("مكبر الصوت", "السبيكر", "سبيكر", "Speaker", "Loudspeaker")
            for (kw in speakerKeywords) {
                val res = clickElementByText(kw)
                if (res.first) return Pair(true, "تم تبديل مكبر الصوت ($kw) 🔊✓")
            }
            return Pair(false, "لم يتم العثور على زر مكبر الصوت على الشاشة")
        }

        suspend fun performWhatsAppSendStep(messageText: String): Pair<Boolean, String> {
            if (serviceRef?.get() == null) {
                return Pair(false, "خدمة إمكانية الوصول غير مفعلة لإكمال الإرسال داخل واتساب")
            }
            val typed = typeTextIntoField(null, messageText)
            if (!typed.first) {
                delay(400)
            }
            val sendKeywords = listOf("إرسال", "ارسال", "Send")
            for (kw in sendKeywords) {
                val clicked = clickElementByText(kw)
                if (clicked.first) {
                    return Pair(true, "تم كتابة الرسالة والنقر على إرسال داخل واتساب بنجاح 💬✓")
                }
            }
            return Pair(false, "تم فتح المحادثة، ولكن تعذر العثور على زر الإرسال تلقائياً")
        }
    }
}
