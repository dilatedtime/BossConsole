package ai.rever.boss.plugin.browser

import ai.rever.boss.utils.PinchZoomAccumulator
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.teamdev.jxbrowser.engine.RenderingMode
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins handle-side pinch gesture coordination: document epoch invalidation, out-of-order
 * sequence protection, timeout budgeting, and geometry gating without native Chromium.
 */
class BrowserPinchCoordinatorTest {
    private class TestHarness(
        var renderingMode: RenderingMode = RenderingMode.HARDWARE_ACCELERATED,
        var isValid: Boolean = true,
        var isHovered: Boolean = true,
        var pointerLogical: Offset? = Offset(100f, 100f),
        var boundsPx: Rect? = Rect(0f, 0f, 200f, 200f),
        var density: Float = 1f,
        var pointerInsideBrowser: Boolean? = true,
        val zoomInCalls: AtomicInteger = AtomicInteger(0),
        val zoomOutCalls: AtomicInteger = AtomicInteger(0),
        val logMessages: MutableList<Pair<String, Map<String, String>>> = mutableListOf(),
        var scriptExecutor: (String, (Boolean) -> Unit, () -> Boolean) -> Unit = { _, answer, _ -> answer(false) },
        val accumulator: PinchZoomAccumulator = PinchZoomAccumulator(threshold = 0.15),
        var currentTimeNs: Long = 1_000_000_000L,
    ) {
        val coordinator: BrowserPinchCoordinator =
            BrowserPinchCoordinator(
                handleId = "test-handle",
                environment =
                    BrowserPinchEnvironment(
                        renderingMode = { renderingMode },
                        isValid = { isValid },
                        isHovered = { isHovered },
                        getPointerLogical = { pointerLogical },
                        getBoundsPx = { boundsPx },
                        getDensity = { density },
                        isPointerInsideBrowserView = { pointerInsideBrowser },
                    ),
                actions =
                    BrowserPinchActions(
                        executeScript = { script, answer, isStale -> scriptExecutor(script, answer, isStale) },
                        zoomIn = { zoomInCalls.incrementAndGet() },
                        zoomOut = { zoomOutCalls.incrementAndGet() },
                        dispatchToEdt = { it.run() },
                        onLogDebug = { msg, params -> logMessages += msg to params },
                    ),
                pinchZoomAccumulator = accumulator,
                timeSourceNs = { currentTimeNs },
            )
    }

    // --- worthReadingPointer: fast-path rejection of non-gestures ---

