package ai.rever.boss.plugin.logging

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThrowableSanitizerTest {
    @Test
    fun `sanitizeFileName handles null and empty filenames`() {
        assertNull(LogSanitizer.sanitizeFileName(null))
        assertEquals("", LogSanitizer.sanitizeFileName(""))
    }

    @Test
    fun `sanitizeFileName preserves standard filename without path`() {
        assertEquals("LogSanitizer.kt", LogSanitizer.sanitizeFileName("LogSanitizer.kt"))
        assertEquals("BossApp.java", LogSanitizer.sanitizeFileName("BossApp.java"))
    }

    @Test
    fun `sanitizeFileName strips absolute Unix paths to basename`() {
        assertEquals(
            "Service.kt",
            LogSanitizer.sanitizeFileName("/Users/username/workspace/project/src/Service.kt"),
        )
        assertEquals(
            "Script.kts",
            LogSanitizer.sanitizeFileName("/home/alice/.config/app/Script.kts"),
        )
    }

    @Test
    fun `sanitizeFileName strips absolute Windows paths to basename`() {
        assertEquals(
            "Main.kt",
            LogSanitizer.sanitizeFileName("""C:\Users\username\Desktop\project\Main.kt"""),
        )
        assertEquals(
            "Runner.java",
            LogSanitizer.sanitizeFileName("""D:\workspace\src\Runner.java"""),
        )
    }

    @Test
    fun `sanitizeFileName strips forward-slash Windows paths to basename`() {
        assertEquals(
            "App.kt",
            LogSanitizer.sanitizeFileName("C:/Users/username/workspace/App.kt"),
        )
    }

    @Test
    fun `sanitizeFileName strips relative paths to basename`() {
        assertEquals(
            "Helper.kt",
            LogSanitizer.sanitizeFileName("../../src/common/Helper.kt"),
        )
        assertEquals(
            "Utils.kt",
            LogSanitizer.sanitizeFileName("""..\..\utils\Utils.kt"""),
        )
    }

    @Test
    fun `sanitizeStackTraceElement removes directory paths from filename`() {
        val rawElement =
            StackTraceElement(
                "ai.rever.boss.auth.AuthManager",
                "authenticate",
                "/Users/alice/secrets/AuthManager.kt",
                42,
            )
        val sanitized = LogSanitizer.sanitizeStackTraceElement(rawElement)

        assertEquals("ai.rever.boss.auth.AuthManager", sanitized.className)
        assertEquals("authenticate", sanitized.methodName)
        assertEquals("AuthManager.kt", sanitized.fileName)
        assertEquals(42, sanitized.lineNumber)
    }

    @Test
    fun `sanitizeStackTraceElement handles null filename in stack trace element`() {
        val rawElement =
            StackTraceElement(
                "java.lang.Thread",
                "run",
                null,
                -1,
            )
        val sanitized = LogSanitizer.sanitizeStackTraceElement(rawElement)

        assertEquals("java.lang.Thread", sanitized.className)
        assertEquals("run", sanitized.methodName)
        assertNull(sanitized.fileName)
        assertEquals(-1, sanitized.lineNumber)
    }

    @Test
    fun `sanitizeStackTraceElement redacts sensitive tokens in class or method names`() {
        val rawElement =
            StackTraceElement(
                "ai.rever.boss.api.Bearer eyJhbGciOiJIUzI1NiJ9.payload.signature",
                "processKey_secret123456789",
                "API.kt",
                100,
            )
        val sanitized = LogSanitizer.sanitizeStackTraceElement(rawElement)

        assertFalse(sanitized.className.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertEquals("API.kt", sanitized.fileName)
        assertEquals(100, sanitized.lineNumber)
    }

    @Test
    fun `sanitizeThrowable returns null when input is null`() {
        assertNull(LogSanitizer.sanitizeThrowable(null))
    }

    @Test
    fun `sanitizeThrowable redacts paths in stack trace filenames and exception message`() {
        val exception =
            IllegalArgumentException(
                "Invalid token eyJhbGciOiJIUzI1NiJ9.payload.signature at /Users/alice/file.txt",
            )
        exception.stackTrace =
            arrayOf(
                StackTraceElement(
                    "ai.rever.boss.Service",
                    "execute",
                    "/Users/alice/workspace/Service.kt",
                    12,
                ),
                StackTraceElement(
                    "ai.rever.boss.Main",
                    "main",
                    """C:\Users\alice\Main.kt""",
                    45,
                ),
            )

        val sanitized = LogSanitizer.sanitizeThrowable(exception)
        assertNotNull(sanitized)
        assertTrue(sanitized is SanitizedThrowable)
        assertEquals("java.lang.IllegalArgumentException", sanitized.originalClassName)

        // Exception message should be sanitized
        val message = sanitized.message.orEmpty()
        assertFalse(message.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertFalse(message.contains("/Users/alice/file.txt"))

        // Stack frames should have basenames only
        val frames = sanitized.stackTrace
        assertEquals(2, frames.size)
        assertEquals("Service.kt", frames[0].fileName)
        assertEquals("Main.kt", frames[1].fileName)
        assertFalse(frames[0].toString().contains("/Users/alice/workspace/"))
        assertFalse(frames[1].toString().contains("""C:\Users\alice\"""))
    }

    @Test
    fun `sanitizeThrowable recursively sanitizes nested cause`() {
        val root = IOException("Connection reset by secret-host.internal at /tmp/root.log")
        root.stackTrace =
            arrayOf(
                StackTraceElement("com.net.Socket", "read", "/usr/src/Socket.java", 80),
            )

        val wrapper = RuntimeException("Failed operation at /var/log/run.log", root)
        wrapper.stackTrace =
            arrayOf(
                StackTraceElement("ai.rever.boss.Worker", "run", "C:\\projects\\Worker.kt", 99),
            )

        val sanitized = LogSanitizer.sanitizeThrowable(wrapper)
        assertNotNull(sanitized)
        assertEquals("Worker.kt", sanitized.stackTrace[0].fileName)

        val sanitizedCause = sanitized.cause
        assertNotNull(sanitizedCause)
        assertTrue(sanitizedCause is SanitizedThrowable)
        assertEquals("java.io.IOException", sanitizedCause.originalClassName)
        assertEquals("Socket.java", sanitizedCause.stackTrace[0].fileName)
        assertFalse(sanitizedCause.message.orEmpty().contains("/tmp/root.log"))
    }

    @Test
    fun `sanitizeThrowable recursively sanitizes suppressed exceptions`() {
        val main = IllegalStateException("Primary error")
        val suppressed = RuntimeException("Suppressed detail at /Users/alice/suppressed.txt")
        suppressed.stackTrace =
            arrayOf(
                StackTraceElement("com.suppressed.Helper", "close", "/Users/alice/Helper.kt", 15),
            )
        main.addSuppressed(suppressed)

        val sanitized = LogSanitizer.sanitizeThrowable(main)
        assertNotNull(sanitized)
        assertEquals(1, sanitized.suppressed.size)

        val sanitizedSuppressed = sanitized.suppressed[0]
        assertTrue(sanitizedSuppressed is SanitizedThrowable)
        assertEquals("Helper.kt", sanitizedSuppressed.stackTrace[0].fileName)
        assertFalse(sanitizedSuppressed.message.orEmpty().contains("/Users/alice/suppressed.txt"))
    }

    @Test
    fun `sanitizeThrowable handles circular reference causes gracefully`() {
        val first = Exception("First error")
        val second = Exception("Second error", first)
        // Introduce cycle: first's cause points to second
        first.initCause(second)

        val sanitized = LogSanitizer.sanitizeThrowable(first)
        assertNotNull(sanitized)
        val cause1 = sanitized.cause
        assertNotNull(cause1)
        val cause2 = cause1.cause
        assertNotNull(cause2)
        assertEquals("[circular reference]", cause2.message)
    }

    @Test
    fun `SanitizedThrowable toString preserves original class name and message`() {
        val t = SanitizedThrowable("custom.package.SpecialException", "something failed")
        assertEquals("custom.package.SpecialException: something failed", t.toString())

        val tNoMsg = SanitizedThrowable("custom.package.SpecialException", null)
        assertEquals("custom.package.SpecialException", tNoMsg.toString())
    }
}
