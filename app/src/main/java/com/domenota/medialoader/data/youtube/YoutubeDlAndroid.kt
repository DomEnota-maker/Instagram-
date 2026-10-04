package com.domenota.medialoader.data.youtube

import android.content.Context
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

    /** First use unpacks the bundled Python and ffmpeg, which takes a few seconds. */
    private suspend fun ensureReady() {
        if (ready) return
        initLock.withLock {
            if (ready) return
            withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().init(appContext)
                FFmpeg.getInstance().init(appContext)
            }
            ready = true
        }
    }

    /** Updates the extractor in app-private storage. Invoke when no downloads are active. */
    suspend fun update(): String {
        ensureReady()
        return withContext(Dispatchers.IO) {
            YoutubeDL.getInstance().updateYoutubeDL(appContext, YoutubeDL.UpdateChannel.STABLE)
            "yt-dlp обновлён или уже актуален"
        }
    }

    override suspend fun extract(url: String): StreamInfo {
        try {
            ensureReady()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw ExtractionException("Не удалось запустить yt-dlp: ${error.message}", cause = error)
        }
        val request = YoutubeDLRequest(url).apply {
            addOption("-J")
            addOption("--no-playlist")
            addOption("--no-warnings")
        }
        val output = try {
            withContext(Dispatchers.IO) { YoutubeDL.getInstance().execute(request).out }
        } catch (error: YoutubeDLException) {
            currentCoroutineContext().ensureActive()
            throw toExtractionException(error)
        }
        return try {
            YtDlpJson.parse(output.trim())
        } catch (error: JSONException) {
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
        val request = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("--no-warnings")
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
        val processId = UUID.randomUUID().toString()
        coroutineScope {
            // The library call blocks and ignores coroutine cancellation, so a watcher kills the process.
            val killer = launch(Dispatchers.IO) {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { runCatching { YoutubeDL.getInstance().destroyProcessById(processId) } }
                }
            }
            try {
                withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().execute(request, processId) { progress, _, _ -> onProgress(progress) }
                }
            } catch (error: YoutubeDLException) {
                currentCoroutineContext().ensureActive() // a killed process after cancel is a cancel, not a failure
                throw DownloadFailure(failureMessage(error), error)
            } finally {
                killer.cancel()
            }
        }
        val files = targetDir.listFiles().orEmpty()
        val expected = if (audioOnly) "out.m4a" else "out.mp4"
        return files.firstOrNull { it.name == expected }
            ?: files.firstOrNull { it.name.startsWith("out.") && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            ?: throw DownloadFailure("yt-dlp не создал файл.")
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
            line != null -> "YouTube: ${line.take(200)}"
            else -> "Не удалось получить видео с YouTube."
        }
    }

    private companion object {
        val UNAVAILABLE_MARKERS = listOf(
            "Private video", "Video unavailable", "This video is not available", "members-only",
            "has been removed", "is no longer available", "blocked it",
        )
    }
}
