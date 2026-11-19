package dev.scooph.ui.sources

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** 条目页与搜索（计划 Task 23）：大小写不敏感子串匹配，已安装在前。 */
class EntriesViewModelTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `k9 matches K-9 Mail by name`() {
        val vm = viewModel()

        vm.onQueryChange("k9")

        assertEquals(listOf("com.fsck.k9"), vm.state.value.rows.map { it.packageName })
    }

    @Test
    fun `com fsck matches the same app by package name`() {
        val vm = viewModel()

        vm.onQueryChange("com.fsck")

        assertEquals(listOf("com.fsck.k9"), vm.state.value.rows.map { it.packageName })
    }

    @Test
    fun `empty query returns every entry`() {
        val vm = viewModel()

        assertEquals(3, vm.state.value.rows.size)
    }

    @Test
    fun `installed rows come first then names are alphabetical`() {
        val vm = viewModel()

        assertEquals(
            listOf("com.android.chrome", "org.mozilla.firefox"),
            vm.state.value.rows.take(2).map { it.packageName },
        )
    }

    private fun viewModel(): EntriesViewModel =
        EntriesViewModel(SOURCE, FakeEntriesRepository(ROWS), scope)

    private class FakeEntriesRepository(private val rows: List<EntryRow>) : EntriesRepository {
        override suspend fun entries(sourceUrl: String): List<EntryRow> = rows
    }

    private companion object {
        const val SOURCE = "https://example.com/bucket.git"
        val ROWS =
            listOf(
                EntryRow("com.fsck.k9", "K-9 Mail", "7.0", installed = false, state = null),
                EntryRow("com.android.chrome", "Chrome", "120", installed = true, state = null),
                EntryRow("org.mozilla.firefox", "Firefox", "121", installed = false, state = null),
            )
    }
}
