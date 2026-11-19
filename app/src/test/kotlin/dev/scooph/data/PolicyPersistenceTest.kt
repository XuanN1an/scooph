package dev.scooph.data

import dev.scooph.data.db.EntryEntity
import dev.scooph.data.db.SourceEntity
import dev.scooph.data.db.TrackedEntity
import dev.scooph.domain.DerivedState
import dev.scooph.domain.Policy
import dev.scooph.engine.ApkDto
import dev.scooph.engine.EngineGateway
import dev.scooph.engine.EntryDto
import dev.scooph.engine.FetchResultDto
import dev.scooph.engine.LoadResultDto
import dev.scooph.engine.PlanItemDto
import dev.scooph.engine.PlanRequestDto
import dev.scooph.engine.PlanResultDto
import dev.scooph.engine.SyncResultDto
import dev.scooph.scan.InstalledApp
import dev.scooph.scan.PackageScanner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 三个策略落库（spec §9.2 / 计划 Task 14）。
 *
 * 内存 DAO + 假引擎与假扫描：验证策略写入后再读回一致、`FOLLOW` 清空跳过版本，
 * 以及 `UNTRACKED` 墓碑不会被 `refresh()` 改回。
 */
class PolicyPersistenceTest {

    private val daos = FakeDaos()

    @Test
    fun `setPolicy stores SKIP_VERSION together with the skip version code`() = runBlocking {
        seedTracked(policy = Policy.FOLLOW, skipVersionCode = 0)

        repository().setPolicy(PKG, Policy.SKIP_VERSION, 7000)

        val tracked = daos.tracked.get(PKG)!!
        assertEquals("SKIP_VERSION", tracked.policy)
        assertEquals(7000L, tracked.skipVersionCode)
    }

    @Test
    fun `setPolicy FOLLOW clears the skip version code`() = runBlocking {
        seedTracked(policy = Policy.SKIP_VERSION, skipVersionCode = 7000)

        repository().setPolicy(PKG, Policy.FOLLOW, 7000)

        val tracked = daos.tracked.get(PKG)!!
        assertEquals("FOLLOW", tracked.policy)
        assertEquals(0L, tracked.skipVersionCode)
    }

    @Test
    fun `UNTRACKED tombstone survives refresh`() = runBlocking {
        seedSourceAndEntry()
        seedTracked(policy = Policy.UNTRACKED, skipVersionCode = 0, installedVersionCode = 6804)
        val repo =
            repository(
                engine =
                    FakeEngine(
                        planItems =
                            listOf(
                                PlanItemDto(
                                    packageName = PKG,
                                    name = "Example",
                                    relation = "newer",
                                    entryVersionCode = 7000,
                                    installedVersionCode = 6805,
                                    apkUrl = "https://example.com/app.apk",
                                    sha256 = "0".repeat(64),
                                    size = 1,
                                    sourceId = SOURCE_URL,
                                ),
                            ),
                    ),
                scanner = FakeScanner(installedVersionCode = 6805),
            )

        val rows = repo.refresh()

        val tracked = daos.tracked.get(PKG)!!
        // 墓碑不被同步/扫描覆盖，只刷新本机版本。
        assertEquals("UNTRACKED", tracked.policy)
        assertEquals(6805L, tracked.installedVersionCode)
        assertEquals(0L, tracked.skipVersionCode)
        assertEquals(DerivedState.IGNORED, rows.single().state)
    }

    private fun repository(
        engine: EngineGateway = FakeEngine(),
        scanner: PackageScanner = FakeScanner(),
    ) = Repository(
        sourceDao = daos.sources,
        entryDao = daos.entries,
        trackedDao = daos.tracked,
        engine = engine,
        scanner = scanner,
    )

    private suspend fun seedSourceAndEntry() {
        daos.sources.upsert(
            SourceEntity(
                url = SOURCE_URL,
                name = "bucket-a",
                lastSyncAt = 0L,
                lastCommit = null,
                status = "OK",
                addedOrder = 0,
            ),
        )
        daos.entries.insertAll(listOf(entry()))
    }

    private suspend fun seedTracked(
        policy: Policy,
        skipVersionCode: Long,
        installedVersionCode: Long = 6804,
    ) {
        daos.tracked.upsert(
            TrackedEntity(
                packageName = PKG,
                policy = policy.name,
                skipVersionCode = skipVersionCode,
                installedVersionCode = installedVersionCode,
                signature = "sig",
                installedAt = 1L,
                lastCheckedAt = 1L,
                lastError = null,
            ),
        )
    }

    private fun entry() = EntryEntity(
        packageName = PKG,
        sourceId = SOURCE_URL,
        name = "Example",
        versionCode = 7000,
        versionName = "7.0",
        apkUrl = "https://example.com/app.apk",
        sha256 = "0".repeat(64),
        size = 1,
        notes = null,
    )

    private class FakeEngine(
        private val planItems: List<PlanItemDto> = emptyList(),
    ) : EngineGateway {
        override fun version(): String = "test"

        override fun sync(url: String): SyncResultDto =
            SyncResultDto(commit = "c1", files = emptyList(), empty = false)

        override fun load(url: String): LoadResultDto =
            LoadResultDto(
                entries =
                    listOf(
                        EntryDto(
                            schema = 1,
                            packageName = PKG,
                            name = "Example",
                            versionCode = 7000,
                            versionName = "7.0",
                            apk = ApkDto(url = "https://example.com/app.apk", size = 1, sha256 = "0".repeat(64)),
                            source = url,
                            minSdk = 0,
                            notes = "",
                        ),
                    ),
                drops = emptyList(),
            )

        override fun plan(request: PlanRequestDto): PlanResultDto = PlanResultDto(items = planItems)

        override fun fetch(url: String, destPath: String, wantSHA256: String): FetchResultDto =
            FetchResultDto(path = "", size = 0, sha256 = "")
    }

    private class FakeScanner(private val installedVersionCode: Long = 6804) : PackageScanner {
        override suspend fun scan(): List<InstalledApp> =
            listOf(
                InstalledApp(
                    packageName = PKG,
                    versionCode = installedVersionCode,
                    signature = "sig",
                    hasSplits = false,
                    label = "Example",
                ),
            )
    }

    private companion object {
        const val PKG = "com.example.app"
        const val SOURCE_URL = "https://example.com/bucket-a.git"
    }
}
