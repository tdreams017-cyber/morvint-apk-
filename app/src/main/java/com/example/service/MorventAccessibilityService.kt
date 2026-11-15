package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
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
    val isFocused: Boolean
)

data class RichScreenSnapshot(
    val packageName: String = "",
    val activityName: String = "",
    val screenWidth: Int = 1080,
    val screenHeight: Int = 2400,
    val elements: List<IndexedUiElement> = emptyList(),
    val formattedTree: String = "",
    val visibleTexts: List<String> = emptyList()
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
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val cls = event.className?.toString()
                if (!pkg.isNullOrBlank() && pkg != packageName) {
                    _currentForegroundPackage.value = pkg
                }
                if (!cls.isNullOrBlank()) {
                    _currentForegroundActivity.value = cls
                }
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

        val isRunning: Boolean
            get() = serviceRef?.get() != null

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

        fun captureRichScreenSnapshot(): RichScreenSnapshot {
            val service = serviceRef?.get() ?: return RichScreenSnapshot()
            val (screenW, screenH) = getScreenDimensions()
            val ownPackage = service.packageName

            val roots = mutableListOf<AccessibilityNodeInfo>()
            try {
                val allWindows = service.windows
                if (!allWindows.isNullOrEmpty()) {
                    for (window in allWindows) {
                        val root = window.root ?: continue
                        val pkg = root.packageName?.toString() ?: ""
                        // Exclude Morvent's own floating overlay HUD so the agent doesn't click its own bubble
                        val isOwnOverlay = pkg == ownPackage && window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                        if (!isOwnOverlay) {
                            roots.add(root)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed reading windows list, using rootInActiveWindow", e)
            }

            if (roots.isEmpty()) {
                service.rootInActiveWindow?.let { roots.add(it) }
            }

            if (roots.isEmpty()) {
                return RichScreenSnapshot(
                    packageName = _currentForegroundPackage.value,
                    activityName = _currentForegroundActivity.value,
                    screenWidth = screenW,
                    screenHeight = screenH
                )
            }

            indexedBoundsMap.clear()
            val elements = mutableListOf<IndexedUiElement>()
            val visibleTexts = mutableListOf<String>()
            val treeLines = mutableListOf<String>()
            var nextId = 0
            var detectedPkg = ""

            fun traverse(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null || nextId >= 110) return
                if (!node.isVisibleToUser) return

                val pkg = node.packageName?.toString() ?: ""
                if (detectedPkg.isBlank() && pkg.isNotBlank() && pkg != ownPackage) {
                    detectedPkg = pkg
                }

                val text = node.text?.toString()?.trim()?.replace("\n", " ") ?: ""
                val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    node.hintText?.toString()?.trim()?.replace("\n", " ") ?: ""
                } else ""
                val desc = node.contentDescription?.toString()?.trim()?.replace("\n", " ") ?: ""
                val viewIdFull = node.viewIdResourceName ?: ""
                val shortViewId = viewIdFull.substringAfterLast('/')
                val clsShort = node.className?.toString()?.substringAfterLast('.') ?: ""

                val rect = Rect()
                node.getBoundsInScreen(rect)
                val validBounds = rect.width() > 2 && rect.height() > 2 &&
                        rect.right > 0 && rect.bottom > 0 &&
                        rect.left < screenW && rect.top < screenH

                val label = when {
                    text.isNotBlank() -> text
                    desc.isNotBlank() -> desc
                    hint.isNotBlank() -> "[تلميح: $hint]"
                    else -> ""
                }

                if (label.isNotBlank()) {
                    visibleTexts.add(label)
                }

                val isInteractive = node.isClickable || node.isLongClickable ||
                        node.isEditable || node.isScrollable || node.isCheckable

                if (validBounds && (isInteractive || label.isNotBlank())) {
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
                        isFocused = node.isFocused
                    )
                    elements.add(el)

                    val flags = buildList {
                        if (node.isClickable) add("CLICK")
                        if (node.isEditable) add("EDIT")
                        if (node.isScrollable) add("SCROLL")
                        if (node.isCheckable) add(if (node.isChecked) "CHECKED" else "UNCHECKED")
                        if (node.isFocused) add("FOCUSED")
                    }.joinToString(",")

                    val idTag = if (shortViewId.isNotBlank()) " #$shortViewId" else ""
                    val labelTag = if (label.isNotBlank()) " \"$label\"" else ""
                    val flagsTag = if (flags.isNotBlank()) " [$flags]" else ""
                    treeLines.add("[$id] $clsShort$idTag$labelTag$flagsTag @($normX,$normY)")
                }

                for (i in 0 until node.childCount) {
                    traverse(node.getChild(i), depth + 1)
                    if (nextId >= 110) break
                }
            }

            for (root in roots) {
                traverse(root, 0)
            }

            val finalPkg = detectedPkg.ifBlank {
                _currentForegroundPackage.value.ifBlank {
                    roots.firstOrNull()?.packageName?.toString() ?: ""
                }
            }

            return RichScreenSnapshot(
                packageName = finalPkg,
                activityName = _currentForegroundActivity.value,
                screenWidth = screenW,
                screenHeight = screenH,
                elements = elements,
                formattedTree = treeLines.joinToString("\n"),
                visibleTexts = visibleTexts.distinct().take(40)
            )
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
                .take(15)
            val inputFields = rich.elements
                .filter { it.isEditable || it.className.contains("EditText", ignoreCase = true) }
                .map { it.label.ifBlank { it.viewId.ifBlank { "حقل إدخال" } } }
                .distinct()
                .take(10)

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
                var matchedNode: AccessibilityNodeInfo? = null
                val nodeRect = Rect()
                fun findAtRect(node: AccessibilityNodeInfo?) {
                    if (node == null || matchedNode != null) return
                    node.getBoundsInScreen(nodeRect)
                    if (nodeRect == rect && node.isClickable) {
                        matchedNode = node
                        return
                    }
                    for (i in 0 until node.childCount) {
                        findAtRect(node.getChild(i))
                    }
                }
                for (root in roots) {
                    findAtRect(root)
                    if (matchedNode != null) break
                }

                if (matchedNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                    return Pair(true, "تم النقر على العنصر [$elementId] بنجاح")
                }

                val tapped = performHumanTap(rect.centerX().toFloat(), rect.centerY().toFloat())
                if (tapped) {
                    return Pair(true, "تم النقر البشري على العنصر [$elementId] عند (${rect.centerX()}, ${rect.centerY()})")
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

        fun clickElementByText(query: String): Pair<Boolean, String> {
            val service = serviceRef?.get()
                ?: return Pair(false, "خدمة إمكانية الوصول غير مفعلة")
            val roots = getAllActiveWindowRoots(service)
            if (roots.isEmpty()) {
                return Pair(false, "تعذر قراءة محتوى الشاشة النشطة")
            }

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

            val matched = targetNode
                ?: return Pair(false, "لم يتم العثور على عنصر بالشاشة يحتوي على '$query'")

            // If query matches a Settings row label with a sibling Switch/CheckBox inside the same container, toggle it
            val siblingToggle = findCheckableInAncestors(matched)
            if (siblingToggle != null && siblingToggle.isClickable) {
                if (siblingToggle.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return Pair(true, "تم تبديل خيار '$query' بنجاح ✓")
                }
            }

            var clickTarget: AccessibilityNodeInfo? = matched
            while (clickTarget != null && !clickTarget.isClickable) {
                clickTarget = clickTarget.parent
            }

            if (clickTarget != null && clickTarget.isClickable) {
                val ok = clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (ok) return Pair(true, "تم النقر على '$query' بنجاح")
            }

            val rect = Rect()
            matched.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0) {
                val tapped = dispatchTapGesture(rect.centerX().toFloat(), rect.centerY().toFloat())
                if (tapped) {
                    return Pair(true, "تم النقر بإيماءة اللمس على '$query' عند الإحداثيات (${rect.centerX()}, ${rect.centerY()})")
                }
            }

            return Pair(false, "تعذر تنفيذ النقر على '$query'")
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
                if (isEdit) {
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

            editNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
            }
            val success = editNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

            if (success && submitEnter && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                editNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }

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

        fun typeTextIntoField(targetField: String?, textToType: String): Pair<Boolean, String> {
            val service = serviceRef?.get()
                ?: return Pair(false, "خدمة الوصول غير مفعلة")
            val roots = getAllActiveWindowRoots(service)
            if (roots.isEmpty()) return Pair(false, "تعذر قراءة نافذة التطبيق النشطة")

            var targetNode: AccessibilityNodeInfo? = null

            fun findEditable(node: AccessibilityNodeInfo?) {
                if (node == null || targetNode != null) return
                val isEdit = node.isEditable || node.className?.toString()?.contains("EditText", ignoreCase = true) == true
                if (isEdit) {
                    if (targetField.isNullOrBlank()) {
                        targetNode = node
                        return
                    } else {
                        val text = node.text?.toString() ?: ""
                        val desc = node.contentDescription?.toString() ?: ""
                        val id = node.viewIdResourceName ?: ""
                        if (text.contains(targetField, ignoreCase = true) ||
                            desc.contains(targetField, ignoreCase = true) ||
                            id.contains(targetField, ignoreCase = true)
                        ) {
                            targetNode = node
                            return
                        }
                    }
                }

                for (i in 0 until node.childCount) {
                    findEditable(node.getChild(i))
                    if (targetNode != null) return
                }
            }

            for (root in roots) {
                findEditable(root)
                if (targetNode != null) break
            }

            val editNode = targetNode
                ?: return Pair(false, "لم يتم العثور على حقل إدخال نشط على الشاشة")

            editNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
            }
            val success = editNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

            return if (success) {
                Pair(true, "تمت كتابة '$textToType' في الحقل بنجاح")
            } else {
                Pair(false, "تعذر إدخال النص في الحقل المطلوب")
            }
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

            val gestured = dispatchSwipeGesture(startX, startY, startX, endY, 340)
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

            val jitterX = ((-3..3).random()).toFloat()
            val jitterY = ((-3..3).random()).toFloat()
            val tapX = (x + jitterX).coerceAtLeast(1f)
            val tapY = (y + jitterY).coerceAtLeast(1f)

            val path = Path().apply {
                moveTo(tapX, tapY)
                lineTo(tapX + 0.6f, tapY + 0.9f)
            }
            val duration = (75L..120L).random()
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
            val stroke1 = GestureDescription.StrokeDescription(p1, 0, 75L)
            val stroke2 = GestureDescription.StrokeDescription(p2, 145L, 75L)
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

            val ok = dispatchSwipeGesture(startX, y, endX, y, 360L)
            return if (ok) {
                Pair(true, if (toRight) "تم السحب لليمين 👉" else "تم السحب لليسار 👈")
            } else {
                Pair(false, "تعذر تنفيذ السحب الأفقي")
            }
        }

        fun dispatchSwipeGesture(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 380): Boolean {
            val service = serviceRef?.get() ?: return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

            val path = Path().apply {
                moveTo(startX, startY)
                val midX = (startX + endX) / 2f + (if (startX < endX) 18f else -18f)
                val midY = (startY + endY) / 2f + ((-12..12).random()).toFloat()
                quadTo(midX, midY, endX, endY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
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
