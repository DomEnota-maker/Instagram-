package com.domenota.medialoader.data.vk

import android.content.Context
import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.core.provider.ExtractionException
import com.domenota.medialoader.core.provider.StreamExtractor
import com.domenota.medialoader.core.provider.StreamInfo
import com.domenota.medialoader.core.provider.VkProvider
import com.domenota.medialoader.core.provider.YtDlpJson
import com.domenota.medialoader.data.download.DownloadFailure
import com.domenota.medialoader.data.youtube.YtDlpDownloader
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

/** VK-specific yt-dlp adapter with its own authenticated cookie jar. */
class VkYtDlpRuntime(context: Context) : StreamExtractor, YtDlpDownloader {
    private val appContext = context.applicationContext
    private val initLock = Mutex()
    @Volatile private var ready = false
    private val cookiesFile get() = File(appContext.filesDir, "vk-cookies.txt")

    val hasCookies: Boolean get() = cookiesFile.exists() && cookiesFile.length() > 0L

    init {
        AppLog.init(appContext)
    }

    /** Captures the user's authenticated VK WebView session without reading credentials. */
    fun saveWebSession(
        vkCookies: String?,
        mobileVkCookies: String?,
        idVkCookies: String?,
        vkVideoCookies: String?,
    ): Boolean {
        val values = linkedMapOf<Pair<String, String>, String>()
        collectCookieHeader(".vk.com", vkCookies, values)
        collectCookieHeader(".vk.com", mobileVkCookies, values)
        collectCookieHeader(".id.vk.com", idVkCookies, values)
        collectCookieHeader(".vkvideo.ru", vkVideoCookies, values)
        val authenticated = values.keys.any { (_, name) ->
            AUTH_COOKIE_NAMES.any { it.equals(name, ignoreCase = true) }
        }
        if (!authenticated) return false

        val temporary = File(appContext.filesDir, "vk-web-cookies.tmp")
        try {
            temporary.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.appendLine("# Netscape HTTP Cookie File")
                writer.appendLine("# Generated locally by MediaLoader from the user's VK WebView session.")
                values.forEach { (key, value) ->
                    val (domain, name) = key
                    writer.append(domain).append('\t')
                        .append("TRUE\t/\tTRUE\t0\t")
                        .append(name).append('\t').append(value).appendLine()
                }
            }
            require(temporary.length() in 1..MAX_COOKIE_BYTES) { "Сессия VK слишком большая" }
            if (cookiesFile.exists()) require(cookiesFile.delete()) { "Не удалось заменить сессию VK" }
            require(temporary.renameTo(cookiesFile)) { "Не удалось сохранить сессию VK" }
            AppLog.i("VKAuth", "Web session saved · cookies=${values.size}")
            return true
        } catch (error: Exception) {
            AppLog.e("VKAuth", "Web session save failed: ${error.message}", error)
            return false
        } finally {
            temporary.delete()
        }
    }

    fun clearCookies() {
        cookiesFile.delete()
        AppLog.i("VKAuth", "VK session removed")
    }

    override suspend fun extract(url: String): StreamInfo {
        ensureReady()
        AppLog.i("VK", "Analyze start · cookies=$hasCookies · url=$url")
        val output = try {
            val request = YoutubeDLRequest(url).apply {
                addOption("-J")
                addOption("--no-playlist")
                addOption("--no-warnings")
                if (hasCookies) addOption("--cookies", cookiesFile.absolutePath)
            }
            withContext(Dispatchers.IO) { YoutubeDL.getInstance().execute(request).out }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: YoutubeDLException) {
            currentCoroutineContext().ensureActive()
            AppLog.e("VK", "Analyze failed: ${error.message}", error)
            throw toExtractionException(error)
        } catch (error: Exception) {
            AppLog.e("VK", "Analyze failed: ${error.message}", error)
            throw ExtractionException("Не удалось получить данные видео VK.", cause = error)
        }
        return try {
            YtDlpJson.parse(output.trim()).also { info ->
                AppLog.i("VK", "Analyze success · id=${info.id} · formats=${info.formats.size}")
            }
        } catch (error: JSONException) {
            AppLog.e("VK", "Could not parse yt-dlp JSON: ${error.message}", error)
            throw ExtractionException("VK вернул непонятный ответ.", cause = error)
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
            "VK",
            "Download start · audioOnly=$audioOnly · selector=${selector ?: "default"} · cookies=$hasCookies · url=$url",
        )
        val request = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("--no-warnings")
            if (hasCookies) addOption("--cookies", cookiesFile.absolutePath)
            addOption("-o", File(targetDir, "out.%(ext)s").absolutePath)
            if (audioOnly) {
                addOption("-f", VkProvider.AUDIO_SELECTOR)
                addOption("-x")
                addOption("--audio-format", "mp3")
                addOption("--audio-quality", "192K")
                addOption("--convert-thumbnails", "jpg")
                addOption("--embed-thumbnail")
            } else {
                addOption("-f", selector ?: VkProvider.BEST_VIDEO_SELECTOR)
                addOption("--merge-output-format", "mp4")
            }
        }
        try {
            executeDownload(request, onProgress)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: YoutubeDLException) {
            currentCoroutineContext().ensureActive()
            AppLog.e("VK", "Download failed: ${error.message}", error)
            throw DownloadFailure(failureMessage(error), error)
        }

        val files = targetDir.listFiles().orEmpty()
        val expected = if (audioOnly) "out.mp3" else "out.mp4"
        val result = files.firstOrNull { it.name == expected }
            ?: files.firstOrNull {
                it.name.startsWith("out.") && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl")
            }
            ?: throw DownloadFailure("yt-dlp не создал файл VK.")
        AppLog.i("VK", "Download success · file=${result.name} · bytes=${result.length()}")
        return result
    }

    private suspend fun ensureReady() {
        if (ready) return
        initLock.withLock {
            if (ready) return
            AppLog.i("VK", "Initializing yt-dlp runtime")
            try {
                withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().init(appContext)
                    FFmpeg.getInstance().init(appContext)
                }
            } catch (error: Exception) {
                AppLog.e("VK", "Runtime initialization failed: ${error.message}", error)
                throw ExtractionException("Не удалось запустить yt-dlp для VK: ${error.message}", cause = error)
            }
            ready = true
            AppLog.i("VK", "yt-dlp runtime ready")
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

    private fun toExtractionException(error: YoutubeDLException): ExtractionException {
        val text = error.message.orEmpty()
        val authRequired = !hasCookies && AUTH_MARKERS.any { text.contains(it, ignoreCase = true) }
        val unavailable = UNAVAILABLE_MARKERS.any { text.contains(it, ignoreCase = true) }
        val kind = when {
            authRequired -> ExtractionException.Kind.AUTH_REQUIRED
            unavailable -> ExtractionException.Kind.UNAVAILABLE
            else -> ExtractionException.Kind.FAILED
        }
        return ExtractionException(failureMessage(error), kind, error)
    }

    private fun failureMessage(error: YoutubeDLException): String {
        val text = error.message.orEmpty()
        if (!hasCookies && AUTH_MARKERS.any { text.contains(it, ignoreCase = true) }) {
            return "VK требует вход в аккаунт. Откройте Настройки → Аккаунт VK."
        }
        val line = text.lineSequence().firstOrNull { it.trimStart().startsWith("ERROR:") }
            ?.substringAfter("ERROR:")?.trim()
        return when {
            line != null -> "VK: ${line.take(220)}"
            text.contains("403", ignoreCase = true) -> "VK не дал доступ к потоку. Обновите yt-dlp и повторите."
            else -> "Не удалось получить видео из VK."
        }
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

    private companion object {
        const val MAX_COOKIE_BYTES = 2L * 1024L * 1024L
        val AUTH_COOKIE_NAMES = setOf("remixsid", "remixsid6", "remixnsid")
        val AUTH_MARKERS = listOf(
            "sign up to watch videos without restrictions",
            "video only available to followers",
            "login required",
            "authentication required",
            "please log in",
            "only available to registered users",
        )
        val UNAVAILABLE_MARKERS = listOf(
            "video has been removed",
            "video is unavailable",
            "access denied",
            "private video",
            "video only available to followers",
        )
    }
}
