package dev.scooph.ui.sources

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.scooph.ScoophApp
import dev.scooph.domain.DerivedState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 条目页一行；[state] 为空表示本机没装（spec §11.4 三态）。 */
data class EntryRow(
    val packageName: String,
    val name: String,
    val versionName: String,
    val installed: Boolean,
    val state: DerivedState?,
)

data class EntriesUiState(
    val query: String = "",
    val rows: List<EntryRow> = emptyList(),
)

/** 条目页的数据接口，便于单测。 */
interface EntriesRepository {
    suspend fun entries(sourceUrl: String): List<EntryRow>
}

/**
 * 条目页（spec §11.4 / 计划 Task 23）。
 *
 * 页面回答「这个源里有什么」：搜索框常驻，名称与包名都做大小写不敏感的子串匹配；
 * 已安装的排在前面，其余按名称。
 */
class EntriesViewModel(
    private val sourceUrl: String,
    private val repository: EntriesRepository,
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val workScope = scope ?: viewModelScope

    private val _state = MutableStateFlow(EntriesUiState())
    val state: StateFlow<EntriesUiState> = _state.asStateFlow()

    private var allRows: List<EntryRow> = emptyList()

    init {
        load()
    }

    fun load() {
        workScope.launch {
            try {
                allRows = repository.entries(sourceUrl)
                _state.value = _state.value.copy(rows = filterAndSort(allRows, _state.value.query))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 读失败保留上一屏。
            }
        }
    }

    fun onQueryChange(query: String) {
        _state.value = _state.value.copy(query = query, rows = filterAndSort(allRows, query))
    }

    companion object {
        fun factory(application: Application, sourceUrl: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(EntriesViewModel::class.java)) {
                        "未知的 ViewModel：${modelClass.name}"
                    }
                    @Suppress("UNCHECKED_CAST")
                    return EntriesViewModel(sourceUrl, (application as ScoophApp).repository) as T
                }
            }
    }
}

/** 过滤 + 排序的唯一实现：已安装在前，其次按名称（不区分大小写）。 */
internal fun filterAndSort(rows: List<EntryRow>, query: String): List<EntryRow> {
    val needle = query.trim().lowercase()
    return rows
        .filter { row ->
            needle.isEmpty() ||
                row.name.lowercase().contains(needle) ||
                row.packageName.lowercase().contains(needle)
        }
        .sortedWith(
            compareByDescending<EntryRow> { it.installed }
                .thenBy { it.name.lowercase() },
        )
}
