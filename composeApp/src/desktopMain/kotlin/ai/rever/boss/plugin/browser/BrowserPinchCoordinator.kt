package ai.rever.boss.plugin.browser

import ai.rever.boss.utils.PinchZoomAccumulator
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.teamdev.jxbrowser.engine.RenderingMode
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities
import kotlin.math.abs

/**
 * Environment queries for trackpad pinch coordinate reconciliation.
 */
internal data class BrowserPinchEnvironment(
    val renderingMode: () -> RenderingMode,
    val isValid: () -> Boolean,
    val isHovered: () -> Boolean,
    val getPointerLogical: () -> Offset?,
    val getBoundsPx: () -> Rect?,
    val getDensity: () -> Float,
    val isPointerInsideBrowserView: () -> Boolean?,
)

/**
 * Callbacks invoked during pinch offers, zoom actions, and dispatching.
 */
internal data class BrowserPinchActions(
    val executeScript: (script: String, onAnswer: (Boolean) -> Unit, isStale: () -> Boolean) -> Unit,
    val zoomIn: () -> Unit,
    val zoomOut: () -> Unit,
    val dispatchToEdt: (Runnable) -> Unit = { SwingUtilities.invokeLater(it) },
    val onLogDebug: (message: String, params: Map<String, String>) -> Unit = { _, _ -> },
)

/**
 * Coordinates macOS trackpad pinch gestures for a browser view.
 *
 * Reconciles native pointer geometry against Compose layout bounds, offers pinch deltas
 * as synthetic Ctrl+wheel DOM events to the loaded document, and smooths declined deltas
 * into discrete page zoom steps.
 *
 * Protects against out-of-order claim arrivals, navigation epoch races, and hung renderers
 * using sequence tagging, document epochs, and an unanswered offer budget.
 */
