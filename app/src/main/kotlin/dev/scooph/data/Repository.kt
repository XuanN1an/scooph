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
import dev.scooph.engine.EngineGateway
import dev.scooph.engine.EntryDto
import dev.scooph.engine.InstalledSnapshotDto
import dev.scooph.engine.PlanCandidateDto
import dev.scooph.engine.PlanItemDto
import dev.scooph.engine.PlanRequestDto
import dev.scooph.scan.InstalledApp
import dev.scooph.scan.PackageScanner
import dev.scooph.ui.detail.DetailRepository
import dev.scooph.ui.detail.DetailUiState
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
    private val engine: EngineGateway,
    private val scanner: PackageScanner,
) : MainRepository, DetailRepository {

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

    override suspend fun load(packageName: String): DetailUiState? = withContext(Dispatchers.IO) {
        val tracked = trackedDao.get(packageName) ?: return@withContext null
        val sources = sourceDao.observeAll().first()
        val entries = entryDao.observeAll().first().filter { it.packageName == packageName }

        val policy = tracked.policy.toPolicy() ?: Policy.FOLLOW
        val resolved =
            if (entries.isEmpty()) {
                null
            } else {
                engine
                    .plan(
                        PlanRequestDto(
                            candidates = entries.map { it.toCandidate(sources) },
                            installed =
                                listOf(
                                    InstalledSnapshotDto(
                                        packageName = packageName,
                                        versionCode = tracked.installedVersionCode,
                                    ),
                                ),
                        ),
                    ).items
                    .firstOrNull()
            }
        val relation = resolved?.relation?.toRelation()
        val entryVersionCode = resolved?.entryVersionCode ?: 0L

        val state =
            derive(
                policy = policy,
                relation = relation,
                skipVersionCode = tracked.skipVersionCode,
                entryVersionCode = entryVersionCode,
            )

        val effectiveEntry =
            resolved?.sourceId?.let { sourceId -> entries.firstOrNull { it.sourceId == sourceId } }
                ?: entries.maxByOrNull { it.versionCode }

        val name = resolved?.name?.takeIf { it.isNotBlank() } ?: effectiveEntry?.name ?: packageName
        val installedVersionName =
            if (tracked.installedVersionCode > 0) tracked.installedVersionCode.toString() else ""
        val entryVersionName =
            effectiveEntry?.versionName?.takeIf { it.isNotBlank() }
                ?: if (entryVersionCode > 0) entryVersionCode.toString() else ""
        val sourceId = resolved?.sourceId ?: effectiveEntry?.sourceId
        val sourceLabel =
            sourceId?.let {
                sources.firstOrNull { source -> source.url == it }?.name?.takeIf(String::isNotBlank) ?: it
            }.orEmpty()

        DetailUiState(
            packageName = packageName,
            name = name,
            state = state,
            installedVersionName = installedVersionName,
            entryVersionName = entryVersionName,
            policy = policy,
            skipVersionCode = tracked.skipVersionCode,
            sourceLabel = sourceLabel,
            signatureChanged = tracked.lastError?.contains("签名") == true,
            entryVersionCode = entryVersionCode,
        )
    }

    /**
     * 写策略（spec §9.2 / 计划 Task 14）。
     *
     * 只有「跳过这一版」保留跳过目标；其余策略一律清 0，避免旧跳过值在切回
     * `FOLLOW` 后仍然生效。追踪记录不存在时什么都不做（详情页只对已追踪应用开放）。
     */
    override suspend fun setPolicy(
        packageName: String,
        policy: Policy,
        skipVersionCode: Long,
    ) = withContext(Dispatchers.IO) {
        val current = trackedDao.get(packageName) ?: return@withContext
        trackedDao.upsert(
            current.copy(
                policy = policy.name,
                skipVersionCode = if (policy == Policy.SKIP_VERSION) skipVersionCode else 0L,
            ),
        )
    }

    /**
     * 首次启动接入官方 bucket（spec §8 / 计划 Task 12）。
     *
     * `sources` 为空时插入 [DEFAULT_BUCKET_URL] 并同步一次；已有任意来源时
     * 什么都不做，重复调用不会插入第二条。与手动添加的来源走同一条 [syncOne]，
     * 同步失败只把来源标成 ERROR，不向调用方抛出。
     */
    suspend fun ensureDefaultSource() = withContext(Dispatchers.IO) {
        if (sourceDao.observeAll().first().isNotEmpty()) return@withContext

        val now = System.currentTimeMillis()
        val source =
            SourceEntity(
                url = DEFAULT_BUCKET_URL,
                name = DEFAULT_BUCKET_NAME,
                lastSyncAt = 0L,
                lastCommit = null,
                status = STATUS_PENDING,
                addedOrder = 0,
            )
        // 先落一行再同步：进程在同步中途被杀，下次启动也能看到这个来源，
        // 不会无限重复「空表 → 插入」。
        sourceDao.upsert(source)
        syncOne(source, now)
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
                        // spec §10.3：扫描即建锚；但 UNTRACKED 是墓碑，不为它补锚点。
                        signature =
                            if (current.policy == Policy.UNTRACKED.name) {
                                current.signature
                            } else {
                                current.signature ?: app.signature
                            },
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
        const val STATUS_PENDING = "PENDING"
    }
}
