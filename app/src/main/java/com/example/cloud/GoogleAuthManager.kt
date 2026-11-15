package com.example.cloud

import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.example.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        trySend(firebaseAuth.currentUser)
    }
    addAuthStateListener(listener)
    awaitClose { removeAuthStateListener(listener) }
}

/**
 * Handles Google Sign-In via Jetpack Credential Manager (never anonymous/email fallback in app code).
 */
object GoogleAuthManager {

    private const val TAG = "GoogleAuthManager"

    suspend fun attemptSilentGoogleSignIn(context: Context, auth: FirebaseAuth): FirebaseUser? {
        if (auth.currentUser != null) return auth.currentUser
        return try {
            val webClientId = context.getString(R.string.default_web_client_id)
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(true)
                .setServerClientId(webClientId)
                .setAutoSelectEnabled(true)
                .build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()
            val credentialManager = CredentialManager.create(context)
            val result = credentialManager.getCredential(context = context, request = request)
            val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(result.credential.data)
            val credential = GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)
            auth.signInWithCredential(credential).await().user
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: NoCredentialException) {
            null
        } catch (e: Exception) {
            Log.d(TAG, "Silent sign-in unavailable: ${e.message}")
            null
        }
    }

    suspend fun signInWithGoogleInteractive(
        activityContext: Context,
        auth: FirebaseAuth
    ): Result<FirebaseUser> {
        return try {
            val webClientId = activityContext.getString(R.string.default_web_client_id)
            val signInWithGoogleOption = GetSignInWithGoogleOption.Builder(webClientId).build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(signInWithGoogleOption)
                .build()

            val credentialManager = CredentialManager.create(activityContext)
            val result = credentialManager.getCredential(context = activityContext, request = request)
            val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(result.credential.data)
            val firebaseCredential = GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)
            val authResult = auth.signInWithCredential(firebaseCredential).await()
            val user = authResult.user
                ?: return Result.failure(IllegalStateException("فشل استلام بيانات حساب Google"))
            Result.success(user)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: GetCredentialCancellationException) {
            Log.w(TAG, "Interactive Google sign-in cancelled (possible SHA-1 fingerprint or user cancel)", e)
            Result.failure(Exception("تم إلغاء تسجيل الدخول عبر Google"))
        } catch (e: Exception) {
            Log.e(TAG, "Interactive Google sign-in failed", e)
            Result.failure(e)
        }
    }

    suspend fun signOut(context: Context, auth: FirebaseAuth) {
        runCatching {
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
        }
        auth.signOut()
    }
}
