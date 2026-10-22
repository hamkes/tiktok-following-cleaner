package com.hamdi.tiktokcleaner

import com.hamdi.tiktokcleaner.GuardDecisions.FollowState
import com.hamdi.tiktokcleaner.GuardDecisions.NodeSnapshot
import com.hamdi.tiktokcleaner.GuardDecisions.TabVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4: executed tests for the guard decision core (extracted from
 * CleanerAccessibilityService in the v1.4 testability refactor). These are
 * the very decisions whose live behaviour could not be exercised in this
 * VM during the v1.3 round (no emulator/KVM) — the pure core makes them
 * JVM-testable, and these tests pin the v1.3 semantics case by case.
 *
 * Geometry used below mimics a phone screen: root 1080x2400.
 *  - Primary-button band (v1.3): top in [15%, 62%] of height = [360, 1488]
 *  - Wide button (v1.3): width >= 35% of 1080 = 378 px
 */
class GuardDecisionsTest {

    private val W = 1080
    private val H = 2400

    private fun tab(text: String, selected: Boolean, top: Int = 200) =
        NodeSnapshot(text = text, selectedInChain = selected, left = 40, top = top, right = 340, bottom = top + 60)

    /** The profile's primary action button: wide, in the upper-middle band. */
    private fun primaryButton(text: String?, top: Int = 700) =
        NodeSnapshot(text = text, left = 60, top = top, right = 1020, bottom = top + 90)

    // ---------- tab verification ----------

    @Test
    fun followersTabSelectedIsWrongTab() {
        val nodes = listOf(
            tab("Following", selected = false),
            tab("Followers", selected = true),
            tab("Friends", selected = false)
        )
        val decision = GuardDecisions.decideTab(nodes)
        assertEquals(TabVerdict.WRONG_TAB, decision.verdict)
        assertTrue(decision.scan.tabRowPresent)
        assertTrue(decision.scan.followersSelected)
        assertFalse(decision.scan.followingSelected)
    }

    @Test
    fun followingTabSelectedIsConfirmed() {
        val nodes = listOf(
            tab("Following", selected = true),
            tab("Followers", selected = false),
            tab("Friends", selected = false)
        )
        assertEquals(TabVerdict.CONFIRMED, GuardDecisions.decideTab(nodes).verdict)
    }

    @Test
    fun friendsTabSelectedIsWrongTab() {
        val nodes = listOf(
            tab("Following", selected = false),
            tab("Followers", selected = false),
            tab("Friends", selected = true)
        )
        assertEquals(TabVerdict.WRONG_TAB, GuardDecisions.decideTab(nodes).verdict)
    }

    @Test
    fun noSelectedStateFallsBackToSortBy() {
        // TikTok exposing no selected state at all: the Following-only
        // "Sort by" control confirms (v1.3 fallback branch).
        val nodes = listOf(
            tab("Following", selected = false),
            tab("Followers", selected = false),
            NodeSnapshot(text = "Sort by: Default", left = 40, top = 300, right = 500, bottom = 350)
        )
        val decision = GuardDecisions.decideTab(nodes)
        assertEquals(TabVerdict.CONFIRMED, decision.verdict)
        assertTrue(decision.scan.hasSortBy)
    }

    @Test
    fun tabRowMissingIsInconclusive() {
        val nodes = listOf(
            NodeSnapshot(text = "Home", left = 0, top = 0, right = 200, bottom = 80),
            NodeSnapshot(text = "Discover", left = 200, top = 0, right = 500, bottom = 80)
        )
        val decision = GuardDecisions.decideTab(nodes)
        assertEquals(TabVerdict.INCONCLUSIVE, decision.verdict)
        assertFalse(decision.scan.tabRowPresent)
    }

    @Test
    fun tabRowPresentButNothingSelectedAndNoSortByIsInconclusive() {
        val nodes = listOf(
            tab("Following", selected = false),
            tab("Followers", selected = false)
        )
        assertEquals(TabVerdict.INCONCLUSIVE, GuardDecisions.decideTab(nodes).verdict)
    }

    @Test
    fun followingSelectedWinsOverFollowersSelected() {
        // v1.3 branch order: the Following-selected check runs first.
        val nodes = listOf(
            tab("Following", selected = true),
            tab("Followers", selected = true)
        )
        assertEquals(TabVerdict.CONFIRMED, GuardDecisions.decideTab(nodes).verdict)
    }

    @Test
    fun arabicTabLabelsConfirm() {
        // v1.4 broadened markers: unvocalized AR tab labels.
        val nodes = listOf(
            tab("المتابعة", selected = true),
            tab("المتابعون", selected = false)
        )
        assertEquals(TabVerdict.CONFIRMED, GuardDecisions.decideTab(nodes).verdict)
    }

    // ---------- v1.6: count-carrying header tabs ----------

