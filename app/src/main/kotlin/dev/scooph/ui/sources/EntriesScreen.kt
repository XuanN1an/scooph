package dev.scooph.ui.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.scooph.domain.DerivedState

/**
 * 条目页（spec §11.4 / 计划 Task 23）。
 *
 * 顶部搜索框常驻；行右侧三态：已安装显示本机版本、可更新一个点、未安装无标记。
 * 点已安装行进详情页，点未安装行直接开始安装（Task 24 接线）。
 */
@Composable
fun EntriesScreen(
    viewModel: EntriesViewModel,
    onBack: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onInstall: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    EntriesContent(
        state = state,
        onBack = onBack,
        onQueryChange = viewModel::onQueryChange,
        onRowClick = { row -> if (row.installed) onOpenDetail(row.packageName) else onInstall(row.packageName) },
    )
}

@Composable
private fun EntriesContent(
    state: EntriesUiState,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onRowClick: (EntryRow) -> Unit,
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
                Text(text = "来源", fontSize = 14.sp)
            }
        }
        Text(
            text = "条目",
            modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 10.dp),
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
        )
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            singleLine = true,
            placeholder = { Text(text = "搜索", fontSize = 14.sp) },
            shape = RoundedCornerShape(12.dp),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.rows, key = { it.packageName }) { row ->
                EntryItem(row = row, onClick = { onRowClick(row) })
            }
        }
    }
}

@Composable
private fun EntryItem(row: EntryRow, onClick: () -> Unit) {
    Surface(
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
            Column(Modifier.weight(1f)) {
                Text(
                    text = row.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = row.packageName,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when {
                row.installed ->
                    Text(
                        text = row.versionName,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                row.state == DerivedState.UPDATABLE ->
                    Box(
                        modifier =
                            Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                    )
            }
        }
    }
}
