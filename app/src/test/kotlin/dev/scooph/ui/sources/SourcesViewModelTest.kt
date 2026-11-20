package dev.scooph.ui.sources

import dev.scooph.data.FakeDaos
import dev.scooph.data.Repository
import dev.scooph.data.db.SourceEntity
import dev.scooph.data.db.TrackedEntity
import dev.scooph.engine.EngineGateway
import dev.scooph.engine.FetchResultDto
import dev.scooph.engine.LoadResultDto
import dev.scooph.engine.PlanRequestDto
import dev.scooph.engine.PlanResultDto
import dev.scooph.engine.SyncResultDto
import dev.scooph.scan.InstalledApp
import dev.scooph.scan.PackageScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 来源页（计划 Task 22）。
 *
 * ViewModel 只依赖 [SourcesRepository]，非法地址在进仓库之前就被挡住；
 * 删除最后一个来源不影响 `tracked` 记录——数据属于设备，不属于 bucket。
 */
class SourcesViewModelTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `a non http url is rejected with a readable error`() {
        val repository = FakeSourcesRepository()
        val vm = SourcesViewModel(repository, scope)

        vm.onInputChange("ftp://example.com/bucket.git")
        vm.add()

        assertEquals("只支持 http/https 地址", vm.state.value.error)
        assertFalse(repository.added)
    }

    @Test
    fun `a valid url is added and the list reloads`() {
        val repository = FakeSourcesRepository()
        val vm = SourcesViewModel(repository, scope)

        vm.onInputChange("https://example.com/bucket.git")
        vm.add()

        assertTrue(repository.added)
        assertNull(vm.state.value.error)
        assertEquals(listOf(URL), vm.state.value.sources.map { it.url })
        assertEquals("", vm.state.value.input)
    }

    @Test
    fun `removing a source deletes it and reloads`() {
        val repository = FakeSourcesRepository(mutableListOf(SourceRow(URL, "bucket", 3, 0L, "OK")))
        val vm = SourcesViewModel(repository, scope)
        vm.load()

        vm.remove(URL)

        assertEquals(listOf(URL), repository.removed)
        assertTrue(vm.state.value.sources.isEmpty())
    }

    @Test
    fun `clearing the download cache reports how much was freed`() {
        val vm = SourcesViewModel(FakeSourcesRepository(), scope)

        vm.clearCache()

        assertEquals(4096L, vm.state.value.cacheCleared)
        assertEquals(0L, vm.state.value.cacheBytes)
    }

    @Test
    fun `the last source can be removed without losing tracked rows`() = runBlocking {
        val daos = FakeDaos()
        val repository =
            Repository(
                sourceDao = daos.sources,
                entryDao = daos.entries,
                trackedDao = daos.tracked,
                engine = NoopEngine(),
                scanner = NoopScanner(),
            )
        daos.sources.upsert(
            SourceEntity(
                url = URL,
                name = "bucket",
                lastSyncAt = 0L,
                lastCommit = null,
                status = "OK",
                addedOrder = 0,
            ),
        )
        daos.tracked.upsert(
            TrackedEntity(
                packageName = "com.example.app",
                policy = "FOLLOW",
                skipVersionCode = 0L,
                installedVersionCode = 1L,
                signature = "sig",
                installedAt = 1L,
                lastCheckedAt = 1L,
                lastError = null,
            ),
        )

        repository.remove(URL)

        assertTrue(daos.sources.observeAll().first().isEmpty())
        // 追踪记录属于设备：来源没了也还在，只是状态变成 NO_SOURCE。
        assertNotNull(daos.tracked.get("com.example.app"))
    }

    private class FakeSourcesRepository(
        private val rows: MutableList<SourceRow> = mutableListOf(),
    ) : SourcesRepository {
        var added = false
            private set
        val removed = mutableListOf<String>()

        override suspend fun load(): List<SourceRow> = rows.toList()

        override suspend fun add(url: String): String? {
            added = true
            rows += SourceRow(url, url, 0, 0L, "PENDING")
            return null
        }

        override suspend fun remove(url: String) {
            removed += url
            rows.removeAll { it.url == url }
        }

        override suspend fun sync(url: String) = Unit

        override suspend fun cacheSize(): Long = 4096L

        override suspend fun clearCache(): Long = 4096L
    }

    private class NoopEngine : EngineGateway {
        override fun version(): String = "test"

        override fun sync(url: String): SyncResultDto =
            SyncResultDto(commit = "", files = emptyList(), empty = true)

        override fun load(url: String): LoadResultDto = LoadResultDto(entries = emptyList(), drops = emptyList())

        override fun plan(request: PlanRequestDto): PlanResultDto = PlanResultDto(items = emptyList())

        override fun fetch(url: String, destPath: String, wantSHA256: String): FetchResultDto =
            FetchResultDto(path = "", size = 0, sha256 = "")
    }

    private class NoopScanner : PackageScanner {
        override suspend fun scan(): List<InstalledApp> = emptyList()
    }

    private companion object {
        const val URL = "https://example.com/bucket.git"
    }
}
