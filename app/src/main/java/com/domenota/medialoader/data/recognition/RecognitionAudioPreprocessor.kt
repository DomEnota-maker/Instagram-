package com.domenota.medialoader.data.recognition

import android.content.Context
import android.media.MediaMetadataRetriever
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.domenota.medialoader.core.recognition.RecognitionAudioSpec
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Decodes an already-downloaded audio file to the format expected by Shazam:
 * signed 16-bit little-endian PCM, mono, 16 kHz.
 *
 * At most the middle 12 seconds are decoded, so recognition never needs the whole MP3 in memory.
 */
class RecognitionAudioPreprocessor(private val context: Context) {
    suspend fun decode(input: File): ShortArray = withContext(Dispatchers.IO) {
        val output = File.createTempFile("recognition-", ".pcm", context.cacheDir)
        try {
            val durationMs = mediaDurationMs(input)
            val targetMs = RecognitionAudioSpec.TARGET_SECONDS * 1000L
            val startMs = if (durationMs != null && durationMs > targetMs) {
                (durationMs - targetMs) / 2L
            } else {
                0L
            }
            val limitMs = if (durationMs == null || durationMs > targetMs) targetMs else null
            decodeWithFfmpeg(input, output, startMs, limitMs)
            rawPcmToShorts(output)
        } finally {
            output.delete()
        }
    }

    private fun mediaDurationMs(input: File): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(input.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private suspend fun decodeWithFfmpeg(
        input: File,
        output: File,
        startMs: Long,
        limitMs: Long?,
    ) {
        fun quote(file: File) = "\"" + file.absolutePath.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val seek = if (startMs > 0L) "-ss ${startMs / 1000.0} " else ""
        val limit = limitMs?.let { "-t ${it / 1000.0} " }.orEmpty()
        val command = "-nostdin -hide_banner -loglevel error -y " +
            "${seek}-i ${quote(input)} ${limit}-vn -ac 1 -ar ${RecognitionAudioSpec.SAMPLE_RATE_HZ} " +
            "-acodec pcm_s16le -f s16le ${quote(output)}"

        val succeeded = suspendCancellableCoroutine<Boolean> { continuation ->
            val session = FFmpegKit.executeAsync(command) { completed ->
                if (continuation.isActive) continuation.resume(ReturnCode.isSuccess(completed.returnCode))
            }
            continuation.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
        }
        if (!succeeded || !output.exists() || output.length() < 2L) {
            throw IOException("Не удалось подготовить аудио для распознавания.")
        }
    }

    private fun rawPcmToShorts(file: File): ShortArray {
        val bytes = file.readBytes()
        val usableSize = bytes.size - (bytes.size % 2)
        if (usableSize <= 0) return ShortArray(0)

        val shorts = ShortArray(usableSize / 2)
        ByteBuffer.wrap(bytes, 0, usableSize)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(shorts)
        return shorts
    }
}
