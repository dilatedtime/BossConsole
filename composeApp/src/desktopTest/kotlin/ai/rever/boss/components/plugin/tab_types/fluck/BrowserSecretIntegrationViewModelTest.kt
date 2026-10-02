@file:Suppress("PackageNaming")

package ai.rever.boss.components.plugin.tab_types.fluck

import ai.rever.boss.services.supabase.models.PaginatedSecrets
import ai.rever.boss.services.supabase.models.SecretEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserSecretIntegrationViewModelTest {
    private fun sampleSecret(
        id: String = "sec-1",
        website: String = "github.com",
        username: String = "user@example.com",
        tags: List<String> = listOf("dev"),
    ): SecretEntry =
        SecretEntry(
            id = id,
            website = website,
            username = username,
            password = "secret-pass",
            notes = "personal note",
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z",
            tags = tags,
        )

    @Test
    fun `initialize loads secrets and updates state successfully`() {
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val secret = sampleSecret()
            val viewModel =
                BrowserSecretIntegrationViewModel(
                    fetchSecrets = {
                        Result.success(PaginatedSecrets(listOf(secret), hasMore = false))
                    },
                )

            try {
                viewModel.initialize()
                advanceUntilIdle()

                assertEquals(listOf(secret), viewModel.state.allSecrets)
                assertFalse(viewModel.state.isLoadingSecrets)
                assertEquals(null, viewModel.state.error)
            } finally {
                viewModel.dispose()
                Dispatchers.resetMain()
            }
        }
    }

    @Test
    fun `multiple initialize calls cancel previous collector without duplicating events`() {
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val fetchCount = AtomicInteger(0)
            val viewModel =
                BrowserSecretIntegrationViewModel(
                    fetchSecrets = {
                        fetchCount.incrementAndGet()
                        Result.success(PaginatedSecrets(emptyList(), hasMore = false))
                    },
                )

            try {
                viewModel.initialize()
                advanceUntilIdle()
                assertEquals(1, fetchCount.get())

                viewModel.initialize()
                advanceUntilIdle()
                assertEquals(2, fetchCount.get())

                SecretChangeNotifier.notifyRefresh()
                advanceUntilIdle()
                assertEquals(3, fetchCount.get())
            } finally {
                viewModel.dispose()
                Dispatchers.resetMain()
            }
        }
    }

    @Test
    fun `dispose cancels in flight load and resets isLoadingSecrets`() {
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val deferred = CompletableDeferred<PaginatedSecrets>()
            val viewModel =
                BrowserSecretIntegrationViewModel(
                    fetchSecrets = {
                        val data = deferred.await()
                        Result.success(data)
                    },
                )

            try {
                viewModel.initialize()
                assertTrue(viewModel.state.isLoadingSecrets)

                viewModel.dispose()
                assertFalse(viewModel.state.isLoadingSecrets)
            } finally {
                viewModel.dispose()
                Dispatchers.resetMain()
            }
        }
    }

    @Test
    fun `post-dispose operations are guarded and do not trigger network fetch`() {
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val fetchCount = AtomicInteger(0)
            val viewModel =
                BrowserSecretIntegrationViewModel(
                    fetchSecrets = {
                        fetchCount.incrementAndGet()
                        Result.success(PaginatedSecrets(emptyList(), hasMore = false))
                    },
                )

            try {
                viewModel.initialize()
                advanceUntilIdle()
                assertEquals(1, fetchCount.get())

                viewModel.dispose()
                val countAfterDispose = fetchCount.get()

                viewModel.initialize()
                viewModel.reloadSecrets()
                advanceUntilIdle()

                assertEquals(countAfterDispose, fetchCount.get())
                assertFalse(viewModel.state.isLoadingSecrets)
            } finally {
                viewModel.dispose()
                Dispatchers.resetMain()
            }
        }
    }

    @Test
    fun `domain matching and search query filter secret entries correctly`() {
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val s1 = sampleSecret(id = "1", website = "https://github.com", username = "alice")
            val s2 = sampleSecret(id = "2", website = "https://gitlab.com", username = "bob")
            val viewModel =
                BrowserSecretIntegrationViewModel(
                    fetchSecrets = {
                        Result.success(PaginatedSecrets(listOf(s1, s2), hasMore = false))
                    },
                )

            try {
                viewModel.initialize()
                advanceUntilIdle()

                viewModel.onUrlChanged("https://github.com/settings")
                assertEquals("github.com", viewModel.state.currentDomain)
                assertEquals(1, viewModel.state.matchingSecrets.size)
                val matched = viewModel.state.matchingSecrets.first()
                assertEquals("1", matched.id)

                viewModel.searchSecrets("bob")
                assertEquals(1, viewModel.state.filteredSecrets.size)
                val filtered = viewModel.state.filteredSecrets.first()
                assertEquals("2", filtered.id)
            } finally {
                viewModel.dispose()
                Dispatchers.resetMain()
            }
        }
    }
}
