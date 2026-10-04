package com.domenota.medialoader.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.database.HistoryDatabase
import com.domenota.medialoader.core.model.MediaItem
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.provider.DefaultMediaResolver
import com.domenota.medialoader.core.provider.InstagramProvider
import com.domenota.medialoader.core.provider.MediaResolver
import com.domenota.medialoader.core.provider.ProviderException
import com.domenota.medialoader.core.provider.YouTubeProvider
import com.domenota.medialoader.data.download.AudioDownloadEngine
import com.domenota.medialoader.data.download.AudioExtractor
import com.domenota.medialoader.data.download.DownloadQueue
import com.domenota.medialoader.data.download.DownloadNotifier
import com.domenota.medialoader.data.download.RoutingDownloadEngine
import com.domenota.medialoader.data.download.HttpDownloadEngine
import com.domenota.medialoader.data.download.mediaHeaders
import com.domenota.medialoader.data.download.ActiveDownloadService
import com.domenota.medialoader.data.download.YouTubeDownloadEngine
import com.domenota.medialoader.data.youtube.YoutubeDlAndroid
import com.domenota.medialoader.core.storage.StorageNaming
import com.domenota.medialoader.data.storage.StorageManager
import com.domenota.medialoader.data.storage.StorageSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * The single top-level coordinator. It owns no logic of its own: it wires MediaResolver,
 * DownloadQueue (with its DownloadEngine), StorageManager, HistoryRepository and SessionStore.
 * One instance per process, so the queue keeps running when the screen is recreated.
 */
class MediaRepository private constructor(context: Context) {
    private val appContext = context.applicationContext