    /**
     * A header tab as the user's TikTok build renders it: the label
     * CARRIES THE COUNT ("Following 9,947"). Geometry mimics the real
     * list header on a 1080x2400 screen: tabs around top=300, well
     * inside the top-30% band (<= 720).
     */
    private fun countedTab(text: String, selected: Boolean, left: Int, top: Int = 300) =
        NodeSnapshot(text = text, selectedInChain = selected, left = left, top = top, right = left + 300, bottom = top + 60)

    private val sortByNode =
        NodeSnapshot(text = "Sort by: Default", left = 40, top = 430, right = 500, bottom = 480)

    private fun countedRow(followingSel: Boolean = false, followersSel: Boolean = false) = listOf(
        // the bare screen-title node the v1.5 diagnostics saw
        NodeSnapshot(text = "Following", left = 390, top = 190, right = 690, bottom = 240),
        countedTab("Following 9,947", followingSel, left = 40),
        countedTab("Followers 18.9K", followersSel, left = 380),
        countedTab("Friends 226", false, left = 720)
    )

    @Test
    fun countedRowNoSelectedWithSortByConfirms() {
        // (a) The user's exact screen: count-carrying row, no selected
        // state exposed, "Sort by" present -> CONFIRMED via the fallback.
        val decision = GuardDecisions.decideTab(countedRow() + sortByNode, H)
        assertEquals(TabVerdict.CONFIRMED, decision.verdict)
        assertTrue(decision.scan.tabRowPresent)
        assertTrue(decision.scan.followingTabPresent)
        assertTrue(decision.scan.hasSortBy)
    }

    @Test
    fun countedRowFollowersSelectedIsWrongTab() {
        // (b) Same row, but the count-carrying Followers tab is selected:
        // WRONG_TAB — the fallback must never override a selected tab.
        val decision = GuardDecisions.decideTab(countedRow(followersSel = true) + sortByNode, H)
        assertEquals(TabVerdict.WRONG_TAB, decision.verdict)
        assertTrue(decision.scan.followersSelected)
    }

    @Test
    fun countedRowFollowingSelectedConfirms() {
        // (c) The count-carrying Following tab selected -> CONFIRMED.
        val decision = GuardDecisions.decideTab(countedRow(followingSel = true), H)
        assertEquals(TabVerdict.CONFIRMED, decision.verdict)
        assertTrue(decision.scan.followingSelected)
    }

    @Test
    fun bareFollowingTitleAloneIsInconclusive() {
        // (d) ONLY the bare title "Following" (what the v1.5 diagnostics
        // saw), no other tab, no Sort-by -> not a row -> INCONCLUSIVE.
        val nodes = listOf(
            NodeSnapshot(text = "Following", left = 390, top = 190, right = 690, bottom = 240)
        )
        val decision = GuardDecisions.decideTab(nodes, H)
        assertEquals(TabVerdict.INCONCLUSIVE, decision.verdict)
        assertFalse(decision.scan.tabRowPresent)
        assertTrue(decision.scan.followingTabPresent)
    }

    @Test
    fun countedRowNoSortByNoSelectedIsInconclusive() {
        // (e) Count-suffixed Following present in a row, but Sort-by
        // ABSENT and no selected state: the fallback still requires
        // Sort-by -> INCONCLUSIVE.
        val nodes = listOf(
            countedTab("Following 9,947", false, left = 40),
            countedTab("Followers 18.9K", false, left = 380)
        )
        val decision = GuardDecisions.decideTab(nodes, H)
        assertEquals(TabVerdict.INCONCLUSIVE, decision.verdict)
        assertTrue(decision.scan.tabRowPresent)
        assertFalse(decision.scan.hasSortBy)
    }

    @Test
    fun followingYouIsNotATabLabel() {
        // (f) "Following you" continues with a LETTER after the marker —
        // a different phrase, not a count — so it is not a tab label.
        assertFalse(GuardDecisions.matchesTabLabel("Following you", "Following"))
        assertTrue(GuardDecisions.matchesTabLabel("Following 9,947", "Following"))
        assertTrue(GuardDecisions.matchesTabLabel("Followers 18.9K", "Followers"))
        assertTrue(GuardDecisions.matchesTabLabel("Following", "Following"))
        // …and a screen whose only "Following-ish" text is that phrase
        // has no Following tab in its row, even with Sort-by around.
        val nodes = listOf(
            NodeSnapshot(text = "Following you", left = 40, top = 300, right = 400, bottom = 360),
            countedTab("Followers 18.9K", false, left = 380),
            sortByNode
        )
        val decision = GuardDecisions.decideTab(nodes, H)
        assertEquals(TabVerdict.INCONCLUSIVE, decision.verdict)
        assertFalse(decision.scan.followingTabPresent)
    }

    @Test
    fun countedTabsBelowBandDoNotFormRow() {
        // The row must live in the TOP band: the same count-carrying
        // labels far down the screen (row buttons, stats) form no row.
        val nodes = listOf(
            countedTab("Following 9,947", false, left = 40, top = 1500),
            countedTab("Followers 18.9K", false, left = 380, top = 1500),
            sortByNode
        )
        val decision = GuardDecisions.decideTab(nodes, H)
        assertEquals(TabVerdict.INCONCLUSIVE, decision.verdict)
        assertFalse(decision.scan.tabRowPresent)
    }

