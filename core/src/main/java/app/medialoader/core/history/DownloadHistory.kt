package app.medialoader.core.history

import app.medialoader.core.model.DownloadTask
import kotlinx.coroutines.flow.Flow

interface DownloadHistory {
    fun observe(): Flow<List<DownloadTask>>
    suspend fun record(task: DownloadTask)
}
