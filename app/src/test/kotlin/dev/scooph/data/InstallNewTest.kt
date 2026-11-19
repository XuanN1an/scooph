package dev.scooph.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.scooph.data.db.AppDatabase
import dev.scooph.data.db.EntryEntity
import dev.scooph.data.db.SourceEntity
import dev.scooph.data.db.TestDatabase
import dev.scooph.data.db.TestDatabaseHandle
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

/**
 * 从条目页安装新应用（计划 Task 24）。
 *
 * 与单包更新共用下载/校验/签名管道；唯一差别是首次安装没有锚点，
 * 装成功才建 tracked（FOLLOW）与首次签名锚。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class InstallNewTest {

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
    fun `installing a new app creates tracked with follow and the first anchor`() = runBlocking {
        seedEntry()
        val repo = repository(installer = FakeInstaller(InstallOutcome.Success))

        val outcome = repo.installNew(PKG)

        assertEquals(UpdateOutcome.Success, outcome)
        val tracked = db.trackedDao().get(PKG)
        assertNotNull(tracked)
        assertEquals("FOLLOW", tracked!!.policy)
        assertEquals(7000L, tracked.installedVersionCode)
        assertEquals("new-anchor", tracked.signature)
    }

    @Test
    fun `a failed install does not create tracked`() = runBlocking {
        seedEntry()
        val repo = repository(installer = FakeInstaller(InstallOutcome.Failed("INSTALL_FAILED_INVALID_APK")))

        val outcome = repo.installNew(PKG)

        assertEquals(UpdateOutcome.Failed("INSTALL_FAILED_INVALID_APK"), outcome)
        assertNull(db.trackedDao().get(PKG))
    }

    private fun repository(installer: Installer) =
        Repository(
            sourceDao = db.sourceDao(),
            entryDao = db.entryDao(),
            trackedDao = db.trackedDao(),
            engine = FakeEngine(),
            scanner = FakeScanner(),
            installer = installer,
            downloadsDir = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "downloads-new"),
            apkFingerprint = { "new-anchor" },
        )

    private suspend fun seedEntry() {
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
                    name = "New App",
                    versionCode = 7000,
                    versionName = "7.0",
                    apkUrl = "https://example.com/app.apk",
                    sha256 = SHA,
                    size = 1,
                    notes = null,
                ),
            ),
        )
    }

    private class FakeEngine : EngineGateway {
        override fun version(): String = "test"

        override fun sync(url: String): SyncResultDto =
            SyncResultDto(commit = "", files = emptyList(), empty = true)

        override fun load(url: String): LoadResultDto = LoadResultDto(entries = emptyList(), drops = emptyList())

        override fun plan(request: PlanRequestDto): PlanResultDto = PlanResultDto(items = emptyList())

        override fun fetch(url: String, destPath: String, wantSHA256: String): FetchResultDto {
            val file = File(destPath)
            file.parentFile?.mkdirs()
            file.writeBytes(byteArrayOf(1, 2, 3))
            return FetchResultDto(path = file.absolutePath, size = 3, sha256 = SHA)
        }
    }

    private class FakeInstaller(private val outcome: InstallOutcome) : Installer {
        override suspend fun install(apk: File, packageName: String): InstallOutcome = outcome
    }

    private class FakeScanner : PackageScanner {
        override suspend fun scan(): List<InstalledApp> = emptyList()
    }

    private companion object {
        const val PKG = "com.example.new"
        const val SOURCE_URL = "https://example.com/bucket-a.git"
        const val SHA = "entry-sha"
    }
}
