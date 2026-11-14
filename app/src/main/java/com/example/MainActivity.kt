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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ai.configureAppCheck
import com.example.nlp.MorventAgentHub
import com.example.ui.MorventMainScreen
import com.example.ui.MorventViewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val triggerVoiceState = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureAppCheck(this, intent)
        enableEdgeToEdge()
        checkVoiceTriggerIntent(intent)

        setContent {
            MyApplicationTheme(darkTheme = true) {
                val viewModel: MorventViewModel = viewModel()

                val voiceLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    if (result.resultCode == Activity.RESULT_OK) {
                        val spoken = result.data
                            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                            ?.firstOrNull()
                            ?.trim()
                        if (!spoken.isNullOrEmpty()) {
                            MorventAgentHub.getInstance(applicationContext).submitCommand(spoken, sourceLabel = "الأمر الصوتي بالنظام")
                        }
                    }
                }

                // Request camera permission for flashlight control and notifications
                val permissionsLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { _ ->
                    // Permissions evaluated gracefully
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
                                putExtra(RecognizerIntent.EXTRA_PROMPT, "تحدث الآن لتنفيذ أي أمر في نظام أندرويد...")
                            }
                            voiceLauncher.launch(speechIntent)
                        }
                    }
                }

                // Arabic is RTL
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    MorventMainScreen(
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxSize()
                    )
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
