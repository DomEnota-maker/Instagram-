package com.domenota.medialoader.core.database

import androidx.room.Dao
import androidx.room.Database
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import kotlinx.coroutines.flow.Flow

/** One row of download history. State and type use the shared enums, never raw strings. */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val id: String,
    val providerId: String,
    val originalName: String,
    val mediaType: MediaType,
    val state: DownloadState,
    val savedUri: String?,
    val sizeBytes: Long?,
    val createdAtEpochMillis: Long,
    val bytesDownloaded: Long = 0,
    val errorMessage: String? = null,
    val sourceUrl: String? = null,
    val sourcePageUrl: String? = null,
    @ColumnInfo(defaultValue = "0") val hidden: Boolean = false,
    val previewUrl: String? = null,
    val groupId: String? = null,
    val formatSelector: String? = null,
    val sourceAudioArtist: String? = null,
    val sourceAudioTitle: String? = null,
    val sourceAudioArtworkUrl: String? = null,
    val recognizedArtist: String? = null,
    val recognizedTitle: String? = null,
    val recognizedAlbum: String? = null,
    val recognitionTrackId: String? = null,
    val recognitionSource: String? = null,
    val recognitionArtworkUrl: String? = null,
)

@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val key: String,
    val value: String,
)

class DatabaseConverters {
    @TypeConverter
    fun stateToString(value: DownloadState): String = value.name

    /** Unknown values (for example from a newer app version) are shown as failed instead of crashing. */
    @TypeConverter
    fun stringToState(value: String): DownloadState =
        DownloadState.values().firstOrNull { it.name == value } ?: DownloadState.FAILED

    @TypeConverter
    fun typeToString(value: MediaType): String = value.name

    @TypeConverter
    fun stringToType(value: String): MediaType =
        MediaType.values().firstOrNull { it.name == value } ?: MediaType.PHOTO
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads WHERE hidden = 0 ORDER BY createdAtEpochMillis DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE hidden = 1 ORDER BY createdAtEpochMillis DESC")
    fun observeHidden(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads ORDER BY createdAtEpochMillis DESC")
    suspend fun all(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): DownloadEntity?

    @Query("SELECT COUNT(*) FROM downloads WHERE hidden = 1")
    fun observeHiddenCount(): Flow<Int>

    @Query("UPDATE downloads SET hidden = 0 WHERE hidden = 1")
    suspend fun restoreHidden()

    @Query("DELETE FROM downloads WHERE id = :id AND hidden = 1")
    suspend fun deleteHidden(id: String)

    @Query("DELETE FROM downloads WHERE hidden = 1")
    suspend fun clearHidden()

    @Query("DELETE FROM downloads WHERE state IN ('COMPLETED', 'FAILED', 'CANCELLED')")
    suspend fun clearHistory()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: DownloadEntity)
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): SettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: SettingsEntity)
}

@Database(entities = [DownloadEntity::class, SettingsEntity::class], version = 7, exportSchema = false)
@TypeConverters(DatabaseConverters::class)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun settingsDao(): SettingsDao
}
