package ai.rever.boss.plugin.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PinchDocumentStateTest {
    @Test
    fun `an older claim cannot reset accumulation after a newer decline`() {
        val state = PinchDocumentState(maxUnansweredClaims = 2)
        val older = state.beginOffer()
        val newer = state.beginOffer()

        val decline = state.resolve(newer, PinchAnswer.DECLINED)

        assertEquals(PinchResolution(claimed = false, claimChangedTo = false), decline)
        assertNull(state.resolve(older, PinchAnswer.CLAIMED))
    }

    @Test
    fun `an older decline cannot add its old-direction delta after a newer claim`() {
        val state = PinchDocumentState(maxUnansweredClaims = 2)
        val older = state.beginOffer()
        val newer = state.beginOffer()

        val claim = state.resolve(newer, PinchAnswer.CLAIMED)

        assertEquals(PinchResolution(claimed = true, claimChangedTo = true), claim)
        assertNull(state.resolve(older, PinchAnswer.DECLINED))
    }

    @Test
    fun `navigation rejects every answer from the previous document`() {
        val state = PinchDocumentState(maxUnansweredClaims = 2)
        val oldClaim = state.beginOffer()
        val oldTimeout = state.beginOffer()

        state.advanceDocument()
        val current = state.beginOffer()

        assertNull(state.resolve(oldClaim, PinchAnswer.CLAIMED))
        assertNull(state.resolve(oldTimeout, PinchAnswer.TIMED_OUT))
        assertEquals(
            PinchResolution(claimed = false, claimChangedTo = false),
            state.resolve(current, PinchAnswer.DECLINED),
        )
    }

    @Test
    fun `tokens restart within a new document but retain a distinct epoch`() {
        val state = PinchDocumentState(maxUnansweredClaims = 2)
        val old = state.beginOffer()

        state.advanceDocument()
        val current = state.beginOffer()

        assertEquals(old.sequence, current.sequence)
        assertFalse(old.documentEpoch == current.documentEpoch)
    }

    @Test
    fun `timeouts carry a claim only through the bounded unanswered budget`() {
        val state = PinchDocumentState(maxUnansweredClaims = 2)
        assertTrue(state.resolve(state.beginOffer(), PinchAnswer.CLAIMED)!!.claimed)

        assertTrue(state.resolve(state.beginOffer(), PinchAnswer.TIMED_OUT)!!.claimed)
        assertTrue(state.resolve(state.beginOffer(), PinchAnswer.TIMED_OUT)!!.claimed)
        assertFalse(state.resolve(state.beginOffer(), PinchAnswer.TIMED_OUT)!!.claimed)
    }

    @Test
    fun `skipped offers carry a claim without spending the timeout budget`() {
        val state = PinchDocumentState(maxUnansweredClaims = 1)
        assertTrue(state.resolve(state.beginOffer(), PinchAnswer.CLAIMED)!!.claimed)

        repeat(20) {
            assertTrue(state.resolve(state.beginOffer(), PinchAnswer.SKIPPED)!!.claimed)
        }

        assertTrue(state.resolve(state.beginOffer(), PinchAnswer.TIMED_OUT)!!.claimed)
        assertFalse(state.resolve(state.beginOffer(), PinchAnswer.TIMED_OUT)!!.claimed)
    }

    @Test
    fun `a fresh explicit answer restores the unanswered budget`() {
        val state = PinchDocumentState(maxUnansweredClaims = 1)
        assertTrue(state.resolve(state.beginOffer(), PinchAnswer.CLAIMED)!!.claimed)
        assertTrue(state.resolve(state.beginOffer(), PinchAnswer.TIMED_OUT)!!.claimed)
        assertFalse(state.resolve(state.beginOffer(), PinchAnswer.TIMED_OUT)!!.claimed)

        val reaffirmed = state.resolve(state.beginOffer(), PinchAnswer.CLAIMED)

        assertEquals(PinchResolution(claimed = true, claimChangedTo = null), reaffirmed)
        assertTrue(state.resolve(state.beginOffer(), PinchAnswer.TIMED_OUT)!!.claimed)
    }

    @Test
    fun `an unanswered older delta is ignored after a newer explicit answer`() {
        val state = PinchDocumentState(maxUnansweredClaims = 2)
        val older = state.beginOffer()
        val newer = state.beginOffer()
        state.resolve(newer, PinchAnswer.DECLINED)

        assertNull(state.resolve(older, PinchAnswer.TIMED_OUT))
        assertNull(state.resolve(older, PinchAnswer.SKIPPED))
    }

    @Test
    fun `negative unanswered budget is rejected`() {
        val failure = runCatching { PinchDocumentState(maxUnansweredClaims = -1) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }
}
