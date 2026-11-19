package dev.scooph.ui.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * 来源页（spec §11.1 / 计划 Task 22）。
 *
 * 每个 bucket 一行：名称、条目数、上次同步时间；右侧「同步」「删除」。
 * 添加入口是常驻输入行，不弹对话框。
 */
@Composable
fun SourcesScreen(
    viewModel: SourcesViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    SourcesContent(
        state = state,
        onBack = onBack,
        onInputChange = viewModel::onInputChange,
        onAdd = viewModel::add,
        onSync = viewModel::sync,
        onRemove = viewModel::remove,
    )
}

@Composable
private fun SourcesContent(
    state: SourcesUiState,
    onBack: () -> Unit,
    onInputChange: (String) -> Unit,
    onAdd: () -> Unit,
    onSync: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text(text = "应用", fontSize = 14.sp)
            }
        }
        Text(
            text = "来源",
            modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 10.dp),
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedTextField(
                value = state.input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text(text = "Git 地址", fontSize = 14.sp) },
                shape = RoundedCornerShape(12.dp),
            )
            TextButton(onClick = onAdd, enabled = state.input.isNotBlank()) {
                Text(text = "添加", fontSize = 14.sp)
            }
        }
        state.error?.let { message ->
            Text(
                text = message,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.sources, key = { it.url }) { row ->
                SourceItem(
                    row = row,
                    onSync = { onSync(row.url) },
                    onRemove = { onRemove(row.url) },
                )
            }
        }
    }
}

@Composable
private fun SourceItem(
    row: SourceRow,
    onSync: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = row.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${row.entryCount} 个条目 · ${syncLabel(row.lastSyncAt, row.status)}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onSync) {
                Text(text = "同步", fontSize = 12.sp)
            }
            TextButton(onClick = onRemove) {
                Text(text = "删除", fontSize = 12.sp)
            }
        }
    }
}

/** 「3 分钟前」这类相对时间；超过 7 天显示日期，不显示年份以外的东西。 */
internal fun syncLabel(
    lastSyncAt: Long,
    status: String,
    now: Long = System.currentTimeMillis(),
): String {
    if (status == "ERROR") return "同步失败"
    if (lastSyncAt <= 0L) return "从未同步"
    val elapsed = (now - lastSyncAt).coerceAtLeast(0L)
    val days = TimeUnit.MILLISECONDS.toDays(elapsed)
    if (days >= 7) {
        val date = Instant.ofEpochMilli(lastSyncAt).atZone(ZoneId.systemDefault()).toLocalDate()
        return date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
    }
    return when {
        elapsed < TimeUnit.MINUTES.toMillis(1) -> "刚刚同步"
        elapsed < TimeUnit.HOURS.toMillis(1) ->
            "${TimeUnit.MILLISECONDS.toMinutes(elapsed)} 分钟前"
        elapsed < TimeUnit.DAYS.toMillis(1) ->
            "${TimeUnit.MILLISECONDS.toHours(elapsed)} 小时前"
        else -> "${days} 天前"
    }
}
