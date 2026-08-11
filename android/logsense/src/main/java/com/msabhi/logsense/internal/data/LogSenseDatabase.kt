package com.msabhi.logsense.internal.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** Rows whose owning run is unknown (pre-session data, or exits we couldn't attribute). */
internal const val EARLIER_SESSION_ID = "earlier"

/** One process run. Events and crashes are grouped by the session that produced them. */
@Entity(tableName = "sessions")
internal data class SessionEntity(
    @PrimaryKey val id: String,
    val startedAt: Long,
    val endedAt: Long?,
    val appVersion: String,
)

@Entity(tableName = "events")
internal data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    @ColumnInfo(defaultValue = EARLIER_SESSION_ID) val sessionId: String = EARLIER_SESSION_ID,
    val tag: String,
    val name: String,
    val paramsJson: String,
)

@Entity(tableName = "crashes")
internal data class CrashEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    @ColumnInfo(defaultValue = EARLIER_SESSION_ID) val sessionId: String = EARLIER_SESSION_ID,
    val type: String, // "JVM" | "ANR" | "NATIVE"
    val threadName: String?,
    val exceptionClass: String?,
    val message: String?,
    val stacktrace: String,
    val deviceInfo: String,
    val logContext: String,
)

@Dao
internal interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(session: SessionEntity)

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC")
    suspend fun getAll(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun get(id: String): SessionEntity?

    /** The session that was running at [t] (greatest startedAt not after t), for attributing exits. */
    @Query("SELECT id FROM sessions WHERE startedAt <= :t ORDER BY startedAt DESC LIMIT 1")
    suspend fun sessionActiveAt(t: Long): String?

    @Query("SELECT id FROM sessions ORDER BY startedAt DESC LIMIT :cap")
    suspend fun recentIds(cap: Int): List<String>

    @Query("DELETE FROM sessions WHERE id NOT IN (:keep)")
    suspend fun deleteNotIn(keep: List<String>)

    @Query("DELETE FROM sessions WHERE id = :id") suspend fun delete(id: String)

    @Query("DELETE FROM sessions") suspend fun clear()
}

@Dao
internal interface EventDao {
    @Insert suspend fun insert(events: List<EventEntity>)

    @Query("SELECT * FROM events ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<EventEntity>>

    @Query("SELECT * FROM events ORDER BY timestamp DESC")
    suspend fun getAll(): List<EventEntity>

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun get(id: Long): EventEntity?

    @Query("DELETE FROM events WHERE id = :id") suspend fun delete(id: Long)

    @Query("DELETE FROM events WHERE id IN (:ids)") suspend fun deleteIds(ids: List<Long>)

    @Query("DELETE FROM events WHERE sessionId = :sessionId") suspend fun deleteBySession(sessionId: String)

    @Query("DELETE FROM events WHERE sessionId NOT IN (:keep)") suspend fun deleteNotInSessions(keep: List<String>)

    @Query("DELETE FROM events") suspend fun clear()

    @Query("DELETE FROM events WHERE timestamp < :minTs")
    suspend fun trimAge(minTs: Long)

    /** Keeps only the newest [cap] events *within* [sessionId] — so a busy run can't evict older sessions. */
    @Query(
        "DELETE FROM events WHERE sessionId = :sessionId AND id NOT IN " +
            "(SELECT id FROM events WHERE sessionId = :sessionId ORDER BY timestamp DESC LIMIT :cap)",
    )
    suspend fun trimCountInSession(sessionId: String, cap: Int)
}

@Dao
internal interface CrashDao {
    @Insert suspend fun insert(crash: CrashEntity): Long

    @Query("SELECT * FROM crashes ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<CrashEntity>>

    @Query("SELECT * FROM crashes WHERE id = :id")
    suspend fun get(id: Long): CrashEntity?

    /** Reactive by-id lookup: emits when the row is (later) ingested, so a deep-link opened before
     *  ingestion finishes fills in instead of staying blank. */
    @Query("SELECT * FROM crashes WHERE id = :id")
    fun observe(id: Long): Flow<CrashEntity?>

    @Query("DELETE FROM crashes WHERE id = :id") suspend fun delete(id: Long)

    @Query("DELETE FROM crashes WHERE id IN (:ids)") suspend fun deleteIds(ids: List<Long>)

    @Query("DELETE FROM crashes WHERE sessionId = :sessionId") suspend fun deleteBySession(sessionId: String)

    @Query("DELETE FROM crashes WHERE sessionId NOT IN (:keep)") suspend fun deleteNotInSessions(keep: List<String>)

    @Query("DELETE FROM crashes") suspend fun clear()

    @Query("DELETE FROM crashes WHERE timestamp < :minTs")
    suspend fun trimAge(minTs: Long)

    @Query("DELETE FROM crashes WHERE id NOT IN (SELECT id FROM crashes ORDER BY timestamp DESC LIMIT :cap)")
    suspend fun trimCount(cap: Int)
}

@Database(entities = [SessionEntity::class, EventEntity::class, CrashEntity::class], version = 2, exportSchema = false)
internal abstract class LogSenseDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun eventDao(): EventDao
    abstract fun crashDao(): CrashDao

    companion object {
        /** v1→v2: add sessions + sessionId columns, backfilling existing rows into an "Earlier" session. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sessions` (`id` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, " +
                        "`endedAt` INTEGER, `appVersion` TEXT NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `sessions` (`id`, `startedAt`, `endedAt`, `appVersion`) " +
                        "VALUES ('$EARLIER_SESSION_ID', 0, NULL, '')",
                )
                db.execSQL("ALTER TABLE `events` ADD COLUMN `sessionId` TEXT NOT NULL DEFAULT '$EARLIER_SESSION_ID'")
                db.execSQL("ALTER TABLE `crashes` ADD COLUMN `sessionId` TEXT NOT NULL DEFAULT '$EARLIER_SESSION_ID'")
            }
        }

        fun create(context: Context): LogSenseDatabase =
            Room.databaseBuilder(context, LogSenseDatabase::class.java, "logsense.db")
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
