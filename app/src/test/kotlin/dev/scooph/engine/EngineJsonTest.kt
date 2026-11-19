package dev.scooph.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EngineJsonTest {

    @Test
    fun `parse plan json decodes expected fields`() {
        val raw = """
            {
              "items": [
                {
                  "package": "com.a",
                  "name": "App A",
                  "relation": "newer",
                  "entryVersionCode": 7000,
                  "installedVersionCode": 6804,
                  "apkUrl": "https://x/a.apk",
                  "sha256": "abc",
                  "size": 1,
                  "sourceId": "bucket-a"
                }
              ]
            }
        """.trimIndent()

        val plan = EngineJson.parsePlan(raw)
        assertEquals(1, plan.items.size)
        val item = plan.items.first()
        assertEquals("com.a", item.packageName)
        assertEquals("newer", item.relation)
        assertEquals(7000L, item.entryVersionCode)
        assertEquals(6804L, item.installedVersionCode)
        assertEquals("bucket-a", item.sourceId)
    }

    @Test
    fun `empty items remains empty list not null`() {
        val plan = EngineJson.parsePlan("""{"items": []}""")
        assertNotNull(plan.items)
        assertTrue(plan.items.isEmpty())
    }

    @Test
    fun `plan request json roundtrip keeps package key and candidates`() {
        val req =
            PlanRequestDto(
                candidates =
                    listOf(
                        PlanCandidateDto(
                            entry =
                                EntryDto(
                                    schema = 1,
                                    packageName = "com.a",
                                    name = "App A",
                                    versionCode = 7000,
                                    versionName = "7.0",
                                    apk = ApkDto(url = "https://x/a.apk", size = 1, sha256 = "abc"),
                                    source = "https://example.org/src",
                                    minSdk = 26,
                                    notes = "",
                                ),
                            sourceId = "bucket-a",
                            sourceOrder = 0,
                        ),
                    ),
                installed = listOf(InstalledSnapshotDto(packageName = "com.a", versionCode = 6804)),
            )

        val encoded = EngineJson.encodePlanRequest(req)
        assertTrue(encoded.contains("\"candidates\""))
        assertTrue(encoded.contains("\"installed\""))
        assertTrue(encoded.contains("\"package\":\"com.a\""))
        assertTrue(encoded.contains("\"sourceId\":\"bucket-a\""))
    }
}
