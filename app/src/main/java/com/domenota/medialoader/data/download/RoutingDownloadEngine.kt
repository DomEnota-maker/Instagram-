package com.domenota.medialoader.data.download

import com.domenota.medialoader.core.model.DownloadTask
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.core.provider.YouTubeProvider

/** Single entry point for the queue: audio goes to the extractor, photos and video to the media engine. */
class RoutingDownloadEngine(
    private val media: DownloadEngine,
    private val audio: DownloadEngine,
    private val youtube: DownloadEngine? = null,
) : DownloadEngine {
    override suspend fun download(
        task: DownloadTask,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long?) -> Unit,
    ): DownloadResult =
        if (task.item.providerId == YouTubeProvider.ID) requireNotNull(youtube).download(task, onProgress)
        else if (task.item.type == MediaType.AUDIO) audio.download(task, onProgress)
        else media.download(task, onProgress)
}
