package com.domenota.medialoader.data.youtube

import android.content.Context
import android.net.Uri
import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.core.provider.ExtractionException
import com.domenota.medialoader.core.provider.StreamExtractor
import com.domenota.medialoader.core.provider.StreamInfo
import com.domenota.medialoader.core.provider.YouTubeFormats
import com.domenota.medialoader.core.provider.YtDlpJson
import com.domenota.medialoader.data.download.DownloadFailure
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException

/**
 * The only class that touches youtubedl-android (yt-dlp + ffmpeg bundled for Android).
 * Everything else sees [StreamExtractor] and [YtDlpDownloader].
 */
class YoutubeDlAndroid(context: Context) : StreamExtractor, YtDlpDownloader {
    private val appContext = context.applicationContext
    private val initLock = Mutex()
    private val updateLock = Mutex()
    private val state = appContext.getSharedPreferences("youtube_runtime", Context.MODE_PRIVATE)
    @Volatile private var ready = false
    private val cookiesFile get() = File(appContext.filesDir, "youtube-cookies.txt")
    val hasCookies get() = cookiesFile.exists() && cookiesFile.length() > 0L
    val cookieSource: String? get() = state.getString(KEY_COOKIE_SOURCE, null)

    val lastUpdateStatus: String?
        get() = state.getLong(KEY_LAST_UPDATE, 0L).takeIf { it > 0L }?.let { timestamp ->
            "Обновлён: ${SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(timestamp))}"
        }

    init {
        AppLog.init(appContext)
    }

    fun importCookies(uri: Uri) {
        val temporary = File(appContext.filesDir, "youtube-cookies.tmp")
        try {
            val input = appContext.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("Не удалось открыть файл")
            input.use { source ->
                temporary.outputStream().use { target ->
                    val copied = source.copyTo(target)
                    require(copied in 1..MAX_COOKIE_BYTES) { "Файл cookies пустой или слишком большой" }
                }
            }
            val header = temporary.bufferedReader().use { it.readLine().orEmpty() }
            require(
                header.startsWith("# Netscape HTTP Cookie File") ||
                    header.startsWith("# HTTP Cookie File"),
            ) { "Нужен файл cookies в формате Netscape" }
            replaceCookiesFile(temporary)
            state.edit().putString(KEY_COOKIE_SOURCE, COOKIE_SOURCE_FILE).apply()
            AppLog.i("YouTube", "Cookies imported · bytes=${cookiesFile.length()}")
        } catch (error: Exception) {
            AppLog.e("YouTube", "Cookies import failed: ${error.message}", error)
            throw error
        } finally {
            temporary.delete()
        }
    }

