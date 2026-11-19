package dev.scooph.ui.detail

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.scooph.ScoophApp
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
)

/** 详情页读取与策略写入接口，便于测试。 */
interface DetailRepository {
    suspend fun load(packageName: String): DetailUiState?

    /**
     * 写策略。调用方传当前条目版本作为 [skipVersionCode]；
     * 非 [Policy.SKIP_VERSION] 时由实现统一清成 0。
     */
    suspend fun setPolicy(packageName: String, policy: Policy, skipVersionCode: Long)
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

    companion object {
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
