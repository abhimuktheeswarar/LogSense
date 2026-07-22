package com.msabhi.logsense.internal.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "events")
internal data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val tag: String,
    val name: String,
    val paramsJson: String,
)

@Entity(tableName = "crashes")
internal data class CrashEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val type: String, // "JVM" | "ANR" | "NATIVE"
    val threadName: String?,
    val exceptionClass: String?,
    val message: String?,
    val stacktrace: String,
    val deviceInfo: String,
    val logContext: String,
)

@Dao
internal interface EventDao {
    @Insert suspend fun insert(events: List<EventEntity>)

    @Query("SELECT * FROM events ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun get(id: Long): EventEntity?

    @Query("DELETE FROM events") suspend fun clear()

    @Query("DELETE FROM events WHERE timestamp < :minTs")
    suspend fun trimAge(minTs: Long)

    @Query("DELETE FROM events WHERE id NOT IN (SELECT id FROM events ORDER BY timestamp DESC LIMIT :cap)")
    suspend fun trimCount(cap: Int)
}

@Dao
internal interface CrashDao {
    @Insert suspend fun insert(crash: CrashEntity): Long

    @Query("SELECT * FROM crashes ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<CrashEntity>>

    @Query("SELECT * FROM crashes WHERE id = :id")
    suspend fun get(id: Long): CrashEntity?

    @Query("DELETE FROM crashes") suspend fun clear()

    @Query("DELETE FROM crashes WHERE timestamp < :minTs")
    suspend fun trimAge(minTs: Long)

    @Query("DELETE FROM crashes WHERE id NOT IN (SELECT id FROM crashes ORDER BY timestamp DESC LIMIT :cap)")
    suspend fun trimCount(cap: Int)
}

@Database(entities = [EventEntity::class, CrashEntity::class], version = 1, exportSchema = false)
internal abstract class LogSenseDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun crashDao(): CrashDao

    companion object {
        fun create(context: Context): LogSenseDatabase =
            Room.databaseBuilder(context, LogSenseDatabase::class.java, "logsense.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
