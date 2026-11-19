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
)

/** 详情页读取接口，便于测试。 */
interface DetailRepository {
    suspend fun load(packageName: String): DetailUiState?
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
