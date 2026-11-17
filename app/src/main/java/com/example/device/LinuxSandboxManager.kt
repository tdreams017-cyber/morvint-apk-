package com.example.device

import android.content.Context
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class LinuxExecutionResult(
    val command: String,
    val workingDir: String,
    val exitCode: Int,
    val output: String,
    val durationMs: Long
)

/**
 * Private Linux Workspace & Shell Sandbox under `filesDir/morvent_linux`.
 * Runs genuine `/system/bin/sh` & Toybox commands in-process with persistent `cwd`,
 * environment variables, math/JSON helpers, and an `/uploads` folder for chat attachments.
 * Complies with Google Play System Integrity policies (no downloading/loading external .dex/.jar/.so).
 */
class LinuxSandboxManager private constructor(private val context: Context) {

    val rootDir: File = File(context.filesDir, "morvent_linux").apply { mkdirs() }
    val homeDir: File = File(rootDir, "home/morvent").apply { mkdirs() }
    val scriptsDir: File = File(homeDir, "scripts").apply { mkdirs() }
    val uploadsDir: File = File(homeDir, "uploads").apply { mkdirs() }
    val tmpDir: File = File(rootDir, "tmp").apply { mkdirs() }

    private val envVars = linkedMapOf(
        "HOME" to homeDir.absolutePath,
        "USER" to "morvent",
        "SHELL" to "/system/bin/sh",
        "TERM" to "xterm-256color",
        "TMPDIR" to tmpDir.absolutePath,
        "MORVENT_ROOT" to rootDir.absolutePath
    )

    private val _currentDir = MutableStateFlow(homeDir)
    val currentDir: StateFlow<File> = _currentDir.asStateFlow()

    private val _currentDirDisplay = MutableStateFlow(formatDisplayPath(homeDir))
    val currentDirDisplay: StateFlow<String> = _currentDirDisplay.asStateFlow()

    private val _commandHistory = MutableStateFlow<List<String>>(emptyList())
    val commandHistory: StateFlow<List<String>> = _commandHistory.asStateFlow()

    private val _terminalLogs = MutableStateFlow<List<LinuxExecutionResult>>(emptyList())
    val terminalLogs: StateFlow<List<LinuxExecutionResult>> = _terminalLogs.asStateFlow()

    init {
        ensureReadmeInitialized()
    }

    private fun ensureReadmeInitialized() {
        val readme = File(homeDir, "README_MORVENT.txt")
        if (!readme.exists()) {
            readme.writeText(
                """
                Morvent Private Linux Environment
                HOME=${homeDir.absolutePath}
                SCRIPTS=${scriptsDir.absolutePath}
                UPLOADS=${uploadsDir.absolutePath}
                Built-in commands: help, morvent-info, calc <expr>, json <text>, cd, pwd, export, unset, env, history, clear
                """.trimIndent()
            )
        }
    }

    fun getCurrentPath(): String = _currentDir.value.absolutePath

    fun getDisplayPromptPath(): String = formatDisplayPath(_currentDir.value)

    private fun formatDisplayPath(dir: File): String {
        val abs = dir.absolutePath
        val home = homeDir.absolutePath
        return when {
            abs == home -> "~"
            abs.startsWith("$home/") -> "~/${abs.removePrefix("$home/")}"
            else -> abs
        }
    }

    fun clearLogs() {
        _terminalLogs.value = emptyList()
    }

    fun saveUploadedFile(uri: Uri, displayName: String): File? {
        return try {
            val safeName = displayName
                .replace(Regex("[^a-zA-Z0-9._\\-\\u0600-\\u06FF]"), "_")
                .ifBlank { "upload_${System.currentTimeMillis()}" }
            val dest = File(uploadsDir, safeName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { out ->
                    input.copyTo(out)
                }
            }
            if (dest.exists()) dest else null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun executeCommand(rawCommand: String, timeoutSeconds: Long = 15L): LinuxExecutionResult =
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            val command = rawCommand.trim()
            if (command.isBlank()) {
                return@withContext LinuxExecutionResult(
                    command = "",
                    workingDir = getDisplayPromptPath(),
                    exitCode = 0,
                    output = "",
                    durationMs = 0L
                )
            }

            // Record history
            val hist = _commandHistory.value.toMutableList()
            if (hist.lastOrNull() != command) {
                hist.add(command)
                _commandHistory.value = hist.takeLast(80)
            }

            val result = handleBuiltInOrShell(command, start, timeoutSeconds)
            if (command == "clear" || command == "cls") {
                _terminalLogs.value = emptyList()
            } else {
                _terminalLogs.value = (_terminalLogs.value + result).takeLast(120)
            }
            result
        }

    private fun handleBuiltInOrShell(
        command: String,
        startMs: Long,
        timeoutSeconds: Long
    ): LinuxExecutionResult {
        val pwdBefore = getDisplayPromptPath()

        if (command == "clear" || command == "cls") {
            return LinuxExecutionResult(command, pwdBefore, 0, "", System.currentTimeMillis() - startMs)
        }

        if (command == "help") {
            val helpOut = """
                بيئة لينكس الخاصة بمورڤينت (/system/bin/sh + Toybox):
                • أوامر التنقل والملفات: pwd, ls -la, cd <dir>, mkdir, touch, cat, head, tail, cp, mv, rm, find, grep
                • معلومات النظام: uname -a, id, df -h, free -m, ps, uptime, env, morvent-info
                • أدوات ذكية مدمجة:
                  - calc <expression>  (حساب رياضي دقيق مثل: calc (25*4)+15/3)
                  - json <string>      (تنسيق وفحص JSON)
                  - export K=V / unset K
                  - history / clear
                • مجلد الملفات المرفوعة: ~/uploads (${uploadsDir.absolutePath})
            """.trimIndent()
            return LinuxExecutionResult(command, pwdBefore, 0, helpOut, System.currentTimeMillis() - startMs)
        }

        if (command == "pwd") {
            return LinuxExecutionResult(
                command,
                pwdBefore,
                0,
                _currentDir.value.absolutePath,
                System.currentTimeMillis() - startMs
            )
        }

        if (command == "cd" || command.startsWith("cd ")) {
            val targetArg = command.removePrefix("cd").trim()
                .replace("~", homeDir.absolutePath)
            val targetDir = when {
                targetArg.isBlank() -> homeDir
                targetArg.startsWith("/") -> File(targetArg).canonicalFile
                else -> File(_currentDir.value, targetArg).canonicalFile
            }
            return if (targetDir.exists() && targetDir.isDirectory) {
                _currentDir.value = targetDir
                _currentDirDisplay.value = formatDisplayPath(targetDir)
                LinuxExecutionResult(command, formatDisplayPath(targetDir), 0, "", System.currentTimeMillis() - startMs)
            } else {
                LinuxExecutionResult(
                    command,
                    pwdBefore,
                    1,
                    "cd: $targetArg: No such directory",
                    System.currentTimeMillis() - startMs
                )
            }
        }

        if (command == "env") {
            val out = envVars.entries.joinToString("\n") { "${it.key}=${it.value}" }
            return LinuxExecutionResult(command, pwdBefore, 0, out, System.currentTimeMillis() - startMs)
        }

        if (command.startsWith("export ")) {
            val assignment = command.removePrefix("export ").trim()
            val eqIdx = assignment.indexOf('=')
            return if (eqIdx > 0) {
                val key = assignment.substring(0, eqIdx).trim()
                val value = assignment.substring(eqIdx + 1).trim().trim('"', '\'')
                envVars[key] = value
                LinuxExecutionResult(command, pwdBefore, 0, "export $key=$value", System.currentTimeMillis() - startMs)
            } else {
                LinuxExecutionResult(command, pwdBefore, 1, "Usage: export KEY=VALUE", System.currentTimeMillis() - startMs)
            }
        }

        if (command.startsWith("unset ")) {
            val key = command.removePrefix("unset ").trim()
            envVars.remove(key)
            return LinuxExecutionResult(command, pwdBefore, 0, "", System.currentTimeMillis() - startMs)
        }

        if (command == "history") {
            val out = _commandHistory.value.mapIndexed { idx, s -> "${idx + 1}  $s" }.joinToString("\n")
            return LinuxExecutionResult(command, pwdBefore, 0, out, System.currentTimeMillis() - startMs)
        }

        if (command == "morvent-info") {
            val uploadsCount = uploadsDir.listFiles()?.size ?: 0
            val scriptsCount = scriptsDir.listFiles()?.size ?: 0
            val out = """
                Morvent Linux Sandbox v2.0
                Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})
                ABI: ${Build.SUPPORTED_ABIS.joinToString(", ")}
                Working Dir: ${_currentDir.value.absolutePath}
                Uploads: $uploadsCount file(s) in ~/uploads
                Scripts: $scriptsCount file(s) in ~/scripts
                Free Space: ${rootDir.usableSpace / (1024 * 1024)} MB
            """.trimIndent()
            return LinuxExecutionResult(command, pwdBefore, 0, out, System.currentTimeMillis() - startMs)
        }

        if (command.startsWith("calc ")) {
            val expr = command.removePrefix("calc ").trim()
            return try {
                val value = evaluateMathExpression(expr)
                val formatted = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
                LinuxExecutionResult(command, pwdBefore, 0, formatted, System.currentTimeMillis() - startMs)
            } catch (e: Exception) {
                LinuxExecutionResult(command, pwdBefore, 1, "calc error: ${e.message}", System.currentTimeMillis() - startMs)
            }
        }

        if (command.startsWith("json ")) {
            val jsonText = command.removePrefix("json ").trim()
            return try {
                val formatted = JSONObject(jsonText).toString(2)
                LinuxExecutionResult(command, pwdBefore, 0, formatted, System.currentTimeMillis() - startMs)
            } catch (e: Exception) {
                LinuxExecutionResult(command, pwdBefore, 1, "json error: ${e.message}", System.currentTimeMillis() - startMs)
            }
        }

        // Execute genuine OS shell command using /system/bin/sh (fallback /bin/sh on JVM Robolectric host)
        return runSystemShell(command, pwdBefore, startMs, timeoutSeconds)
    }

    private fun runSystemShell(
        command: String,
        pwdBefore: String,
        startMs: Long,
        timeoutSeconds: Long
    ): LinuxExecutionResult {
        val shellBinary = when {
            File("/system/bin/sh").exists() -> "/system/bin/sh"
            File("/bin/sh").exists() -> "/bin/sh"
            else -> "sh"
        }

        // Expand leading ~ references safely
        val expanded = command.replace("~/", "${homeDir.absolutePath}/")
            .replace(" ~", " ${homeDir.absolutePath}")

        return try {
            val pb = ProcessBuilder(shellBinary, "-c", expanded)
            pb.directory(_currentDir.value)
            pb.redirectErrorStream(true)
            val procEnv = pb.environment()
            envVars.forEach { (k, v) -> procEnv[k] = v }

            val process = pb.start()
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return LinuxExecutionResult(
                    command = command,
                    workingDir = pwdBefore,
                    exitCode = 124,
                    output = "Command timed out after ${timeoutSeconds}s",
                    durationMs = System.currentTimeMillis() - startMs
                )
            }

            val rawBytes = process.inputStream.readBytes()
            val rawOut = String(rawBytes, Charsets.UTF_8).trimEnd()
            val boundedOut = if (rawOut.length > MAX_OUTPUT_CHARS) {
                rawOut.take(MAX_OUTPUT_CHARS) + "\n... [تم اختصار المخرجات الطويلة]"
            } else {
                rawOut.ifBlank { "(تم التنفيذ بنجاح بدون مخرجات نصية)" }
            }

            LinuxExecutionResult(
                command = command,
                workingDir = pwdBefore,
                exitCode = process.exitValue(),
                output = boundedOut,
                durationMs = System.currentTimeMillis() - startMs
            )
        } catch (e: Exception) {
            LinuxExecutionResult(
                command = command,
                workingDir = pwdBefore,
                exitCode = 127,
                output = "Shell execution error: ${e.message}",
                durationMs = System.currentTimeMillis() - startMs
            )
        }
    }

