package com.domenota.medialoader.data

import com.domenota.medialoader.core.database.DownloadDao
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import kotlinx.coroutines.flow.Flow

/** The only place that talks to the download table. */
class HistoryRepository(private val dao: DownloadDao) {
    fun observeAll(): Flow<List<DownloadEntity>> = dao.observeAll()
    fun observeHiddenCount(): Flow<Int> = dao.observeHiddenCount()
    fun observeHidden(): Flow<List<DownloadEntity>> = dao.observeHidden()

    suspend fun all(): List<DownloadEntity> = dao.all()

    suspend fun byId(id: String): DownloadEntity? = dao.byId(id)

    suspend fun save(entity: DownloadEntity) = dao.upsert(entity)
    suspend fun hide(id: String) { dao.byId(id)?.let { dao.upsert(it.copy(hidden = true)) } }
    suspend fun restoreHidden() = dao.restoreHidden()
    suspend fun clearHidden() = dao.clearHidden()
    suspend fun deleteHidden(id: String) = dao.deleteHidden(id)
    suspend fun clearHistory() = dao.clearHistory()

    /** Names of files that are queued or downloading, so they are not on disk yet but are already taken. */
    suspend fun reservedNames(): Set<String> = dao.all()
        .filter { it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING }
        .map { it.originalName }
        .toSet()
}
