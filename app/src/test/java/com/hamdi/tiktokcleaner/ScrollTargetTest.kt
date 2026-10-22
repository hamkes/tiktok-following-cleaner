package com.hamdi.tiktokcleaner

import com.hamdi.tiktokcleaner.GuardDecisions.NodeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v1.8 — scroll-target selection.
 *
 * Device evidence (2026-10-22, two runs): the audit verified the Following
 * list, collected one name, scrolled — and the mid-run guard stopped the
 * run as WRONG_TAB because the screen had flipped to Followers. Root
 * cause: the service scrolled the LARGEST scrollable node, which on
 * TikTok's user-list screen is the tab pager; ACTION_SCROLL_FORWARD on a
 * pager switches to the next tab page. These tests pin the replacement
 * rule: pagers are never a scroll target.
 */
class ScrollTargetTest {

    private fun node(
        className: String,
        scrollable: Boolean = true,
        left: Int = 0, top: Int = 0, right: Int = 1000, bottom: Int = 2000
    ) = NodeSnapshot(
        className = className, scrollable = scrollable,
        left = left, top = top, right = right, bottom = bottom
    )

    @Test
    fun recyclerChosenOverLargerPager() {
        val nodes = listOf(
            // pager is BIGGER (full screen) — the pre-v1.8 rule picked it.
            node("androidx.viewpager2.widget.ViewPager2", right = 1080, bottom = 2400),
            node("androidx.recyclerview.widget.RecyclerView", right = 1080, bottom = 2200)
        )
        assertEquals(1, GuardDecisions.pickScrollTarget(nodes))
    }

    @Test
    fun legacyViewPagerAlsoExcluded() {
        val nodes = listOf(
            node("androidx.viewpager.widget.ViewPager", right = 1080, bottom = 2400),
            node("androidx.recyclerview.widget.RecyclerView", right = 900, bottom = 2000)
        )
        assertEquals(1, GuardDecisions.pickScrollTarget(nodes))
    }

    @Test
    fun onlyPagerScrollableReturnsNull() {
        val nodes = listOf(
            node("androidx.viewpager2.widget.ViewPager2", right = 1080, bottom = 2400),
            node("android.widget.FrameLayout", scrollable = false)
        )
        assertNull(GuardDecisions.pickScrollTarget(nodes))
    }

    @Test
    fun noScrollableNodesReturnsNull() {
        val nodes = listOf(
            node("android.widget.TextView", scrollable = false),
            node("android.widget.LinearLayout", scrollable = false)
        )
        assertNull(GuardDecisions.pickScrollTarget(nodes))
    }

    @Test
    fun largestNonPagerWins() {
        val nodes = listOf(
            node("androidx.recyclerview.widget.RecyclerView", right = 500, bottom = 1000),
            node("android.widget.ScrollView", right = 1080, bottom = 2000),
            node("androidx.recyclerview.widget.RecyclerView", right = 700, bottom = 900)
        )
        assertEquals(1, GuardDecisions.pickScrollTarget(nodes))
    }

    @Test
    fun tieKeepsEarliestIndex() {
        val nodes = listOf(
            node("androidx.recyclerview.widget.RecyclerView", right = 1000, bottom = 2000),
            node("androidx.recyclerview.widget.RecyclerView", right = 1000, bottom = 2000)
        )
        assertEquals(0, GuardDecisions.pickScrollTarget(nodes))
    }

    @Test
    fun nonScrollablePagerAloneIsIgnored() {
        val nodes = listOf(
            node("androidx.viewpager.widget.ViewPager", scrollable = false),
            node("androidx.recyclerview.widget.RecyclerView", right = 800, bottom = 1600)
        )
        assertEquals(1, GuardDecisions.pickScrollTarget(nodes))
    }
}
