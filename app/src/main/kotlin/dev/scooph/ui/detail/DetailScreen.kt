package dev.scooph.ui.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.scooph.domain.DerivedState
import dev.scooph.domain.Policy

@Composable
fun DetailScreen(
    viewModel: DetailViewModel,
    onBack: () -> Unit,
    onUpdateClick: (String) -> Unit = {},
    onAcceptAndUpdate: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(viewModel) { viewModel.load() }
    DetailContent(
        state = state,
        onBack = onBack,
        onUpdateClick = { onUpdateClick(state.packageName) },
        onAcceptAndUpdate = { onAcceptAndUpdate(state.packageName) },
        onPolicySelected = viewModel::selectPolicy,
    )
}

@Composable
private fun DetailContent(
    state: DetailUiState,
    onBack: () -> Unit,
    onUpdateClick: () -> Unit,
    onAcceptAndUpdate: () -> Unit,
    onPolicySelected: (Policy) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text(text = "应用", fontSize = 14.sp)
            }
        }

        Spacer(Modifier.height(12.dp))
        Box(
            modifier =
                Modifier
                    .size(62.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .align(Alignment.CenterHorizontally),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = state.name.take(1).uppercase(),
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = state.name,
            modifier = Modifier.align(Alignment.CenterHorizontally),
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = state.packageName,
            modifier = Modifier.align(Alignment.CenterHorizontally),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(22.dp))
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                VersionRow(label = "当前", value = state.installedVersionName.ifBlank { "-" })
                Spacer(Modifier.height(8.dp))
                VersionRow(label = "最新", value = state.entryVersionName.ifBlank { "-" }, emphasize = true)
            }
        }

        // spec §9.2：AHEAD 无更新动作、UP_TO_DATE 无事可做，
        // 这两种状态和已忽略一样不出现主按钮，避免给出反方向的「更新」入口。
        if (state.state == DerivedState.UPDATABLE) {
            Spacer(Modifier.height(14.dp))
            OutlinedButton(
                onClick = onUpdateClick,
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, accent),
                shape = RoundedCornerShape(12.dp),
            ) {
                val target = state.entryVersionName.ifBlank { "最新" }
                Text(text = "更新到 $target", color = accent, fontSize = 15.sp)
            }
        }

        Spacer(Modifier.height(18.dp))
        Text(
            text = "策略",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                PolicyRow(
                    title = "正常更新",
                    selected = state.policy == Policy.FOLLOW,
                    accentColor = accent,
                    onClick = { onPolicySelected(Policy.FOLLOW) },
                )
                PolicyRow(
                    title = "跳过这一版",
                    selected = state.policy == Policy.SKIP_VERSION,
                    accentColor = accent,
                    onClick = { onPolicySelected(Policy.SKIP_VERSION) },
                )
                PolicyRow(
                    title = "忽略后续更新",
                    selected = state.policy == Policy.UNTRACKED,
                    accentColor = accent,
                    onClick = { onPolicySelected(Policy.UNTRACKED) },
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        Text(
            text = "来源",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.sourceLabel.ifBlank { "-" },
                    fontSize = 14.sp,
                    maxLines = 1,
                )
            }
        }

        if (state.signatureChanged) {
            Spacer(Modifier.height(14.dp))
            OutlinedButton(
                onClick = onAcceptAndUpdate,
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, accent),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(text = "接受并更新", color = accent, fontSize = 15.sp)
            }
        }

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun VersionRow(
    label: String,
    value: String,
    emphasize: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = if (emphasize) FontWeight.SemiBold else FontWeight.Normal,
            color = if (emphasize) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun PolicyRow(
    title: String,
    selected: Boolean,
    accentColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            fontSize = 15.sp,
            color = if (selected) accentColor else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = if (selected) "✓" else "",
            fontSize = 16.sp,
            color = accentColor,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