    // ---------- profile follow-state ----------

    @Test
    fun followBackButtonIsNotFollowed() {
        val nodes = listOf(primaryButton("Follow back"))
        assertEquals(FollowState.NOT_FOLLOWED, GuardDecisions.followState(nodes, W, H))
    }

    @Test
    fun followButtonIsNotFollowed() {
        val nodes = listOf(primaryButton("Follow"))
        assertEquals(FollowState.NOT_FOLLOWED, GuardDecisions.followState(nodes, W, H))
    }

    @Test
    fun followingButtonIsFollowed() {
        val nodes = listOf(primaryButton("Following"))
        assertEquals(FollowState.FOLLOWED, GuardDecisions.followState(nodes, W, H))
    }

    @Test
    fun friendsButtonIsFollowed() {
        val nodes = listOf(primaryButton("Friends"))
        assertEquals(FollowState.FOLLOWED, GuardDecisions.followState(nodes, W, H))
    }

    @Test
    fun arabicFollowButtonIsNotFollowed() {
        val nodes = listOf(primaryButton("متابعة"))
        assertEquals(FollowState.NOT_FOLLOWED, GuardDecisions.followState(nodes, W, H))
    }

    @Test
    fun narrowButtonNeverDecides() {
        // A per-row button in a suggested-accounts strip: same label, but
        // only 300 px wide (< 378) — must not be read as the primary button.
        val narrow = NodeSnapshot(text = "Follow back", left = 700, top = 700, right = 1000, bottom = 760)
        assertEquals(FollowState.UNKNOWN, GuardDecisions.followState(listOf(narrow), W, H))
    }

    @Test
    fun wideButtonBelowBandNeverDecides() {
        val low = primaryButton("Follow back", top = 2000) // below 62% band
        assertEquals(FollowState.UNKNOWN, GuardDecisions.followState(listOf(low), W, H))
    }

    @Test
    fun iconOnlyButtonIsUnknown() {
        val iconOnly = NodeSnapshot(text = null, contentDescription = null, left = 60, top = 700, right = 1020, bottom = 790)
        assertEquals(FollowState.UNKNOWN, GuardDecisions.followState(listOf(iconOnly), W, H))
    }

    @Test
    fun goneAccountProfileIsUnknown() {
        // "Couldn't find this account" profile: no follow-state button at all.
        val nodes = listOf(
            NodeSnapshot(text = "Couldn't find this account", left = 140, top = 900, right = 940, bottom = 980),
            NodeSnapshot(text = "Check the username and try again", left = 140, top = 1000, right = 940, bottom = 1060)
        )
        assertEquals(FollowState.UNKNOWN, GuardDecisions.followState(nodes, W, H))
    }

    @Test
    fun zeroSizeRootIsUnknown() {
        val nodes = listOf(primaryButton("Follow back"))
        assertEquals(FollowState.UNKNOWN, GuardDecisions.followState(nodes, 0, 0))
    }

    @Test
    fun topmostQualifyingButtonWins() {
        // v1.3 rule: among qualifying wide in-band buttons, the topmost one
        // is the primary button.
        val nodes = listOf(
            primaryButton("Following", top = 900),
            primaryButton("Follow back", top = 700)
        )
        assertEquals(FollowState.NOT_FOLLOWED, GuardDecisions.followState(nodes, W, H))
        val reversed = listOf(
            primaryButton("Follow back", top = 900),
            primaryButton("Following", top = 700)
        )
        assertEquals(FollowState.FOLLOWED, GuardDecisions.followState(reversed, W, H))
    }

    @Test
    fun labelFallsBackToContentDescription() {
        val cdOnly = NodeSnapshot(
            text = null, contentDescription = "Following",
            left = 60, top = 700, right = 1020, bottom = 790
        )
        assertEquals(FollowState.FOLLOWED, GuardDecisions.followState(listOf(cdOnly), W, H))
    }

    // ---------- marker-set regression pin ----------

    @Test
    fun v13MarkersPreservedVerbatim() {
        assertTrue(GuardDecisions.TAB_FOLLOWING.containsAll(listOf("Following", "المتابَعة")))
        assertTrue(GuardDecisions.TAB_FOLLOWERS.containsAll(listOf("Followers", "المتابعون")))
        assertTrue(GuardDecisions.TAB_FRIENDS.containsAll(listOf("Friends", "الأصدقاء")))
        assertTrue(GuardDecisions.SORT_BY_MARKERS.containsAll(listOf("Sort by", "الترتيب حسب")))
        assertTrue(
            GuardDecisions.FOLLOWED_BUTTONS.containsAll(
                listOf("Following", "أتابع", "Friends", "الأصدقاء")
            )
        )
        assertTrue(
            GuardDecisions.NOT_FOLLOWED_BUTTONS.containsAll(
                listOf("Follow", "Follow back", "متابعة")
            )
        )
    }
}
