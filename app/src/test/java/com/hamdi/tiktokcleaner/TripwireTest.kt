package com.hamdi.tiktokcleaner

import com.hamdi.tiktokcleaner.GuardDecisions.FollowState
import com.hamdi.tiktokcleaner.GuardDecisions.WrongListTripwire
import com.hamdi.tiktokcleaner.GuardDecisions.WrongListTripwire.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.9 — wrong-list tripwire.
 *
 * Replaces "one NOT_FOLLOWED profile invalidates the whole audit"
 * (v1.3) after the 2026-10-22 device incident: a single stray handle
 * destroyed a long run's progress repeatedly. A wrong LIST
 * is statistical — nearly every profile reads NOT_FOLLOWED — while a
 * genuine Following list shows NOT_FOLLOWED only as rare exceptions.
 * These tests pin the evidence thresholds both ways.
 */
class TripwireTest {

    private fun feed(t: WrongListTripwire, vararg states: FollowState): Decision {
        var d = Decision.CONTINUE
        for (s in states) d = t.onFollowState(s)
        return d
    }

    @Test
    fun twoNotFollowedBeforeAnyFollowedInvalidates() {
        val t = WrongListTripwire()
        assertEquals(Decision.CONTINUE, t.onFollowState(FollowState.NOT_FOLLOWED))
        assertEquals(Decision.INVALIDATE, t.onFollowState(FollowState.NOT_FOLLOWED))
    }

    @Test
    fun singleStrayAmongFollowedNeverInvalidates() {
        val t = WrongListTripwire()
        var d = feed(t, FollowState.FOLLOWED, FollowState.NOT_FOLLOWED)
        repeat(50) { d = t.onFollowState(FollowState.FOLLOWED) }
        assertEquals(Decision.CONTINUE, d)
        assertEquals(1, t.notFollowedCount)
        assertEquals(51, t.followedCount)
    }

    @Test
    fun strayFirstThenFollowedIsTolerated() {
        val t = WrongListTripwire()
        val d = feed(
            t,
            FollowState.NOT_FOLLOWED,
            FollowState.FOLLOWED, FollowState.FOLLOWED,
            FollowState.FOLLOWED, FollowState.FOLLOWED
        )
        assertEquals(Decision.CONTINUE, d)
    }

    @Test
    fun majorityNotFollowedInvalidatesAtFiveDecided() {
        val t = WrongListTripwire()
        // F, NF, NF, NF -> decided 4: majority rule needs >= 5 decided.
        var d = feed(
            t,
            FollowState.FOLLOWED,
            FollowState.NOT_FOLLOWED, FollowState.NOT_FOLLOWED, FollowState.NOT_FOLLOWED
        )
        assertEquals(Decision.CONTINUE, d)
        // 5th decided: NF 4 > F 1 -> invalidate.
        d = t.onFollowState(FollowState.NOT_FOLLOWED)
        assertEquals(Decision.INVALIDATE, d)
    }

    @Test
    fun followersListAlternatingInvalidates() {
        val t = WrongListTripwire()
        // A Followers list: roughly half the profiles are not followed
        // back. NF,F,NF,F -> decided 4, NF 2 = F 2, continue; 5th NF
        // makes NF 3 > F 2 at decided 5 -> invalidate.
        var d = feed(
            t,
            FollowState.NOT_FOLLOWED, FollowState.FOLLOWED,
            FollowState.NOT_FOLLOWED, FollowState.FOLLOWED
        )
        assertEquals(Decision.CONTINUE, d)
        d = t.onFollowState(FollowState.NOT_FOLLOWED)
        assertEquals(Decision.INVALIDATE, d)
    }

    @Test
    fun unknownReadsAreNotEvidence() {
        val t = WrongListTripwire()
        var d = Decision.CONTINUE
        repeat(10) { d = t.onFollowState(FollowState.UNKNOWN) }
        assertEquals(Decision.CONTINUE, d)
        assertEquals(0, t.decidedCount)
    }

    @Test
    fun longGenuineRunWithRareStraysSurvives() {
        val t = WrongListTripwire()
        var d = Decision.CONTINUE
        for (i in 1..300) {
            d = t.onFollowState(
                if (i == 57 || i == 213) FollowState.NOT_FOLLOWED else FollowState.FOLLOWED
            )
        }
        assertEquals(Decision.CONTINUE, d)
        assertEquals(2, t.notFollowedCount)
        assertEquals(298, t.followedCount)
    }

    @Test
    fun countsAreExposed() {
        val t = WrongListTripwire()
        feed(t, FollowState.FOLLOWED, FollowState.NOT_FOLLOWED, FollowState.UNKNOWN)
        assertEquals(1, t.followedCount)
        assertEquals(1, t.notFollowedCount)
        assertEquals(2, t.decidedCount)
    }
}
