package ai.rever.boss.utils

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketAddress
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression test for issue #1326:
 * `SingleInstanceManager.handleClient` spawned one daemon thread per accepted connection
 * without bounding in-flight handlers, allowing a local flood to park an unbounded number
 * of short-lived daemon threads.
 *
 * The fix gates `handleClient` behind a semaphore bounded by `MAX_CLIENT_HANDLERS` (32).
 * Any connection accepted when all 32 handler slots are occupied is answered with `BUSY`
 * and immediately closed without spawning a thread.
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
        SingleInstanceManager.watchdogSchedulerOverride = null
        SingleInstanceManager.connectionBudgetMsOverride = null
        SingleInstanceManager.llmTokenProviderOverride = null
        SingleInstanceManager.runtimeDirOverride = null
        waitForSlotsRecovery()
    }

    @Test
    fun `handleClient is bounded so a local flood cannot spawn unbounded threads`() {
        SingleInstanceManager.connectionBudgetMsOverride = 30_000L
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

            // Saturated instance must preserve single-instance ownership against a second launch
            val secondAcquire = SingleInstanceManager.acquireLock()
            assertFalse(secondAcquire, "Second acquireLock must fail while first host is saturated")
            val currentDescriptor = readPublishedDescriptor()
            assertNotNull(currentDescriptor)
            assertEquals(descriptor.endpoint, currentDescriptor.endpoint, "Endpoint must stay with first host")
            assertEquals(descriptor.pid, currentDescriptor.pid, "PID must stay with first host")
            assertEquals(descriptor.token, currentDescriptor.token, "Token must stay with first host")

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

        val ping = formatPingRequest(descriptor.token)
        val pong = SingleInstanceWire.exchange(descriptor, ping)
        assertEquals(RESPONSE_PONG, pong, "Server must respond with PONG after slots recovery")
    }

    @Test
    fun `failing watchdog setup does not leak socket or client slots`() {
        val failingScheduler =
            object : ScheduledExecutorService by Executors.newSingleThreadScheduledExecutor() {
                override fun schedule(
                    command: Runnable,
                    delay: Long,
                    unit: TimeUnit,
                ): ScheduledFuture<*> = throw RejectedExecutionException("Watchdog scheduling rejected")
            }
        SingleInstanceManager.watchdogSchedulerOverride = failingScheduler
        try {
            assertTrue(SingleInstanceManager.acquireLock(), "Failed to bind")
            val descriptor = assertNotNull(readPublishedDescriptor(), "Published descriptor must exist")
            val targetAddress = toSocketAddress(descriptor)

            val ch = SocketChannel.open(targetAddress)
            ch.configureBlocking(false)
            val buf = ByteBuffer.allocate(16)
            val readBytes = readWithTimeout(ch, buf, timeoutMs = 2000L)
            assertEquals(-1, readBytes, "Socket must be closed when watchdog setup fails")
            ch.close()

            waitForSlotsRecovery()
            assertEquals(
                MAX_CLIENT_HANDLERS,
                SingleInstanceManager.availableClientSlots,
                "Slots must recover even if watchdog setup throws",
            )
        } finally {
            failingScheduler.shutdownNow()
            SingleInstanceManager.watchdogSchedulerOverride = null
        }
    }

    @Test
    fun `accepted connection with empty or partial reply preserves single-instance ownership`() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val dummyPort = server.localPort
        val descriptor =
            InstanceDescriptor(
                transport = SingleInstanceTransport.TCP,
                endpoint = dummyPort.toString(),
                token = newChannelToken(),
                pid = ProcessHandle.current().pid(),
            )

        val acceptThread =
            kotlin.concurrent.thread(isDaemon = true) {
                while (!server.isClosed) {
                    try {
                        val client = server.accept()
                        // Accept and close immediately without sending PONG
                        client.close()
                    } catch (_: IOException) {
                        break
                    }
                }
            }

        try {
            val probe = SingleInstanceWire.probeInstance(descriptor)
            assertEquals(
                SingleInstanceProbe.CONNECTED_NO_REPLY,
                probe,
                "Accepted connection without reply must probe as CONNECTED_NO_REPLY",
            )

            // When published on disk, acquireLock must NOT reclaim the descriptor
            Files.createDirectories(descriptorPath().parent)
            Files.writeString(descriptorPath(), descriptor.encode())
            assertTrue(SingleInstanceManager.isAnotherInstanceRunning(), "Instance should be considered running")
            val acquired = SingleInstanceManager.acquireLock()
            assertFalse(acquired, "Must not reclaim descriptor from an active endpoint that gives no reply")
            val published = readPublishedDescriptor()
            assertNotNull(published)
            assertEquals(descriptor.token, published.token, "Descriptor token must remain unchanged")
        } finally {
            runCatching { server.close() }
            acceptThread.join(1000L)
        }
    }

    @Test
    fun `rejection under failing watchdog scheduler still rejects with BUSY without leaking permits`() {
        SingleInstanceManager.connectionBudgetMsOverride = 30_000L
        assertTrue(SingleInstanceManager.acquireLock(), "SingleInstanceManager failed to bind")
        val descriptor = assertNotNull(readPublishedDescriptor(), "Published descriptor must exist")
        val targetAddress = toSocketAddress(descriptor)

        val failingScheduler =
            object : ScheduledExecutorService by Executors.newSingleThreadScheduledExecutor() {
                override fun schedule(
                    command: Runnable,
                    delay: Long,
                    unit: TimeUnit,
                ): ScheduledFuture<*> = throw RejectedExecutionException("Watchdog scheduling rejected")
            }

        val heldSockets = mutableListOf<SocketChannel>()
        try {
            repeat(MAX_CLIENT_HANDLERS) {
                heldSockets += SocketChannel.open(targetAddress)
            }
            waitForSlotsExhaustion()
            assertEquals(0, SingleInstanceManager.availableClientSlots, "All client slots should be occupied")

            SingleInstanceManager.watchdogSchedulerOverride = failingScheduler
            assertOverflowConnectionsRejected(targetAddress, count = 3)
        } finally {
            heldSockets.forEach { runCatching { it.close() } }
            failingScheduler.shutdownNow()
            SingleInstanceManager.watchdogSchedulerOverride = null
        }

        waitForSlotsRecovery()
        assertEquals(
            MAX_CLIENT_HANDLERS,
            SingleInstanceManager.availableClientSlots,
            "Slots must fully recover after held connections close",
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

            overflowSockets.forEach { ch ->
                val buf = ByteBuffer.allocate(32)
                val readBytes = runCatching { readWithTimeout(ch, buf) }.getOrDefault(-1)
                assertTrue(readBytes > 0, "Must read BUSY response bytes before connection is closed")
                val msg = String(buf.array(), 0, readBytes, StandardCharsets.UTF_8).trim()
                assertEquals(RESPONSE_BUSY, msg, "Overflow connection must receive BUSY")
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
