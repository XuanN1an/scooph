package dev.scooph.data

import dev.scooph.data.db.EntryEntity
import dev.scooph.data.db.SourceEntity
import dev.scooph.data.db.TrackedEntity
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
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 签名锚点在扫描时就建立（spec §10.3 / 计划 Task 16）。
 *
 * 本机已装且有签名、`tracked.signature` 为空 → 刷新后写入扫描值；
 * `UNTRACKED` 是用户显式停止跟踪的墓碑，不为它建锚。
 */
class AnchorTest {

    private val daos = FakeDaos()

    @Test
    fun `refresh fills a missing signature anchor from the scanned app`() = runBlocking {
        seedSourceAndEntry()
        seedTracked(policy = Policy.FOLLOW, signature = null)

        repository().refresh()

        assertEquals(SCANNED_SIGNATURE, daos.tracked.get(PKG)!!.signature)
    }

    @Test
    fun `UNTRACKED app does not get an anchor`() = runBlocking {
        seedSourceAndEntry()
        seedTracked(policy = Policy.UNTRACKED, signature = null)

        repository().refresh()

        assertNull(daos.tracked.get(PKG)!!.signature)
    }

    @Test
    fun `an existing anchor is never overwritten`() = runBlocking {
        seedSourceAndEntry()
        seedTracked(policy = Policy.FOLLOW, signature = "original")

        repository().refresh()

        assertEquals("original", daos.tracked.get(PKG)!!.signature)
    }

    private fun repository() = Repository(
        sourceDao = daos.sources,
        entryDao = daos.entries,
        trackedDao = daos.tracked,
        engine = FakeEngine(),
        scanner = FakeScanner(),
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
        daos.entries.insertAll(
            listOf(
                EntryEntity(
                    packageName = PKG,
                    sourceId = SOURCE_URL,
                    name = "Example",
                    versionCode = 7000,
                    versionName = "7.0",
                    apkUrl = "https://example.com/app.apk",
                    sha256 = "0".repeat(64),
                    size = 1,
                    notes = null,
                ),
            ),
        )
    }

    private suspend fun seedTracked(policy: Policy, signature: String?) {
        daos.tracked.upsert(
            TrackedEntity(
                packageName = PKG,
                policy = policy.name,
                skipVersionCode = 0L,
                installedVersionCode = 6804,
                signature = signature,
                installedAt = 1L,
                lastCheckedAt = 1L,
                lastError = null,
            ),
        )
    }

    private class FakeEngine : EngineGateway {
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

        override fun plan(request: PlanRequestDto): PlanResultDto =
            PlanResultDto(
                items =
                    listOf(
                        PlanItemDto(
                            packageName = PKG,
                            name = "Example",
                            relation = "newer",
                            entryVersionCode = 7000,
                            installedVersionCode = 6804,
                            apkUrl = "https://example.com/app.apk",
                            sha256 = "0".repeat(64),
                            size = 1,
                            sourceId = SOURCE_URL,
                        ),
                    ),
            )

        override fun fetch(url: String, destPath: String, wantSHA256: String): FetchResultDto =
            FetchResultDto(path = "", size = 0, sha256 = "")
    }

    private class FakeScanner : PackageScanner {
        override suspend fun scan(): List<InstalledApp> =
            listOf(
                InstalledApp(
                    packageName = PKG,
                    versionCode = 6804,
                    signature = SCANNED_SIGNATURE,
                    hasSplits = false,
                    label = "Example",
                ),
            )
    }

    private companion object {
        const val PKG = "com.example.app"
        const val SOURCE_URL = "https://example.com/bucket-a.git"
        const val SCANNED_SIGNATURE = "scanned-signature"
    }
}
