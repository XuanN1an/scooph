package dev.scooph.ui.main

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.scooph.ScoophApp
import dev.scooph.data.MainRepository
import dev.scooph.domain.DerivedState
import dev.scooph.install.InstallService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) : ViewModel() {

    private val workScope: CoroutineScope = scope ?: viewModelScope

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private var refreshing = false

    /** 重新同步所有来源并重算一屏。已在刷新中时忽略重复调用。 */
    fun refresh() {
        if (refreshing) return
        refreshing = true
        _state.update { it.copy(syncing = true) }
        workScope.launch {
            try {
                _state.value = group(repository.refresh())
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
                    return MainViewModel(
                        repository = (application as ScoophApp).repository,
                        startQueue = { packages ->
                            application.startService(InstallService.intent(application, packages))
                        },
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
