package dev.scooph.ui.main

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.scooph.ScoophApp
import dev.scooph.data.MainRepository
import dev.scooph.domain.DerivedState
import dev.scooph.install.InstallService
import dev.scooph.install.QueueItem
import dev.scooph.install.QueueState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 主屏一行的数据。
 *
 * [state] 是 [dev.scooph.domain.derive] 算出的七值状态；主屏不自己判断版本，
 * 只把状态投影进三组。[error] 是失败原因覆盖层，不改变分组（spec §9.2）。
 */
data class MainRow(
    val packageName: String,
    val name: String,
    val state: DerivedState,
    val installedVersionName: String,
    val entryVersionName: String,
    val skipVersionCode: Long,
    val error: String?,
)

/**
 * 主屏三组的快照。
 *
 * 顺序固定：可更新 → 已是最新 → 已忽略；空组在界面上不显示标题。
 */
data class MainUiState(
    val updatable: List<MainRow> = emptyList(),
    val upToDate: List<MainRow> = emptyList(),
    val ignored: List<MainRow> = emptyList(),
    val syncing: Boolean = false,
    /** 一键更新的队列快照；空表示这一轮已经结束。 */
    val queue: List<QueueItem> = emptyList(),
    val active: Boolean = false,
)

/**
 * 主屏状态。
 *
 * 本身不推导状态机：Repository 交来已按 [dev.scooph.domain.derive] 推导好的行，
 * 这里只做分组投影。刷新在 [workScope]（默认 `viewModelScope`）上跑，
 * 测试可注入同步作用域。
 */
class MainViewModel(
    private val repository: MainRepository,
    scope: CoroutineScope? = null,
    private val startQueue: (List<String>) -> Unit = {},
    queue: StateFlow<List<QueueItem>>? = null,
) : ViewModel() {

    private val workScope: CoroutineScope = scope ?: viewModelScope

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private var refreshing = false

    init {
        queue?.let { flow ->
            workScope.launch {
                flow.collect { items ->
                    _state.value =
                        _state.value.copy(
                            queue = items,
                            active = items.any { !it.state.isProcessed() },
                        )
                }
            }
        }
    }

    /** 重新同步所有来源并重算一屏。已在刷新中时忽略重复调用。 */
    fun refresh() {
        if (refreshing) return
        refreshing = true
        _state.update { it.copy(syncing = true) }
        workScope.launch {
            try {
                // 刷新不打断正在显示的队列状态。
                val current = _state.value
                _state.value =
                    group(repository.refresh()).copy(queue = current.queue, active = current.active)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 失败时保留上一屏数据，只结束刷新态；具体原因由行上的 error 承担。
                _state.update { it.copy(syncing = false) }
            } finally {
                refreshing = false
            }
        }
    }

    /** 右上「全部更新」：把当前可更新的包名交给前台服务（spec §10.4）。 */
    fun updateAll() {
        val packages = _state.value.updatable.map { it.packageName }
        if (packages.isNotEmpty()) startQueue(packages)
    }

    companion object {
        /** 从 Application 取进程级 Repository 的工厂，供 Activity 注入。 */
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(MainViewModel::class.java)) {
                        "未知的 ViewModel：${modelClass.name}"
                    }
                    @Suppress("UNCHECKED_CAST")
                    val app = application as ScoophApp
                    return MainViewModel(
                        repository = app.repository,
                        startQueue = { packages ->
                            application.startService(InstallService.intent(application, packages))
                        },
                        queue = app.installQueue.items,
                    ) as T
                }
            }
    }
}

/** 三组各自的状态集合；其余状态（NO_SOURCE / NOT_INSTALLED）不显示。 */
private val UP_TO_DATE_STATES = setOf(DerivedState.UP_TO_DATE, DerivedState.AHEAD)
private val IGNORED_STATES = setOf(DerivedState.IGNORED, DerivedState.SKIPPED)

/**
 * 七值状态到三组的投影（spec §9.2 / §11.1）。
 *
 * 保留 Repository 给出的行序，组内不重排；[MainUiState.syncing] 恒为 false。
 */
internal fun group(rows: List<MainRow>): MainUiState =
    MainUiState(
        updatable = rows.filter { it.state == DerivedState.UPDATABLE },
        upToDate = rows.filter { it.state in UP_TO_DATE_STATES },
        ignored = rows.filter { it.state in IGNORED_STATES },
        syncing = false,
    )

/** 队列进行中时右上角显示「已处理 / 总数」，否则是动作名（spec §10.4）。 */
internal fun updateLabel(state: MainUiState): String =
    if (state.active) {
        "${state.queue.count { it.state.isProcessed() }} / ${state.queue.size}"
    } else {
        "全部更新"
    }

/** 行的副行：队列里有这一项时显示队列状态，否则回到普通版本行。 */
internal fun subtitleFor(row: MainRow, queueItem: QueueItem?): String =
    if (queueItem == null) rowSubtitle(row) else queueSubtitle(queueItem)

/** 队列状态到副行文案的唯一投影处（spec §10.4）。 */
internal fun queueSubtitle(item: QueueItem): String =
    when (val state = item.state) {
        QueueState.Waiting -> "等待更新"
        QueueState.Downloading ->
            item.progressPercent?.let { "正在下载 $it%" } ?: "正在下载"
        QueueState.AwaitingConfirm -> "等你确认安装"
        QueueState.Done -> "已装好"
        is QueueState.Skipped -> "跳过：${state.reason}"
        is QueueState.Failed -> "失败：${state.reason}"
    }

/** 已处理：记进度时分母用；签名变更也算这一项走完了。 */
internal fun QueueState.isProcessed(): Boolean =
    when (this) {
        QueueState.Waiting, QueueState.Downloading -> false
        QueueState.Done, QueueState.AwaitingConfirm, is QueueState.Skipped, is QueueState.Failed -> true
    }

/** 副行只显示版本变化或状态说明，不需要额外提示文案。 */
internal fun rowSubtitle(row: MainRow): String =
    when {
        row.error != null -> row.error
        row.state == DerivedState.UPDATABLE ->
            if (row.installedVersionName.isBlank()) {
                row.entryVersionName
            } else {
                "${row.installedVersionName} → ${row.entryVersionName}"
            }
        row.state == DerivedState.UP_TO_DATE -> row.entryVersionName
        row.state == DerivedState.AHEAD -> "本机版本更新"
        row.state == DerivedState.SKIPPED -> "已跳过 ${row.entryVersionName}"
        else -> "已忽略"
    }
