package dev.scooph.ui.detail

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.scooph.ScoophApp
import dev.scooph.data.UpdateOutcome
import dev.scooph.domain.DerivedState
import dev.scooph.domain.Policy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 详情页状态。 */
data class DetailUiState(
    val packageName: String,
    val name: String,
    val state: DerivedState,
    val installedVersionName: String,
    val entryVersionName: String,
    val policy: Policy,
    val skipVersionCode: Long,
    val sourceLabel: String,
    val signatureChanged: Boolean,
    /** 生效条目的 versionCode；「跳过这一版」用它作为跳过目标。 */
    val entryVersionCode: Long = 0L,
    val updating: Boolean = false,
    val progressDone: Long = 0L,
    val progressTotal: Long = 0L,
    val lastOutcome: UpdateOutcome? = null,
)

/** 详情页读取与策略写入接口，便于测试。 */
interface DetailRepository {
    suspend fun load(packageName: String): DetailUiState?

    /**
     * 写策略。调用方传当前条目版本作为 [skipVersionCode]；
     * 非 [Policy.SKIP_VERSION] 时由实现统一清成 0。
     */
    suspend fun setPolicy(packageName: String, policy: Policy, skipVersionCode: Long)

    /** 下载 + 校验 + 安装一个应用；进度回调给界面。 */
    suspend fun update(packageName: String, onProgress: (Long, Long) -> Unit): UpdateOutcome
}

class DetailViewModel(
    private val packageName: String,
    private val repository: DetailRepository,
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val workScope = scope ?: viewModelScope

    private val _state = MutableStateFlow(fallbackState(packageName))
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    fun load() {
        workScope.launch {
            try {
                _state.value = repository.load(packageName) ?: fallbackState(packageName)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.value = fallbackState(packageName)
            }
        }
    }

    /**
     * 选中一个策略并立即落库，成功后重新读一遍详情。
     *
     * 「跳过这一版」把当前生效条目的 versionCode 记成跳过目标；其余策略清 0，
     * 这样上游出了更新版本会自动回到可更新（spec §9.2）。
     */
    fun selectPolicy(policy: Policy) {
        workScope.launch {
            try {
                val current = _state.value
                val skip = if (policy == Policy.SKIP_VERSION) current.entryVersionCode else 0L
                repository.setPolicy(packageName, policy, skip)
                _state.value = repository.load(packageName) ?: fallbackState(packageName)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 写入失败时保留当前界面，重新读一次真实状态。
                runCatching { repository.load(packageName) }
                    .getOrNull()
                    ?.let { _state.value = it }
            }
        }
    }

    /**
     * 下载并安装当前应用（spec §10.3 / 计划 Task 18）。
     *
     * 进度写进 [DetailUiState.progressDone] / [DetailUiState.progressTotal]；
     * 结束后重新读详情，行内状态由 Repository 落库的结果决定。
     * 界面自己的模态框不存在，系统确认框由 Android 弹。
     */
    fun update() {
        if (_state.value.updating) return
        workScope.launch {
            _state.value = _state.value.copy(updating = true, progressDone = 0L, progressTotal = 0L, lastOutcome = null)
            val outcome =
                try {
                    repository.update(packageName) { done, total ->
                        _state.value = _state.value.copy(progressDone = done, progressTotal = total)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    UpdateOutcome.Failed(e.message ?: "更新失败")
                }
            _state.value = _state.value.copy(updating = false, lastOutcome = outcome)
            Log.i(TAG, "update $packageName -> $outcome")
            runCatching { repository.load(packageName) }
                .getOrNull()
                ?.let { fresh -> _state.value = fresh.copy(lastOutcome = outcome) }
        }
    }

    companion object {
        private const val TAG = "ScoophUpdate"

        fun factory(application: Application, packageName: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(DetailViewModel::class.java)) {
                        "未知的 ViewModel：${modelClass.name}"
                    }
                    val repo = (application as ScoophApp).repository
                    @Suppress("UNCHECKED_CAST")
                    return DetailViewModel(packageName, repo) as T
                }
            }

        fun fallbackState(packageName: String) =
            DetailUiState(
                packageName = packageName,
                name = packageName,
                state = DerivedState.NO_SOURCE,
                installedVersionName = "",
                entryVersionName = "",
                policy = Policy.FOLLOW,
                skipVersionCode = 0L,
                sourceLabel = "",
                signatureChanged = false,
            )
    }
}
