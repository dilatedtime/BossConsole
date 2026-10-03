package ai.rever.boss.plugin.browser

/** Identifies one pinch offer within the document that received it. */
internal data class PinchOfferToken(
    val documentEpoch: Long,
    val sequence: Long,
)

/**
 * The effect of a current pinch answer.
 *
 * [claimChangedTo] is non-null only when a fresh explicit page answer changes the remembered
 * ownership. It lets the handle keep transition logging outside this concurrency primitive.
 */
internal data class PinchResolution(
    val claimed: Boolean,
    val claimChangedTo: Boolean?,
)

/**
 * Orders asynchronous pinch answers and fences them to the document that received the offer.
 *
 * Renderer answers can complete in a different order from trackpad deltas. An explicit answer
 * older than the newest explicit answer is therefore discarded: applying it could reset a newer
 * declined delta's partial page-zoom step, or add an old-direction delta after the page claimed a
 * newer one. Timeouts and skipped offers carry the latest explicit ownership decision, preserving
 * the busy-page fallback without allowing old evidence to replace new evidence.
 *
 * A navigation or renderer death advances the epoch and clears ownership as one synchronized
 * operation. Every callback from the old document then becomes stale, including callbacks that
 * were already queued for the EDT.
 */
internal class PinchDocumentState(
    private val maxUnansweredClaims: Int,
) {
    private var documentEpoch = 0L
    private var nextSequence = 0L
    private var newestExplicitSequence = 0L
    private var lastClaimed: Boolean? = null
    private var unansweredOffers = 0

    init {
        require(maxUnansweredClaims >= 0) { "maxUnansweredClaims must not be negative" }
    }

    /** Starts an offer against the current document. */
    @Synchronized
    fun beginOffer(): PinchOfferToken = PinchOfferToken(documentEpoch, ++nextSequence)

    /**
     * Resolves [token], or returns null when its document or explicit answer is stale.
     *
     * Only a timeout spends the unanswered budget. A skipped offer was never sent to the page,
     * so it carries the last claim without making a slow page look hung.
     */
    @Synchronized
    fun resolve(
        token: PinchOfferToken,
        answer: PinchAnswer,
    ): PinchResolution? {
        if (token.documentEpoch != documentEpoch) return null

        val explicit =
            when (answer) {
                PinchAnswer.CLAIMED -> true
                PinchAnswer.DECLINED -> false
                PinchAnswer.TIMED_OUT, PinchAnswer.SKIPPED -> null
            }

        return if (explicit != null) resolveExplicit(token, explicit) else resolveUnanswered(token, answer)
    }

    private fun resolveExplicit(
        token: PinchOfferToken,
        claimed: Boolean,
    ): PinchResolution? {
        if (token.sequence <= newestExplicitSequence) return null
        newestExplicitSequence = token.sequence
        unansweredOffers = 0
        val changedTo = claimed.takeIf { it != lastClaimed }
        lastClaimed = claimed
        return PinchResolution(claimed = claimed, claimChangedTo = changedTo)
    }

    private fun resolveUnanswered(
        token: PinchOfferToken,
        answer: PinchAnswer,
    ): PinchResolution? {
        // An unanswered old delta must not be applied after a newer explicit ownership decision.
        if (token.sequence < newestExplicitSequence) return null
        if (answer == PinchAnswer.TIMED_OUT) unansweredOffers++
        val carriesClaim = lastClaimed == true && unansweredOffers <= maxUnansweredClaims
        return PinchResolution(claimed = carriesClaim, claimChangedTo = null)
    }

    /** Invalidates every outstanding offer and forgets the old document's ownership. */
    @Synchronized
    fun advanceDocument() {
        documentEpoch++
        nextSequence = 0L
        newestExplicitSequence = 0L
        lastClaimed = null
        unansweredOffers = 0
    }
}
