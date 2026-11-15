package com.example.service

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference
import java.util.Locale

data class CapturedNotificationMessage(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val sender: String,
    val text: String,
    val timestamp: Long,
    val canDirectReply: Boolean
)

private data class ReplyActionHandle(
    val pendingIntent: PendingIntent,
    val remoteInputs: Array<RemoteInput>,
    val packageName: String,
    val sender: String,
    val lastText: String,
    val timestamp: Long
)

/**
 * Captures live notifications across WhatsApp, Telegram, Messenger, SMS, and system apps,
 * and allows Morvent to read messages or fire silent background RemoteInput replies
 * without interrupting the foreground screen.
 */
class MorventNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        serviceRef = WeakReference(this)
        _isConnected.value = true
        refreshActiveNotifications()
        Log.d(TAG, "MorventNotificationListenerService connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        _isConnected.value = false
        if (serviceRef?.get() == this) {
            serviceRef = null
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName == packageName) return
        val captured = extractAndStore(sbn)
        if (captured != null) {
            _incomingEvents.tryEmit(captured)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        val updated = _recentMessages.value.filterNot { it.key == sbn.key }
        _recentMessages.value = updated
    }

    private fun refreshActiveNotifications() {
        try {
            activeNotifications?.forEach { sbn ->
                if (sbn.packageName != packageName) {
                    extractAndStore(sbn)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read active notifications on connect", e)
        }
    }

    private fun extractAndStore(sbn: StatusBarNotification): CapturedNotificationMessage? {
        val notification = sbn.notification ?: return null
        val extras = notification.extras ?: return null

        val isGroupSummary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        if (isGroupSummary) return null

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val plainText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()

        val messageBody = bigText.ifBlank { plainText }.ifBlank { subText }
        if (title.isBlank() && messageBody.isBlank()) return null

        val replyAction = findRemoteInputAction(notification)
        if (replyAction != null) {
            val (pi, remoteInputs) = replyAction
            replyHandles[sbn.key] = ReplyActionHandle(
                pendingIntent = pi,
                remoteInputs = remoteInputs,
                packageName = sbn.packageName,
                sender = title.ifBlank { friendlyAppName(sbn.packageName) },
                lastText = messageBody,
                timestamp = sbn.postTime
            )
        }

        val item = CapturedNotificationMessage(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = friendlyAppName(sbn.packageName),
            sender = title.ifBlank { friendlyAppName(sbn.packageName) },
            text = messageBody,
            timestamp = sbn.postTime,
            canDirectReply = replyAction != null
        )

        synchronized(messagesLock) {
            val current = _recentMessages.value.toMutableList()
            current.removeAll { it.key == item.key || (it.sender == item.sender && it.text == item.text && it.packageName == item.packageName) }
            current.add(0, item)
            if (current.size > 40) {
                _recentMessages.value = current.take(40)
            } else {
                _recentMessages.value = current
            }
        }
        return item
    }

    private fun findRemoteInputAction(notification: Notification): Pair<PendingIntent, Array<RemoteInput>>? {
        val actions = notification.actions ?: return null
        for (action in actions) {
            val inputs = action.remoteInputs
            val pi = action.actionIntent
            if (!inputs.isNullOrEmpty() && pi != null) {
                return Pair(pi, inputs)
            }
        }
        return null
    }

    companion object {
        private const val TAG = "MorventNotifListener"
        private var serviceRef: WeakReference<MorventNotificationListenerService>? = null
        private val messagesLock = Any()
        private val replyHandles = LinkedHashMap<String, ReplyActionHandle>()

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        private val _recentMessages = MutableStateFlow<List<CapturedNotificationMessage>>(emptyList())
        val recentMessages: StateFlow<List<CapturedNotificationMessage>> = _recentMessages.asStateFlow()

        private val _incomingEvents = MutableSharedFlow<CapturedNotificationMessage>(extraBufferCapacity = 24)
        val incomingEvents: SharedFlow<CapturedNotificationMessage> = _incomingEvents.asSharedFlow()

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val target = ComponentName(context, MorventNotificationListenerService::class.java).flattenToString()
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(flat)
            while (splitter.hasNext()) {
                if (splitter.next().equals(target, ignoreCase = true)) {
                    return true
                }
            }
            return false
        }

        fun openSettings(context: Context): Boolean {
            return try {
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            } catch (_: Exception) {
                false
            }
        }

        fun getMessagesForApp(appFilter: String? = null): List<CapturedNotificationMessage> {
            val all = _recentMessages.value
            if (appFilter.isNullOrBlank()) return all
            val q = appFilter.lowercase(Locale.ROOT)
            return all.filter {
                it.appLabel.lowercase(Locale.ROOT).contains(q) ||
                    it.packageName.lowercase(Locale.ROOT).contains(q) ||
                    it.sender.lowercase(Locale.ROOT).contains(q)
            }
        }

        fun sendSilentReply(
            context: Context,
            recipientOrAppQuery: String?,
            replyText: String
        ): Pair<Boolean, String> {
            val cleanReply = replyText.trim()
            if (cleanReply.isEmpty()) {
                return Pair(false, "نص الرد فارغ.")
            }

            val queryLower = recipientOrAppQuery?.trim()?.lowercase(Locale.ROOT).orEmpty()
            val handlesSnapshot = synchronized(messagesLock) {
                replyHandles.values.sortedByDescending { it.timestamp }
            }

            val matched = when {
                queryLower.isBlank() -> handlesSnapshot.firstOrNull()
                queryLower.contains("واتساب") || queryLower.contains("whatsapp") || queryLower.contains("واتس") ->
                    handlesSnapshot.firstOrNull { it.packageName.contains("whatsapp", ignoreCase = true) }
                        ?: handlesSnapshot.firstOrNull { it.sender.lowercase(Locale.ROOT).contains(queryLower) }
                else ->
                    handlesSnapshot.firstOrNull {
                        it.sender.lowercase(Locale.ROOT).contains(queryLower) ||
                            it.packageName.lowercase(Locale.ROOT).contains(queryLower) ||
                            friendlyAppName(it.packageName).lowercase(Locale.ROOT).contains(queryLower)
                    } ?: handlesSnapshot.firstOrNull()
            }

            if (matched == null) {
                return Pair(false, "NO_ACTIVE_REMOTE_INPUT")
            }

            return try {
                val replyIntent = Intent()
                val bundle = Bundle()
                for (remoteInput in matched.remoteInputs) {
                    bundle.putCharSequence(remoteInput.resultKey, cleanReply)
                }
                RemoteInput.addResultsToIntent(matched.remoteInputs, replyIntent, bundle)
                matched.pendingIntent.send(context.applicationContext, 0, replyIntent)
                Pair(
                    true,
                    "تم الرد بصمت في الخلفية على **${matched.sender}** (${friendlyAppName(matched.packageName)}):\n\"$cleanReply\" ✓"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Silent RemoteInput reply failed", e)
                Pair(false, "تعذر إرسال الرد عبر الإشعار: ${e.message}")
            }
        }

        fun dismissAllCaptured() {
            val service = serviceRef?.get() ?: return
            try {
                service.cancelAllNotifications()
            } catch (e: Exception) {
                Log.w(TAG, "cancelAllNotifications failed", e)
            }
        }

        fun friendlyAppName(pkg: String): String = when {
            pkg == "com.whatsapp" -> "واتساب"
            pkg == "com.whatsapp.w4b" -> "واتساب للأعمال"
            pkg.contains("telegram") -> "تيليجرام"
            pkg.contains("orca") || pkg.contains("messenger") -> "ماسنجر"
            pkg.contains("instagram") -> "إنستغرام"
            pkg.contains("messaging") || pkg.contains("mms") || pkg.contains("sms") -> "الرسائل النصية"
            pkg.contains("dialer") || pkg.contains("telecom") -> "المكالمات"
            pkg.contains("gm") || pkg.contains("gmail") -> "Gmail"
            else -> pkg.substringAfterLast('.')
        }
    }
}
