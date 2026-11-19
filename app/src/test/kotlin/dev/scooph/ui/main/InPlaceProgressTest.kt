package dev.scooph.ui.main

import dev.scooph.domain.DerivedState
import dev.scooph.install.QueueItem
import dev.scooph.install.QueueState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 主屏就地显示更新进度（spec §10.4 / 计划 Task 20）。
 *
 * 文案是纯函数，不碰 Compose：每一行的副行只由队列状态决定；
 * 顶栏在队列进行中显示「已处理 / 总数」。
 */
class InPlaceProgressTest {

    @Test
    fun `queue states project to the exact row subtitles`() {
        assertEquals("正在下载 62%", queueSubtitle(QueueItem("com.a", QueueState.Downloading, progressPercent = 62)))
        assertEquals("等待更新", queueSubtitle(QueueItem("com.a", QueueState.Waiting)))
        assertEquals("等你确认安装", queueSubtitle(QueueItem("com.a", QueueState.AwaitingConfirm)))
        assertEquals("已装好", queueSubtitle(QueueItem("com.a", QueueState.Done)))
        assertEquals("跳过：已取消", queueSubtitle(QueueItem("com.a", QueueState.Skipped("已取消"))))
        assertEquals("失败：网络断开", queueSubtitle(QueueItem("com.a", QueueState.Failed("网络断开"))))
    }

    @Test
    fun `rows in the queue use the queue subtitle instead of the version line`() {
        val row =
            MainRow(
                packageName = "com.a",
                name = "A",
                state = DerivedState.UPDATABLE,
                installedVersionName = "1.0",
                entryVersionName = "2.0",
                skipVersionCode = 0,
                error = null,
            )

        assertEquals("已装好", subtitleFor(row, QueueItem("com.a", QueueState.Done)))
        assertEquals("1.0 → 2.0", subtitleFor(row, null))
    }

    @Test
    fun `top right shows processed over total while the queue is active`() {
        val queue =
            listOf(
                QueueItem("com.a", QueueState.Done),
                QueueItem("com.b", QueueState.Done),
                QueueItem("com.c", QueueState.Failed("网络断开")),
                QueueItem("com.d", QueueState.Downloading, progressPercent = 30),
                QueueItem("com.e", QueueState.Waiting),
                QueueItem("com.f", QueueState.Waiting),
            )

        val state = MainUiState(queue = queue, active = true)

        assertEquals("3 / 6", updateLabel(state))
    }

    @Test
    fun `top right falls back to the action label when the queue is idle`() {
        assertEquals("全部更新", updateLabel(MainUiState()))
    }
}
