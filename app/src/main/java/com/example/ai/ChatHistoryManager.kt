package com.example.ai

import android.content.Context
import android.content.SharedPreferences
import com.example.model.ChatAttachment
import com.example.model.ChatMessage
import com.example.model.MessageStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ChatSessionSummary(
    val id: String,
    val title: String,
    val lastPreview: String,
    val messageCount: Int,
    val updatedAt: Long
)

/**
 * Persists multi-session chat threads and message history locally via JSON in SharedPreferences.
 */
class ChatHistoryManager private constructor(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _sessions = MutableStateFlow<List<ChatSessionSummary>>(loadSessionSummaries())
    val sessions: StateFlow<List<ChatSessionSummary>> = _sessions.asStateFlow()

    private val _currentSessionId = MutableStateFlow(ensureActiveSessionId())
    val currentSessionId: StateFlow<String> = _currentSessionId.asStateFlow()

    private fun ensureActiveSessionId(): String {
        val saved = prefs.getString(KEY_CURRENT_SESSION_ID, null)
        if (!saved.isNullOrBlank()) return saved
        val existingFirst = loadSessionSummaries().firstOrNull()?.id
        if (!existingFirst.isNullOrBlank()) {
            prefs.edit().putString(KEY_CURRENT_SESSION_ID, existingFirst).apply()
            return existingFirst
        }
        val freshId = "session_${UUID.randomUUID().toString().take(8)}"
        prefs.edit().putString(KEY_CURRENT_SESSION_ID, freshId).apply()
        return freshId
    }

    fun listSessions(): List<ChatSessionSummary> = _sessions.value

    fun createNewSession(initialMessages: List<ChatMessage> = emptyList()): String {
        val newId = "session_${UUID.randomUUID().toString().take(8)}"
        prefs.edit().putString(KEY_CURRENT_SESSION_ID, newId).apply()
        _currentSessionId.value = newId
        saveSession(newId, initialMessages)
        return newId
    }

    fun switchSession(sessionId: String): List<ChatMessage> {
        prefs.edit().putString(KEY_CURRENT_SESSION_ID, sessionId).apply()
        _currentSessionId.value = sessionId
        return loadSessionMessages(sessionId)
    }

    fun saveSession(sessionId: String, messages: List<ChatMessage>) {
        // Keep up to 100 latest messages per session for fast JSON serialization
        val recent = messages.takeLast(100)
        val arr = JSONArray()
        recent.forEach { msg ->
            arr.put(JSONObject().apply {
                put("id", msg.id)
                put("text", msg.text)
                put("isUser", msg.isUser)
                put("timestamp", msg.timestamp)
                put("status", msg.status.name)
                if (!msg.actionDetail.isNullOrBlank()) put("actionDetail", msg.actionDetail)
                if (msg.subSteps.isNotEmpty()) {
                    put("subSteps", JSONArray(msg.subSteps))
                }
                if (msg.attachments.isNotEmpty()) {
                    val attArr = JSONArray()
                    msg.attachments.forEach { att ->
                        attArr.put(JSONObject().apply {
                            put("uriString", att.uriString)
                            put("displayName", att.displayName)
                            put("mimeType", att.mimeType)
                            put("isImage", att.isImage)
                            put("sizeBytes", att.sizeBytes)
                            if (!att.localLinuxPath.isNullOrBlank()) {
                                put("localLinuxPath", att.localLinuxPath)
                            }
                        })
                    }
                    put("attachments", attArr)
                }
            })
        }
        prefs.edit().putString("$KEY_MESSAGES_PREFIX$sessionId", arr.toString()).apply()

        val firstUserMsg = recent.firstOrNull { it.isUser && it.text.isNotBlank() }?.text
        val title = firstUserMsg?.take(38)?.let {
            if (firstUserMsg.length > 38) "$it…" else it
        } ?: "محادثة جديدة"

        val lastNonEmpty = recent.lastOrNull { it.text.isNotBlank() }?.text ?: "جاهز للتنفيذ"
        val preview = lastNonEmpty.replace("\n", " ").take(54)

        val currentList = _sessions.value.toMutableList()
        val idx = currentList.indexOfFirst { it.id == sessionId }
        val summary = ChatSessionSummary(
            id = sessionId,
            title = title,
            lastPreview = preview,
            messageCount = recent.size,
            updatedAt = recent.lastOrNull()?.timestamp ?: System.currentTimeMillis()
        )
        if (idx >= 0) {
            currentList[idx] = summary
        } else {
            currentList.add(0, summary)
        }
        val ordered = currentList.sortedByDescending { it.updatedAt }.take(MAX_SESSIONS)
        _sessions.value = ordered
        saveSessionSummaries(ordered)
    }

    fun loadSessionMessages(sessionId: String): List<ChatMessage> {
        val raw = prefs.getString("$KEY_MESSAGES_PREFIX$sessionId", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val statusName = obj.optString("status", MessageStatus.SUCCESS.name)
                val status = runCatching { MessageStatus.valueOf(statusName) }.getOrDefault(MessageStatus.SUCCESS)

                val subStepsArr = obj.optJSONArray("subSteps")
                val subSteps = if (subStepsArr != null) {
                    (0 until subStepsArr.length()).mapNotNull { subStepsArr.optString(it) }
                } else emptyList()

                val attArr = obj.optJSONArray("attachments")
                val attachments = if (attArr != null) {
                    (0 until attArr.length()).mapNotNull { j ->
                        val a = attArr.optJSONObject(j) ?: return@mapNotNull null
                        ChatAttachment(
                            uriString = a.optString("uriString"),
                            displayName = a.optString("displayName", "file"),
                            mimeType = a.optString("mimeType", "*/*"),
                            isImage = a.optBoolean("isImage", false),
                            sizeBytes = a.optLong("sizeBytes", 0L),
                            localLinuxPath = a.optString("localLinuxPath").takeIf { it.isNotBlank() }
                        )
                    }
                } else emptyList()

                ChatMessage(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    text = obj.optString("text", ""),
                    isUser = obj.optBoolean("isUser", false),
                    timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                    status = status,
                    actionDetail = obj.optString("actionDetail").takeIf { it.isNotBlank() },
                    subSteps = subSteps,
                    attachments = attachments
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun deleteSession(sessionId: String): String {
        prefs.edit().remove("$KEY_MESSAGES_PREFIX$sessionId").apply()
        val updated = _sessions.value.filterNot { it.id == sessionId }
        _sessions.value = updated
        saveSessionSummaries(updated)

        return if (_currentSessionId.value == sessionId) {
            val nextId = updated.firstOrNull()?.id ?: createNewSession()
            prefs.edit().putString(KEY_CURRENT_SESSION_ID, nextId).apply()
            _currentSessionId.value = nextId
            nextId
        } else {
            _currentSessionId.value
        }
    }

    private fun loadSessionSummaries(): List<ChatSessionSummary> {
        val raw = prefs.getString(KEY_SESSIONS_INDEX, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("id")
                if (id.isBlank()) null else ChatSessionSummary(
                    id = id,
                    title = obj.optString("title", "محادثة"),
                    lastPreview = obj.optString("lastPreview", ""),
                    messageCount = obj.optInt("messageCount", 0),
                    updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
                )
            }.sortedByDescending { it.updatedAt }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveSessionSummaries(list: List<ChatSessionSummary>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("title", s.title)
                put("lastPreview", s.lastPreview)
                put("messageCount", s.messageCount)
                put("updatedAt", s.updatedAt)
            })
        }
        prefs.edit().putString(KEY_SESSIONS_INDEX, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "morvent_chat_history"
        private const val KEY_CURRENT_SESSION_ID = "current_session_id"
        private const val KEY_SESSIONS_INDEX = "sessions_index"
        private const val KEY_MESSAGES_PREFIX = "messages_"
        private const val MAX_SESSIONS = 35

        @Volatile
        private var INSTANCE: ChatHistoryManager? = null

        fun getInstance(context: Context): ChatHistoryManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ChatHistoryManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
