package ai.rever.boss.services.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PasskeySessionEventHandlerTest {
    @Before
    fun setUp() {
        PasskeySessionEventHandler.resetForTest()
    }

    @After
    fun tearDown() {
        PasskeySessionEventHandler.resetForTest()
    }

    @Test
    fun `session registration stores metadata and allows retrieval`() {
        val metadata =
            PasskeySessionEventHandler.SessionMetadata(
                sessionId = "session-123",
                email = "user@example.com",
                type = PasskeySessionEventHandler.SessionType.REGISTRATION,
            )

        PasskeySessionEventHandler.registerSession(metadata)

        val retrieved = PasskeySessionEventHandler.getSessionMetadata("session-123")
        assertNotNull(retrieved)
        assertEquals("session-123", retrieved.sessionId)
        assertEquals("user@example.com", retrieved.email)
        assertEquals(PasskeySessionEventHandler.SessionType.REGISTRATION, retrieved.type)
        assertTrue(retrieved.timestamp > 0L)
    }

    @Test
    fun `session removal clears tracked metadata`() {
        val metadata =
            PasskeySessionEventHandler.SessionMetadata(
                sessionId = "session-456",
                email = "auth@example.com",
                type = PasskeySessionEventHandler.SessionType.AUTHENTICATION,
            )

        PasskeySessionEventHandler.registerSession(metadata)
        val removed = PasskeySessionEventHandler.removeSession("session-456")

        assertNotNull(removed)
        assertEquals("session-456", removed.sessionId)
        assertNull(PasskeySessionEventHandler.getSessionMetadata("session-456"))
    }

    @Test
    fun `handleRegistrationCompleted publishes event only for registered session`() {
        val metadata =
            PasskeySessionEventHandler.SessionMetadata(
                sessionId = "reg-session",
                email = "reg@example.com",
                type = PasskeySessionEventHandler.SessionType.REGISTRATION,
            )

        PasskeySessionEventHandler.handleRegistrationCompleted("unknown-session")
        assertNull(PasskeySessionEventHandler.sessionEvents.value)

        PasskeySessionEventHandler.registerSession(metadata)
        PasskeySessionEventHandler.handleRegistrationCompleted("reg-session")

        val event = PasskeySessionEventHandler.sessionEvents.value
        assertNotNull(event)
        assertTrue(event is PasskeySessionEventHandler.PasskeySessionEvent.RegistrationCompleted)
        assertEquals("reg-session", event.sessionId)
    }

    @Test
    fun `handleAuthenticationCompleted publishes event only for registered session`() {
        val metadata =
            PasskeySessionEventHandler.SessionMetadata(
                sessionId = "auth-session",
                email = "auth@example.com",
                type = PasskeySessionEventHandler.SessionType.AUTHENTICATION,
            )

        PasskeySessionEventHandler.handleAuthenticationCompleted("unknown-session")
        assertNull(PasskeySessionEventHandler.sessionEvents.value)

        PasskeySessionEventHandler.registerSession(metadata)
        PasskeySessionEventHandler.handleAuthenticationCompleted("auth-session")

        val event = PasskeySessionEventHandler.sessionEvents.value
        assertNotNull(event)
        assertTrue(event is PasskeySessionEventHandler.PasskeySessionEvent.AuthenticationCompleted)
        assertEquals("auth-session", event.sessionId)
    }

    @Test
    fun `concurrent session registration and metadata reads do not throw or tear`() =
        runTest {
            val jobs =
                (1..10).map { threadIdx ->
                    async(Dispatchers.Default) {
                        for (i in 1..50) {
                            val id = "session-$threadIdx-$i"
                            val meta =
                                PasskeySessionEventHandler.SessionMetadata(
                                    sessionId = id,
                                    email = "thread$threadIdx@example.com",
                                    type = PasskeySessionEventHandler.SessionType.REGISTRATION,
                                )
                            PasskeySessionEventHandler.registerSession(meta)
                            val read = PasskeySessionEventHandler.getSessionMetadata(id)
                            assertNotNull(read)
                            assertEquals(id, read.sessionId)
                            PasskeySessionEventHandler.removeSession(id)
                        }
                    }
                }
            jobs.awaitAll()
        }

    @Test
    fun `resetForTest clears all tracked sessions and resets events flow`() {
        val metadata =
            PasskeySessionEventHandler.SessionMetadata(
                sessionId = "session-to-reset",
                email = "reset@example.com",
                type = PasskeySessionEventHandler.SessionType.REGISTRATION,
            )
        PasskeySessionEventHandler.registerSession(metadata)
        PasskeySessionEventHandler.handleRegistrationCompleted("session-to-reset")

        assertNotNull(PasskeySessionEventHandler.getSessionMetadata("session-to-reset"))
        assertNotNull(PasskeySessionEventHandler.sessionEvents.value)

        PasskeySessionEventHandler.resetForTest()

        assertNull(PasskeySessionEventHandler.getSessionMetadata("session-to-reset"))
        assertNull(PasskeySessionEventHandler.sessionEvents.value)
    }
}
