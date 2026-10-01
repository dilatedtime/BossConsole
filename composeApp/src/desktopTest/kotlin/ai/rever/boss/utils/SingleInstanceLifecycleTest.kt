package ai.rever.boss.utils

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SingleInstanceLifecycleTest {
    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        SingleInstanceManager.runtimeDirOverride = File(tempDir.toFile(), "run")
        SingleInstanceManager.llmTokenProviderOverride = null
    }

    @AfterEach
    fun tearDown() {
        SingleInstanceManager.release()
        SingleInstanceManager.runtimeDirOverride = null
        SingleInstanceManager.acceptNextClientOverride = null
        SingleInstanceManager.beforeTeardownFaultedListenerForTest = null
    }

    @Test
    fun `stale withdraw does not delete new owner descriptor or socket`() {
        assertTrue(SingleInstanceManager.acquireLock(), "First launch must acquire lock")
        val descA = assertNotNull(SingleInstanceManager.publishedInstanceDescriptor, "First descriptor must exist")
        SingleInstanceManager.release()

        assertTrue(SingleInstanceManager.acquireLock(), "Second launch must acquire lock")
        val descB = assertNotNull(SingleInstanceManager.publishedInstanceDescriptor, "Second descriptor must exist")
        assertTrue(descA.token != descB.token, "New launch must have a fresh channel token")

        SingleInstanceManager.withdrawForTest(descA)

        val onDisk = readPublishedDescriptor()
        assertNotNull(onDisk, "Descriptor file must not be deleted by stale withdraw")
        assertEquals(descB.token, onDisk.token, "Descriptor of active owner must remain intact")
        if (descB.transport == SingleInstanceTransport.UNIX) {
            assertTrue(File(descB.endpoint).exists(), "Socket file of active owner must remain intact")
        }

        SingleInstanceManager.release()
    }

    @Test
    fun `retained owner publication failure cleans up without overlapping file lock or wedged join`() {
        assertTrue(SingleInstanceManager.acquireLock(), "First launch must acquire lock and become owner")
        assertTrue(SingleInstanceManager.isInstanceOwner, "Must be instance owner")

        val runDir = File(tempDir.toFile(), "run")
        val blockerDir = File(runDir, "single-instance.tmp")
        Files.createDirectories(blockerDir.toPath())
        val blockerFile = File(blockerDir, "blocker")
        Files.createFile(blockerFile.toPath())

        Files.deleteIfExists(descriptorPath())

        try {
            val acquireStart = System.nanoTime()
            val reacquired = SingleInstanceManager.acquireLock()
            val acquireDurationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acquireStart)
            assertFalse(reacquired, "Re-acquire must fail cleanly when publication fails")
            assertTrue(
                acquireDurationMs < 800L,
                "Failing acquireLock must not stall joining listener; took ${acquireDurationMs}ms",
            )
            assertFalse(SingleInstanceManager.isInstanceOwner, "Must not be owner after publication failure")
            assertNull(SingleInstanceFiles.read(), "Descriptor must be absent after cleanup")
            val socketKey =
                try {
                    val attrs =
                        Files.readAttributes(
                            SingleInstanceFiles.socketFile.toPath(),
                            BasicFileAttributes::class.java,
                        )
                    attrs.fileKey()
                } catch (_: Exception) {
                    null
                }
            if (socketKey != null) {
                assertFalse(
                    SingleInstanceFiles.socketFile.exists(),
                    "Socket file must be unlinked after publication failure cleanup",
                )
            }
        } finally {
            Files.deleteIfExists(blockerFile.toPath())
            Files.deleteIfExists(blockerDir.toPath())
        }

        assertTrue(SingleInstanceManager.acquireLock(), "Subsequent acquire must succeed cleanly")
        assertTrue(SingleInstanceManager.isInstanceOwner, "Must be instance owner after recovery")
        SingleInstanceManager.release()
    }

    @Test
    fun `stale listener epoch teardown in accept loop does not clobber new server channel or descriptor`() {
        assertTrue(SingleInstanceManager.acquireLock(), "First launch must acquire lock")
        val descA = assertNotNull(SingleInstanceManager.publishedInstanceDescriptor, "First descriptor must exist")
        val epochA = SingleInstanceManager.listenerEpochForTest

        val acceptFaultPaused = CountDownLatch(1)
        val allowTeardownProceed = CountDownLatch(1)
        val staleListenerThread = AtomicReference<Thread>()

        SingleInstanceManager.beforeTeardownFaultedListenerForTest = { epoch ->
            if (epoch == epochA) {
                staleListenerThread.set(Thread.currentThread())
                acceptFaultPaused.countDown()
                allowTeardownProceed.await(5, TimeUnit.SECONDS)
            }
        }

        SingleInstanceManager.acceptNextClientOverride = { _, _ -> null }
        runCatching { SocketChannel.open(toSocketAddress(descA)).close() }

        assertTrue(acceptFaultPaused.await(5, TimeUnit.SECONDS), "Accept loop must pause before teardown")

        SingleInstanceManager.release()
        assertTrue(SingleInstanceManager.acquireLock(), "Second launch must acquire lock with bumped epoch")
        val descB = assertNotNull(SingleInstanceManager.publishedInstanceDescriptor, "Second descriptor must exist")
        assertTrue(descA.token != descB.token, "Tokens must differ")
        val epochB = SingleInstanceManager.listenerEpochForTest
        assertTrue(epochB > epochA, "Epoch must have been bumped")

        allowTeardownProceed.countDown()
        staleListenerThread.get()?.join(2000L)

        assertTrue(SingleInstanceManager.isListening, "Active instance must remain listening")
        assertEquals(descB.token, readPublishedDescriptor()?.token, "Active descriptor must remain published")
        assertEquals(descB.token, SingleInstanceManager.publishedInstanceDescriptor?.token)

        SingleInstanceManager.release()
    }

    @Test
    fun `faulted listener teardown on real listener thread does not stall acquireLock or release`() {
        assertTrue(SingleInstanceManager.acquireLock(), "Initial launch must succeed")
        val desc = assertNotNull(SingleInstanceManager.publishedInstanceDescriptor)

        val teardownBlocked = CountDownLatch(1)
        val teardownProceed = CountDownLatch(1)

        SingleInstanceManager.beforeTeardownFaultedListenerForTest = { _ ->
            teardownBlocked.countDown()
            teardownProceed.await(5, TimeUnit.SECONDS)
        }

        SingleInstanceManager.acceptNextClientOverride = { _, _ -> null }
        runCatching { SocketChannel.open(toSocketAddress(desc)).close() }

        assertTrue(teardownBlocked.await(5, TimeUnit.SECONDS), "Real listener thread must enter fault path")

        kotlin.concurrent.thread(isDaemon = true) {
            Thread.sleep(50L)
            teardownProceed.countDown()
        }
        val releaseStart = System.nanoTime()
        SingleInstanceManager.release()
        val releaseDurationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - releaseStart)

        assertTrue(
            releaseDurationMs < 800L,
            "release() must not stall for the 1000ms join timeout; took ${releaseDurationMs}ms",
        )
    }

    @Test
    fun `concurrent caller serializes cross-process lock and holds real file lock`() {
        val lockHeldLatch = CountDownLatch(1)
        val lockCanReleaseLatch = CountDownLatch(1)
        val lockAcquiredByThread2 = CountDownLatch(1)

        val lockThread =
            kotlin.concurrent.thread(name = "test-proc1-lock", isDaemon = true) {
                SingleInstanceFiles.withCrossProcessLock {
                    lockHeldLatch.countDown()
                    lockCanReleaseLatch.await(5, TimeUnit.SECONDS)
                }
            }

        try {
            assertTrue(lockHeldLatch.await(5, TimeUnit.SECONDS), "Thread 1 must acquire cross-process lock")

            // Discriminating proof that a real OS FileLock is held: another channel in the same JVM
            // attempting to lock the region throws OverlappingFileLockException.
            val probeChannel =
                FileChannel.open(
                    SingleInstanceFiles.lifecycleLockFile.toPath(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                )
            try {
                assertThrows<OverlappingFileLockException> {
                    probeChannel.tryLock()
                }
            } finally {
                probeChannel.close()
            }

            val lockWaiterThread =
                kotlin.concurrent.thread(name = "test-proc2-lock", isDaemon = true) {
                    SingleInstanceFiles.withCrossProcessLock {
                        lockAcquiredByThread2.countDown()
                    }
                }

            assertFalse(
                lockAcquiredByThread2.await(200, TimeUnit.MILLISECONDS),
                "Thread 2 must wait while Thread 1 holds cross-process lock",
            )

            lockCanReleaseLatch.countDown()
            assertTrue(lockAcquiredByThread2.await(5, TimeUnit.SECONDS), "Thread 2 must acquire lock after release")
            lockThread.join(2000L)
            lockWaiterThread.join(2000L)
        } finally {
            lockCanReleaseLatch.countDown()
        }
    }

    @Test
    fun `stale descriptor withdraw fails closed preserving socket on missing or mismatched key`() {
        val runDir = File(tempDir.toFile(), "run")
        Files.createDirectories(runDir.toPath())
        val socketPath = File(runDir, "test-uds.sock").toPath()
        Files.writeString(socketPath, "dummy-socket-content")

        val currentKey =
            try {
                Files.readAttributes(socketPath, BasicFileAttributes::class.java).fileKey()
            } catch (_: Exception) {
                null
            }

        val missingKeyDesc =
            InstanceDescriptor(
                transport = SingleInstanceTransport.UNIX,
                endpoint = socketPath.toString(),
                token = newChannelToken(),
                pid = ProcessHandle.current().pid(),
                socketFileKey = null,
            )
        SingleInstanceManager.withdrawForTest(missingKeyDesc)
        assertTrue(Files.exists(socketPath), "Socket must be preserved when bound key is missing (fail closed)")

        val mismatchedKeyDesc =
            InstanceDescriptor(
                transport = SingleInstanceTransport.UNIX,
                endpoint = socketPath.toString(),
                token = newChannelToken(),
                pid = ProcessHandle.current().pid(),
                socketFileKey = "different-inode-token",
            )
        SingleInstanceManager.withdrawForTest(mismatchedKeyDesc)
        assertTrue(Files.exists(socketPath), "Socket must be preserved on inode mismatch")

        assertFalse(Files.exists(SingleInstanceFiles.descriptorFile.toPath()))
        SingleInstanceManager.withdrawForTest(mismatchedKeyDesc)
        assertTrue(Files.exists(socketPath), "Socket must be preserved when descriptor absent and key mismatches")

        verifyPositiveMatchingKeyOrPreserve(currentKey, socketPath)

        // Exercise real Unix domain socket rebind and replacement if supported on this runtime
        verifyRealUnixSocketReplacementIfSupported(runDir)
    }

    private fun verifyRealUnixSocketReplacementIfSupported(runDir: File) {
        val realSocketPath = File(runDir, "real-replacement.sock").toPath()
        try {
            val server1 = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            server1.bind(UnixDomainSocketAddress.of(realSocketPath))
            val key1 = Files.readAttributes(realSocketPath, BasicFileAttributes::class.java).fileKey()
            server1.close()
            Files.deleteIfExists(realSocketPath)

            val server2 = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            server2.bind(UnixDomainSocketAddress.of(realSocketPath))
            val key2 = Files.readAttributes(realSocketPath, BasicFileAttributes::class.java).fileKey()
            try {
                if (key1 != null && key2 != null && key1 != key2) {
                    val staleDesc =
                        InstanceDescriptor(
                            transport = SingleInstanceTransport.UNIX,
                            endpoint = realSocketPath.toString(),
                            token = newChannelToken(),
                            pid = ProcessHandle.current().pid(),
                            socketFileKey = key1,
                        )
                    SingleInstanceManager.withdrawForTest(staleDesc)
                    assertTrue(Files.exists(realSocketPath), "Live socket with key2 must survive withdraw of key1")
                }
            } finally {
                server2.close()
                Files.deleteIfExists(realSocketPath)
            }
        } catch (_: UnsupportedOperationException) {
            // Unix domain sockets not supported on this platform/kernel
        } catch (_: IOException) {
            // Environment restricted Unix domain socket creation
        }
    }

    private fun verifyPositiveMatchingKeyOrPreserve(
        currentKey: Any?,
        socketPath: Path,
    ) {
        if (currentKey != null) {
            val matchingKeyDesc =
                InstanceDescriptor(
                    transport = SingleInstanceTransport.UNIX,
                    endpoint = socketPath.toString(),
                    token = newChannelToken(),
                    pid = ProcessHandle.current().pid(),
                    socketFileKey = currentKey,
                )
            SingleInstanceManager.withdrawForTest(matchingKeyDesc)
            assertFalse(Files.exists(socketPath), "Socket must be unlinked when file key matches positively")
        } else {
            val fakeMatchingDesc =
                InstanceDescriptor(
                    transport = SingleInstanceTransport.UNIX,
                    endpoint = socketPath.toString(),
                    token = newChannelToken(),
                    pid = ProcessHandle.current().pid(),
                    socketFileKey = "any-key",
                )
            SingleInstanceManager.withdrawForTest(fakeMatchingDesc)
            assertTrue(Files.exists(socketPath), "Socket must be preserved when OS does not expose fileKey")
            Files.deleteIfExists(socketPath)
        }
    }

    private fun toSocketAddress(descriptor: InstanceDescriptor): SocketAddress =
        when (descriptor.transport) {
            SingleInstanceTransport.UNIX -> {
                UnixDomainSocketAddress.of(descriptor.endpoint)
            }

            SingleInstanceTransport.TCP -> {
                val port = descriptor.endpoint.substringAfter(':').toInt()
                InetSocketAddress(InetAddress.getByName("127.0.0.1"), port)
            }
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
