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
 * The only class that touches the youtubedl-android library (yt-dlp + ffmpeg bundled for Android).
 * Everything else sees [StreamExtractor] and [YtDlpDownloader].
 */
class YoutubeDlAndroid(context: Context) : StreamExtractor, YtDlpDownloader {
    private val appContext = context.applicationContext
    private val initLock = Mutex()
    @Volatile private var ready = false
    private val cookiesFile get() = File(appContext.filesDir, "youtube-cookies.txt")
    val hasCookies get() = cookiesFile.exists()

    init {
        AppLog.init(appContext)
    }

    fun importCookies(uri: Uri) {
        val temporary = File(appContext.filesDir, "youtube-cookies.tmp")
        try {
            val input = appContext.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("Не удалось открыть файл")
            input.use { source -> temporary.outputStream().use { target ->
                val copied = source.copyTo(target)
                require(copied in 1..MAX_COOKIE_BYTES) { "Файл cookies пустой или слишком большой" }
            } }
            val header = temporary.bufferedReader().use { it.readLine().orEmpty() }
            require(header.startsWith("# Netscape HTTP Cookie File") ||
                header.startsWith("# HTTP Cookie File")) { "Нужен файл cookies в формате Netscape" }
            require(temporary.renameTo(cookiesFile)) { "Не удалось сохранить cookies" }
            AppLog.i("YouTube", "Cookies imported · bytes=${cookiesFile.length()}")
        } catch (error: Exception) {
            AppLog.e("YouTube", "Cookies import failed: ${error.message}", error)
            throw error
        } finally { temporary.delete() }
    }

    fun clearCookies() {
        cookiesFile.delete()
        AppLog.i("YouTube", "Cookies removed")
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
        AppLog.i("YouTube", "yt-dlp update requested")
        return try {
            withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().updateYoutubeDL(appContext, YoutubeDL.UpdateChannel.STABLE)
                "yt-dlp обновлён или уже актуален"
            }.also { AppLog.i("YouTube", it) }
        } catch (error: Exception) {
            AppLog.e("YouTube", "yt-dlp update failed: ${error.message}", error)
            throw error
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
        AppLog.i("YouTube", "Analyze start · cookies=$hasCookies · url=$url")
        val output = try {
            executeExtract(url, PRIMARY_EXTRACTOR_ARGS)
        } catch (error: YoutubeDLException) {
            currentCoroutineContext().ensureActive()
            if (isHttp403(error)) {
                AppLog.w("YouTube", "Analyze got HTTP 403; retrying with web_embedded", error)
                try {
                    executeExtract(url, EMBEDDED_EXTRACTOR_ARGS)
                } catch (fallbackError: YoutubeDLException) {
                    currentCoroutineContext().ensureActive()
                    AppLog.e("YouTube", "Analyze fallback failed: ${fallbackError.message}", fallbackError)
                    throw toExtractionException(fallbackError)
                }
            } else {
                AppLog.e("YouTube", "Analyze failed: ${error.message}", error)
                throw toExtractionException(error)
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
            "Download start · audioOnly=$audioOnly · selector=${selector ?: "default"} · cookies=$hasCookies · url=$url",
        )

        try {
            executeDownload(
                buildDownloadRequest(url, selector, audioOnly, targetDir, PRIMARY_EXTRACTOR_ARGS),
                onProgress,
            )
        } catch (error: YoutubeDLException) {
            currentCoroutineContext().ensureActive()
            if (isHttp403(error)) {
                AppLog.w(
                    "YouTube",
                    "Download got HTTP 403 with default client chain; retrying with web_embedded only",
                    error,
                )
                removePartialOutput(targetDir)
                try {
                    executeDownload(
                        buildDownloadRequest(url, selector, audioOnly, targetDir, EMBEDDED_EXTRACTOR_ARGS),
                        onProgress,
                    )
                } catch (fallbackError: YoutubeDLException) {
                    currentCoroutineContext().ensureActive()
                    AppLog.e("YouTube", "Download fallback failed: ${fallbackError.message}", fallbackError)
                    throw DownloadFailure(failureMessage(fallbackError), fallbackError)
                }
            } else {
                AppLog.e("YouTube", "Download failed: ${error.message}", error)
                throw DownloadFailure(failureMessage(error), error)
            }
        }

        val files = targetDir.listFiles().orEmpty()
        val expected = if (audioOnly) "out.m4a" else "out.mp4"
        val result = files.firstOrNull { it.name == expected }
            ?: files.firstOrNull { it.name.startsWith("out.") && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            ?: throw DownloadFailure("yt-dlp не создал файл.")
        AppLog.i("YouTube", "Download success · file=${result.name} · bytes=${result.length()}")
        return result
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
            // The library call blocks and ignores coroutine cancellation, so a watcher kills the process.
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
            text.contains("Sign in to confirm", ignoreCase = true) ||
                text.contains("age-restricted", ignoreCase = true) ||
                text.contains("login required", ignoreCase = true) ->
                "YouTube просит подтвердить, что вы не бот, или возраст. Без входа это видео недоступно."
            isHttp403(error) ->
                "YouTube отклонил поток (HTTP 403). Обновите yt-dlp и повторите загрузку."
            line != null -> "YouTube: ${line.take(200)}"
            else -> "Не удалось получить видео с YouTube."
        }
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
        val UNAVAILABLE_MARKERS = listOf(
            "Private video", "Video unavailable", "This video is not available", "members-only",
            "has been removed", "is no longer available", "blocked it",
        )
    }
}
