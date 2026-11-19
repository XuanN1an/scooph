package dev.scooph.data

import android.app.Application
import dev.scooph.data.db.EntryDao
import dev.scooph.data.db.EntryEntity
import dev.scooph.data.db.SourceDao
import dev.scooph.data.db.SourceEntity
import dev.scooph.data.db.TrackedDao
import dev.scooph.data.db.TrackedEntity
import dev.scooph.engine.EngineFacade
import dev.scooph.scan.InstalledApp
import dev.scooph.scan.PackageScanner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 首次启动接入官方 bucket（spec §8 / 计划 Task 12）。
 *
 * 假 DAO 直接放在内存里：`sources` 为空时 [Repository.ensureDefaultSource] 插入
 * [DEFAULT_BUCKET_URL] 并同步一次；已有来源时不再插入，重复调用也只留一条。
 *
 * 用 Robolectric 而不是纯 JVM：`EngineFacade` 在 JVM 单测里走占位 JSON，
 * 需要真的 `org.json` 才能解析（纯 JVM 的 android.jar 方法会抛 Stub!）。
 * `application = Application::class` 避开 [dev.scooph.ScoophApp] 的 onCreate 副作用。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DefaultSourceTest {

    @Test
    fun `sources empty inserts the official bucket and syncs it once`() = runBlocking {
        val sourceDao = FakeSourceDao()

        repository(sourceDao).ensureDefaultSource()

        val sources = sourceDao.observeAll().first()
        assertEquals(1, sources.size)
        assertEquals(DEFAULT_BUCKET_URL, sources.single().url)
        // 插入后同步了一次：占位引擎同步成功，lastSyncAt 从 0 被写成当前时间。
        assertTrue(sources.single().lastSyncAt > 0L)
    }

    @Test
    fun `an existing source is kept and nothing is inserted`() = runBlocking {
        val sourceDao = FakeSourceDao()
        sourceDao.upsert(existingSource())
        val writesBefore = sourceDao.writeCount

        repository(sourceDao).ensureDefaultSource()

        val sources = sourceDao.observeAll().first()
        assertEquals(1, sources.size)
        assertEquals(EXISTING_URL, sources.single().url)
        // 已有来源时连写都不写：没有插入默认 source 的动作。
        assertEquals(writesBefore, sourceDao.writeCount)
    }

    @Test
    fun `calling twice does not duplicate the official bucket`() = runBlocking {
        val sourceDao = FakeSourceDao()
        val repository = repository(sourceDao)

        repository.ensureDefaultSource()
        repository.ensureDefaultSource()

        assertEquals(listOf(DEFAULT_BUCKET_URL), sourceDao.observeAll().first().map { it.url })
    }

    private fun repository(sourceDao: SourceDao) =
        Repository(
            sourceDao = sourceDao,
            entryDao = FakeEntryDao(),
            trackedDao = FakeTrackedDao(),
            engine = EngineFacade(File(System.getProperty("java.io.tmpdir"), "scooph-default-source-test")),
            scanner = FakeScanner(),
        )

    private fun existingSource() = SourceEntity(
        url = EXISTING_URL,
        name = "用户自己的 bucket",
        lastSyncAt = 42L,
        lastCommit = "deadbeef",
        status = "OK",
        addedOrder = 0,
    )

    private class FakeSourceDao : SourceDao {
        private val rows = MutableStateFlow<List<SourceEntity>>(emptyList())

        var writeCount = 0
            private set

        override suspend fun upsert(source: SourceEntity) {
            writeCount++
            rows.value = rows.value.filterNot { it.url == source.url } + source
        }

        override fun observeAll(): Flow<List<SourceEntity>> = rows

        override suspend fun delete(url: String) {
            rows.value = rows.value.filterNot { it.url == url }
        }
    }

    private class FakeEntryDao : EntryDao() {
        private val rows = MutableStateFlow<Map<Pair<String, String>, EntryEntity>>(emptyMap())

        override suspend fun insertAll(entries: List<EntryEntity>) {
            rows.value = rows.value + entries.associateBy { it.packageName to it.sourceId }
        }

        override fun observeAll(): Flow<List<EntryEntity>> =
            MutableStateFlow(rows.value.values.toList())

        override suspend fun deleteForSource(sourceId: String) {
            rows.value = rows.value.filterKeys { it.second != sourceId }
        }
    }

    private class FakeTrackedDao : TrackedDao {
        private val rows = MutableStateFlow<Map<String, TrackedEntity>>(emptyMap())

        override suspend fun upsert(tracked: TrackedEntity) {
            rows.value = rows.value + (tracked.packageName to tracked)
        }

        override suspend fun get(packageName: String): TrackedEntity? = rows.value[packageName]

        override fun observeAll(): Flow<List<TrackedEntity>> =
            MutableStateFlow(rows.value.values.toList())
    }

    private class FakeScanner : PackageScanner {
        override suspend fun scan(): List<InstalledApp> = emptyList()
    }

    private companion object {
        const val EXISTING_URL = "https://example.com/user-bucket.git"
    }
}
