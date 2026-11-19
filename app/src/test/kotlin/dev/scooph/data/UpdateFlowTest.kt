package dev.scooph.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.scooph.data.db.AppDatabase
import dev.scooph.data.db.EntryEntity
import dev.scooph.data.db.SourceEntity
import dev.scooph.data.db.TestDatabase
import dev.scooph.data.db.TestDatabaseHandle
import dev.scooph.data.db.TrackedEntity
import dev.scooph.domain.Policy
import dev.scooph.engine.EngineGateway
import dev.scooph.engine.FetchResultDto
import dev.scooph.engine.LoadResultDto
import dev.scooph.engine.PlanRequestDto
import dev.scooph.engine.PlanResultDto
import dev.scooph.engine.SyncResultDto
import dev.scooph.install.InstallOutcome
import dev.scooph.install.Installer
import dev.scooph.scan.InstalledApp
import dev.scooph.scan.PackageScanner
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

/**
 * 单包更新端到端（计划 Task 18）。
 *
 * 用假引擎、假指纹和假安装器走四条路径：哈希不符、签名变更、用户取消、成功。
 * 失败与取消都不动 `tracked`；成功才写回版本、签名并恢复 `FOLLOW`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class UpdateFlowTest {

    private lateinit var handle: TestDatabaseHandle

    private val db: AppDatabase
        get() = handle.db

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        handle = TestDatabase.create(context)
    }

    @After
    fun tearDown() = handle.release()

    @Test
    fun `hash mismatch fails without touching tracked`() = runBlocking {
        seed(policy = Policy.FOLLOW)
        val installer = FakeInstaller(InstallOutcome.Success)
        val repo = repository(engine = FakeEngine(fetchedSHA = "not-the-entry-sha"), installer = installer)

        val outcome = repo.updateOne(PKG)

        assertEquals(UpdateOutcome.Failed("sha256 不一致"), outcome)
        assertEquals(6804L, db.trackedDao().get(PKG)!!.installedVersionCode)
        assertFalse(installer.called)
    }

    @Test
    fun `changed signature fails before installing`() = runBlocking {
        seed(policy = Policy.FOLLOW)
        val installer = FakeInstaller(InstallOutcome.Success)
        val repo =
            repository(
                engine = FakeEngine(fetchedSHA = ENTRY_SHA),
                installer = installer,
                fingerprint = { "different-signature" },
            )

        val outcome = repo.updateOne(PKG)

        assertEquals(UpdateOutcome.Failed("签名已变更"), outcome)
        assertFalse(installer.called)
        assertEquals("anchor", db.trackedDao().get(PKG)!!.signature)
    }

    @Test
    fun `user cancel keeps tracked untouched`() = runBlocking {
        seed(policy = Policy.FOLLOW)
        val repo =
            repository(
                engine = FakeEngine(fetchedSHA = ENTRY_SHA),
                installer = FakeInstaller(InstallOutcome.Cancelled),
            )

        val outcome = repo.updateOne(PKG)

        assertEquals(UpdateOutcome.Cancelled, outcome)
        val tracked = db.trackedDao().get(PKG)!!
        assertEquals(6804L, tracked.installedVersionCode)
        assertEquals("FOLLOW", tracked.policy)
    }

    @Test
    fun `success writes version and anchor and restores follow`() = runBlocking {
        seed(policy = Policy.SKIP_VERSION, skipVersionCode = 6804)
        val installer = FakeInstaller(InstallOutcome.Success)
        val repo = repository(engine = FakeEngine(fetchedSHA = ENTRY_SHA), installer = installer)

        val outcome = repo.updateOne(PKG)

        assertEquals(UpdateOutcome.Success, outcome)
        val tracked = db.trackedDao().get(PKG)!!
        assertEquals(7000L, tracked.installedVersionCode)
        assertEquals("anchor", tracked.signature)
        assertEquals("FOLLOW", tracked.policy)
        assertEquals(0L, tracked.skipVersionCode)
        assertTrue(installer.called)
    }

    private fun repository(
        engine: EngineGateway,
        installer: Installer,
        fingerprint: (File) -> String = { "anchor" },
    ) = Repository(
        sourceDao = db.sourceDao(),
        entryDao = db.entryDao(),
        trackedDao = db.trackedDao(),
        engine = engine,
        scanner = FakeScanner(),
        installer = installer,
        downloadsDir = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "downloads-test"),
        apkFingerprint = fingerprint,
    )

    private suspend fun seed(policy: Policy, skipVersionCode: Long = 0L) {
        db.sourceDao().upsert(
            SourceEntity(
                url = SOURCE_URL,
                name = "bucket-a",
                lastSyncAt = 0L,
                lastCommit = null,
                status = "OK",
                addedOrder = 0,
            ),
        )
        db.entryDao().replaceForSource(
            SOURCE_URL,
            listOf(
                EntryEntity(
                    packageName = PKG,
                    sourceId = SOURCE_URL,
                    name = "Example",
                    versionCode = 7000,
                    versionName = "7.0",
                    apkUrl = "https://example.com/app.apk",
                    sha256 = ENTRY_SHA,
                    size = 1,
                    notes = null,
                ),
            ),
        )
        db.trackedDao().upsert(
            TrackedEntity(
                packageName = PKG,
                policy = policy.name,
                skipVersionCode = skipVersionCode,
                installedVersionCode = 6804,
                signature = "anchor",
                installedAt = 1L,
                lastCheckedAt = 1L,
                lastError = null,
            ),
        )
    }

    private class FakeEngine(private val fetchedSHA: String) : EngineGateway {
        override fun version(): String = "test"

        override fun sync(url: String): SyncResultDto =
            SyncResultDto(commit = "c1", files = emptyList(), empty = false)

        override fun load(url: String): LoadResultDto = LoadResultDto(entries = emptyList(), drops = emptyList())

        override fun plan(request: PlanRequestDto): PlanResultDto = PlanResultDto(items = emptyList())

        override fun fetch(url: String, destPath: String, wantSHA256: String): FetchResultDto {
            val file = File(destPath)
            file.parentFile?.mkdirs()
            file.writeBytes(byteArrayOf(1, 2, 3))
            return FetchResultDto(path = file.absolutePath, size = 3, sha256 = fetchedSHA)
        }
    }

    private class FakeInstaller(private val outcome: InstallOutcome) : Installer {
        var called = false
            private set

        override suspend fun install(apk: File, packageName: String): InstallOutcome {
            called = true
            return outcome
        }
    }

    private class FakeScanner : PackageScanner {
        override suspend fun scan(): List<InstalledApp> = emptyList()
    }

    private companion object {
        const val PKG = "com.example.app"
        const val SOURCE_URL = "https://example.com/bucket-a.git"
        const val ENTRY_SHA = "entry-sha"
    }
}
