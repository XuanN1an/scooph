package dev.scooph.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {
    /** 按 url 覆盖写入：已存在则更新，否则插入。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: SourceEntity)

    @Query("SELECT * FROM sources ORDER BY addedOrder ASC, url ASC")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("DELETE FROM sources WHERE url = :url")
    suspend fun delete(url: String)
}

@Dao
abstract class EntryDao {
    /**
     * 用 [entries] 整批替换 [sourceId] 的条目：先删该来源的旧条目再插入新条目，
     * 两个动作在同一个事务里完成，别的来源的条目不受影响。
     */
    @Transaction
    open suspend fun replaceForSource(sourceId: String, entries: List<EntryEntity>) {
        deleteForSource(sourceId)
        if (entries.isNotEmpty()) {
            insertAll(entries)
        }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(entries: List<EntryEntity>)

    @Query("SELECT * FROM entries ORDER BY packageName ASC, sourceId ASC")
    abstract fun observeAll(): Flow<List<EntryEntity>>

    @Query("DELETE FROM entries WHERE sourceId = :sourceId")
    abstract suspend fun deleteForSource(sourceId: String)
}

@Dao
interface TrackedDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tracked: TrackedEntity)

    @Query("SELECT * FROM tracked WHERE packageName = :packageName")
    suspend fun get(packageName: String): TrackedEntity?

    @Query("SELECT * FROM tracked ORDER BY packageName ASC")
    fun observeAll(): Flow<List<TrackedEntity>>
}
