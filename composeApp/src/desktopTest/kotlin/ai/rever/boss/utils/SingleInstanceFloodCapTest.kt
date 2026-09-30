package ai.rever.boss.utils

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression test for issue #1326:
 * `SingleInstanceManager.handleClient` spawned one daemon thread per accepted connection
 * without bounding in-flight handlers, allowing a local flood to park an unbounded number
 * of short-lived daemon threads.
 *
 * The fix gates `handleClient` behind a semaphore bounded by `MAX_CLIENT_HANDLERS` (32).
 * Any connection accepted when all 32 handler slots are occupied is immediately closed
 * without spawning a thread.
 */
class SingleInstanceFloodCapTest {
    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun useTempRuntimeDir() {
        SingleInstanceManager.runtimeDirOverride = File(tempDir.toFile(), "run")
        SingleInstanceManager.llmTokenProviderOverride = null
    }

    @AfterEach
    fun releaseChannel() {
        SingleInstanceManager.release()
        SingleInstanceManager.llmTokenProviderOverride = null
        SingleInstanceManager.runtimeDirOverride = null
    }

    @Test
    fun `handleClient is bounded so a local flood cannot spawn unbounded threads`() {
        assertTrue(SingleInstanceManager.acquireLock(), "SingleInstanceManager failed to bind")
        val descriptor = assertNotNull(readPublishedDescriptor(), "Published descriptor must exist")
        val targetAddress = toSocketAddress(descriptor)

        val parkedThreadsBefore = parkedThreadCount()
        val heldSockets = mutableListOf<SocketChannel>()
        try {
            repeat(MAX_CLIENT_HANDLERS) {
                heldSockets += SocketChannel.open(targetAddress)
            }

            waitForSlotsExhaustion()
            assertEquals(0, SingleInstanceManager.availableClientSlots, "All client slots should be occupied")

            assertOverflowConnectionsRejected(targetAddress, count = 5)

            val parkedThreadsAfter = parkedThreadCount()
            val spawned = parkedThreadsAfter - parkedThreadsBefore
            assertTrue(
                spawned <= MAX_CLIENT_HANDLERS + 2,
                "Flood parked $spawned handler threads; expected <= ${MAX_CLIENT_HANDLERS + 2}",
            )
        } finally {
            heldSockets.forEach { runCatching { it.close() } }
        }

        waitForSlotsRecovery()
        assertEquals(
            MAX_CLIENT_HANDLERS,
            SingleInstanceManager.availableClientSlots,
            "Client handler slots must fully recover after connections close",
        )
    }

    private fun waitForSlotsExhaustion() {
        val deadline = System.currentTimeMillis() + 5000L
        while (SingleInstanceManager.availableClientSlots > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
    }

    private fun waitForSlotsRecovery() {
        val deadline = System.currentTimeMillis() + 5000L
        while (SingleInstanceManager.availableClientSlots < MAX_CLIENT_HANDLERS &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(20)
        }
    }

    private fun assertOverflowConnectionsRejected(
        targetAddress: SocketAddress,
        count: Int,
    ) {
        val overflowSockets = mutableListOf<SocketChannel>()
        try {
            repeat(count) {
                try {
                    val ch = SocketChannel.open(targetAddress)
                    ch.configureBlocking(false)
                    overflowSockets += ch
                } catch (_: IOException) {
                    // Rejected synchronously at transport layer
                }
            }

            val buf = ByteBuffer.allocate(16)
            overflowSockets.forEach { ch ->
                val readBytes = runCatching { readWithTimeout(ch, buf) }.getOrDefault(-1)
                assertEquals(-1, readBytes, "Overflow connection must be immediately closed by server")
            }
        } finally {
            overflowSockets.forEach { runCatching { it.close() } }
        }
    }

    private fun readWithTimeout(
        ch: SocketChannel,
        buf: ByteBuffer,
        timeoutMs: Long = 2000L,
    ): Int {
        val deadline = System.currentTimeMillis() + timeoutMs
        var r = ch.read(buf)
        while (r == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            r = ch.read(buf)
        }
        return r
    }

    private fun toSocketAddress(descriptor: InstanceDescriptor): SocketAddress =
        when (descriptor.transport) {
            SingleInstanceTransport.UNIX -> {
                UnixDomainSocketAddress.of(descriptor.endpoint)
            }

            SingleInstanceTransport.TCP -> {
                InetSocketAddress(InetAddress.getLoopbackAddress(), descriptor.endpoint.toInt())
            }
        }

    private fun parkedThreadCount(): Int {
        val threads = Thread.getAllStackTraces().keys
        return threads.count { it.name == "BOSS-IPC-Client-Handler" }
    }

    private fun descriptorPath(): Path = File(tempDir.toFile(), "run").toPath().resolve("single-instance")

    private fun readPublishedDescriptor(): InstanceDescriptor? {
        val path = descriptorPath()
        return if (Files.exists(path)) {
            parseInstanceDescriptor(Files.readString(path))
        } else {
            null
        }
    }
}
