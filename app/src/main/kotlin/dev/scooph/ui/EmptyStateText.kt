package dev.scooph.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 空状态的三种情形（spec §11.2：空状态是一句话）。 */
enum class EmptyKind {
    /** 一个 bucket 都没有。 */
    NO_SOURCE,

    /** 有来源，但与已装应用没有交集。 */
    NO_MATCH,

    /** 有可管理的应用，但全都已是最新。 */
    ALL_UP_TO_DATE,
}

/** 空状态文案的唯一来源。 */
fun emptyStateText(state: EmptyKind): String =
    when (state) {
        EmptyKind.NO_SOURCE -> "还没有来源"
        EmptyKind.NO_MATCH -> "没有可管理的应用"
        EmptyKind.ALL_UP_TO_DATE -> "全部是最新的"
    }

/** 所有页面共用的空状态：一行灰字，居中，不加按钮。 */
@Composable
fun EmptyStateLine(
    kind: EmptyKind,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = emptyStateText(kind),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
