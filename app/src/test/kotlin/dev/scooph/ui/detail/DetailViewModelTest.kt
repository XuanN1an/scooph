package dev.scooph.ui.detail

import dev.scooph.domain.DerivedState
import dev.scooph.domain.Policy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class DetailViewModelTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `skip version detail state is exposed`() {
        val vm =
            DetailViewModel(
                packageName = "com.example.app",
                repository =
                    FakeDetailRepository(
                        DetailUiState(
                            packageName = "com.example.app",
                            name = "Example",
                            state = DerivedState.SKIPPED,
                            installedVersionName = "1.0",
                            entryVersionName = "2.0",
                            policy = Policy.SKIP_VERSION,
                            skipVersionCode = 2000,
                            sourceLabel = "bucket-a",
                            signatureChanged = false,
                        ),
                    ),
                scope = scope,
            )

        vm.load()

        val state = vm.state.value
        assertEquals(DerivedState.SKIPPED, state.state)
        assertEquals(Policy.SKIP_VERSION, state.policy)
        assertEquals(2000L, state.skipVersionCode)
    }

    @Test
    fun `default fallback is used when repository has no row`() {
        val vm = DetailViewModel("com.missing", FakeDetailRepository(null), scope)

        vm.load()

        val state = vm.state.value
        assertEquals("com.missing", state.packageName)
        assertEquals(DerivedState.NO_SOURCE, state.state)
        assertEquals(Policy.FOLLOW, state.policy)
    }

    private class FakeDetailRepository(private val row: DetailUiState?) : DetailRepository {
        override suspend fun load(packageName: String): DetailUiState? = row
    }
}
