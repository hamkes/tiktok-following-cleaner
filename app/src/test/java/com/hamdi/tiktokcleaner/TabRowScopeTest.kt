package com.hamdi.tiktokcleaner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.0.1/v2.0.2-gemini regression tests, reproducing the live logs of
 * 2026-10-27 22:58 and 23:17: TikTok renders the tab row TWICE
 * ("Following 9948" AND "Following 9,948", plus bare "Following"
 * repeats), and the duplicate copy carries a STALE selected flag on
 * Followers — the old decideTab collapsed both rows into one slot per
 * kind, so the stale flag vetoed the visible row and every run stopped
 * seconds after confirming. Geometry mimics a 1080px-wide screen: four
 * tabs side by side, each 300px wide.
 */
class TabRowScopeTest {

    private fun tab(
        text: String,
        selected: Boolean,
        top: Int,
        left: Int,
        chain: Boolean = false
    ) = GuardDecisions.NodeSnapshot(
        text = text, isSelected = selected, selectedInChain = chain,
        boundsLeft = left, boundsTop = top, boundsRight = left + 300, boundsBottom = top + 80
    )

    /** The exact live scenario: visible row correct, duplicate row stale. */
    @Test
    fun duplicateRowsStaleSelectedFollowersDoesNotStopRun() {
        val nodes = listOf(
            tab("Following 9948", selected = true, top = 120, left = 40),
            tab("Followers 19.1K", selected = false, top = 120, left = 400),
            tab("Friends 225", selected = false, top = 120, left = 760),
            tab("Suggested", selected = false, top = 120, left = 1120),
            tab("Following", selected = false, top = 120, left = 40),
            tab("Following 9,948", selected = false, top = 400, left = 40),
            tab("Followers 19.1K", selected = true, top = 400, left = 400),
            tab("Friends 225", selected = false, top = 400, left = 760),
            tab("Suggested", selected = false, top = 400, left = 1120),
            tab("Following", selected = false, top = 400, left = 40)
        )
        val decision = GuardDecisions.decideTab(nodes)
        assertEquals(GuardDecisions.TabVerdict.CONFIRMED_FOLLOWING, decision.verdict)
    }

    @Test
    fun realWrongTabStillDetected() {
        val nodes = listOf(
            tab("Following 9948", selected = false, top = 120, left = 40),
            tab("Followers 19.1K", selected = true, top = 120, left = 400),
            tab("Friends 225", selected = false, top = 120, left = 760)
        )
        assertEquals(
            GuardDecisions.TabVerdict.WRONG_TAB,
            GuardDecisions.decideTab(nodes).verdict
        )
    }

    @Test
    fun chainSelectedFollowingConfirms() {
        val nodes = listOf(
            tab("Following 9948", selected = false, top = 120, left = 40, chain = true),
            tab("Followers 19.1K", selected = false, top = 120, left = 400),
            tab("Friends 225", selected = false, top = 120, left = 760)
        )
        assertEquals(
            GuardDecisions.TabVerdict.CONFIRMED_FOLLOWING,
            GuardDecisions.decideTab(nodes).verdict
        )
    }

    @Test
    fun noFollowingSelectedAnywhereStaysWrongTab() {
        val nodes = listOf(
            tab("Following 9948", selected = false, top = 120, left = 40),
            tab("Followers 19.1K", selected = false, top = 120, left = 400),
            tab("Following 9,948", selected = false, top = 400, left = 40),
            tab("Followers 19.1K", selected = true, top = 400, left = 400)
        )
        assertEquals(
            GuardDecisions.TabVerdict.WRONG_TAB,
            GuardDecisions.decideTab(nodes).verdict
        )
    }

    @Test
    fun pickTabRowChoosesFullestRowThenTopmost() {
        val nodes = listOf(
            tab("Following 9948", selected = false, top = 120, left = 40),
            tab("Followers 19.1K", selected = false, top = 120, left = 400),
            tab("Following 9,948", selected = true, top = 400, left = 40),
            tab("Followers 19.1K", selected = false, top = 400, left = 400),
            tab("Friends 225", selected = false, top = 400, left = 760),
            tab("Suggested", selected = false, top = 400, left = 1120)
        )
        val row = GuardDecisions.pickTabRow(nodes)
        assertTrue(row.any { it.text == "Following 9,948" })
        assertEquals(
            GuardDecisions.TabVerdict.CONFIRMED_FOLLOWING,
            GuardDecisions.decideTab(nodes).verdict
        )
    }

    @Test
    fun fusedSingleBandIndicatorSiblingConfirms() {
        // Both duplicate rows sit in ONE band (same centerY): the
        // Following label itself is unselected, the Followers copy
        // carries a stale selected flag, but the selected indicator
        // strip (no text) sits under the Following label.
        val nodes = listOf(
            tab("Following 9948", selected = false, top = 120, left = 40),
            tab("Followers 19.1K", selected = true, top = 120, left = 400),
            tab("Friends 225", selected = false, top = 120, left = 760),
            GuardDecisions.NodeSnapshot(
                text = null, contentDescription = null, isSelected = true,
                boundsLeft = 60, boundsTop = 195, boundsRight = 320, boundsBottom = 205
            )
        )
        assertEquals(
            GuardDecisions.TabVerdict.CONFIRMED_FOLLOWING,
            GuardDecisions.decideTab(nodes).verdict
        )
    }

    @Test
    fun rowWithSelectedFollowingBeatsFullerUnselectedRow() {
        val nodes = listOf(
            tab("Following 9948", selected = true, top = 120, left = 40),
            tab("Followers 19.1K", selected = false, top = 120, left = 400),
            tab("Following 9,948", selected = false, top = 400, left = 40),
            tab("Followers 19.1K", selected = false, top = 400, left = 400),
            tab("Friends 225", selected = false, top = 400, left = 760),
            tab("Suggested", selected = false, top = 400, left = 1120)
        )
        assertEquals(
            GuardDecisions.TabVerdict.CONFIRMED_FOLLOWING,
            GuardDecisions.decideTab(nodes).verdict
        )
    }
}
