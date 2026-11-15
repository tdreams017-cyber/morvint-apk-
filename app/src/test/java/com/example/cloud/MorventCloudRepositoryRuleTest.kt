package com.example.cloud

import com.example.base.FirestoreEmulatorTestBase
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MorventCloudRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun createAndObserveTask_authenticatedOwner_succeeds() = runBlocking {
        val uid = signInTestUser("alice@morvent.test")
        val repo = MorventCloudRepository(firestore, auth)

        val profileRes = withTimeout(TIMEOUT_MS) {
            repo.ensureUserProfile(auth.currentUser!!, "gemini-3.5-flash-lite")
        }
        assertTrue("Profile upsert should succeed", profileRes.isSuccess)

        val createRes = withTimeout(TIMEOUT_MS) {
            repo.createTaskRecord(
                goal = "افتح يوتيوب وشغل قرآن كريم",
                status = "running",
                stepsCount = 2,
                aiProvider = "gemini-3.5-flash-lite"
            )
        }
        assertTrue("Task create should succeed", createRes.isSuccess)
        val taskId = createRes.getOrThrow()

        val observed = withTimeout(TIMEOUT_MS) {
            repo.observeTasks(uid).first { list -> list.any { it.id == taskId } }
        }
        val task = observed.first { it.id == taskId }
        assertEquals("running", task.status)
        assertEquals("افتح يوتيوب وشغل قرآن كريم", task.goal)

        val updateRes = withTimeout(TIMEOUT_MS) {
            repo.updateTaskRecord(
                taskId = taskId,
                status = "completed",
                summary = "تم التنفيذ بنجاح",
                stepsCount = 3,
                aiProvider = "gemini-3.5-flash-lite"
            )
        }
        assertTrue("Task update should succeed", updateRes.isSuccess)

        val msgRes = withTimeout(TIMEOUT_MS) {
            repo.saveChatMessage("السلام عليكم", isUser = true, status = "INFO")
        }
        assertTrue("Chat message create should succeed", msgRes.isSuccess)
    }

    @Test
    fun unauthenticatedRead_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser("alice2@morvent.test")
        val repo = MorventCloudRepository(firestore, auth)
        withTimeout(TIMEOUT_MS) {
            repo.createTaskRecord("مهمة خاصة", status = "running", stepsCount = 1)
        }

        auth.signOut()

        var thrown: Throwable? = null
        try {
            withTimeout(TIMEOUT_MS) {
                firestore.collection("users").document(aliceUid).collection("tasks").get().await()
            }
        } catch (e: Throwable) {
            thrown = e
        }
        val fsErr = thrown?.unwrapFirestoreException()
        assertNotNull("Expected FirebaseFirestoreException for unauthenticated read", fsErr)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsErr?.code)
    }

    @Test
    fun crossUserReadAndWrite_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser("alice3@morvent.test")
        val aliceRepo = MorventCloudRepository(firestore, auth)
        val taskId = withTimeout(TIMEOUT_MS) {
            aliceRepo.createTaskRecord("مهمة أليس", status = "completed", stepsCount = 1).getOrThrow()
        }

        signInTestUser("bob3@morvent.test")
        var crossReadErr: FirebaseFirestoreException? = null
        try {
            withTimeout(TIMEOUT_MS) {
                firestore.collection("users").document(aliceUid)
                    .collection("tasks").document(taskId).get().await()
            }
        } catch (e: Throwable) {
            crossReadErr = e.unwrapFirestoreException()
        }
        assertNotNull("Expected PERMISSION_DENIED on cross-user read", crossReadErr)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, crossReadErr?.code)
    }

    private companion object {
        const val TIMEOUT_MS = 8000L
    }
}
