package ai.rever.boss.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UrlOpenReadinessQueueTest {
    @Test
    fun `cold start retains confirmation for each URL when the queue drains`() {
        val queue = UrlOpenReadinessQueue()
        assertFalse(queue.enqueueOrClaimForCaller("https://external.example", requiresConfirmation = true))
        assertFalse(queue.enqueueOrClaimForCaller("https://operator.example", requiresConfirmation = false))
        assertTrue(queue.hasQueuedURLs())

        assertEquals(
            listOf(
                QueuedUrlOpen("https://external.example", requiresConfirmation = true),
                QueuedUrlOpen("https://operator.example", requiresConfirmation = false),
            ),
            queue.markReadyAndClaimQueued(),
        )
        assertFalse(queue.hasQueuedURLs())
        assertTrue(queue.enqueueOrClaimForCaller("https://later.example", requiresConfirmation = true))
        assertTrue(queue.markReadyAndClaimQueued().isEmpty())
    }

    @Test
    fun `enqueueOrClaim drops excess URLs when capacity limit is reached`() {
        val queue = UrlOpenReadinessQueue(maxQueued = 3)
        assertEquals(
            EnqueueResult.ENQUEUED,
            queue.enqueueOrClaim("https://site1.example", requiresConfirmation = true),
        )
        assertEquals(
            EnqueueResult.ENQUEUED,
            queue.enqueueOrClaim("https://site2.example", requiresConfirmation = false),
        )
        assertEquals(
            EnqueueResult.ENQUEUED,
            queue.enqueueOrClaim("https://site3.example", requiresConfirmation = true),
        )
        assertEquals(3, queue.size)

        // 4th request exceeds maxQueued and must be dropped
        assertEquals(
            EnqueueResult.DROPPED,
            queue.enqueueOrClaim("https://site4.example", requiresConfirmation = true),
        )
        assertEquals(3, queue.size)
        assertFalse(queue.enqueueOrClaimForCaller("https://site5.example", requiresConfirmation = false))
        assertEquals(3, queue.size)

        val drained = queue.markReadyAndClaimQueued()
        assertEquals(3, drained.size)
        assertEquals("https://site1.example", drained[0].url)
        assertEquals("https://site2.example", drained[1].url)
        assertEquals("https://site3.example", drained[2].url)
    }

    @Test
    fun `enqueueOrClaim claims immediately once service is ready`() {
        val queue = UrlOpenReadinessQueue(maxQueued = 2)
        assertEquals(
            EnqueueResult.ENQUEUED,
            queue.enqueueOrClaim("https://first.example", requiresConfirmation = true),
        )
        val initialDrain = queue.markReadyAndClaimQueued()
        assertEquals(1, initialDrain.size)

        // Now ready: any subsequent call claims immediately
        assertEquals(
            EnqueueResult.CLAIMED,
            queue.enqueueOrClaim("https://second.example", requiresConfirmation = true),
        )
        assertTrue(queue.enqueueOrClaimForCaller("https://third.example", requiresConfirmation = false))
        assertEquals(0, queue.size)
        assertFalse(queue.hasQueuedURLs())
        assertTrue(queue.markReadyAndClaimQueued().isEmpty())
    }

    @Test
    fun `default queue capacity enforces MAX_QUEUED bound`() {
        val queue = UrlOpenReadinessQueue()
        assertEquals(32, UrlOpenReadinessQueue.MAX_QUEUED)

        for (i in 1..UrlOpenReadinessQueue.MAX_QUEUED) {
            val result = queue.enqueueOrClaim("https://test.example/$i", requiresConfirmation = false)
            assertEquals(EnqueueResult.ENQUEUED, result)
        }
        assertEquals(UrlOpenReadinessQueue.MAX_QUEUED, queue.size)

        val overflowResult = queue.enqueueOrClaim("https://test.example/overflow", requiresConfirmation = false)
        assertEquals(EnqueueResult.DROPPED, overflowResult)
        assertEquals(UrlOpenReadinessQueue.MAX_QUEUED, queue.size)
    }
}
