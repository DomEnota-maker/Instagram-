package com.domenota.medialoader.data.recognition

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.core.recognition.MusicRecognitionResult
import com.domenota.medialoader.data.storage.StorageManager
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Rewrites ID3 metadata on a temporary copy and replaces the saved MP3 only after FFmpeg succeeds.
 * Cover download is best-effort; tag writing still proceeds when artwork is unavailable.
 */
class MusicMetadataWriter(
    private val context: Context,
    private val storage: StorageManager,
) {
    suspend fun write(savedUri: String, result: MusicRecognitionResult): Boolean =
        withContext(Dispatchers.IO) {
            val input = File.createTempFile("recognized-src-", ".mp3", context.cacheDir)
            val output = File.createTempFile("recognized-out-", ".mp3", context.cacheDir)
            var cover: File? = null
            try {
                copyFromUri(savedUri, input)
                cover = result.artworkUrl?.let { downloadCover(it) }
                if (!rewrite(input, output, cover, result)) return@withContext false
                storage.replace(savedUri, output)
            } finally {
                input.delete()
                output.delete()
                cover?.delete()
            }
        }

    private fun copyFromUri(savedUri: String, target: File) {
        val uri = Uri.parse(savedUri)
        val input = when (uri.scheme) {
            "file" -> File(uri.path ?: throw IOException("Файл недоступен")).inputStream()
            "content" -> context.contentResolver.openInputStream(uri)
                ?: throw IOException("Файл недоступен")
            else -> throw IOException("Файл недоступен")
        }
        input.use { stream -> target.outputStream().use { stream.copyTo(it) } }
    }

    private suspend fun rewrite(
        input: File,
        output: File,
        cover: File?,
        result: MusicRecognitionResult,
    ): Boolean {
        fun quote(value: String): String =
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        val coverInput = cover?.let { "-i ${quote(it.absolutePath)} " }.orEmpty()
        val coverMap = if (cover != null) {
            "-map 0:a:0 -map 1:v:0 -c:a copy -c:v copy -disposition:v:0 attached_pic " +
                "-metadata:s:v title=${quote("Album cover")} " +
                "-metadata:s:v comment=${quote("Cover (front)")} "
        } else {
            "-map 0:a:0 -c:a copy "
        }
        val album = result.album?.takeIf { it.isNotBlank() }
            ?.let { "-metadata album=${quote(it)} " }.orEmpty()
        val command = "-nostdin -hide_banner -loglevel error -y " +
            "-i ${quote(input.absolutePath)} $coverInput" +
            "$coverMap-id3v2_version 3 " +
            "-metadata artist=${quote(result.artist)} " +
            "-metadata title=${quote(result.title)} " +
            "$album${quote(output.absolutePath)}"

        val success = suspendCancellableCoroutine<Boolean> { continuation ->
            val session = FFmpegKit.executeAsync(command) { completed ->
                if (continuation.isActive) continuation.resume(ReturnCode.isSuccess(completed.returnCode))
            }
            continuation.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
        }
        return success && output.length() > 0L
    }

    private fun downloadCover(url: String): File? {
        val source = runCatching { URL(url) }.getOrNull() ?: return null
        if (!source.protocol.equals("https", true)) return null

        var current = source
        repeat(4) {
            val connection = current.openConnection() as? HttpURLConnection ?: return null
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 8_000
                connection.readTimeout = 12_000
                connection.setRequestProperty("User-Agent", USER_AGENT)
                val status = connection.responseCode
                if (status in 300..399) {
                    val location = connection.getHeaderField("Location") ?: return null
                    val next = URL(current, location)
                    if (!next.protocol.equals("https", true)) return null
                    current = next
                    return@repeat
                }
                if (status !in 200..299) return null
                val declared = connection.contentLengthLong
                if (declared > MAX_COVER_BYTES) return null

                val target = File.createTempFile("recognized-cover-", ".img", context.cacheDir)
                var total = 0L
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_COVER_BYTES) {
                                target.delete()
                                return null
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                return target.takeIf { it.length() > 0L }
            } catch (error: Exception) {
                AppLog.w("Recognition", "Artwork download failed; continuing without cover.", error)
                return null
            } finally {
                connection.disconnect()
            }
        }
        return null
    }

    private companion object {
        const val MAX_COVER_BYTES = 5L * 1024 * 1024
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36"
    }
}
