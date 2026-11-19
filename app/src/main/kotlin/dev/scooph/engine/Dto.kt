package dev.scooph.engine

import org.json.JSONArray
import org.json.JSONObject

data class SyncResultDto(
    val commit: String,
    val files: List<String>,
    val empty: Boolean,
)

data class LoadResultDto(
    val entries: List<EntryDto>,
    val drops: List<DropDto>,
)

data class EntryDto(
    val schema: Int,
    val packageName: String,
    val name: String,
    val versionCode: Long,
    val versionName: String,
    val apk: ApkDto,
    val source: String,
    val minSdk: Int,
    val notes: String,
)

data class ApkDto(
    val url: String,
    val size: Long,
    val sha256: String,
)

data class DropDto(
    val file: String,
    val reason: String,
)

data class PlanRequestDto(
    val candidates: List<PlanCandidateDto>,
    val installed: List<InstalledSnapshotDto>,
)

data class PlanCandidateDto(
    val entry: EntryDto,
    val sourceId: String,
    val sourceOrder: Int,
)

data class InstalledSnapshotDto(
    val packageName: String,
    val versionCode: Long,
)

data class FetchResultDto(
    val path: String,
    val size: Long,
    val sha256: String,
)

data class PlanResultDto(
    val items: List<PlanItemDto>,
)

data class PlanItemDto(
    val packageName: String,
    val name: String,
    val relation: String,
    val entryVersionCode: Long,
    val installedVersionCode: Long,
    val apkUrl: String,
    val sha256: String,
    val size: Long,
    val sourceId: String,
)

internal object EngineJson {

    fun parseSync(raw: String): SyncResultDto {
        val o = JSONObject(raw)
        val files = o.optJSONArray("files").toStringList()
        return SyncResultDto(
            commit = o.optString("commit"),
            files = files,
            empty = o.optBoolean("empty", files.isEmpty()),
        )
    }

    fun parseLoad(raw: String): LoadResultDto {
        val o = JSONObject(raw)
        val entries = o.optJSONArray("entries").toEntryList()
        val drops = o.optJSONArray("drops").toDropList()
        return LoadResultDto(entries = entries, drops = drops)
    }

    fun parseFetch(raw: String): FetchResultDto {
        val o = JSONObject(raw)
        return FetchResultDto(
            path = o.optString("path"),
            size = o.optLong("size"),
            sha256 = o.optString("sha256"),
        )
    }

    fun parsePlan(raw: String): PlanResultDto {
        val o = JSONObject(raw)
        val arr = o.optJSONArray("items") ?: JSONArray()
        val items = buildList(arr.length()) {
            for (i in 0 until arr.length()) {
                val it = arr.getJSONObject(i)
                add(
                    PlanItemDto(
                        packageName = it.optString("package"),
                        name = it.optString("name"),
                        relation = it.optString("relation"),
                        entryVersionCode = it.optLong("entryVersionCode"),
                        installedVersionCode = it.optLong("installedVersionCode"),
                        apkUrl = it.optString("apkUrl"),
                        sha256 = it.optString("sha256"),
                        size = it.optLong("size"),
                        sourceId = it.optString("sourceId"),
                    ),
                )
            }
        }
        return PlanResultDto(items)
    }

    fun encodePlanRequest(req: PlanRequestDto): String {
        val o = JSONObject()
        val cands = JSONArray()
        req.candidates.forEach { c ->
            cands.put(
                JSONObject()
                    .put("entry", c.entry.toJson())
                    .put("sourceId", c.sourceId)
                    .put("sourceOrder", c.sourceOrder),
            )
        }
        val installed = JSONArray()
        req.installed.forEach { i ->
            installed.put(
                JSONObject()
                    .put("package", i.packageName)
                    .put("versionCode", i.versionCode),
            )
        }
        o.put("candidates", cands)
        o.put("installed", installed)
        return o.toString()
    }

    private fun EntryDto.toJson(): JSONObject =
        JSONObject()
            .put("schema", schema)
            .put("package", packageName)
            .put("name", name)
            .put("versionCode", versionCode)
            .put("versionName", versionName)
            .put("apk", JSONObject().put("url", apk.url).put("size", apk.size).put("sha256", apk.sha256))
            .put("source", source)
            .put("minSdk", minSdk)
            .put("notes", notes)

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (i in 0 until length()) add(optString(i))
        }
    }

    private fun JSONArray?.toDropList(): List<DropDto> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (i in 0 until length()) {
                val o = getJSONObject(i)
                add(DropDto(file = o.optString("file"), reason = o.optString("reason")))
            }
        }
    }

    private fun JSONArray?.toEntryList(): List<EntryDto> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (i in 0 until length()) {
                val o = getJSONObject(i)
                val apk = o.optJSONObject("apk") ?: JSONObject()
                add(
                    EntryDto(
                        schema = o.optInt("schema", 0),
                        packageName = o.optString("package"),
                        name = o.optString("name"),
                        versionCode = o.optLong("versionCode"),
                        versionName = o.optString("versionName"),
                        apk = ApkDto(
                            url = apk.optString("url"),
                            size = apk.optLong("size"),
                            sha256 = apk.optString("sha256"),
                        ),
                        source = o.optString("source"),
                        minSdk = o.optInt("minSdk", 0),
                        notes = o.optString("notes"),
                    ),
                )
            }
        }
    }
}
