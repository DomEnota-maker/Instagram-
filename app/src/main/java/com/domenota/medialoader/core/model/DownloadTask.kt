package com.domenota.medialoader.core.model

import kotlinx.coroutines.CompletableDeferred

/** In-memory unit of work for the download queue. [fileName] is already collision-free. */
data class DownloadTask(
    val id: String,
    val item: MediaItem,
    val fileName: String,
    val state: DownloadState = DownloadState.QUEUED,
    val bytesDownloaded: Long = 0,
    val savedUri: String? = null,
    val errorMessage: String? = null,
    /** Parallel transfers wait here before publishing into the destination folder. */
    val publishAfter: CompletableDeferred<Unit>? = null,
)

enum class DownloadState { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }
