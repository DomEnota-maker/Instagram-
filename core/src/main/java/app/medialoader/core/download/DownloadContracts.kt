package app.medialoader.core.download

import app.medialoader.core.model.DownloadTask
import app.medialoader.core.model.MediaItem
import kotlinx.coroutines.flow.Flow

/** Contracts only; no workers, network or file operations in Foundation. */
interface DownloadManager {
    suspend fun enqueue(item: MediaItem): DownloadTask
    suspend fun cancel(taskId: String)
}

interface QueueManager {
    fun observeTasks(): Flow<List<DownloadTask>>
}

interface StorageManager {
    suspend fun save(item: MediaItem): String
}
