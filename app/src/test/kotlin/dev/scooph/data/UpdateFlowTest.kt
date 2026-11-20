package dev.scooph.data

import dev.scooph.data.db.EntryEntity
import dev.scooph.data.db.SourceEntity
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 单包更新端到端（计划 Task 18）。
 *
 * 内存 DAO + 假引擎/指纹/安装器走四条路径：哈希不符、签名变更、用户取消、成功。
 * 失败与取消都不动 `tracked`；成功才写回版本、签名并恢复 `FOLLOW`。
 */
class UpdateFlowTest {

    private val daos = FakeDaos()
    private val downloads = File(System.getProperty("java.io.tmpdir"), "scooph-update-flow-test-${System.nanoTime()}")

    @Test
    fun `hash mismatch fails without touching tracked`() = runBlocking {
        seed(policy = Policy.FOLLOW)
        val installer = FakeInstaller(InstallOutcome.Success)
        val repo = repository(engine = FakeEngine(fetchedSHA = "not-the-entry-sha"), installer = installer)

        val outcome = repo.updateOne(PKG)

        assertEquals(UpdateOutcome.Failed("sha256 不一致"), outcome)
        assertEquals(6804L, daos.tracked.get(PKG)!!.installedVersionCode)
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
        assertEquals("anchor", daos.tracked.get(PKG)!!.signature)
        assertEquals("签名已变更", daos.tracked.get(PKG)!!.lastError)
    }

    @Test
    fun `accepting the new signature installs and re-anchors`() = runBlocking {
        seed(policy = Policy.FOLLOW)
        val installer = FakeInstaller(InstallOutcome.Success)
        val repo =
            repository(
                engine = FakeEngine(fetchedSHA = ENTRY_SHA),
                installer = installer,
                fingerprint = { "new-anchor" },
            )

        val outcome = repo.acceptAndUpdate(PKG) { _, _ -> }

        assertEquals(UpdateOutcome.Success, outcome)
        assertTrue(installer.called)
        val tracked = daos.tracked.get(PKG)!!
        assertEquals("new-anchor", tracked.signature)
        assertNull(tracked.lastError)
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
        val tracked = daos.tracked.get(PKG)!!
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
        val tracked = daos.tracked.get(PKG)!!
        assertEquals(7000L, tracked.installedVersionCode)
        assertEquals("anchor", tracked.signature)
        assertEquals("FOLLOW", tracked.policy)
        assertEquals(0L, tracked.skipVersionCode)
        assertTrue(installer.called)
    }

    @Test
    fun `an OEM-aborted install that actually landed counts as success`() = runBlocking {
        seed(policy = Policy.FOLLOW)
        val repo =
            repository(
                engine = FakeEngine(fetchedSHA = ENTRY_SHA),
                installer = FakeInstaller(InstallOutcome.Cancelled),
                scanner = FakeScanner(installedVersionCode = 7000),
            )

        val outcome = repo.updateOne(PKG)

        assertEquals(UpdateOutcome.Success, outcome)
        assertEquals(7000L, daos.tracked.get(PKG)!!.installedVersionCode)
    }

    @Test
    fun `split-installed app is skipped without downloading`() = runBlocking {
        seed(policy = Policy.FOLLOW)
        val installer = FakeInstaller(InstallOutcome.Success)
        val repo =
            repository(
                engine = FakeEngine(fetchedSHA = ENTRY_SHA),
                installer = installer,
                scanner = FakeScanner(installedVersionCode = 6804, hasSplits = true),
            )

        val outcome = repo.updateOne(PKG)

        assertEquals(UpdateOutcome.Skipped("该应用为分包安装，暂不支持"), outcome)
        assertFalse(installer.called)
        assertEquals("FOLLOW", daos.tracked.get(PKG)!!.policy)
    }

    @Test
    fun `a cached verified apk is reused instead of downloading again`() = runBlocking {
        seed(policy = Policy.FOLLOW)
        val firstEngine = FakeEngine(fetchedSHA = ENTRY_SHA)
        repository(firstEngine, FakeInstaller(InstallOutcome.Success)).updateOne(PKG)
        assertEquals(1, firstEngine.fetchCalls)

        // 模拟上次被进程重启留下的完整包（成功安装后正常会删除）。
        File(downloads, "$PKG-7000.apk").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val secondEngine = FakeEngine(fetchedSHA = ENTRY_SHA)
        val outcome = repository(secondEngine, FakeInstaller(InstallOutcome.Success)).updateOne(PKG)

        assertEquals(UpdateOutcome.Success, outcome)
        assertEquals(0, secondEngine.fetchCalls)
    }

