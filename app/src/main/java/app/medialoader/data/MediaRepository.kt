package app.medialoader.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.room.Room
import app.medialoader.core.database.DownloadEntity
import app.medialoader.core.database.HistoryDatabase
import app.medialoader.core.model.MediaItem
import app.medialoader.core.provider.DefaultMediaResolver
import app.medialoader.core.provider.InstagramProvider
import app.medialoader.core.storage.StorageNaming
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class MediaRepository(private val context: Context) {
    private val database = Room.databaseBuilder(context, HistoryDatabase::class.java, "media-loader.db").build()
    private val system = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val resolver = DefaultMediaResolver(listOf(InstagramProvider()))
    val history: Flow<List<DownloadEntity>> = database.downloadDao().observeAll()

    suspend fun resolve(url: String): List<MediaItem> = resolver.resolve(url)

    suspend fun enqueue(item: MediaItem): DownloadEntity = withContext(Dispatchers.IO) {
        require(InstagramProvider.safeMediaUrl(item.downloadUrl))
        val existing = database.downloadDao().all().map { it.originalName }.toMutableSet()
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val folder = File(downloads, "MediaLoader")
        folder.list()?.let(existing::addAll)
        if (Build.VERSION.SDK_INT >= 29) context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("%Download/MediaLoader/%"), null
        )?.use { cursor -> while (cursor.moveToNext()) existing.add(cursor.getString(0)) }
        val name = StorageNaming.availableName(item.originalName, existing)
        val request = DownloadManager.Request(Uri.parse(item.downloadUrl))
            .setTitle(name)
            .setDescription("MediaLoader · Instagram")
            .setMimeType(if (item.type.name == "VIDEO") "video/mp4" else "image/jpeg")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "MediaLoader/$name")
        val systemId = system.enqueue(request)
        DownloadEntity(
            id = UUID.randomUUID().toString(), providerId = item.providerId,
            originalName = name, mediaType = item.type.name, state = "QUEUED",
            savedUri = null, sizeBytes = item.sizeBytes,
            createdAtEpochMillis = System.currentTimeMillis(), systemDownloadId = systemId,
        ).also { database.downloadDao().upsert(it) }
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        database.downloadDao().all().filter { it.state == "QUEUED" || it.state == "RUNNING" }.forEach { entry ->
            val systemId = entry.systemDownloadId ?: return@forEach
            system.query(DownloadManager.Query().setFilterById(systemId))?.use { cursor ->
                if (!cursor.moveToFirst()) return@use
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val state = when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> "COMPLETED"
                    DownloadManager.STATUS_FAILED -> "FAILED"
                    DownloadManager.STATUS_RUNNING -> "RUNNING"
                    else -> "QUEUED"
                }
                val localUri = if (state == "COMPLETED") system.getUriForDownloadedFile(systemId)?.toString() else null
                database.downloadDao().upsert(entry.copy(
                    state = state, savedUri = localUri,
                    sizeBytes = if (total > 0) total else entry.sizeBytes,
                    errorMessage = if (state == "FAILED") "Не удалось сохранить файл. Проверьте доступность публикации и повторите анализ." else null,
                ))
            }
        }
    }

    suspend fun cancel(entry: DownloadEntity) = withContext(Dispatchers.IO) {
        entry.systemDownloadId?.let { system.remove(it) }
        database.downloadDao().upsert(entry.copy(state = "CANCELLED"))
    }
}