    /** Saves authenticated WebView cookies as a private Netscape file consumable by yt-dlp. */
    fun saveWebSession(
        youtubeCookies: String?,
        googleCookies: String?,
        accountsCookies: String?,
    ): Boolean {
        val values = linkedMapOf<Pair<String, String>, String>()
        collectCookieHeader(".youtube.com", youtubeCookies, values)
        collectCookieHeader(".google.com", googleCookies, values)
        collectCookieHeader(".google.com", accountsCookies, values)
        val authenticated = values.keys.any { (_, name) ->
            AUTH_COOKIE_NAMES.any { it.equals(name, ignoreCase = true) }
        }
        if (!authenticated) return false

        val temporary = File(appContext.filesDir, "youtube-web-cookies.tmp")
        try {
            temporary.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.appendLine("# Netscape HTTP Cookie File")
                writer.appendLine("# Generated locally by MediaLoader from the user's YouTube WebView session.")
                values.forEach { (key, value) ->
                    val (domain, name) = key
                    writer.append(domain).append('\t')
                        .append("TRUE\t/\tTRUE\t0\t")
                        .append(name).append('\t').append(value).appendLine()
                }
            }
            require(temporary.length() in 1..MAX_COOKIE_BYTES) { "Сессия YouTube слишком большая" }
            replaceCookiesFile(temporary)
            state.edit().putString(KEY_COOKIE_SOURCE, COOKIE_SOURCE_WEB).apply()
            AppLog.i("YouTube", "Web session saved · cookies=${values.size}")
            return true
        } catch (error: Exception) {
            AppLog.e("YouTube", "Web session save failed: ${error.message}", error)
            return false
        } finally {
            temporary.delete()
        }
    }

    fun clearCookies() {
        cookiesFile.delete()
        state.edit().remove(KEY_COOKIE_SOURCE).apply()
        AppLog.i("YouTube", "Cookies removed")
    }

    private fun replaceCookiesFile(temporary: File) {
        if (cookiesFile.exists()) require(cookiesFile.delete()) { "Не удалось заменить cookies" }
        require(temporary.renameTo(cookiesFile)) { "Не удалось сохранить cookies" }
    }

    private fun collectCookieHeader(
        domain: String,
        header: String?,
        destination: MutableMap<Pair<String, String>, String>,
    ) {
        header.orEmpty().split(';').forEach { raw ->
            val entry = raw.trim()
            val separator = entry.indexOf('=')
            if (separator <= 0) return@forEach
            val name = entry.substring(0, separator).trim()
            val value = entry.substring(separator + 1).trim()
            if (name.isNotEmpty() && value.isNotEmpty()) destination[domain to name] = value
        }
    }

    /** First use unpacks the bundled Python and ffmpeg, which takes a few seconds. */
    private suspend fun ensureReady() {
        if (ready) return
        initLock.withLock {
            if (ready) return
            AppLog.i("YouTube", "Initializing yt-dlp runtime")
            withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().init(appContext)
                FFmpeg.getInstance().init(appContext)
            }
            ready = true
            AppLog.i("YouTube", "yt-dlp runtime ready")
        }
    }

    /** Updates the extractor in app-private storage. Invoke when no downloads are active. */
    suspend fun update(): String {
        ensureReady()
        return updateLock.withLock {
            AppLog.i("YouTube", "yt-dlp update requested")
            try {
                withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().updateYoutubeDL(appContext, YoutubeDL.UpdateChannel.STABLE)
                }
                val now = System.currentTimeMillis()
                state.edit().putLong(KEY_LAST_UPDATE, now).apply()
                val text = lastUpdateStatus ?: "yt-dlp обновлён или уже актуален"
                AppLog.i("YouTube", text)
                text
            } catch (error: Exception) {
                AppLog.e("YouTube", "yt-dlp update failed: ${error.message}", error)
                throw error
            }
        }
    }

    override suspend fun extract(url: String): StreamInfo {
        try {
            ensureReady()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppLog.e("YouTube", "Runtime initialization failed: ${error.message}", error)
            throw ExtractionException("Не удалось запустить yt-dlp: ${error.message}", cause = error)
        }
        AppLog.i("YouTube", "Analyze start · cookies=$hasCookies · source=${cookieSource ?: "none"} · url=$url")
        val output = try {
            executeExtract(url, PRIMARY_EXTRACTOR_ARGS)
        } catch (error: YoutubeDLException) {
            currentCoroutineContext().ensureActive()
            if (!isHttp403(error)) {
                AppLog.e("YouTube", "Analyze failed: ${error.message}", error)
                throw toExtractionException(error)
            }
            AppLog.w("YouTube", "Analyze got HTTP 403", error)
            val refreshed = maybeAutoUpdateAfter403()
            if (refreshed) {
                try {
                    executeExtract(url, PRIMARY_EXTRACTOR_ARGS)
                } catch (retryError: YoutubeDLException) {
                    currentCoroutineContext().ensureActive()
                    if (!isHttp403(retryError)) throw toExtractionException(retryError)
                    executeExtractFallback(url, retryError)
                }
            } else {
                executeExtractFallback(url, error)
            }
        }
        return try {
            YtDlpJson.parse(output.trim()).also { info ->
                AppLog.i("YouTube", "Analyze success · id=${info.id} · formats=${info.formats.size}")
            }
        } catch (error: JSONException) {
            AppLog.e("YouTube", "Could not parse yt-dlp JSON: ${error.message}", error)
            throw ExtractionException("yt-dlp вернул непонятный ответ.", cause = error)
        }
    }

    private suspend fun executeExtractFallback(url: String, original: YoutubeDLException): String {
        AppLog.w("YouTube", "Analyze retrying with web_embedded after 403")
        return try {
            executeExtract(url, EMBEDDED_EXTRACTOR_ARGS)
        } catch (fallbackError: YoutubeDLException) {
            currentCoroutineContext().ensureActive()
            AppLog.e("YouTube", "Analyze fallback failed: ${fallbackError.message}", fallbackError)
            if (isAuthRequired(fallbackError)) throw toExtractionException(fallbackError)
            throw ExtractionException(RECOVERY_FAILED_MESSAGE, cause = original)
        }
    }

    override suspend fun download(
        url: String,
        selector: String?,
        audioOnly: Boolean,
        targetDir: File,
        onProgress: (percent: Float) -> Unit,
    ): File {
        ensureReady()
        AppLog.i(
            "YouTube",
            "Download start · audioOnly=$audioOnly · selector=${selector ?: "default"} · cookies=$hasCookies · source=${cookieSource ?: "none"} · url=$url",
        )

        try {
            executeDownload(
                buildDownloadRequest(url, selector, audioOnly, targetDir, PRIMARY_EXTRACTOR_ARGS),
                onProgress,
            )
        } catch (error: YoutubeDLException) {
            currentCoroutineContext().ensureActive()
            if (!isHttp403(error)) {
                AppLog.e("YouTube", "Download failed: ${error.message}", error)
                throw DownloadFailure(failureMessage(error), error)
            }

            AppLog.w("YouTube", "Download got HTTP 403", error)
            var last403 = error
            if (maybeAutoUpdateAfter403()) {
                removePartialOutput(targetDir)
                AppLog.i("YouTube", "Retrying download after automatic yt-dlp update")
                try {
                    executeDownload(
                        buildDownloadRequest(url, selector, audioOnly, targetDir, PRIMARY_EXTRACTOR_ARGS),
                        onProgress,
                    )
                    last403 = error
                } catch (retryError: YoutubeDLException) {
                    currentCoroutineContext().ensureActive()
                    if (!isHttp403(retryError)) {
                        AppLog.e("YouTube", "Download after update failed: ${retryError.message}", retryError)
                        throw DownloadFailure(failureMessage(retryError), retryError)
                    }
                    last403 = retryError
                }
                if (targetDir.listFiles().orEmpty().any { it.name.startsWith("out.") && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }) {
                    return finishDownload(targetDir, audioOnly)
                }
            }

            removePartialOutput(targetDir)
            AppLog.w("YouTube", "Retrying download with web_embedded only")
            try {
                executeDownload(
                    buildDownloadRequest(url, selector, audioOnly, targetDir, EMBEDDED_EXTRACTOR_ARGS),
                    onProgress,
                )
            } catch (fallbackError: YoutubeDLException) {
                currentCoroutineContext().ensureActive()
                AppLog.e("YouTube", "Download fallback failed: ${fallbackError.message}", fallbackError)
                if (isAuthRequired(fallbackError)) {
                    throw DownloadFailure(failureMessage(fallbackError), fallbackError)
                }
                throw DownloadFailure(RECOVERY_FAILED_MESSAGE, last403)
            }
        }

        return finishDownload(targetDir, audioOnly)
    }

    private fun finishDownload(targetDir: File, audioOnly: Boolean): File {
        val files = targetDir.listFiles().orEmpty()
        val expected = if (audioOnly) "out.m4a" else "out.mp4"
        val result = files.firstOrNull { it.name == expected }
            ?: files.firstOrNull {
                it.name.startsWith("out.") && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl")
            }
            ?: throw DownloadFailure("yt-dlp не создал файл.")
        AppLog.i("YouTube", "Download success · file=${result.name} · bytes=${result.length()}")
        return result
    }

    private suspend fun maybeAutoUpdateAfter403(): Boolean {
        val now = System.currentTimeMillis()
        val lastUpdate = state.getLong(KEY_LAST_UPDATE, 0L)
        val lastAttempt = state.getLong(KEY_LAST_AUTO_ATTEMPT, 0L)
        if (lastUpdate > 0L && now - lastUpdate < AUTO_UPDATE_FRESH_MS) {
            AppLog.i("YouTube", "Automatic yt-dlp refresh skipped: current extractor was updated recently")
            return false
        }
        if (lastAttempt > 0L && now - lastAttempt < AUTO_UPDATE_COOLDOWN_MS) {
            AppLog.i("YouTube", "Automatic yt-dlp refresh skipped: cooldown active")
            return false
        }
        state.edit().putLong(KEY_LAST_AUTO_ATTEMPT, now).apply()
        AppLog.i("YouTube", "HTTP 403 recovery: updating yt-dlp automatically")
        return try {
            update()
            true
        } catch (error: Exception) {
            AppLog.w("YouTube", "Automatic yt-dlp update did not succeed", error)
            false
        }
    }

    private suspend fun executeExtract(url: String, extractorArgs: String): String {
        val request = YoutubeDLRequest(url).apply {
            addOption("-J")
            addOption("--no-playlist")
            addOption("--no-warnings")
            addOption("--extractor-args", extractorArgs)
            if (hasCookies) addOption("--cookies", cookiesFile.absolutePath)
        }
        return withContext(Dispatchers.IO) { YoutubeDL.getInstance().execute(request).out }
    }

    private fun buildDownloadRequest(
        url: String,
        selector: String?,
        audioOnly: Boolean,
        targetDir: File,
        extractorArgs: String,
    ) = YoutubeDLRequest(url).apply {
        addOption("--no-playlist")
        addOption("--no-mtime")
        addOption("--no-warnings")
        addOption("--extractor-args", extractorArgs)
        if (hasCookies) addOption("--cookies", cookiesFile.absolutePath)
        addOption("-o", File(targetDir, "out.%(ext)s").absolutePath)
        if (audioOnly) {
            addOption("-f", YouTubeFormats.AUDIO_SELECTOR)
            addOption("-x")
            addOption("--audio-format", "m4a")
            addOption("--embed-thumbnail")
        } else {
            addOption("-f", selector ?: YouTubeFormats.BEST_VIDEO_SELECTOR)
            addOption("--merge-output-format", "mp4")
        }
    }

    private suspend fun executeDownload(
        request: YoutubeDLRequest,
        onProgress: (percent: Float) -> Unit,
    ) {
        val processId = UUID.randomUUID().toString()
        coroutineScope {
            val killer = launch(Dispatchers.IO) {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
                    }
                }
            }
            try {
                withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().execute(request, processId) { progress, _, _ -> onProgress(progress) }
                }
            } finally {
                killer.cancel()
            }
        }
    }

    private fun removePartialOutput(targetDir: File) {
        targetDir.listFiles().orEmpty()
            .filter { it.name.startsWith("out.") }
            .forEach { runCatching { it.deleteRecursively() } }
    }

    private fun toExtractionException(error: YoutubeDLException): ExtractionException {
        val text = error.message.orEmpty()
        val kind = if (UNAVAILABLE_MARKERS.any { text.contains(it, ignoreCase = true) }) {
            ExtractionException.Kind.UNAVAILABLE
        } else {
            ExtractionException.Kind.FAILED
        }
        return ExtractionException(failureMessage(error), kind, error)
    }

    /** The yt-dlp "ERROR:" line is the readable part of its stderr. */
    private fun failureMessage(error: YoutubeDLException): String {
        val text = error.message.orEmpty()
        val line = text.lineSequence().firstOrNull { it.trimStart().startsWith("ERROR:") }
            ?.substringAfter("ERROR:")?.trim()
        return when {
            isAuthRequired(error) ->
                "YouTube требует вход в аккаунт. Откройте Настройки → Аккаунт YouTube."
            isHttp403(error) -> RECOVERY_FAILED_MESSAGE
            line != null -> "YouTube: ${line.take(200)}"
            else -> "Не удалось получить видео с YouTube."
        }
    }

    private fun isAuthRequired(error: YoutubeDLException): Boolean {
        val text = error.message.orEmpty()
        return text.contains("Sign in to confirm", ignoreCase = true) ||
            text.contains("age-restricted", ignoreCase = true) ||
            text.contains("login required", ignoreCase = true) ||
            text.contains("confirm you're not a bot", ignoreCase = true) ||
            text.contains("confirm you’re not a bot", ignoreCase = true)
    }

    private fun isHttp403(error: YoutubeDLException): Boolean {
        val text = error.message.orEmpty()
        return text.contains("HTTP Error 403", ignoreCase = true) ||
            text.contains("403: Forbidden", ignoreCase = true)
    }

    private companion object {
        const val MAX_COOKIE_BYTES = 2L * 1024 * 1024
        const val PRIMARY_EXTRACTOR_ARGS = "youtube:player_client=default,web_embedded"
        const val EMBEDDED_EXTRACTOR_ARGS = "youtube:player_client=web_embedded"
        const val KEY_LAST_UPDATE = "last_update_ms"
        const val KEY_LAST_AUTO_ATTEMPT = "last_auto_update_attempt_ms"
        const val KEY_COOKIE_SOURCE = "cookie_source"
        const val COOKIE_SOURCE_FILE = "file"
        const val COOKIE_SOURCE_WEB = "web"
        const val AUTO_UPDATE_FRESH_MS = 24L * 60L * 60L * 1000L
        const val AUTO_UPDATE_COOLDOWN_MS = 30L * 60L * 1000L
        const val RECOVERY_FAILED_MESSAGE =
            "YouTube не дал доступ к потоку. yt-dlp и резервный клиент не помогли — откройте Логи для диагностики."
        val AUTH_COOKIE_NAMES = setOf(
            "SAPISID", "APISID", "SID", "HSID", "SSID", "LOGIN_INFO",
            "__Secure-1PAPISID", "__Secure-3PAPISID",
        )
        val UNAVAILABLE_MARKERS = listOf(
            "Private video", "Video unavailable", "This video is not available", "members-only",
            "has been removed", "is no longer available", "blocked it",
        )
    }
}