    @Test
    fun `signature mismatch keeps the apk and accept reuses it`() = runBlocking {
        seed(policy = Policy.FOLLOW)
        val firstEngine = FakeEngine(fetchedSHA = ENTRY_SHA)
        repository(firstEngine, FakeInstaller(InstallOutcome.Success), fingerprint = { "different" }).updateOne(PKG)
        assertTrue(File(downloads, "$PKG-7000.apk").isFile)

        val secondEngine = FakeEngine(fetchedSHA = ENTRY_SHA)
        val outcome =
            repository(secondEngine, FakeInstaller(InstallOutcome.Success), fingerprint = { "new-anchor" })
                .acceptAndUpdate(PKG) { _, _ -> }

        assertEquals(UpdateOutcome.Success, outcome)
        assertEquals(0, secondEngine.fetchCalls)
    }

    @Test
    fun `a successful update removes every cached file for the package`() = runBlocking {
        seed(policy = Policy.FOLLOW)
        File(downloads, "$PKG-6800.apk").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(9))
        }

        repository(FakeEngine(fetchedSHA = ENTRY_SHA), FakeInstaller(InstallOutcome.Success)).updateOne(PKG)

        assertTrue(downloads.listFiles().orEmpty().none { it.name.startsWith("$PKG-") })
    }

    @Test
    fun `signature mismatch keeps only the pending apk and drops older caches`() = runBlocking {
        seed(policy = Policy.FOLLOW)
        File(downloads, "$PKG-6800.apk").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(9))
        }

        repository(
            FakeEngine(fetchedSHA = ENTRY_SHA),
            FakeInstaller(InstallOutcome.Success),
            fingerprint = { "different" },
        ).updateOne(PKG)

        assertEquals(setOf("$PKG-7000.apk"), downloads.listFiles().orEmpty().map { it.name }.toSet())
    }

    @Test
    fun `accepting the signature change cleans the pending apk`() = runBlocking {
        seed(policy = Policy.FOLLOW)
        repository(
            FakeEngine(fetchedSHA = ENTRY_SHA),
            FakeInstaller(InstallOutcome.Success),
            fingerprint = { "different" },
        ).updateOne(PKG)
        assertTrue(File(downloads, "$PKG-7000.apk").isFile)

        repository(
            FakeEngine(fetchedSHA = ENTRY_SHA),
            FakeInstaller(InstallOutcome.Success),
            fingerprint = { "new-anchor" },
        ).acceptAndUpdate(PKG) { _, _ -> }

        assertTrue(downloads.listFiles().orEmpty().none { it.name.startsWith("$PKG-") })
    }

    private fun repository(
        engine: EngineGateway,
        installer: Installer,
        fingerprint: (File) -> String = { "anchor" },
        scanner: PackageScanner = FakeScanner(),
    ) = Repository(
        sourceDao = daos.sources,
        entryDao = daos.entries,
        trackedDao = daos.tracked,
        engine = engine,
        scanner = scanner,
        installer = installer,
        downloadsDir = downloads,
        apkFingerprint = fingerprint,
    )

    private suspend fun seed(policy: Policy, skipVersionCode: Long = 0L) {
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
        daos.entries.replaceForSource(
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
                    size = 3,
                    notes = null,
                ),
            ),
        )
        daos.tracked.upsert(
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
        var fetchCalls = 0
            private set

        override fun version(): String = "test"

        override fun sync(url: String): SyncResultDto =
            SyncResultDto(commit = "c1", files = emptyList(), empty = false)

        override fun load(url: String): LoadResultDto = LoadResultDto(entries = emptyList(), drops = emptyList())

        override fun plan(request: PlanRequestDto): PlanResultDto = PlanResultDto(items = emptyList())

        override fun fetch(url: String, destPath: String, wantSHA256: String): FetchResultDto {
            fetchCalls++
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

    private class FakeScanner(
        private val installedVersionCode: Long? = null,
        private val hasSplits: Boolean = false,
    ) : PackageScanner {
        override suspend fun scan(): List<InstalledApp> =
            installedVersionCode?.let {
                listOf(
                    InstalledApp(
                        packageName = PKG,
                        versionCode = it,
                        signature = "anchor",
                        hasSplits = hasSplits,
                        label = "Example",
                    ),
                )
            }.orEmpty()
    }

    private companion object {
        const val PKG = "com.example.app"
        const val SOURCE_URL = "https://example.com/bucket-a.git"
        const val ENTRY_SHA = "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81"
    }
}
