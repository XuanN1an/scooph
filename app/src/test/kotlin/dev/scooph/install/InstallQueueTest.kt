package dev.scooph.install

import dev.scooph.data.UpdateOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 一键更新的串行队列（spec §10.4 / 计划 Task 19）。
 *
 * 用假的 `updateOne` 直接喂结果：普通失败与取消都继续下一项，
 * 只有签名变更停住队列。
 */
class InstallQueueTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `queue runs strictly serial and continues after ordinary failures`() {
        val calls = mutableListOf<String>()
        val outcomes =
            listOf(
                UpdateOutcome.Success,
                UpdateOutcome.Cancelled,
                UpdateOutcome.Failed("网络断开"),
                UpdateOutcome.Success,
            )
        val queue = queue(calls, outcomes)

        queue.start(PACKAGES)

        assertEquals(PACKAGES, calls)
        assertEquals(
            listOf(
                QueueItem("com.a", QueueState.Done),
                QueueItem("com.b", QueueState.Skipped("已取消")),
                QueueItem("com.c", QueueState.Failed("网络断开")),
                QueueItem("com.d", QueueState.Done),
            ),
            queue.items.value,
        )
    }

    @Test
    fun `signature change pauses the queue and leaves the rest waiting`() {
        val calls = mutableListOf<String>()
        val queue =
            queue(
                calls,
                listOf(
                    UpdateOutcome.Success,
                    UpdateOutcome.Failed("签名已变更"),
                    UpdateOutcome.Success,
                ),
            )

        queue.start(listOf("com.a", "com.b", "com.c"))

        // 第三项不该被调用：签名变更要人做决定。
        assertEquals(listOf("com.a", "com.b"), calls)
        assertEquals(
            listOf(
                QueueItem("com.a", QueueState.Done),
                QueueItem("com.b", QueueState.AwaitingConfirm),
                QueueItem("com.c", QueueState.Waiting),
            ),
            queue.items.value,
        )
    }

    @Test
    fun `items are downloading while their update is in flight`() {
        lateinit var queue: InstallQueue
        var observed: List<QueueState> = emptyList()
        queue =
            InstallQueue(scope) { _, _ ->
                observed = queue.items.value.map { it.state }
                UpdateOutcome.Success
            }

        queue.start(listOf("com.a"))

        // 回调发生时这一项已经在下载态。
        assertEquals(listOf<QueueState>(QueueState.Downloading), observed)
    }

    private fun queue(
        calls: MutableList<String>,
        outcomes: List<UpdateOutcome>,
    ): InstallQueue {
        var index = 0
        return InstallQueue(scope) { packageName, _ ->
            calls += packageName
            outcomes[index++]
        }
    }

    private companion object {
        val PACKAGES = listOf("com.a", "com.b", "com.c", "com.d")
    }
}
