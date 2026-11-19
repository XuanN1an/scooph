package dev.scooph.install

import dev.scooph.data.UpdateOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 队列里的一项：只记包名、状态与下载百分比，不持有已下载文件（进程死亡即消失）。
 *
 * [progressPercent] 只在 [QueueState.Downloading] 且服务端给了总大小时有值。
 */
data class QueueItem(
    val packageName: String,
    val state: QueueState,
    val progressPercent: Int? = null,
)

/** 一键更新时单行的状态（spec §10.4）。 */
sealed interface QueueState {
    data object Waiting : QueueState

    data object Downloading : QueueState

    /** 签名变更：队列在这里停住，等人决定。 */
    data object AwaitingConfirm : QueueState

    data object Done : QueueState

    data class Skipped(val reason: String) : QueueState

    data class Failed(val reason: String) : QueueState
}

/**
 * 串行更新队列（spec §10.4 / 计划 Task 19）。
 *
 * 一个协程一个 `for`：一次只跑一个 [update]，普通失败与用户取消都继续下一项，
 * 只有「签名已变更」停下并把这一项标成 [QueueState.AwaitingConfirm]。
 * 队列本身不落库，进程被杀后自然消失。
 */
class InstallQueue(
    private val scope: CoroutineScope,
    private val update: suspend (packageName: String, onProgress: (Long, Long) -> Unit) -> UpdateOutcome,
) {

    private val _items = MutableStateFlow<List<QueueItem>>(emptyList())
    val items: StateFlow<List<QueueItem>> = _items.asStateFlow()

    private var job: Job? = null

    /** 开始一轮队列；已经在跑时忽略重复调用。 */
    fun start(packages: List<String>) {
        if (job?.isActive == true) return
        if (packages.isEmpty()) return
        _items.value = packages.map { QueueItem(it, QueueState.Waiting) }
        job =
            scope.launch {
                for (packageName in packages) {
                    if (!isActive) return@launch
                    setState(packageName, QueueState.Downloading)
                    val outcome =
                        try {
                            update(packageName) { done, total ->
                                if (total > 0) {
                                    setProgress(packageName, ((done * 100) / total).toInt())
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            UpdateOutcome.Failed(e.message ?: "更新失败")
                        }
                    when (outcome) {
                        UpdateOutcome.Success -> setState(packageName, QueueState.Done)
                        UpdateOutcome.Cancelled -> setState(packageName, QueueState.Skipped("已取消"))
                        is UpdateOutcome.Failed ->
                            if (outcome.reason == SIGNATURE_CHANGED) {
                                // 队列暂停：剩下的人还没开始，保持 Waiting。
                                setState(packageName, QueueState.AwaitingConfirm)
                                return@launch
                            } else {
                                setState(packageName, QueueState.Failed(outcome.reason))
                            }
                    }
                }
            }
    }

    /** 用户主动中止：正在下载与还没开始的都记成跳过，已完成的保留。 */
    fun cancel() {
        job?.cancel()
        _items.value =
            _items.value.map { item ->
                when (item.state) {
                    QueueState.Waiting, QueueState.Downloading ->
                        item.copy(state = QueueState.Skipped("已取消"))

                    else -> item
                }
            }
    }

    private fun setState(packageName: String, state: QueueState) {
        _items.value =
            _items.value.map { item ->
                if (item.packageName != packageName) {
                    item
                } else {
                    // 进入终态后不再保留百分比。
                    item.copy(state = state, progressPercent = null)
                }
            }
    }

    private fun setProgress(packageName: String, percent: Int) {
        _items.value =
            _items.value.map { item ->
                if (item.packageName == packageName) item.copy(progressPercent = percent) else item
            }
    }

    private companion object {
        const val SIGNATURE_CHANGED = "签名已变更"
    }
}
