package dev.scooph.ui.sources

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.scooph.ScoophApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.URI

/** 来源页一行：bucket 自己的信息加条目数。 */
data class SourceRow(
    val url: String,
    val name: String,
    val entryCount: Int,
    val lastSyncAt: Long,
    val status: String,
)

data class SourcesUiState(
    val sources: List<SourceRow> = emptyList(),
    val input: String = "",
    val error: String? = null,
)

/** 来源页的数据接口，便于单测。 */
interface SourcesRepository {
    suspend fun load(): List<SourceRow>

    /** 添加并同步一个 bucket；返回可读错误，成功返回 null。 */
    suspend fun add(url: String): String?

    suspend fun remove(url: String)

    suspend fun sync(url: String)
}

/**
 * 来源页（spec §11.1 / 计划 Task 22）。
 *
 * 添加用一个常驻输入行，不弹对话框；地址在进仓库前先做格式校验。
 */
class SourcesViewModel(
    private val repository: SourcesRepository,
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val workScope = scope ?: viewModelScope

    private val _state = MutableStateFlow(SourcesUiState())
    val state: StateFlow<SourcesUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        workScope.launch {
            try {
                _state.value = _state.value.copy(sources = repository.load())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 读失败时保留上一屏。
            }
        }
    }

    fun onInputChange(value: String) {
        _state.value = _state.value.copy(input = value, error = null)
    }

    fun add() {
        val url = _state.value.input.trim()
        validateBucketUrl(url)?.let { message ->
            _state.value = _state.value.copy(error = message)
            return
        }
        workScope.launch {
            val message = repository.add(url)
            if (message != null) {
                _state.value = _state.value.copy(error = message)
            } else {
                _state.value = _state.value.copy(input = "", error = null)
                reload()
            }
        }
    }

    fun remove(url: String) {
        workScope.launch {
            repository.remove(url)
            reload()
        }
    }

    fun sync(url: String) {
        workScope.launch {
            repository.sync(url)
            reload()
        }
    }

    private suspend fun reload() {
        _state.value = _state.value.copy(sources = repository.load())
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(SourcesViewModel::class.java)) {
                        "未知的 ViewModel：${modelClass.name}"
                    }
                    @Suppress("UNCHECKED_CAST")
                    return SourcesViewModel((application as ScoophApp).repository) as T
                }
            }
    }
}

/**
 * bucket 地址校验：只接受 http/https。
 *
 * 返回 null 表示可用，否则返回一句给人看的原因。
 */
internal fun validateBucketUrl(raw: String): String? {
    if (raw.isBlank()) return "请输入 Git 地址"
    val uri =
        try {
            URI(raw)
        } catch (_: Exception) {
            return "地址格式不对"
        }
    if (uri.scheme != "http" && uri.scheme != "https") {
        return "只支持 http/https 地址"
    }
    if (uri.host.isNullOrBlank()) return "地址格式不对"
    return null
}
