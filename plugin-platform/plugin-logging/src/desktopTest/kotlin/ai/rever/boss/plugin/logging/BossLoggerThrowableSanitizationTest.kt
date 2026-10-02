package ai.rever.boss.plugin.logging

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BossLoggerThrowableSanitizationTest {
    private val testListener =
        object : LogListener {
            val entries = mutableListOf<LogEntry>()

            override fun onLog(entry: LogEntry) {
                entries.add(entry)
            }
        }

    @BeforeTest
    fun setUp() {
        BossLogger.clearLogs()
        BossLogger.addListener(testListener)
    }

    @AfterTest
    fun tearDown() {
        BossLogger.removeListener(testListener)
        BossLogger.clearLogs()
    }

    @Test
    fun `listeners receive sanitized throwables with stripped frame paths`() {
        val logger = BossLogger.forComponent("ThrowableSanitizationTest")
        val exception = RuntimeException("Sensitive failure with token eyJhbGciOiJIUzI1NiJ9.payload.signature")
        exception.stackTrace =
            arrayOf(
                StackTraceElement(
                    "ai.rever.boss.plugin.SecretPlugin",
                    "invokeAction",
                    "/home/user/workspace/SecretPlugin.kt",
                    77,
                ),
            )

        logger.error(
            category = LogCategory.SYSTEM,
            message = "Operation failed",
            error = exception,
        )

        val entry = testListener.entries.find { it.component == "ThrowableSanitizationTest" }
        assertNotNull(entry, "Expected log entry received by listener")

        val sanitizedError = entry.error
        assertNotNull(sanitizedError, "Expected error on log entry")
        assertTrue(sanitizedError is SanitizedThrowable)

        val message = sanitizedError.message.orEmpty()
        assertFalse(message.contains("eyJhbGciOiJIUzI1NiJ9"))

        val frames = sanitizedError.stackTrace
        assertEquals(1, frames.size)
        assertEquals("SecretPlugin.kt", frames[0].fileName)
        assertFalse(frames[0].toString().contains("/home/user/workspace/"))
    }

    @Test
    fun `recent logs store sanitized throwables with stripped frame paths`() {
        val logger = BossLogger.forComponent("RecentLogsSanitizationTest")
        val exception = IllegalArgumentException("Bad argument at C:\\Users\\secret\\Conf.kt")
        exception.stackTrace =
            arrayOf(
                StackTraceElement(
                    "ai.rever.boss.Config",
                    "parse",
                    """C:\Users\secret\Conf.kt""",
                    10,
                ),
            )

        logger.error(
            category = LogCategory.SYSTEM,
            message = "Config load failed",
            error = exception,
        )

        val recent = BossLogger.getRecentLogs().find { it.component == "RecentLogsSanitizationTest" }
        assertNotNull(recent, "Expected entry in recent logs")

        val error = recent.error
        assertNotNull(error)
        assertTrue(error is SanitizedThrowable)
        assertEquals("Conf.kt", error.stackTrace[0].fileName)
        assertFalse(error.stackTrace[0].toString().contains("""C:\Users\secret"""))
    }
}
