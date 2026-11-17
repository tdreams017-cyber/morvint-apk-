package com.example.ai

import android.content.Context
import android.content.SharedPreferences
import com.example.model.InstalledApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class LearnedUserRule(
    val id: String,
    val instruction: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class CommandFrequencyEntry(
    val command: String,
    val count: Int,
    val lastUsedAt: Long
)

/**
 * Learns the user's writing style, frequent commands, preferred apps, and explicit rules
 * ("علم مورڤينت...") to continuously personalize AI responses and pre-typing predictions.
 */
class UserLearningManager private constructor(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _learnedRules = MutableStateFlow<List<LearnedUserRule>>(loadRules())
    val learnedRules: StateFlow<List<LearnedUserRule>> = _learnedRules.asStateFlow()

    private val _frequentCommands = MutableStateFlow<List<CommandFrequencyEntry>>(loadFrequencies())
    val frequentCommands: StateFlow<List<CommandFrequencyEntry>> = _frequentCommands.asStateFlow()

    private val _preferredApps = MutableStateFlow<Map<String, Int>>(loadPreferredApps())
    val preferredApps: StateFlow<Map<String, Int>> = _preferredApps.asStateFlow()

    private val _styleSummary = MutableStateFlow(loadStyleSummary())
    val styleSummary: StateFlow<String> = _styleSummary.asStateFlow()

    fun recordUserCommand(rawText: String, targetAppName: String? = null) {
        val clean = rawText.trim()
        if (clean.length < 2) return

        // Ignore explicit learning commands from polluting frequency suggestions
        val lower = clean.lowercase(Locale.ROOT)
        if (!lower.startsWith("علم مورفنت") &&
            !lower.startsWith("علم مورڤينت") &&
            !lower.startsWith("احفظ أسلوبي")
        ) {
            val current = _frequentCommands.value.toMutableList()
            val idx = current.indexOfFirst { it.command.equals(clean, ignoreCase = true) }
            val now = System.currentTimeMillis()
            if (idx >= 0) {
                val existing = current[idx]
                current[idx] = existing.copy(count = existing.count + 1, lastUsedAt = now)
            } else {
                current.add(CommandFrequencyEntry(command = clean, count = 1, lastUsedAt = now))
            }
            val sorted = current
                .sortedWith(compareByDescending<CommandFrequencyEntry> { it.count }.thenByDescending { it.lastUsedAt })
                .take(MAX_FREQUENT_COMMANDS)
            _frequentCommands.value = sorted
            saveFrequencies(sorted)
        }

        if (!targetAppName.isNullOrBlank()) {
            val appMap = _preferredApps.value.toMutableMap()
            appMap[targetAppName] = (appMap[targetAppName] ?: 0) + 1
            _preferredApps.value = appMap
            savePreferredApps(appMap)
        }

        updateStyleMetrics(clean)
    }

    fun teachRule(rawInstruction: String): LearnedUserRule? {
        val clean = rawInstruction.trim()
        if (clean.isBlank()) return null

        val existing = _learnedRules.value.toMutableList()
        if (existing.any { it.instruction.equals(clean, ignoreCase = true) }) {
            return existing.first { it.instruction.equals(clean, ignoreCase = true) }
        }
        val rule = LearnedUserRule(
            id = "rule_${System.currentTimeMillis()}",
            instruction = clean
        )
        existing.add(0, rule)
        val trimmed = existing.take(MAX_RULES)
        _learnedRules.value = trimmed
        saveRules(trimmed)
        return rule
    }

    fun removeRule(ruleId: String) {
        val updated = _learnedRules.value.filterNot { it.id == ruleId }
        _learnedRules.value = updated
        saveRules(updated)
    }

    fun clearLearnedData() {
        _learnedRules.value = emptyList()
        _frequentCommands.value = emptyList()
        _preferredApps.value = emptyMap()
        _styleSummary.value = ""
        prefs.edit().clear().apply()
    }

    /**
     * Generates smart pre-typing (when prefix is empty) and live-typing predictive chips.
     */
    fun getPredictiveSuggestions(
        prefix: String,
        installedApps: List<InstalledApp> = emptyList(),
        limit: Int = 8
    ): List<String> {
        val cleanPrefix = prefix.trim()
        val lowerPrefix = cleanPrefix.lowercase(Locale.ROOT)
        val results = LinkedHashSet<String>()

        val frequent = _frequentCommands.value.map { it.command }
        val topAppNames = _preferredApps.value.entries
            .sortedByDescending { it.value }
            .map { it.key }
            .take(5)

        if (cleanPrefix.isEmpty()) {
            // Pre-typing stage: prioritize user habits, top used apps, and powerful templates
            for (cmd in frequent.take(4)) {
                results.add(cmd)
            }
            for (appName in topAppNames.take(2)) {
                results.add("افتح $appName")
            }
            for (starter in DEFAULT_PRETYPE_STARTERS) {
                if (results.size >= limit) break
                results.add(starter)
            }
            return results.take(limit)
        }

        // Live-typing stage: 1. Frequent user commands matching prefix or containing words
        for (cmd in frequent) {
            if (cmd.lowercase(Locale.ROOT).startsWith(lowerPrefix) ||
                cmd.lowercase(Locale.ROOT).contains(lowerPrefix)
            ) {
                results.add(cmd)
                if (results.size >= limit) return results.toList()
            }
        }

        // 2. Smart contextual completions for Arabic & Linux triggers
        for (template in CONTEXTUAL_COMPLETIONS) {
            if (template.lowercase(Locale.ROOT).startsWith(lowerPrefix) ||
                template.lowercase(Locale.ROOT).contains(lowerPrefix)
            ) {
                results.add(template)
                if (results.size >= limit) return results.toList()
            }
        }

        // 3. App-name auto-completion when typing app launch/automation intents
        val appPrefixes = listOf("افتح ", "شغل ", "ادخل ", "اقرأ ", "ارسل ", "تواصل مع ")
        val matchedIntentPrefix = appPrefixes.firstOrNull { lowerPrefix.startsWith(it) }
        if (matchedIntentPrefix != null) {
            val appQuery = cleanPrefix.removePrefix(matchedIntentPrefix).trim().lowercase(Locale.ROOT)
            val matchingApps = installedApps
                .filter {
                    appQuery.isEmpty() ||
                        it.appName.lowercase(Locale.ROOT).contains(appQuery) ||
                        it.packageName.lowercase(Locale.ROOT).contains(appQuery)
                }
                .take(4)
            for (app in matchingApps) {
                results.add("$matchedIntentPrefix${app.appName}")
                if (results.size >= limit) return results.toList()
            }
        } else if (cleanPrefix.length >= 2) {
            installedApps
                .filter { it.appName.lowercase(Locale.ROOT).startsWith(lowerPrefix) }
                .take(3)
                .forEach { results.add("افتح ${it.appName}") }
        }

        return results.take(limit)
    }

    /**
     * Compact prompt block injected into Gemini / Hydrogen so the model mirrors the user's style and rules.
     */
    fun buildLearningPromptContext(): String {
        val sb = StringBuilder()
        val rules = _learnedRules.value
        val freqs = _frequentCommands.value.take(5)
        val topApps = _preferredApps.value.entries.sortedByDescending { it.value }.take(4)
        val style = _styleSummary.value

        if (rules.isEmpty() && freqs.isEmpty() && topApps.isEmpty() && style.isBlank()) {
            return ""
        }
        sb.appendLine("[ملف التعلم الشخصي وأسلوب المستخدم]:")
        if (style.isNotBlank()) {
            sb.appendLine("- نمط الأسلوب المفضل: $style")
        }
        if (rules.isNotEmpty()) {
            sb.appendLine("- قواعد علمها لك المستخدم وتلتزم بها دائماً:")
            rules.take(12).forEachIndexed { idx, r ->
                sb.appendLine("  ${idx + 1}. ${r.instruction}")
            }
        }
        if (topApps.isNotEmpty()) {
            sb.appendLine("- التطبيقات الأكثر استخداماً: ${topApps.joinToString(", ") { it.key }}")
        }
        if (freqs.isNotEmpty()) {
            sb.appendLine("- أكثر الأوامر تكراراً: ${freqs.joinToString(" | ") { it.command }}")
        }
        return sb.toString().trim()
    }

    private fun updateStyleMetrics(text: String) {
        val total = prefs.getInt(KEY_TOTAL_INTERACTIONS, 0) + 1
        val avgLenPrev = prefs.getInt(KEY_AVG_LEN, 25)
        val newAvg = ((avgLenPrev * (total - 1).coerceAtMost(20)) + text.length) / total.coerceAtMost(21)

        val usesShortDirect = text.split(" ").size <= 5
        val directCount = prefs.getInt(KEY_DIRECT_COUNT, 0) + if (usesShortDirect) 1 else 0
        val arabicCount = prefs.getInt(KEY_ARABIC_COUNT, 0) + if (text.any { it in '\u0600'..'\u06FF' }) 1 else 0

        val styleDesc = buildString {
            append(if (arabicCount >= total / 2) "يفضل اللغة العربية" else "يميل للأوامر الإنجليزية/المختلطة")
            append(" • ")
            append(if (directCount >= total / 2) "أسلوب حاسم وسريع ومباشر بدون مقدمات" else "أسلوب تفصيلي ومركب")
            append(" (متوسط طول الأمر: $newAvg حرف، $total تفاعل)")
        }

        prefs.edit()
            .putInt(KEY_TOTAL_INTERACTIONS, total)
            .putInt(KEY_AVG_LEN, newAvg)
            .putInt(KEY_DIRECT_COUNT, directCount)
            .putInt(KEY_ARABIC_COUNT, arabicCount)
            .putString(KEY_STYLE_SUMMARY, styleDesc)
            .apply()
        _styleSummary.value = styleDesc
    }

    private fun loadRules(): List<LearnedUserRule> {
        val raw = prefs.getString(KEY_LEARNED_RULES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val text = obj.optString("instruction").trim()
                if (text.isBlank()) null else LearnedUserRule(
                    id = obj.optString("id", "rule_$i"),
                    instruction = text,
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveRules(list: List<LearnedUserRule>) {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("instruction", r.instruction)
                put("createdAt", r.createdAt)
            })
        }
        prefs.edit().putString(KEY_LEARNED_RULES, arr.toString()).apply()
    }

    private fun loadFrequencies(): List<CommandFrequencyEntry> {
        val raw = prefs.getString(KEY_FREQUENT_COMMANDS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val cmd = obj.optString("command").trim()
                if (cmd.isBlank()) null else CommandFrequencyEntry(
                    command = cmd,
                    count = obj.optInt("count", 1),
                    lastUsedAt = obj.optLong("lastUsedAt", System.currentTimeMillis())
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveFrequencies(list: List<CommandFrequencyEntry>) {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(JSONObject().apply {
                put("command", c.command)
                put("count", c.count)
                put("lastUsedAt", c.lastUsedAt)
            })
        }
        prefs.edit().putString(KEY_FREQUENT_COMMANDS, arr.toString()).apply()
    }

    private fun loadPreferredApps(): Map<String, Int> {
        val raw = prefs.getString(KEY_PREFERRED_APPS, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val map = mutableMapOf<String, Int>()
            obj.keys().forEach { key -> map[key] = obj.optInt(key, 1) }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun savePreferredApps(map: Map<String, Int>) {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString(KEY_PREFERRED_APPS, obj.toString()).apply()
    }

    private fun loadStyleSummary(): String =
        prefs.getString(KEY_STYLE_SUMMARY, "") ?: ""

    companion object {
        private const val PREFS_NAME = "morvent_user_learning"
        private const val KEY_LEARNED_RULES = "learned_rules"
        private const val KEY_FREQUENT_COMMANDS = "frequent_commands"
        private const val KEY_PREFERRED_APPS = "preferred_apps"
        private const val KEY_STYLE_SUMMARY = "style_summary"
        private const val KEY_TOTAL_INTERACTIONS = "total_interactions"
        private const val KEY_AVG_LEN = "avg_len"
        private const val KEY_DIRECT_COUNT = "direct_count"
        private const val KEY_ARABIC_COUNT = "arabic_count"

        private const val MAX_RULES = 30
        private const val MAX_FREQUENT_COMMANDS = 40

        private val DEFAULT_PRETYPE_STARTERS = listOf(
            "اقرأ الإشعارات الجديدة",
            "حالة النظام الكاملة",
            "لينكس: uname -a && ls -la",
            "سجل المكالمات الأخيرة",
            "رسائل SMS الأخيرة",
            "أكثر التطبيقات استخداماً",
            "افحص الشاشة الآن",
            "علم مورڤينت: نفذ أوامري باختصار شديد"
        )

        private val CONTEXTUAL_COMPLETIONS = listOf(
            "لينكس: ls -la",
            "لينكس: pwd && df -h",
            "لينكس: ps | head -n 15",
            "لينكس: morvent-info",
            "نفذ كود: echo 'Morvent Ready'",
            "سجل المكالمات الأخيرة",
            "رسائل SMS الأخيرة",
            "أحداث التقويم القادمة",
            "أكثر التطبيقات استخداماً اليوم",
            "افتح واتساب وابحث عن ",
            "ارسل رسالة واتساب إلى ",
            "ابحث في يوتيوب عن ",
            "اضبط السطوع على 80%",
            "اضبط المنبه على الساعة 7:00",
            "اقرأ الإشعارات ورد على آخر رسالة",
            "علم مورڤينت: دائماً استخدم نبرة عملية ومباشرة",
            "حالة البطارية والعتاد",
            "تشغيل الفلاش",
            "إطفاء الفلاش"
        )

        @Volatile
        private var INSTANCE: UserLearningManager? = null

        fun getInstance(context: Context): UserLearningManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: UserLearningManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
