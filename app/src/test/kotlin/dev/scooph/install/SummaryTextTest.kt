package dev.scooph.install

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 队列结束时的汇总文案（计划 Task 21）。
 *
 * 只描述结果，不加解释；全部失败时以「全部失败」开头。
 */
class SummaryTextTest {

    @Test
    fun `all success`() {
        val items =
            listOf(
                QueueItem("com.a", QueueState.Done),
                QueueItem("com.b", QueueState.Done),
                QueueItem("com.c", QueueState.Done),
                QueueItem("com.d", QueueState.Done),
            )

        assertEquals("更新完成 · 成功 4", summaryText(items))
    }

    @Test
    fun `mixed skip and failure`() {
        val items =
            listOf(
                QueueItem("com.a", QueueState.Done),
                QueueItem("com.b", QueueState.Done),
                QueueItem("com.c", QueueState.Done),
                QueueItem("com.d", QueueState.Done),
                QueueItem("com.e", QueueState.Skipped("已取消")),
                QueueItem("com.f", QueueState.Failed("网络断开")),
            )

        assertEquals("更新完成 · 成功 4，跳过 1，失败 1", summaryText(items))
    }

    @Test
    fun `all failed starts with the all failed phrase`() {
        val items =
            listOf(
                QueueItem("com.a", QueueState.Failed("网络断开")),
                QueueItem("com.b", QueueState.Failed("sha256 不一致")),
                QueueItem("com.c", QueueState.AwaitingConfirm),
            )

        assertEquals("全部失败 · 失败 3", summaryText(items))
    }
}
