package com.example.ai

import android.content.Context
import android.content.SharedPreferences
import com.example.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages the user's Gemini API key, preferred model selection, and live connection state.
 * Persists custom API keys in SharedPreferences while seeding cleanly with BuildConfig.GEMINI_API_KEY
 * when available.
 */
class ApiKeyManager private constructor(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _apiKey = MutableStateFlow(loadInitialKey())
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _selectedModel = MutableStateFlow(
        prefs.getString(KEY_SELECTED_MODEL, DEFAULT_MODEL)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MODEL
    )
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

    private val _lastConnectionStatus = MutableStateFlow(
        if (_apiKey.value.isNotBlank()) "مفتاح Gemini API جاهز (${_selectedModel.value})"
        else "لم يتم تعيين مفتاح API بعد — أدخل المفتاح أو استخدم Firebase AI"
    )
    val lastConnectionStatus: StateFlow<String> = _lastConnectionStatus.asStateFlow()

    private fun loadInitialKey(): String {
        val hasSaved = prefs.contains(KEY_CUSTOM_API_KEY)
        if (hasSaved) {
            return prefs.getString(KEY_CUSTOM_API_KEY, "")?.trim().orEmpty()
        }
        return defaultBuildConfigKey()
    }

    fun getEffectiveApiKey(): String = _apiKey.value.trim()

    fun getSelectedModelName(): String = _selectedModel.value.trim().ifBlank { DEFAULT_MODEL }

    fun saveApiKey(rawKey: String) {
        val cleaned = rawKey.trim()
        prefs.edit().putString(KEY_CUSTOM_API_KEY, cleaned).apply()
        _apiKey.value = cleaned
        _lastConnectionStatus.value = if (cleaned.isNotBlank()) {
            "تم حفظ المفتاح (${cleaned.take(6)}...${cleaned.takeLast(4)}) ✓"
        } else {
            "تم مسح المفتاح المخصص — سيتم استخدام Firebase AI إن توفر"
        }
    }

    fun setSelectedModel(modelName: String) {
        val cleanModel = modelName.trim().ifBlank { DEFAULT_MODEL }
        prefs.edit().putString(KEY_SELECTED_MODEL, cleanModel).apply()
        _selectedModel.value = cleanModel
    }

    fun resetToDefaultKey(): String {
        val defaultKey = defaultBuildConfigKey()
        prefs.edit().remove(KEY_CUSTOM_API_KEY).apply()
        _apiKey.value = defaultKey
        _lastConnectionStatus.value = if (defaultKey.isNotBlank()) {
            "تمت استعادة المفتاح الافتراضي ✓"
        } else {
            "لا يوجد مفتاح افتراضي مدمج"
        }
        return defaultKey
    }

    fun updateConnectionStatus(status: String) {
        _lastConnectionStatus.value = status
    }

    fun defaultBuildConfigKey(): String {
        val builtIn = BuildConfig.GEMINI_API_KEY.trim()
        return if (builtIn.isNotBlank() && !builtIn.startsWith("AIzaSy...Placeholder")) builtIn else ""
    }

    companion object {
        private const val PREFS_NAME = "morvent_ai_prefs"
        private const val KEY_CUSTOM_API_KEY = "gemini_api_key"
        private const val KEY_SELECTED_MODEL = "gemini_selected_model"

        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"

        val SUPPORTED_MODELS = listOf(
            "gemini-3.5-flash-lite",
            "gemini-flash-lite-latest",
            "gemini-3.8-flash"
        )

        @Volatile
        private var INSTANCE: ApiKeyManager? = null

        fun getInstance(context: Context): ApiKeyManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ApiKeyManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
