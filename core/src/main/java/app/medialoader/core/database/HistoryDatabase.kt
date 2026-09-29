package app.medialoader.core.database

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** Schema only. No database instance is opened in this release. */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val id: String,
    val providerId: String,
    val originalName: String,
    val mediaType: String,
    val state: String,
    val savedUri: String?,
    val sizeBytes: Long?,
    val createdAtEpochMillis: Long,
    val systemDownloadId: Long? = null,
    val errorMessage: String? = null,
)

@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAtEpochMillis DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: DownloadEntity)

    @Query("SELECT * FROM downloads ORDER BY createdAtEpochMillis DESC")
    suspend fun all(): List<DownloadEntity>
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): SettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: SettingsEntity)
}

@Database(entities = [DownloadEntity::class, SettingsEntity::class], version = 1, exportSchema = false)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun settingsDao(): SettingsDao
}
