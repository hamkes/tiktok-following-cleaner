package com.hamdi.tiktokcleaner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v2.0.7-gemini regression tests. Anchor: live proof 2026-10-28 —
 * @jaxvalormlbb's profile shows the "Following" COUNTER label ("80 /
 * Following") above the real "Friends▼" button, yet the 21:48 run
 * logged it as unfollowed. The old first-match decideFollowState and
 * first-match click both read/clicked the counter. The button row is
 * below the counter row, so the LAST state label wins, and the button
 * pick prefers clickable + lowest nodes.
 */
class FollowStateTest {

    private fun node(
        label: String,
        boundsTop: Int,
        boundsBottom: Int = boundsTop + 60,
        isClickable: Boolean = false
    ) = GuardDecisions.NodeSnapshot(
        text = label,
        isClickable = isClickable,
        boundsLeft = 100,
        boundsTop = boundsTop,
        boundsRight = 900,
        boundsBottom = boundsBottom
    )

    @Test
    fun jaxScenario_counterFollowingThenFriendsButton_readsFriends() {
        val nodes = listOf(
            node("jax-Valor", 200),
            node("80", 400),
            node("Following", 450),
            node("72", 400),
            node("Followers", 450),
            node("Message", 600, isClickable = true),
            node("Friends", 600, isClickable = true)
        )
        assertEquals(
            GuardDecisions.FollowState.FRIENDS,
            GuardDecisions.decideFollowState(nodes)
        )
    }

    @Test
    fun counterFollowingThenFollowingButton_readsFollowing() {
        val nodes = listOf(
            node("80", 400),
            node("Following", 450),
            node("Following", 600, isClickable = true)
        )
        assertEquals(
            GuardDecisions.FollowState.FOLLOWING,
            GuardDecisions.decideFollowState(nodes)
        )
    }

    @Test
    fun counterFollowingThenFollowButton_readsFollow() {
        val nodes = listOf(
            node("80", 400),
            node("Following", 450),
            node("Follow", 600, isClickable = true)
        )
        assertEquals(
            GuardDecisions.FollowState.FOLLOW,
            GuardDecisions.decideFollowState(nodes)
        )
    }

    @Test
    fun counterFollowingThenFollowBackButton_readsFollowBack() {
        val nodes = listOf(
            node("80", 400),
            node("Following", 450),
            node("Follow back", 600, isClickable = true)
        )
        assertEquals(
            GuardDecisions.FollowState.FOLLOW_BACK,
            GuardDecisions.decideFollowState(nodes)
        )
    }

    @Test
    fun noStateLabel_readsUnknown() {
        val nodes = listOf(node("jax-Valor", 200), node("80", 400))
        assertEquals(
            GuardDecisions.FollowState.UNKNOWN,
            GuardDecisions.decideFollowState(nodes)
        )
    }

    @Test
    fun pickUnfollowButton_prefersClickableLowestButtonOverCounter() {
        val counter = node("Following", 450, isClickable = false)
        val button = node("Friends", 600, isClickable = true)
        assertEquals(button, GuardDecisions.pickUnfollowButton(listOf(counter, button)))
        // Order in the list must not matter.
        assertEquals(button, GuardDecisions.pickUnfollowButton(listOf(button, counter)))
    }

    @Test
    fun pickUnfollowButton_noCandidate_returnsNull() {
        assertNull(GuardDecisions.pickUnfollowButton(listOf(node("Follow", 600, isClickable = true))))
        assertNull(GuardDecisions.pickUnfollowButton(emptyList()))
    }

    @Test
    fun pickUnfollowButton_lowestCandidateWinsWhenNoneClickable() {
        val upper = node("Following", 450)
        val lower = node("Following", 600)
        assertEquals(lower, GuardDecisions.pickUnfollowButton(listOf(upper, lower)))
    }
}
