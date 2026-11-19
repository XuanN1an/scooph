package dev.scooph.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.scooph.domain.DerivedState

/**
 * 主屏（spec §11.1）：大标题「应用」，左上「来源」，右上「全部更新」，
 * 三组固定顺序：可更新 → 已是最新 → 已忽略；空组不显示标题。
 *
 * 「来源」与「全部更新」的动作留给后续任务（Task 22 / Task 19），这里只占位。
 */
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onOpenDetail: (String) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(viewModel) { viewModel.refresh() }
    MainContent(state = state, onOpenDetail = onOpenDetail)
}

@Composable
private fun MainContent(
    state: MainUiState,
    onOpenDetail: (String) -> Unit,
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
            TextButton(onClick = {}) {
                Text(text = "来源", fontSize = 14.sp)
            }
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = {},
                enabled = state.updatable.isNotEmpty(),
            ) {
                Text(text = "全部更新", fontSize = 14.sp)
            }
        }
        Text(
            text = "应用",
            modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 12.dp),
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            mainGroup(title = "可更新", rows = state.updatable, onOpenDetail = onOpenDetail)
            mainGroup(title = "已是最新", rows = state.upToDate, onOpenDetail = onOpenDetail)
            mainGroup(title = "已忽略", rows = state.ignored, onOpenDetail = onOpenDetail)
        }
    }
}

private fun LazyListScope.mainGroup(
    title: String,
    rows: List<MainRow>,
    onOpenDetail: (String) -> Unit,
) {
    if (rows.isEmpty()) return
    item(key = "header-$title") {
        Text(
            text = "$title · ${rows.size}",
            modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    items(rows, key = { "$title-${it.packageName}" }) { row ->
        MainRowItem(
            row = row,
            onClick = { onOpenDetail(row.packageName) },
        )
    }
}

@Composable
private fun MainRowItem(
    row: MainRow,
    onClick: () -> Unit,
) {
    Surface(
        // 先 clip 再 clickable，水波纹不会溢出圆角。
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 清单里没有图标字段，先用名称首字占位，保留 30dp 图标位。
            Box(
                modifier =
                    Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = row.name.take(1).uppercase(),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = row.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = rowSubtitle(row),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = "›",
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 副行只显示版本变化或状态说明，不需要额外提示文案。 */
private fun rowSubtitle(row: MainRow): String =
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
