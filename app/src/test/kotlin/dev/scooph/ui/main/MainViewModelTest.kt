package dev.scooph.ui.main

import dev.scooph.data.MainRepository
import dev.scooph.domain.DerivedState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 主屏分组只是七值状态的投影（spec §9.2 / §11.1）：
 *
 * - [DerivedState.IGNORED] 与 [DerivedState.SKIPPED] → 已忽略组；
 * - [DerivedState.AHEAD] 与 [DerivedState.UP_TO_DATE] → 已是最新组；
 * - [DerivedState.NO_SOURCE] 与 [DerivedState.NOT_INSTALLED] 不显示；
 * - [DerivedState.UPDATABLE] → 可更新组。
 *
 * 测试不碰 Room 与引擎：假 Repository 直接给一屏混合的行，只验证分组这一层。
 */
class MainViewModelTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `ignored and skipped both land in the ignored group`() {
        val vm =
            viewModelWith(
                row("com.untracked", DerivedState.IGNORED),
                row("com.skipped", DerivedState.SKIPPED, skipVersionCode = 70L),
            )

        vm.refresh()

        val state = vm.state.value
        assertEquals(listOf("com.untracked", "com.skipped"), state.ignored.map { it.packageName })
        assertEquals(70L, state.ignored.last().skipVersionCode)
        assertEquals(emptyList<MainRow>(), state.updatable)
        assertEquals(emptyList<MainRow>(), state.upToDate)
    }

    @Test
    fun `ahead and up to date both land in the up to date group`() {
        val vm =
            viewModelWith(
                row("com.ahead", DerivedState.AHEAD),
                row("com.current", DerivedState.UP_TO_DATE),
            )

        vm.refresh()

        val state = vm.state.value
        assertEquals(listOf("com.ahead", "com.current"), state.upToDate.map { it.packageName })
        assertEquals(emptyList<MainRow>(), state.updatable)
        assertEquals(emptyList<MainRow>(), state.ignored)
    }

    @Test
    fun `updatable rows land in the updatable group`() {
        val vm = viewModelWith(row("com.old", DerivedState.UPDATABLE))

        vm.refresh()

        val state = vm.state.value
        assertEquals(listOf("com.old"), state.updatable.map { it.packageName })
        assertEquals(emptyList<MainRow>(), state.upToDate)
        assertEquals(emptyList<MainRow>(), state.ignored)
    }

    @Test
    fun `no source and not installed never appear in any group`() {
        val vm =
            viewModelWith(
                row("com.none", DerivedState.NO_SOURCE),
                row("com.absent", DerivedState.NOT_INSTALLED),
                row("com.keep", DerivedState.UP_TO_DATE),
            )

        vm.refresh()

        val state = vm.state.value
        assertEquals(listOf("com.keep"), state.upToDate.map { it.packageName })
        assertEquals(emptyList<MainRow>(), state.updatable)
        assertEquals(emptyList<MainRow>(), state.ignored)
        val shown = state.updatable + state.upToDate + state.ignored
        assertFalse(shown.any { it.packageName == "com.none" || it.packageName == "com.absent" })
    }

    @Test
    fun `syncing is false again after a refresh finishes`() {
        val vm = viewModelWith(row("com.old", DerivedState.UPDATABLE))

        vm.refresh()

        assertFalse(vm.state.value.syncing)
    }

    @Test
    fun `error overlay stays on the row without changing its group`() {
        val vm = viewModelWith(row("com.err", DerivedState.UPDATABLE, error = "网络断开"))

        vm.refresh()

        val row = vm.state.value.updatable.single()
        assertEquals("网络断开", row.error)
    }

    private fun viewModelWith(vararg rows: MainRow): MainViewModel =
        MainViewModel(FakeRepository(rows.toList()), scope)

    private fun row(
        packageName: String,
        state: DerivedState,
        skipVersionCode: Long = 0L,
        error: String? = null,
    ) = MainRow(
        packageName = packageName,
        name = packageName,
        state = state,
        installedVersionName = "1.0",
        entryVersionName = "1.1",
        skipVersionCode = skipVersionCode,
        error = error,
    )

    private class FakeRepository(private val rows: List<MainRow>) : MainRepository {
        override suspend fun refresh(): List<MainRow> = rows
    }
}
