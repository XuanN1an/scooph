package dev.scooph.ui.detail

import dev.scooph.data.UpdateOutcome
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

    @Test
    fun `selecting skip this version records the current entry version code`() {
        val repository =
            FakeDetailRepository(
                row = detail(policy = Policy.FOLLOW, entryVersionCode = 7000),
                afterWrite = detail(policy = Policy.SKIP_VERSION, entryVersionCode = 7000, skipVersionCode = 7000),
            )
        val vm = DetailViewModel("com.example.app", repository, scope)
        vm.load()

        vm.selectPolicy(Policy.SKIP_VERSION)

        assertEquals(Policy.SKIP_VERSION, repository.lastPolicy)
        assertEquals(7000L, repository.lastSkipVersionCode)
        assertEquals(DerivedState.SKIPPED, vm.state.value.state)
        assertEquals(Policy.SKIP_VERSION, vm.state.value.policy)
    }

    @Test
    fun `selecting follow clears the skip version code`() {
        val repository =
            FakeDetailRepository(
                row = detail(policy = Policy.SKIP_VERSION, entryVersionCode = 7000, skipVersionCode = 7000),
                afterWrite = detail(policy = Policy.FOLLOW, entryVersionCode = 7000),
            )
        val vm = DetailViewModel("com.example.app", repository, scope)
        vm.load()

        vm.selectPolicy(Policy.FOLLOW)

        assertEquals(Policy.FOLLOW, repository.lastPolicy)
        assertEquals(0L, repository.lastSkipVersionCode)
        assertEquals(Policy.FOLLOW, vm.state.value.policy)
    }

    private fun detail(
        policy: Policy,
        entryVersionCode: Long,
        skipVersionCode: Long = 0L,
    ) = DetailUiState(
        packageName = "com.example.app",
        name = "Example",
        state = if (policy == Policy.SKIP_VERSION) DerivedState.SKIPPED else DerivedState.UPDATABLE,
        installedVersionName = "1.0",
        entryVersionName = "2.0",
        policy = policy,
        skipVersionCode = skipVersionCode,
        sourceLabel = "bucket-a",
        signatureChanged = false,
        entryVersionCode = entryVersionCode,
    )

    private class FakeDetailRepository(
        private val row: DetailUiState?,
        private val afterWrite: DetailUiState? = row,
    ) : DetailRepository {
        var lastPolicy: Policy? = null
            private set
        var lastSkipVersionCode: Long? = null
            private set

        private var loads = 0

        override suspend fun load(packageName: String): DetailUiState? {
            loads++
            return if (loads == 1) row else afterWrite
        }

        override suspend fun setPolicy(packageName: String, policy: Policy, skipVersionCode: Long) {
            lastPolicy = policy
            lastSkipVersionCode = skipVersionCode
        }

        override suspend fun update(
            packageName: String,
            onProgress: (Long, Long) -> Unit,
        ): UpdateOutcome = UpdateOutcome.Success
    }
}
