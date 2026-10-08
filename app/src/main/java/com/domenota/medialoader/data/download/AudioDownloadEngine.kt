package com.domenota.medialoader.data.download

import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.core.model.DownloadTask
import com.domenota.medialoader.data.recognition.InstagramMusicRecognizer
import com.domenota.medialoader.data.recognition.RecognitionAudioPreprocessor
import java.io.File
import kotlinx.coroutines.CancellationException

/** AUDIO tasks: download the source media and encode its audio track as MP3. */
class AudioDownloadEngine(
    private val extractor: AudioExtractor,
    private val instagramRecognizer: InstagramMusicRecognizer? = null,
    private val recognitionPreprocessor: RecognitionAudioPreprocessor? = null,
) : DownloadEngine {
    override suspend fun download(
        task: DownloadTask,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long?) -> Unit,
    ): DownloadResult = try {
        val (uri, size) = extractor.saveAudio(
            videoUrl = task.item.downloadUrl,
            fileName = task.fileName,
            onProgress = onProgress,
            beforePublish = { audio -> recognizeWithoutBreakingDownload(task, audio) },
        )
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

    private suspend fun recognizeWithoutBreakingDownload(task: DownloadTask, audio: File) {
        val recognizer = instagramRecognizer ?: return
        try {
            val result = recognizer.recognize(task.item) {
                recognitionPreprocessor?.decode(audio) ?: ShortArray(0)
            }
            if (result == null) {
                AppLog.i(
                    "Recognition",
                    "No reliable match · provider=${task.item.providerId} · sourceGroup=${task.item.sourceGroupId ?: "-"}",
                )
            } else {
                AppLog.i(
                    "Recognition",
                    "Matched · source=${result.source} · trackId=${result.trackId ?: "-"} · " +
                        "artist=${result.artist} · title=${result.title}",
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: LinkageError) {
            AppLog.w("Recognition", "Recognition unavailable; download continues.", error)
        } catch (error: Exception) {
            AppLog.w("Recognition", "Recognition failed; download continues.", error)
        }
    }
}