internal class BrowserPinchCoordinator(
    private val handleId: String,
    private val environment: BrowserPinchEnvironment,
    private val actions: BrowserPinchActions,
    private val pinchOffers: PinchOffers =
        PinchOffers(maxPending = MAX_PENDING_PINCH_OFFERS, deadlineMs = PINCH_OFFER_DEADLINE_MS),
    private val pinchZoomAccumulator: PinchZoomAccumulator = PinchZoomAccumulator(),
    private val timeSourceNs: () -> Long = { System.nanoTime() },
) {
    // Current document epoch. Monotonically incremented on navigation or render process
    // termination to invalidate in-flight offers belonging to previous documents.
    private val documentEpoch = AtomicLong(0)

    // Strictly monotonic sequence tracking to prevent out-of-order offer arrivals from
    // wiping newer deltas or clobbering updated claim statuses.
    private val highestProcessedSequence = AtomicLong(0)

    // The page's last answer to a pinch offer. Carried over slow frames, cleared on navigation.
    @Volatile private var lastPinchClaimed: Boolean? = null

    // Unanswered offers in a row since the page last answered.
    private val unansweredPinchOffers = AtomicInteger(0)

    val currentDocumentEpoch: Long
        get() = documentEpoch.get()

    val currentLastPinchClaimed: Boolean?
        get() = lastPinchClaimed

    val currentUnansweredPinchOffers: Int
        get() = unansweredPinchOffers.get()

    /**
     * One macOS pinch delta arriving from the window-wide gesture listener.
     */
    fun onPinchMagnify(magnification: Double) {
        if (!worthReadingPointer(magnification)) return
        val pointer = environment.getPointerLogical()
        val bounds = environment.getBoundsPx()
        val density = environment.getDensity()
        val geometric =
            if (pointer != null && bounds != null) pointerInsideBounds(bounds, pointer, density) else null
        if (!pinchGateOpen(geometric)) {
            logPinchSuppressed(magnification, geometric)
            return
        }
        val fraction =
            if (pointer != null && bounds != null) pointerFractionInBounds(bounds, pointer, density) else null
        val script = BrowserPinchScript.dispatch(magnification, fraction?.x?.toDouble(), fraction?.y?.toDouble())
        val capturedEpoch = documentEpoch.get()
        pinchOffers.offer(
            send = { answer, isStale -> actions.executeScript(script, answer, isStale) },
            onAnswer = { sequence, answer -> onPinchAnswer(sequence, capturedEpoch, magnification, answer) },
        )
    }

    /**
     * Rejects zero, -0.0, non-finite, and subnormal magnifications before computing geometry.
     */
    fun worthReadingPointer(magnification: Double): Boolean {
        if (!magnification.isFinite() || abs(magnification) < MIN_PINCH_MAGNIFICATION) {
            return false
        }
        val gateAllows =
            (pinchGateUsesGeometry(environment.renderingMode()) && environment.isValid()) || pinchGateOpen(null)
        if (!gateAllows) logPinchSuppressed(magnification, null)
        return gateAllows
    }

    fun pinchGateOpen(pointerInsideBounds: Boolean?): Boolean =
        shouldAllowPinch(
            mode = environment.renderingMode(),
            isValid = environment.isValid(),
            pointerOverComposeView = environment.isHovered(),
            pointerInsideBounds = pointerInsideBounds,
        )

    /**
     * Handles the offer completion for a specific sequence number and document epoch.
     */
    internal fun onPinchAnswer(
        sequence: Long,
        offerEpoch: Long,
        magnification: Double,
        result: PinchAnswer,
    ) {
        if (offerEpoch != documentEpoch.get()) return

        val claimed = resolvePinchClaim(sequence, result)
        actions.dispatchToEdt {
            if (offerEpoch != documentEpoch.get() || sequence < highestProcessedSequence.get()) return@dispatchToEdt

            if (claimed) {
                pinchZoomAccumulator.reset()
                return@dispatchToEdt
            }
            if (!pinchGateOpen(environment.isPointerInsideBrowserView())) return@dispatchToEdt
            when (pinchZoomAccumulator.add(magnification)) {
                PinchZoomAccumulator.Step.IN -> actions.zoomIn()
                PinchZoomAccumulator.Step.OUT -> actions.zoomOut()
                null -> Unit
            }
        }
    }

    /**
     * Resolves whether an offer outcome constitutes a claim by the page.
     */
    internal fun resolvePinchClaim(
        sequence: Long,
        result: PinchAnswer,
    ): Boolean {
        val answer =
            when (result) {
                PinchAnswer.CLAIMED -> true
                PinchAnswer.DECLINED -> false
                PinchAnswer.TIMED_OUT, PinchAnswer.SKIPPED -> null
            }
        if (answer == null) {
            if (result == PinchAnswer.TIMED_OUT) unansweredPinchOffers.incrementAndGet()
            return lastPinchClaimed == true && unansweredPinchOffers.get() <= MAX_UNANSWERED_PINCH_CLAIMS
        }
        unansweredPinchOffers.set(0)
        val isNewer = sequence >= highestProcessedSequence.get()
        if (isNewer) {
            highestProcessedSequence.set(sequence)
            if (lastPinchClaimed != answer) {
                lastPinchClaimed = answer
                actions.onLogDebug(
                    if (answer) "Page claimed pinch" else "Page declined pinch, using page zoom",
                    mapOf("handleId" to handleId),
                )
            }
        }
        return if (isNewer) answer else (lastPinchClaimed ?: answer)
    }

    /**
     * Invalidates in-flight offers, advances the epoch, and resets accumulators upon navigation
     * or renderer termination.
     */
    fun onNavigation() {
        documentEpoch.incrementAndGet()
        lastPinchClaimed = null
        unansweredPinchOffers.set(0)
        highestProcessedSequence.set(0)
        actions.dispatchToEdt { pinchZoomAccumulator.reset() }
    }

    /** Alias for [onNavigation] during renderer termination. */
    fun onRenderProcessTerminated() {
        onNavigation()
    }

    private fun logPinchSuppressed(
        magnification: Double,
        geometric: Boolean?,
    ) {
        val skipped = pinchSuppressedSinceLog.incrementAndGet()
        val now = timeSourceNs()
        val last = pinchSuppressedLoggedAt.get()
        if (now - last < PINCH_SUPPRESSED_LOG_INTERVAL_NS ||
            !pinchSuppressedLoggedAt.compareAndSet(last, now)
        ) {
            return
        }
        pinchSuppressedSinceLog.addAndGet(-skipped)
        actions.onLogDebug(
            "Pinch zoom suppressed",
            mapOf(
                "magnification" to magnification.toString(),
                "mode" to environment.renderingMode().name,
                "hovered" to environment.isHovered().toString(),
                "pointerInsideBounds" to geometric.toString(),
                "bounds" to environment.getBoundsPx().toString(),
                "valid" to environment.isValid().toString(),
                "handleId" to handleId,
                "suppressedSinceLastLine" to skipped.toString(),
            ),
        )
    }

    companion object {
        const val PINCH_OFFER_DEADLINE_MS = 150L
        const val MAX_PENDING_PINCH_OFFERS = 8
        const val MAX_UNANSWERED_PINCH_CLAIMS = 2 * MAX_PENDING_PINCH_OFFERS
        const val PINCH_SUPPRESSED_LOG_INTERVAL_NS = 1_000_000_000L
        const val MIN_PINCH_MAGNIFICATION = 1e-7

        private val pinchSuppressedLoggedAt =
            AtomicLong(System.nanoTime() - PINCH_SUPPRESSED_LOG_INTERVAL_NS)
        private val pinchSuppressedSinceLog = AtomicInteger(0)
    }
}
