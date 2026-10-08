package com.domenota.medialoader.data.download

import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.core.model.DownloadTask
import com.domenota.medialoader.core.provider.InstagramProvider
import com.domenota.medialoader.core.recognition.MusicRecognitionResult
import com.domenota.medialoader.data.recognition.InstagramMusicRecognizer
import com.domenota.medialoader.data.recognition.MusicRecognitionMode
import com.domenota.medialoader.data.recognition.RecognitionAudioPreprocessor
import com.domenota.medialoader.data.recognition.RecognitionSettings
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** AUDIO tasks: download the source media and encode its audio track as MP3. */
class AudioDownloadEngine(
    private val extractor: AudioExtractor,
    private val instagramRecognizer: InstagramMusicRecognizer? = null,
    private val recognitionPreprocessor: RecognitionAudioPreprocessor? = null,
    private val recognitionScope: CoroutineScope? = null,
    private val recognitionSettings: RecognitionSettings? = null,
    private val onRecognized: suspend (DownloadTask, String, MusicRecognitionResult) -> Unit = { _, _, _ -> },
) : DownloadEngine {
    override suspend fun download(
        task: DownloadTask,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long?) -> Unit,
    ): DownloadResult = try {
        val recognizer = instagramRecognizer
        val autoRecognition =
            task.item.providerId == InstagramProvider.ID &&
                recognitionSettings?.mode == MusicRecognitionMode.AUTOMATIC
        val needsPcm = autoRecognition && recognizer?.requiresPcm(task.item) == true
        val hasSourceMetadata = autoRecognition && recognizer?.sourceMetadata(task.item) != null
        var preparedPcm: ShortArray? = null

        val (uri, size) = extractor.saveAudio(
            videoUrl = task.item.downloadUrl,
            fileName = task.fileName,
            onProgress = onProgress,
            beforePublish = { audio ->
                if (needsPcm) preparedPcm = preparePcmWithoutBreakingDownload(audio)
            },
        )

        if (autoRecognition && (hasSourceMetadata || needsPcm)) {
            launchRecognition(task, uri.toString(), preparedPcm)
        }

        DownloadResult(uri.toString(), size)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: AudioExtractor.NoAudioTrackException) {
        throw DownloadFailure(error.message ?: "В этом видео нет звуковой дорожки.", error)
    } catch (error: AudioExtractor.VideoTooLargeException) {
        throw DownloadFailure(error.message ?: "Видео слишком большое для извлечения аудио.", error)
    } catch (error: AudioExtractor.InsufficientStorageException) {
        throw DownloadFailure(error.message ?: "Недостаточно места на устройстве.", error)
    } catch (error: LinkageError) {
        throw DownloadFailure("Не удалось запустить обработку MP3. Обновите приложение.", error)
    } catch (error: Exception) {
        throw DownloadFailure("Не удалось извлечь аудио. Повторите анализ ссылки.", error)
    }

    private suspend fun preparePcmWithoutBreakingDownload(audio: File): ShortArray? {
        val preprocessor = recognitionPreprocessor ?: return null
        return try {
            preprocessor.decode(audio)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: LinkageError) {
            AppLog.w("Recognition", "PCM preparation unavailable; download continues.", error)
            null
        } catch (error: Exception) {
            AppLog.w("Recognition", "PCM preparation failed; download continues.", error)
            null
        }
    }

    private fun launchRecognition(
        task: DownloadTask,
        savedUri: String,
        preparedPcm: ShortArray?,
    ) {
        val recognizer = instagramRecognizer ?: return
        val scope = recognitionScope ?: return
        scope.launch {
            try {
                val result = recognizer.recognize(task.item) { preparedPcm ?: ShortArray(0) }
                if (result == null) {
                    AppLog.i(
                        "Recognition",
                        "No reliable match · provider=${task.item.providerId} · sourceGroup=${task.item.sourceGroupId ?: "-"}",
                    )
                    return@launch
                }
                AppLog.i(
                    "Recognition",
                    "Matched · source=${result.source} · trackId=${result.trackId ?: "-"} · " +
                        "artist=${result.artist} · title=${result.title}",
                )
                onRecognized(task, savedUri, result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: LinkageError) {
                AppLog.w("Recognition", "Recognition unavailable; download already saved.", error)
            } catch (error: Exception) {
                AppLog.w("Recognition", "Recognition failed; download already saved.", error)
            }
        }
    }
}
