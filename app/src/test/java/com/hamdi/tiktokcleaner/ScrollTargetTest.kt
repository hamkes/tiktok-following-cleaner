package com.hamdi.tiktokcleaner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ScrollTargetTest {

    @Test
    fun ignoresViewPagerAndSelectsRecyclerView() {
        val pager = GuardDecisions.NodeSnapshot(
            className = "androidx.viewpager2.widget.ViewPager2",
            isScrollable = true,
            boundsLeft = 0, boundsTop = 200, boundsRight = 1080, boundsBottom = 2200
        )
        val recycler = GuardDecisions.NodeSnapshot(
            className = "androidx.recyclerview.widget.RecyclerView",
            isScrollable = true,
            boundsLeft = 0, boundsTop = 280, boundsRight = 1080, boundsBottom = 2200
        )
        val picked = GuardDecisions.pickScrollTarget(listOf(pager, recycler))
        assertNotNull(picked)
        assertEquals("androidx.recyclerview.widget.RecyclerView", picked?.className)
    }
}
