package com.example.cloud

import android.content.Context
import android.util.Log
import com.example.R
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ServerTimestamp
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

enum class OperationType(val value: String) {
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),
    LIST("list"),
    GET("get"),
    WRITE("write"),
}

@IgnoreExtraProperties
data class CloudUserProfile(
    @DocumentId val id: String = "",
    val uid: String = "",
    val displayName: String = "",
    val email: String = "",
    val preferredModel: String = "gemini-3.5-flash-lite",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class CloudTaskRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val goal: String = "",
    val status: String = "running",
    val summary: String = "",
    val stepsCount: Long = 0L,
    val aiProvider: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class CloudChatRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val text: String = "",
    @get:PropertyName("isUser") @field:PropertyName("isUser") val isUser: Boolean = false,
    val status: String = "INFO",
    @ServerTimestamp val createdAt: Timestamp? = null
)

class MorventCloudRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth
) {
    constructor(context: Context, auth: FirebaseAuth) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        auth
    )

    private fun requireUserId(): String =
        auth.currentUser?.uid ?: throw IllegalStateException("المستخدم غير مسجل الدخول")

    fun handleFirestoreError(exception: Throwable, operationType: OperationType, path: String?): String {
        val causes = generateSequence(exception) { it.cause }
        val rootError = causes.firstOrNull { it is FirebaseFirestoreException }
            ?: causes.firstOrNull { it !is CancellationException }
            ?: exception

        val currentUser = auth.currentUser
        val providerInfoList = currentUser?.providerData?.map { provider ->
            JSONObject().apply {
                put("providerId", provider.providerId)
                put("email", provider.email)
            }
        } ?: emptyList()

        val authInfoJson = JSONObject().apply {
            put("userId", currentUser?.uid)
            put("email", currentUser?.email)
            put("emailVerified", currentUser?.isEmailVerified)
            put("tenantId", currentUser?.tenantId)
            put("providerInfo", JSONArray(providerInfoList))
        }

        val errorInfoJson = JSONObject().apply {
            put("error", rootError.message ?: rootError.toString())
            put("operationType", operationType.value)
            put("path", path)
            put("authInfo", authInfoJson)
        }

        val jsonString = errorInfoJson.toString()
        Log.e("FirestoreError", "Firestore Error: $jsonString")
        return jsonString
    }

    suspend fun ensureUserProfile(user: FirebaseUser, preferredModel: String = "gemini-3.5-flash-lite"): Result<Unit> {
        val uid = user.uid
        val docRef = db.collection("users").document(uid)
        return try {
            val existing = docRef.get().await()
            val payload = hashMapOf<String, Any>(
                "uid" to uid,
                "displayName" to (user.displayName ?: "مستخدم مورڤينت").take(120),
                "email" to (user.email ?: "").take(200),
                "preferredModel" to preferredModel.take(80),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (existing.exists()) {
                payload["createdAt"] = existing.getTimestamp("createdAt") ?: FieldValue.serverTimestamp()
                docRef.set(payload).await()
            } else {
                payload["createdAt"] = FieldValue.serverTimestamp()
                docRef.set(payload).await()
            }
            Result.success(Unit)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun updatePreferredModel(preferredModel: String): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Unauthenticated"))
        val docRef = db.collection("users").document(uid)
        return try {
            val existing = docRef.get().await()
            val displayName = auth.currentUser?.displayName ?: "مستخدم مورڤينت"
            val email = auth.currentUser?.email ?: ""
            val payload = hashMapOf<String, Any>(
                "uid" to uid,
                "displayName" to displayName.take(120),
                "email" to email.take(200),
                "preferredModel" to preferredModel.take(80),
                "createdAt" to (existing.getTimestamp("createdAt") ?: FieldValue.serverTimestamp()),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            Result.success(Unit)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun createTaskRecord(
        goal: String,
        status: String = "running",
        summary: String = "",
        stepsCount: Int = 0,
        aiProvider: String = "gemini-3.5-flash-lite"
    ): Result<String> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val cleanGoal = goal.trim().take(2000)
        if (cleanGoal.isEmpty()) return Result.failure(IllegalArgumentException("Goal is empty"))

        val tasksRef = db.collection("users").document(uid).collection("tasks")
        val docId = "task_${System.currentTimeMillis()}_${(100..999).random()}"
        val docRef = tasksRef.document(docId)
        val record = CloudTaskRecord(
            userId = uid,
            goal = cleanGoal,
            status = status,
            summary = summary.take(4000),
            stepsCount = stepsCount.coerceIn(0, 1000).toLong(),
            aiProvider = aiProvider.take(100),
            createdAt = null,
            updatedAt = null
        )
        return try {
            docRef.set(record).await()
            Result.success(docId)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateTaskRecord(
        taskId: String,
        status: String,
        summary: String,
        stepsCount: Int,
        aiProvider: String
    ): Result<Unit> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val docRef = db.collection("users").document(uid).collection("tasks").document(taskId)
        return try {
            val existing = docRef.get().await().toObject(
                CloudTaskRecord::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            ) ?: return Result.failure(NoSuchElementException("Task $taskId not found"))
            val updated = existing.copy(
                status = status,
                summary = summary.take(4000),
                stepsCount = stepsCount.coerceIn(0, 1000).toLong(),
                aiProvider = aiProvider.take(100),
                updatedAt = null
            )
            docRef.set(updated).await()
            Result.success(Unit)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun saveChatMessage(
        text: String,
        isUser: Boolean,
        status: String = "INFO"
    ): Result<String> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val cleanText = text.trim().take(8000)
        if (cleanText.isEmpty()) return Result.failure(IllegalArgumentException("Message empty"))

        val messagesRef = db.collection("users").document(uid).collection("messages")
        val docId = "msg_${System.currentTimeMillis()}_${(100..999).random()}"
        val docRef = messagesRef.document(docId)
        val payload = hashMapOf<String, Any>(
            "userId" to uid,
            "text" to cleanText,
            "isUser" to isUser,
            "status" to status,
            "createdAt" to FieldValue.serverTimestamp()
        )
        return try {
            docRef.set(payload).await()
            Result.success(docId)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    fun observeTasks(userId: String, limit: Long = 25): Flow<List<CloudTaskRecord>> {
        return db.collection("users").document(userId).collection("tasks")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(limit)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    CloudTaskRecord::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }
    }

    fun observeChatMessages(userId: String, limit: Long = 40): Flow<List<CloudChatRecord>> {
        return db.collection("users").document(userId).collection("messages")
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .limit(limit)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    CloudChatRecord::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }
    }

    suspend fun getRecentTasks(userId: String): Result<List<CloudTaskRecord>> {
        val path = "users/$userId/tasks"
        return try {
            val snap = db.collection("users").document(userId).collection("tasks")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(25)
                .get()
                .await()
            Result.success(
                snap.toObjects(
                    CloudTaskRecord::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, path)
            Result.failure(e)
        }
    }
}
