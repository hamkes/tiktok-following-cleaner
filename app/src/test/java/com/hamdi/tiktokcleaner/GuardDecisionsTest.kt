package com.hamdi.tiktokcleaner

import org.junit.Assert.assertEquals
import org.junit.Test

class GuardDecisionsTest {

    @Test
    fun confirmsSelectedFollowingTabWithCount() {
        val nodes = listOf(
            GuardDecisions.NodeSnapshot(text = "Following 9,946", isSelected = true, boundsTop = 120, boundsBottom = 200),
            GuardDecisions.NodeSnapshot(text = "Followers 1,200", isSelected = false, boundsTop = 120, boundsBottom = 200)
        )
        val decision = GuardDecisions.decideTab(nodes)
        assertEquals(GuardDecisions.TabVerdict.CONFIRMED_FOLLOWING, decision.verdict)
    }

    @Test
    fun rejectsSelectedFollowersTab() {
        val nodes = listOf(
            GuardDecisions.NodeSnapshot(text = "Following 9,946", isSelected = false, boundsTop = 120, boundsBottom = 200),
            GuardDecisions.NodeSnapshot(text = "Followers 1,200", isSelected = true, boundsTop = 120, boundsBottom = 200)
        )
        val decision = GuardDecisions.decideTab(nodes)
        assertEquals(GuardDecisions.TabVerdict.WRONG_TAB, decision.verdict)
    }

    @Test
    fun confirmsCollapsedTabViaSortByAnchor() {
        val nodes = listOf(
            GuardDecisions.NodeSnapshot(text = "Sort by: Default", isSelected = false, boundsTop = 220, boundsBottom = 280)
        )
        val decision = GuardDecisions.decideTab(nodes)
        assertEquals(GuardDecisions.TabVerdict.CONFIRMED_FOLLOWING, decision.verdict)
    }
}
