package app.medialoader.core.model

data class DownloadTask(
    val id: String,
    val item: MediaItem,
    val state: DownloadState = DownloadState.QUEUED,
    val bytesDownloaded: Long = 0,
    val savedUri: String? = null,
    val errorMessage: String? = null,
)

enum class DownloadState { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }
