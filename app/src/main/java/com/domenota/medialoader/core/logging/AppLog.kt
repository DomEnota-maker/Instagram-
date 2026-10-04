package com.domenota.medialoader.core.logging

import android.content.Context
import android.os.Build
import com.domenota.medialoader.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small persistent diagnostic log intended for user-exported bug reports.
 * Secrets are never deliberately logged; common cookie/session values are redacted as a final guard.
 */
object AppLog {
    private const val MAX_BYTES = 1024L * 1024L
    private const val TAIL_LINES = 2500
    private val lock = Any()
    private val timestamp = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }
    private val secretPattern = Regex(
        "(?i)(sessionid|sapisid|apisid|__Secure-1PAPISID|__Secure-3PAPISID|sid|hsid|ssid)=([^;\\s]+)",
    )

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var crashHandlerInstalled = false

    fun init(context: Context) {
        val applicationContext = context.applicationContext
        if (appContext == null) synchronized(lock) {
            if (appContext == null) appContext = applicationContext
        }
        installCrashHandler()
        i("App", "Session started · MediaLoader ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Android ${Build.VERSION.RELEASE}")
    }

    fun d(tag: String, message: String) = append("DEBUG", tag, message, null)
    fun i(tag: String, message: String) = append("INFO", tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = append("WARN", tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = append("ERROR", tag, message, error)

    fun readText(): String = synchronized(lock) {
        val file = logFile() ?: return@synchronized ""
        runCatching { if (file.exists()) file.readText() else "" }.getOrElse { "Не удалось прочитать журнал: ${it.message}" }
    }

    fun clear() = synchronized(lock) {
        val file = logFile() ?: return@synchronized
        runCatching { file.delete() }
    }

    fun exportMarkdown(): String {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        val body = readText().ifBlank { "Журнал пуст." }
        return buildString {
            appendLine("# MediaLoader — журнал приложения")
            appendLine()
            appendLine("- Создан: $now")
            appendLine("- Версия: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("- Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("- Устройство: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine()
            appendLine("> Журнал формируется локально. Значения известных cookie/session-полей автоматически скрываются.")
            appendLine()
            appendLine("```text")
            append(body.replace("```", "` ` `"))
            if (!body.endsWith('\n')) appendLine()
            appendLine("```")
        }
    }

    private fun append(level: String, tag: String, message: String, error: Throwable?) {
        val file = logFile() ?: return
        val safeMessage = redact(message)
        val safeStack = error?.stackTraceToString()?.let(::redact)
        synchronized(lock) {
            runCatching {
                file.parentFile?.mkdirs()
                file.appendText(buildString {
                    append(timestamp.get().format(Date()))
                    append(" [").append(level).append("] [").append(tag).append("] ")
                    appendLine(safeMessage)
                    if (!safeStack.isNullOrBlank()) {
                        safeStack.lineSequence().take(80).forEach { appendLine("    $it") }
                    }
                })
                trimIfNeeded(file)
            }
        }
    }

    private fun redact(value: String): String = secretPattern.replace(value) { match ->
        "${match.groupValues[1]}=<redacted>"
    }

    private fun logFile(): File? = appContext?.let { File(it.filesDir, "logs/medialoader.log") }

    private fun trimIfNeeded(file: File) {
        if (file.length() <= MAX_BYTES) return
        val tail = file.readLines().takeLast(TAIL_LINES)
        file.writeText(tail.joinToString(separator = "\n", postfix = "\n"))
    }

    private fun installCrashHandler() {
        if (crashHandlerInstalled) return
        synchronized(lock) {
            if (crashHandlerInstalled) return
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                e("Crash", "Uncaught exception on thread ${thread.name}", throwable)
                previous?.uncaughtException(thread, throwable)
            }
            crashHandlerInstalled = true
        }
    }
}
