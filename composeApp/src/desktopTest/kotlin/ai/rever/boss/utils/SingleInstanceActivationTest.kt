package ai.rever.boss.utils

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SingleInstanceActivationTest {
    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        SingleInstanceManager.runtimeDirOverride = File(tempDir.toFile(), "run")
        SingleInstanceManager.windowActivationHandler = null
    }

    @AfterEach
    fun tearDown() {
        SingleInstanceManager.release()
        SingleInstanceManager.runtimeDirOverride = null
        SingleInstanceManager.windowActivationHandler = null
    }

    @Test
    fun formatActivateRequestProducesExpectedWireLine() {
        val token = "a".repeat(TOKEN_HEX_LENGTH)
        val formatted = formatActivateRequest(token)
        assertEquals("$PROTOCOL_VERSION $token $VERB_ACTIVATE", formatted)
    }

    @Test
    fun parseRequestLineParsesValidActivateRequest() {
        val token = "b".repeat(TOKEN_HEX_LENGTH)
        val line = "$PROTOCOL_VERSION $token $VERB_ACTIVATE"
        val request = parseRequestLine(line)

        assertNotNull(request)
        assertEquals(token, request.token)
        assertEquals(VERB_ACTIVATE, request.verb)
        assertEquals(DeepLinkOrigin.EXTERNAL, request.origin)
        assertNull(request.url)
    }

    @Test
    fun parseRequestLineRejectsMalformedActivateRequests() {
        val token = "c".repeat(TOKEN_HEX_LENGTH)
        // Extra arguments not allowed on ACTIVATE
        assertNull(parseRequestLine("$PROTOCOL_VERSION $token $VERB_ACTIVATE extra"))
        // Unsupported protocol version
        assertNull(parseRequestLine("boss-si-99 $token $VERB_ACTIVATE"))
        // Missing token
        assertNull(parseRequestLine("$PROTOCOL_VERSION $VERB_ACTIVATE"))
        // Empty line
        assertNull(parseRequestLine(""))
    }

    @Test
    fun activateExistingInstanceReturnsFalseWhenNoInstanceRunning() {
        val activated = SingleInstanceManager.activateExistingInstance()
        assertFalse(activated)
    }

    @Test
    fun activateExistingInstanceReachesRunningInstanceAndInvokesHandler() {
        assertTrue(SingleInstanceManager.acquireLock(), "Must acquire lock")
        val latch = CountDownLatch(1)
        var handlerInvoked = false

        SingleInstanceManager.windowActivationHandler = {
            handlerInvoked = true
            latch.countDown()
        }

        val success = SingleInstanceManager.activateExistingInstance()
        assertTrue(success, "Activation request must succeed")
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Activation handler must be dispatched")
        assertTrue(handlerInvoked, "Activation handler must have been invoked")
    }

    @Test
    fun activateExistingInstanceSurvivesHandlerExceptionGracefully() {
        assertTrue(SingleInstanceManager.acquireLock(), "Must acquire lock")
        val latch = CountDownLatch(1)

        SingleInstanceManager.windowActivationHandler = {
            latch.countDown()
            error("Test simulated fault in window activation handler")
        }

        val success = SingleInstanceManager.activateExistingInstance()
        assertTrue(success, "Activation request should still acknowledge OK")
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Activation handler must be dispatched")
    }

    @Test
    fun activateExistingInstanceRefusesForgedDescriptor() {
        val runDir = File(tempDir.toFile(), "run").apply { mkdirs() }
        val deadPid = 999999999L
        val forgedDescriptor =
            InstanceDescriptor(
                transport = SingleInstanceTransport.TCP,
                endpoint = "127.0.0.1:56789",
                token = "f".repeat(TOKEN_HEX_LENGTH),
                pid = deadPid,
            )
        val descriptorFile = File(runDir, "instance.json")
        descriptorFile.writeText(forgedDescriptor.encode())

        val success = SingleInstanceManager.activateExistingInstance()
        assertFalse(success, "Must refuse to communicate with forged descriptor")
    }
}
