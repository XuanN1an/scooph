package dev.scooph.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一个 bucket 来源。`url` 是主键（同一仓库只允许添加一次），
 * `addedOrder` 决定主屏分组的稳定顺序。
 */
@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey val url: String,
    val name: String,
    val lastSyncAt: Long,
    val lastCommit: String?,
    val status: String,
    val addedOrder: Int,
)

/**
 * bucket 里的一个条目的快照。
 *
 * 同一 package 可以被多个来源描述，所以主键是 `(packageName, sourceId)` 而不是
 * `packageName`；哪个来源的条目生效留给状态推导层去仲裁。
 */
@Entity(tableName = "entries", primaryKeys = ["packageName", "sourceId"])
data class EntryEntity(
    val packageName: String,
    val sourceId: String,
    val name: String,
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val size: Long,
    val notes: String?,
)

/**
 * 本机对某个包的追踪状态。属于设备而不是 bucket：`entries` 可以整表重建，
 * 这份记录不随之丢失。
 *
 * `policy` 存字符串（`FOLLOW` / `SKIP_VERSION` / `UNTRACKED`）而不是枚举序号：
 * 字符串可读，改枚举声明顺序也不会毁掉已落库的数据。
 */
@Entity(tableName = "tracked")
data class TrackedEntity(
    @PrimaryKey val packageName: String,
    val policy: String,
    val skipVersionCode: Long,
    val installedVersionCode: Long,
    val signature: String?,
    val installedAt: Long,
    val lastCheckedAt: Long,
    val lastError: String?,
)