    @Test
    fun `worthReadingPointer rejects zero, negative zero, and non-finite magnifications`() {
        val harness = TestHarness()
        assertFalse(harness.coordinator.worthReadingPointer(0.0))
        assertFalse(harness.coordinator.worthReadingPointer(-0.0))
        assertFalse(harness.coordinator.worthReadingPointer(Double.NaN))
        assertFalse(harness.coordinator.worthReadingPointer(Double.POSITIVE_INFINITY))
        assertFalse(harness.coordinator.worthReadingPointer(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun `worthReadingPointer rejects subnormal magnifications below the minimum threshold`() {
        val harness = TestHarness()
        assertFalse(harness.coordinator.worthReadingPointer(1e-8))
        assertFalse(harness.coordinator.worthReadingPointer(-1e-8))
        assertFalse(harness.coordinator.worthReadingPointer(Double.MIN_VALUE))
    }

    @Test
    fun `worthReadingPointer accepts genuine gesture deltas when handle is valid`() {
        val harness = TestHarness()
        assertTrue(harness.coordinator.worthReadingPointer(0.02))
        assertTrue(harness.coordinator.worthReadingPointer(-0.02))
    }

    @Test
    fun `worthReadingPointer rejects movements when handle is invalid`() {
        val harness = TestHarness(isValid = false)
        assertFalse(harness.coordinator.worthReadingPointer(0.02))
    }

    // --- Document Epoch Invalidation: stale answers across navigation ---

    @Test
    fun `in-flight offers initiated before navigation are discarded once navigation occurs`() {
        var pendingAnswer: ((Boolean) -> Unit)? = null
        val harness =
            TestHarness(
                scriptExecutor = { _, answer, _ -> pendingAnswer = answer },
            )

        harness.coordinator.onPinchMagnify(0.05)
        assertEquals(0L, harness.coordinator.currentDocumentEpoch)

        // Navigation occurs while the offer is in-flight.
        harness.coordinator.onNavigation()
        assertEquals(1L, harness.coordinator.currentDocumentEpoch)

        // Delayed offer completes from the previous page with CLAIMED.
        pendingAnswer?.invoke(true)

        // Stale answer from epoch 0 must NOT set lastPinchClaimed or affect the new document.
        assertNull(harness.coordinator.currentLastPinchClaimed)
        assertEquals(0, harness.coordinator.currentUnansweredPinchOffers)
    }

    @Test
    fun `navigation resets partial zoom accumulator deltas on the EDT`() {
        val harness = TestHarness()

        // Page declines first delta; partial sum accumulates.
        harness.coordinator.onPinchMagnify(0.10)
        assertEquals(0, harness.zoomInCalls.get())

        // Navigation occurs, clearing partial accumulator deltas.
        harness.coordinator.onNavigation()

        // Next delta on the new document is below threshold and should not trip zoom.
        harness.coordinator.onPinchMagnify(0.06)
        assertEquals(0, harness.zoomInCalls.get())
    }

    @Test
    fun `render process termination advances epoch and resets pinch state`() {
        var pendingAnswer: ((Boolean) -> Unit)? = null
        val harness =
            TestHarness(
                scriptExecutor = { _, answer, _ -> pendingAnswer = answer },
            )

        harness.coordinator.onPinchMagnify(0.05)
        harness.coordinator.onRenderProcessTerminated()

        assertEquals(1L, harness.coordinator.currentDocumentEpoch)
        pendingAnswer?.invoke(true)
        assertNull(harness.coordinator.currentLastPinchClaimed)
    }

    // --- Out-of-Order Sequence Tagging: claim arrivals across direction changes ---

    @Test
    fun `out-of-order claimed answer does not overwrite newer processed answers`() {
        val harness = TestHarness()

        // Newer sequence 2 arrives as DECLINED.
        val resolvedSeq2 = harness.coordinator.resolvePinchClaim(sequence = 2L, result = PinchAnswer.DECLINED)
        assertFalse(resolvedSeq2)
        assertEquals(false, harness.coordinator.currentLastPinchClaimed)

        // Older sequence 1 arrives late as CLAIMED.
        val resolvedSeq1 = harness.coordinator.resolvePinchClaim(sequence = 1L, result = PinchAnswer.CLAIMED)
        assertFalse(resolvedSeq1)

        // The current document claim status remains false (seq 2 was newer than seq 1).
        assertEquals(false, harness.coordinator.currentLastPinchClaimed)
    }

    @Test
    fun `out-of-order claimed answer does not wipe accumulator deltas from later sequence`() {
        val harness = TestHarness()

        // Offer 2 was answered as DECLINED and added 0.10 to accumulator.
        harness.coordinator.onPinchAnswer(
            sequence = 2L,
            offerEpoch = 0L,
            magnification = 0.10,
            result = PinchAnswer.DECLINED,
        )

        // Stale Offer 1 arrives later as CLAIMED.
        harness.coordinator.onPinchAnswer(
            sequence = 1L,
            offerEpoch = 0L,
            magnification = 0.05,
            result = PinchAnswer.CLAIMED,
        )

        // Subsequent small delta (0.06) should combine with 0.10 to cross threshold (0.15)
        // proving the stale claim did not wipe the accumulator.
        harness.coordinator.onPinchAnswer(
            sequence = 3L,
            offerEpoch = 0L,
            magnification = 0.06,
            result = PinchAnswer.DECLINED,
        )
        assertEquals(1, harness.zoomInCalls.get())
    }

    // --- Timeout Budgeting: hung renderer fallback vs active canvas ---

    @Test
    fun `timeouts up to the budget retain page claim, but past the budget fall back to page zoom`() {
        val harness = TestHarness()

        // Page initially claims the pinch.
        harness.coordinator.resolvePinchClaim(sequence = 1L, result = PinchAnswer.CLAIMED)
        assertEquals(true, harness.coordinator.currentLastPinchClaimed)

        // 16 timeouts in a row (MAX_UNANSWERED_PINCH_CLAIMS = 16) stay claimed.
        repeat(16) { i ->
            val seq = 2L + i
            val claimed = harness.coordinator.resolvePinchClaim(sequence = seq, result = PinchAnswer.TIMED_OUT)
            assertTrue(claimed)
        }
        assertEquals(16, harness.coordinator.currentUnansweredPinchOffers)

        // 17th consecutive timeout exceeds the budget and falls back to page zoom (returns false).
        val fallbackClaim = harness.coordinator.resolvePinchClaim(sequence = 18L, result = PinchAnswer.TIMED_OUT)
        assertFalse(fallbackClaim)
    }

    @Test
    fun `skipped offers do not consume the unanswered timeout budget`() {
        val harness = TestHarness()

        harness.coordinator.resolvePinchClaim(sequence = 1L, result = PinchAnswer.CLAIMED)
        assertEquals(true, harness.coordinator.currentLastPinchClaimed)

        // 20 skipped offers arrive due to buffer backpressure.
        repeat(20) { i ->
            val claimed = harness.coordinator.resolvePinchClaim(sequence = 2L + i, result = PinchAnswer.SKIPPED)
            assertTrue(claimed)
        }
        // Budget counter remains 0 because skips are backpressure, not renderer timeouts.
        assertEquals(0, harness.coordinator.currentUnansweredPinchOffers)
    }

    @Test
    fun `a definite claim or decline resets the unanswered timeout count`() {
        val harness = TestHarness()

        harness.coordinator.resolvePinchClaim(sequence = 1L, result = PinchAnswer.CLAIMED)
        repeat(10) { i ->
            harness.coordinator.resolvePinchClaim(sequence = 2L + i, result = PinchAnswer.TIMED_OUT)
        }
        assertEquals(10, harness.coordinator.currentUnansweredPinchOffers)

        harness.coordinator.resolvePinchClaim(sequence = 12L, result = PinchAnswer.CLAIMED)
        assertEquals(0, harness.coordinator.currentUnansweredPinchOffers)
    }

    // --- Re-gating on EDT and discrete zoom steps ---

    @Test
    fun `zoom steps fire when accumulated deltas cross threshold`() {
        val harness = TestHarness()

        harness.coordinator.onPinchAnswer(
            sequence = 1L,
            offerEpoch = 0L,
            magnification = 0.10,
            result = PinchAnswer.DECLINED,
        )
        assertEquals(0, harness.zoomInCalls.get())

        harness.coordinator.onPinchAnswer(
            sequence = 2L,
            offerEpoch = 0L,
            magnification = 0.06,
            result = PinchAnswer.DECLINED,
        )
        assertEquals(1, harness.zoomInCalls.get())
        assertEquals(0, harness.zoomOutCalls.get())

        // Negative deltas trigger zoomOut
        harness.coordinator.onPinchAnswer(
            sequence = 3L,
            offerEpoch = 0L,
            magnification = -0.16,
            result = PinchAnswer.DECLINED,
        )
        assertEquals(1, harness.zoomOutCalls.get())
    }

    @Test
    fun `zoom steps are suppressed if pointer left the browser view before EDT execution`() {
        val harness = TestHarness(pointerInsideBrowser = false)

        harness.coordinator.onPinchAnswer(
            sequence = 1L,
            offerEpoch = 0L,
            magnification = 0.20,
            result = PinchAnswer.DECLINED,
        )
        assertEquals(0, harness.zoomInCalls.get())
    }
}
