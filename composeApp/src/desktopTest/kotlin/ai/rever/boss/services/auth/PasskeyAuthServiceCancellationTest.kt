package ai.rever.boss.services.auth

import ai.rever.boss.services.supabase.models.UserInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PasskeyAuthServiceCancellationTest {
    @BeforeEach
    fun setUp() {
        PasskeyAuthService.resetForTest()
        AuthStateManager.setCurrentUser(null)
    }

    @AfterEach
    fun tearDown() {
        PasskeyAuthService.resetForTest()
        AuthStateManager.setCurrentUser(null)
    }

    @Test
    fun `registerPasskey returns failure when no user is logged in`() =
        runTest {
            val result = PasskeyAuthService.registerPasskey()
            assertTrue(result.isFailure)
            assertEquals("No user logged in", result.exceptionOrNull()?.message)
        }

    @Test
    fun `registerPasskey returns failure when passkey service is unavailable`() =
        runTest {
            AuthStateManager.setCurrentUser(
                UserInfo(
                    id = "test-user-id",
                    email = "user@example.com",
                    createdAt = "2026-10-02T00:00:00Z",
                ),
            )
            val result = PasskeyAuthService.registerPasskey()
            assertTrue(result.isFailure)
            assertEquals("Passkey service not available", result.exceptionOrNull()?.message)
        }

    @Test
    fun `registerPasskey rethrows CancellationException when caller is cancelled`() =
        runTest {
            AuthStateManager.setCurrentUser(
                UserInfo(
                    id = "test-user-id",
                    email = "user@example.com",
                    createdAt = "2026-10-02T00:00:00Z",
                ),
            )
            val childJob =
                launch {
                    cancel(CancellationException("registration cancelled"))
                    assertFailsWith<CancellationException> {
                        PasskeyAuthService.registerPasskey()
                    }
                }
            childJob.join()
        }
}
