package com.hamdi.tiktokcleaner

import org.junit.Assert.assertEquals
import org.junit.Test

class TripwireTest {

    @Test
    fun singleStrayNotFollowedDoesNotInvalidateRun() {
        val tripwire = GuardDecisions.WrongListTripwire()
        assertEquals(GuardDecisions.WrongListTripwire.Decision.CONTINUE, tripwire.onFollowState(GuardDecisions.FollowState.FOLLOWING))
        assertEquals(GuardDecisions.WrongListTripwire.Decision.CONTINUE, tripwire.onFollowState(GuardDecisions.FollowState.FOLLOW))
        assertEquals(GuardDecisions.WrongListTripwire.Decision.CONTINUE, tripwire.onFollowState(GuardDecisions.FollowState.FOLLOWING))
    }

    @Test
    fun consecutiveNotFollowedInvalidatesRun() {
        val tripwire = GuardDecisions.WrongListTripwire(maxConsecutiveNotFollowed = 4)
        assertEquals(GuardDecisions.WrongListTripwire.Decision.CONTINUE, tripwire.onFollowState(GuardDecisions.FollowState.FOLLOW))
        assertEquals(GuardDecisions.WrongListTripwire.Decision.CONTINUE, tripwire.onFollowState(GuardDecisions.FollowState.FOLLOW_BACK))
        assertEquals(GuardDecisions.WrongListTripwire.Decision.CONTINUE, tripwire.onFollowState(GuardDecisions.FollowState.FOLLOW))
        assertEquals(GuardDecisions.WrongListTripwire.Decision.INVALIDATE_RUN, tripwire.onFollowState(GuardDecisions.FollowState.FOLLOW))
    }
}