    /**
     * Recursive-descent arithmetic evaluator supporting +, -, *, /, %, ^, and parentheses.
     */
    private fun evaluateMathExpression(str: String): Double {
        return object {
            var pos = -1
            var ch = 0
            fun nextChar() { ch = if (++pos < str.length) str[pos].code else -1 }
            fun eat(charToEat: Int): Boolean {
                while (ch == ' '.code) nextChar()
                if (ch == charToEat) { nextChar(); return true }
                return false
            }
            fun parse(): Double {
                nextChar()
                val x = parseExpression()
                if (pos < str.length) throw IllegalArgumentException("Unexpected: '${ch.toChar()}'")
                return x
            }
            fun parseExpression(): Double {
                var x = parseTerm()
                while (true) {
                    x = when {
                        eat('+'.code) -> x + parseTerm()
                        eat('-'.code) -> x - parseTerm()
                        else -> return x
                    }
                }
            }
            fun parseTerm(): Double {
                var x = parseFactor()
                while (true) {
                    x = when {
                        eat('*'.code) -> x * parseFactor()
                        eat('/'.code) -> x / parseFactor()
                        eat('%'.code) -> x % parseFactor()
                        else -> return x
                    }
                }
            }
            fun parseFactor(): Double {
                if (eat('+'.code)) return parseFactor()
                if (eat('-'.code)) return -parseFactor()
                var x: Double
                val startPos = pos
                if (eat('('.code)) {
                    x = parseExpression()
                    if (!eat(')'.code)) throw IllegalArgumentException("Missing ')'")
                } else if (ch in '0'.code..'9'.code || ch == '.'.code) {
                    while (ch in '0'.code..'9'.code || ch == '.'.code) nextChar()
                    x = str.substring(startPos, pos).toDouble()
                } else {
                    throw IllegalArgumentException("Unexpected character: '${ch.toChar()}'")
                }
                if (eat('^'.code)) x = Math.pow(x, parseFactor())
                return x
            }
        }.parse()
    }

    companion object {
        private const val MAX_OUTPUT_CHARS = 8000

        @Volatile
        private var INSTANCE: LinuxSandboxManager? = null

        fun getInstance(context: Context): LinuxSandboxManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LinuxSandboxManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
