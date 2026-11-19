package dev.scooph.data

import dev.scooph.data.db.EntryDao
import dev.scooph.data.db.EntryEntity
import dev.scooph.data.db.SourceDao
import dev.scooph.data.db.SourceEntity
import dev.scooph.data.db.TrackedDao
import dev.scooph.data.db.TrackedEntity
import dev.scooph.domain.Policy
import dev.scooph.domain.Relation
import dev.scooph.domain.derive
import dev.scooph.engine.ApkDto
import dev.scooph.engine.EngineFacade
import dev.scooph.engine.EntryDto
import dev.scooph.engine.InstalledSnapshotDto
import dev.scooph.engine.PlanCandidateDto
import dev.scooph.engine.PlanItemDto
import dev.scooph.engine.PlanRequestDto
import dev.scooph.scan.InstalledApp
import dev.scooph.scan.PackageScanner
import dev.scooph.ui.main.MainRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 主屏一次刷新所需的数据来源。
 *
 * ViewModel 只依赖这个接口，单测里用假实现直接喂行；真正的编排在 [Repository]。
 */
interface MainRepository {
    /** 同步所有来源、扫描本机、推导状态，返回一屏未分组的行。 */
    suspend fun refresh(): List<MainRow>
}

/**
 * DB + 引擎 + 扫描的编排层（spec §10.1 / §10.2）。
 *
 * 一次 [refresh] 做四件事：逐来源同步并把条目写库 → 扫描已装应用 →
 * 调 Go 的 `Plan()` 拿版本关系 → 用 [derive] 把关系翻译成七值状态。
 * 只投影成 [MainRow]，分组留给 ViewModel。
 */
class Repository(
    private val sourceDao: SourceDao,
    private val entryDao: EntryDao,
    private val trackedDao: TrackedDao,
    private val engine: EngineFacade,
    private val scanner: PackageScanner,
) : MainRepository {

    override suspend fun refresh(): List<MainRow> = withContext(Dispatchers.IO) {
        val sources = sourceDao.observeAll().first()
        val now = System.currentTimeMillis()
        sources.forEach { syncOne(it, now) }

        val entries = entryDao.observeAll().first()
        if (entries.isEmpty()) return@withContext emptyList()

        val installed = scanner.scan()
        if (installed.isEmpty()) return@withContext emptyList()
        val installedByPackage = installed.associateBy { it.packageName }

        val plan =
            engine.plan(
                PlanRequestDto(
                    candidates = entries.map { it.toCandidate(sources) },
                    installed =
                        installed.map {
                            InstalledSnapshotDto(packageName = it.packageName, versionCode = it.versionCode)
                        },
                ),
            )
        if (plan.items.isEmpty()) return@withContext emptyList()

        // §10.5：与本机已装应用匹配的条目自动建成 tracked（FOLLOW）。
        // 已有记录（包括 UNTRACKED 墓碑）不会被改回 FOLLOW。
        adoptTracked(plan.items, installedByPackage, now)

        val trackedByPackage = trackedDao.observeAll().first().associateBy { it.packageName }
        val entryByKey = entries.associateBy { it.packageName to it.sourceId }

        plan.items.mapNotNull { item ->
            val tracked = trackedByPackage[item.packageName] ?: return@mapNotNull null
            val relation = item.relation.toRelation() ?: return@mapNotNull null
            val entry = entryByKey[item.packageName to item.sourceId]
            val state =
                derive(
                    policy = tracked.policy.toPolicy(),
                    relation = relation,
                    skipVersionCode = tracked.skipVersionCode,
                    entryVersionCode = item.entryVersionCode,
                )
            MainRow(
                packageName = item.packageName,
                name = item.name.ifBlank { entry?.name ?: item.packageName },
                state = state,
                // 扫描快照只有 versionCode；显示时先拿它兜底，versionName 留给后续任务。
                installedVersionName = item.installedVersionCode.toString(),
                entryVersionName = entry?.versionName?.takeIf { it.isNotBlank() } ?: item.entryVersionCode.toString(),
                skipVersionCode = tracked.skipVersionCode,
                error = tracked.lastError,
            )
        }
    }

    /** 单个来源同步失败时保留上一次成功的条目，只更新来源状态（spec §10.1）。 */
    private suspend fun syncOne(source: SourceEntity, now: Long) {
        try {
            val sync = engine.sync(source.url)
            val loaded = engine.load(source.url)
            entryDao.replaceForSource(
                sourceId = source.url,
                entries = loaded.entries.map { it.toEntity(source.url) },
            )
            sourceDao.upsert(
                source.copy(
                    lastSyncAt = now,
                    lastCommit = sync.commit.ifBlank { source.lastCommit },
                    status = STATUS_OK,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            sourceDao.upsert(source.copy(status = STATUS_ERROR))
        }
    }

    /** 只为 `Plan()` 返回的已装应用建立/更新 tracked；策略与跳过版本原样保留。 */
    private suspend fun adoptTracked(
        items: List<PlanItemDto>,
        installed: Map<String, InstalledApp>,
        now: Long,
    ) {
        val existing = trackedDao.observeAll().first().associateBy { it.packageName }
        for (item in items) {
            val app = installed[item.packageName] ?: continue
            val current = existing[item.packageName]
            val next =
                if (current == null) {
                    TrackedEntity(
                        packageName = item.packageName,
                        policy = Policy.FOLLOW.name,
                        skipVersionCode = 0L,
                        installedVersionCode = item.installedVersionCode,
                        signature = app.signature,
                        installedAt = now,
                        lastCheckedAt = now,
                        lastError = null,
                    )
                } else {
                    current.copy(
                        installedVersionCode = item.installedVersionCode,
                        signature = current.signature ?: app.signature,
                        lastCheckedAt = now,
                    )
                }
            trackedDao.upsert(next)
        }
    }

    private fun EntryDto.toEntity(sourceId: String) =
        EntryEntity(
            packageName = packageName,
            sourceId = sourceId,
            name = name.ifBlank { packageName },
            versionCode = versionCode,
            versionName = versionName,
            apkUrl = apk.url,
            sha256 = apk.sha256,
            size = apk.size,
            notes = notes.takeIf { it.isNotBlank() },
        )

    private fun EntryEntity.toCandidate(sources: List<SourceEntity>) =
        PlanCandidateDto(
            entry =
                EntryDto(
                    schema = 1,
                    packageName = packageName,
                    name = name,
                    versionCode = versionCode,
                    versionName = versionName,
                    apk = ApkDto(url = apkUrl, size = size, sha256 = sha256),
                    source = sourceId,
                    minSdk = 0,
                    notes = notes.orEmpty(),
                ),
            sourceId = sourceId,
            sourceOrder = sources.firstOrNull { it.url == sourceId }?.addedOrder ?: Int.MAX_VALUE,
        )

    private fun String.toPolicy(): Policy? = Policy.entries.firstOrNull { it.name == this }

    private fun String.toRelation(): Relation? =
        when (this) {
            "newer" -> Relation.NEWER
            "same" -> Relation.SAME
            "older" -> Relation.OLDER
            else -> null
        }

    private companion object {
        const val STATUS_OK = "OK"
        const val STATUS_ERROR = "ERROR"
    }
}
