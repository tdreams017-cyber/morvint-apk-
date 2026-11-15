package com.example

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ai.configureAppCheck
import com.example.cloud.GoogleAuthManager
import com.example.cloud.authStateFlow
import com.example.nlp.MorventAgentHub
import com.example.ui.MorventMainScreen
import com.example.ui.MorventViewModel
import com.example.ui.screens.MorventAuthScreen
import com.example.ui.theme.MyApplicationTheme
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val triggerVoiceState = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureAppCheck(this, intent)
        enableEdgeToEdge()
        checkVoiceTriggerIntent(intent)

        setContent {
            MyApplicationTheme(darkTheme = true) {
                val auth = remember { Firebase.auth }
                val currentUser by auth.authStateFlow()
                    .collectAsStateWithLifecycle(initialValue = auth.currentUser)

                var isSigningIn by remember { mutableStateOf(false) }
                var authError by remember { mutableStateOf<String?>(null) }
                val coroutineScope = rememberCoroutineScope()

                // Attempt silent Google Sign-In once on launch when logged out
                LaunchedEffect(Unit) {
                    if (auth.currentUser == null) {
                        isSigningIn = true
                        GoogleAuthManager.attemptSilentGoogleSignIn(this@MainActivity, auth)
                        isSigningIn = false
                    }
                }

                // Arabic is RTL
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    val activeUser = currentUser
                    if (activeUser == null) {
                        MorventAuthScreen(
                            isSigningIn = isSigningIn,
                            errorMessage = authError,
                            onSignInWithGoogle = {
                                if (!isSigningIn) {
                                    isSigningIn = true
                                    authError = null
                                    coroutineScope.launch {
                                        val res = GoogleAuthManager.signInWithGoogleInteractive(
                                            activityContext = this@MainActivity,
                                            auth = auth
                                        )
                                        res.onFailure { err ->
                                            authError = err.localizedMessage ?: "تعذر تسجيل الدخول عبر Google"
                                        }
                                        isSigningIn = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        val viewModel: MorventViewModel = viewModel()

                        LaunchedEffect(activeUser.uid) {
                            viewModel.bindAuthenticatedUser(activeUser.uid)
                        }

                        val voiceLauncher = rememberLauncherForActivityResult(
                            contract = ActivityResultContracts.StartActivityForResult()
                        ) { result ->
                            if (result.resultCode == Activity.RESULT_OK) {
                                val spoken = result.data
                                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                                    ?.firstOrNull()
                                    ?.trim()
                                if (!spoken.isNullOrEmpty()) {
                                    MorventAgentHub.getInstance(applicationContext)
                                        .submitCommand(spoken, sourceLabel = "الأمر الصوتي بالنظام")
                                }
                            }
                        }

                        val permissionsLauncher = rememberLauncherForActivityResult(
                            contract = ActivityResultContracts.RequestMultiplePermissions()
                        ) { _ ->
                            viewModel.refreshDeviceStatus()
                        }

                        val requestPhoneContacts: () -> Unit = {
                            val callPerms = mutableListOf(
                                Manifest.permission.CALL_PHONE,
                                Manifest.permission.READ_PHONE_STATE,
                                Manifest.permission.READ_CONTACTS
                            )
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                callPerms.add(Manifest.permission.ANSWER_PHONE_CALLS)
                            }
                            permissionsLauncher.launch(callPerms.toTypedArray())
                        }

                        LaunchedEffect(Unit) {
                            val permissionsNeeded = mutableListOf<String>()
                            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA)
                                != PackageManager.PERMISSION_GRANTED
                            ) {
                                permissionsNeeded.add(Manifest.permission.CAMERA)
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS)
                                != PackageManager.PERMISSION_GRANTED
                            ) {
                                permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CALL_PHONE)
                                != PackageManager.PERMISSION_GRANTED
                            ) {
                                permissionsNeeded.add(Manifest.permission.CALL_PHONE)
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ANSWER_PHONE_CALLS)
                                != PackageManager.PERMISSION_GRANTED
                            ) {
                                permissionsNeeded.add(Manifest.permission.ANSWER_PHONE_CALLS)
                            }
                            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_PHONE_STATE)
                                != PackageManager.PERMISSION_GRANTED
                            ) {
                                permissionsNeeded.add(Manifest.permission.READ_PHONE_STATE)
                            }
                            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_CONTACTS)
                                != PackageManager.PERMISSION_GRANTED
                            ) {
                                permissionsNeeded.add(Manifest.permission.READ_CONTACTS)
                            }
                            if (permissionsNeeded.isNotEmpty()) {
                                permissionsLauncher.launch(permissionsNeeded.toTypedArray())
                            }
                        }

                        LaunchedEffect(triggerVoiceState.value) {
                            if (triggerVoiceState.value) {
                                triggerVoiceState.value = false
                                runCatching {
                                    val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                        putExtra(
                                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                                        )
                                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA")
                                        putExtra(
                                            RecognizerIntent.EXTRA_PROMPT,
                                            "تحدث الآن لتنفيذ أي أمر في نظام أندرويد..."
                                        )
                                    }
                                    voiceLauncher.launch(speechIntent)
                                }
                            }
                        }

                        MorventMainScreen(
                            viewModel = viewModel,
                            userDisplayName = activeUser.displayName,
                            userEmail = activeUser.email,
                            onSignOut = {
                                coroutineScope.launch {
                                    viewModel.bindAuthenticatedUser(null)
                                    GoogleAuthManager.signOut(this@MainActivity, auth)
                                }
                            },
                            onRequestPhoneContactsPermissions = requestPhoneContacts,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        checkVoiceTriggerIntent(intent)
    }

    private fun checkVoiceTriggerIntent(intent: Intent?) {
        val action = intent?.action
        val startVoice = intent?.getBooleanExtra(EXTRA_START_VOICE, false) == true ||
            action == Intent.ACTION_ASSIST ||
            action == Intent.ACTION_VOICE_COMMAND
        if (startVoice) {
            triggerVoiceState.value = true
        }
    }

    companion object {
        const val EXTRA_START_VOICE = "extra_start_voice"
    }
}
