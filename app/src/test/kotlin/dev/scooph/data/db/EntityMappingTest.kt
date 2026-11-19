package dev.scooph.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * Room 三表（sources / entries / tracked）的映射与 DAO 契约。
 *
 * Robolectric + in-memory Room：真跑 SQLite，验证复合主键与「按来源整批替换」。
 * SQLite 用 NATIVE 模式：LEGACY 模式会把 sqlite4java 解压到 Guava 的临时目录，
 * 那个目录在本机沙箱里不可写；NATIVE 走 Robolectric 的 TempDirectory，正常。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class EntityMappingTest {

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
    fun `same package from different sources coexists`() = runBlocking {
        db.entryDao().replaceForSource("bucket-a", listOf(entry("com.example.app", "bucket-a", versionCode = 1)))
        db.entryDao().replaceForSource("bucket-b", listOf(entry("com.example.app", "bucket-b", versionCode = 2)))

        val entries = db.entryDao().observeAll().first()

        assertEquals(2, entries.size)
        assertEquals(setOf("bucket-a", "bucket-b"), entries.map { it.sourceId }.toSet())
    }

    @Test
    fun `replaceForSource only replaces that source's entries`() = runBlocking {
        db.entryDao().replaceForSource(
            "bucket-a",
            listOf(entry("com.example.a", "bucket-a", versionCode = 1), entry("com.example.b", "bucket-a", versionCode = 1)),
        )
        db.entryDao().replaceForSource("bucket-b", listOf(entry("com.example.a", "bucket-b", versionCode = 9)))

        db.entryDao().replaceForSource("bucket-a", listOf(entry("com.example.c", "bucket-a", versionCode = 2)))

        val entries = db.entryDao().observeAll().first()
        assertEquals(
            listOf("bucket-b:com.example.a", "bucket-a:com.example.c"),
            entries.map { "${it.sourceId}:${it.packageName}" },
        )
        // bucket-b 的条目原样保留，没有被 bucket-a 的替换波及。
        assertEquals(9L, entries.single { it.sourceId == "bucket-b" }.versionCode)
    }

    @Test
    fun `tracked survives wiping entries`() = runBlocking {
        db.entryDao().replaceForSource("bucket-a", listOf(entry("com.example.app", "bucket-a", versionCode = 7)))
        db.trackedDao().upsert(
            TrackedEntity(
                packageName = "com.example.app",
                policy = "SKIP_VERSION",
                skipVersionCode = 7,
                installedVersionCode = 3,
                signature = "sig",
                installedAt = 100,
                lastCheckedAt = 200,
                lastError = null,
            ),
        )

        db.entryDao().deleteForSource("bucket-a")

        assertEquals(emptyList<EntryEntity>(), db.entryDao().observeAll().first())
        val tracked = db.trackedDao().get("com.example.app")
        assertNotNull(tracked)
        // policy 是字符串，不是枚举序号：改枚举顺序不毁数据。
        assertEquals("SKIP_VERSION", tracked!!.policy)
        assertEquals(7L, tracked.skipVersionCode)
    }

    @Test
    fun `sources upsert by url and observe in added order`() = runBlocking {
        db.sourceDao().upsert(source("https://example.com/a.git", name = "A", addedOrder = 2))
        db.sourceDao().upsert(source("https://example.com/b.git", name = "B", addedOrder = 1))
        // 同一 url 再写一次是更新，不是第二行。
        db.sourceDao().upsert(source("https://example.com/a.git", name = "A2", addedOrder = 2))

        val sources = db.sourceDao().observeAll().first()
        assertEquals(listOf("https://example.com/b.git", "https://example.com/a.git"), sources.map { it.url })
        assertEquals("A2", sources.last().name)

        db.sourceDao().delete("https://example.com/b.git")
        assertEquals(listOf("https://example.com/a.git"), db.sourceDao().observeAll().first().map { it.url })
    }

    private fun entry(packageName: String, sourceId: String, versionCode: Long) = EntryEntity(
        packageName = packageName,
        sourceId = sourceId,
        name = packageName,
        versionCode = versionCode,
        versionName = "1.0.$versionCode",
        apkUrl = "https://example.com/$packageName-$versionCode.apk",
        sha256 = "0".repeat(64),
        size = 1024L,
        notes = null,
    )

    private fun source(url: String, name: String, addedOrder: Int) = SourceEntity(
        url = url,
        name = name,
        lastSyncAt = 0L,
        lastCommit = null,
        status = "OK",
        addedOrder = addedOrder,
    )
}