    val sessions = SessionStore(appContext)
    private val storage = StorageManager(appContext, StorageSettings(appContext))
    private val youtubeDl = YoutubeDlAndroid(appContext)
    private val history = HistoryRepository(
        Room.databaseBuilder(appContext, HistoryDatabase::class.java, "media-loader.db")
            .addMigrations(object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE downloads ADD COLUMN sourceUrl TEXT")
                    db.execSQL("ALTER TABLE downloads ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
                }
            }, object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE downloads ADD COLUMN previewUrl TEXT")
                }
            }, object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE downloads ADD COLUMN groupId TEXT")
                }
            }, object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE downloads ADD COLUMN formatSelector TEXT")
                }
            }).build().downloadDao(),
    )
    private val resolver: MediaResolver =
        DefaultMediaResolver(listOf(InstagramProvider(cookies = { sessions.cookies() }), YouTubeProvider(youtubeDl)))
    private val notifier = DownloadNotifier(appContext)
    private val queue = DownloadQueue(
        history = history,
        engine = RoutingDownloadEngine(
            media = HttpDownloadEngine(appContext, storage, sessions::cookies),
            audio = AudioDownloadEngine(AudioExtractor(appContext, storage, sessions::cookies)),
            youtube = YouTubeDownloadEngine(appContext, storage, youtubeDl),
        ),
        existingNames = storage::existingNames,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        onBatchChanged = { id, items -> notifier.update(id, items) },
    )

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
            // Older builds saved CDN IDs. Rename only owned completed photos whose real file can
            // be renamed and verified, then update the matching history row.
            val rows = history.all()
            val used = rows.map { it.originalName }.toMutableSet()
            rows.filter { it.state == DownloadState.COMPLETED && it.mediaType == MediaType.PHOTO &&
                StorageNaming.meaningfulStem(it.originalName.substringBeforeLast('.')) == null }
                .groupBy { it.groupId ?: it.id }
                .values.forEach { group ->
                    group.sortedWith(compareBy({ it.createdAtEpochMillis }, { it.originalName }))
                        .forEachIndexed { index, entry ->
                            val uri = entry.savedUri ?: return@forEachIndexed
                            val preferred = StorageNaming.mediaFileName(null,
                                if (group.size > 1) index + 1 else null, "jpg")
                            val newName = StorageNaming.availableName(preferred, used)
                            if (storage.renameOwnedDownload(uri, newName)) {
                                history.save(entry.copy(originalName = newName))
                                used.add(newName)
                            }
                        }
                }
            } catch (_: Exception) {
                // An inaccessible old file must not prevent normal downloads from starting.
            }
        }
    }

    val downloads: Flow<List<DownloadEntity>> = history.observeAll()
    val hiddenCount: Flow<Int> = history.observeHiddenCount()
    val hiddenItems: Flow<List<DownloadEntity>> = history.observeHidden()
    fun trashedUri(id: String): String? = storage.trashedUri(id)

    /** Folder shown in Settings, for example "Download/MediaLoader". */
    val storageDisplayPath: String get() = storage.displayPath
    fun setDownloadFolder(name: String) {
        val folder = name.trim().trim('/')
        require(folder.matches(Regex("[\\p{L}\\p{N}_ -]{1,50}")))
        storage.relativeDirectory = "Download/$folder/"
    }
    fun selectDownloadFolder(uri: Uri) = storage.selectDirectory(uri)
    val usesSelectedFolder: Boolean get() = storage.usesSelectedFolder

    fun isLoggedIn(): Boolean = sessions.isLoggedIn()
    suspend fun updateYtDlp(): String = youtubeDl.update()
    val hasYouTubeCookies: Boolean get() = youtubeDl.hasCookies
    fun importYouTubeCookies(uri: Uri) = youtubeDl.importCookies(uri)
    fun clearYouTubeCookies() = youtubeDl.clearCookies()

    fun importSessionId(value: String) = sessions.saveManualSessionId(value)

    fun logout() = sessions.clear()

    /** Public access first; the saved session is used inside the provider only as a fallback. */
    suspend fun resolve(url: String): List<MediaItem> = try {
        coroutineScope {
            resolver.resolve(url).map { item -> async {
                if (item.providerId == YouTubeProvider.ID) item
                else if (item.type == MediaType.AUDIO) item.copy(
                    originalName = StorageNaming.normalizedMediaName(item.originalName, item.position))
                else {
                    val headers = mediaHeaders(item.downloadUrl)
                    val stem = headers?.fileStem
                    val extension = if (item.type == MediaType.VIDEO) "mp4" else "jpg"
                    item.copy(
                        sizeBytes = item.sizeBytes ?: headers?.sizeBytes,
                        originalName = if (stem != null && item.originalName.startsWith(StorageNaming.FALLBACK_STEM))
                            StorageNaming.mediaFileName(stem, item.position, extension)
                            else item.originalName,
                    ).let { it.copy(originalName = StorageNaming.normalizedMediaName(it.originalName, it.position)) }
                }
            } }.awaitAll()
        }
    } catch (error: ProviderException) {
        // A session that still gets ACCESS_REQUIRED is expired: drop it so the UI offers sign-in again.
        if (error.providerId == InstagramProvider.ID && error.reason == ProviderException.Reason.ACCESS_REQUIRED && sessions.isLoggedIn()) sessions.clear()
        throw error
    }

    suspend fun enqueue(items: List<MediaItem>): List<DownloadEntity> {
        require(items.all(resolver::canDownload)) { "Недопустимый адрес файла" }
        return queue.enqueue(items)
    }

    suspend fun cancel(id: String) = queue.cancel(id)
    suspend fun rename(id: String, input: String) {
        val item = history.byId(id) ?: return
        require(item.state == DownloadState.COMPLETED && !item.hidden)
        val uri = item.savedUri ?: throw IllegalStateException("Файл недоступен")
        val desired = StorageNaming.customName(input, item.originalName)
        if (desired == item.originalName) return
        val taken = storage.existingNames() + history.reservedNames()
        require(desired !in taken) { "Имя занято" }
        val newUri = storage.renameDownload(uri, desired)
            ?: throw IllegalStateException("Переименование не удалось")
        history.save(item.copy(originalName = desired, savedUri = newUri))
    }
    suspend fun hide(id: String) {
        val item = history.byId(id) ?: return
        if (item.state == DownloadState.QUEUED || item.state == DownloadState.RUNNING) return
        if (item.state == DownloadState.COMPLETED && item.savedUri != null) {
            storage.moveToTrash(id, item.savedUri)
        }
        history.save(item.copy(hidden = true, savedUri = null))
    }

    suspend fun restoreHidden(ids: Set<String>) {
        history.all().filter { it.hidden && it.id in ids }.forEach { item ->
            if (item.state == DownloadState.COMPLETED) {
                if (storage.hasTrashed(item.id)) {
                    val mime = when (item.mediaType) {
                        MediaType.PHOTO -> "image/jpeg"
                        MediaType.VIDEO -> "video/mp4"
                        MediaType.AUDIO -> if (item.originalName.endsWith(".m4a", true)) "audio/mp4" else "audio/mpeg"
                    }
                    val (name, uri) = storage.restoreFromTrash(item.id, item.originalName, mime)
                    history.save(item.copy(originalName = name, savedUri = uri, hidden = false))
                    storage.discardTrashed(item.id)
                }
            } else history.save(item.copy(hidden = false))
        }
    }

    suspend fun clearHidden() {
        storage.emptyTrash()
        history.clearHidden()
    }

    suspend fun clearHistory() {
        history.clearHistory()
        storage.emptyTrash()
    }
    suspend fun retry(id: String) {
        val previous = history.byId(id) ?: return
        if (previous.sourceUrl == null) return
        if (previous.state != com.domenota.medialoader.core.model.DownloadState.FAILED &&
            previous.state != com.domenota.medialoader.core.model.DownloadState.CANCELLED) return
        ActiveDownloadService.start(appContext)
        queue.retry(previous)
    }

    companion object {
        @Volatile private var instance: MediaRepository? = null

        fun get(context: Context): MediaRepository = instance ?: synchronized(this) {
            instance ?: MediaRepository(context).also { instance = it }
        }
    }
}
