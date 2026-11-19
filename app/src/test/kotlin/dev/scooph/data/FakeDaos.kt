package dev.scooph.data

import dev.scooph.data.db.EntryDao
import dev.scooph.data.db.EntryEntity
import dev.scooph.data.db.SourceDao
import dev.scooph.data.db.SourceEntity
import dev.scooph.data.db.TrackedDao
import dev.scooph.data.db.TrackedEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * 内存版三表 DAO，给仓库逻辑单测用。
 *
 * 不走 Robolectric 的原生 SQLite：本机 Windows 上 `robolectric-nativeruntime.dll`
 * 在 Room 后台线程里偶发 ACCESS_VIOLATION，会把整个测试进程打掉。
 * 真实 SQLite 的映射由 `EntityMappingTest` 单独覆盖。
 */
class FakeDaos {
    val sources = FakeSourceDao()
    val entries = FakeEntryDao()
    val tracked = FakeTrackedDao()

    class FakeSourceDao : SourceDao {
        private val rows = MutableStateFlow<Map<String, SourceEntity>>(emptyMap())

        override suspend fun upsert(source: SourceEntity) {
            rows.value = rows.value + (source.url to source)
        }

        override fun observeAll(): Flow<List<SourceEntity>> =
            rows.map { table -> table.values.sortedWith(compareBy({ it.addedOrder }, { it.url })) }

        override suspend fun delete(url: String) {
            rows.value = rows.value - url
        }
    }

    class FakeEntryDao : EntryDao() {
        private val rows = MutableStateFlow<Map<Pair<String, String>, EntryEntity>>(emptyMap())

        override suspend fun insertAll(entries: List<EntryEntity>) {
            rows.value = rows.value + entries.associateBy { it.packageName to it.sourceId }
        }

        override fun observeAll(): Flow<List<EntryEntity>> =
            rows.map { table ->
                table.values.sortedWith(compareBy({ it.packageName }, { it.sourceId }))
            }

        override suspend fun deleteForSource(sourceId: String) {
            rows.value = rows.value.filterKeys { it.second != sourceId }
        }
    }

    class FakeTrackedDao : TrackedDao {
        private val rows = MutableStateFlow<Map<String, TrackedEntity>>(emptyMap())

        override suspend fun upsert(tracked: TrackedEntity) {
            rows.value = rows.value + (tracked.packageName to tracked)
        }

        override suspend fun get(packageName: String): TrackedEntity? = rows.value[packageName]

        override fun observeAll(): Flow<List<TrackedEntity>> =
            rows.map { table -> table.values.sortedBy { it.packageName } }
    }
}
