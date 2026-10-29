package com.hamdi.tiktokcleaner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.0.3-gemini regression tests for the selection-sync rules.
 * Scenario anchors: live report 2026-10-28 — count stuck at 379 while
 * candidates reached 742; new DEAD rows unchecked; toggles lost on
 * Activity recreation.
 */
class SelectionStateTest {

    private fun result(username: String, category: Category, selected: Boolean) =
        AuditResult(
            username = username,
            category = category,
            detail = "d",
            videoCount = 0,
            lastVideoMs = null,
            checkedAtMs = 0L,
            selectedForCleanup = selected
        )

    @Test
    fun newCandidatesArePreselected_newNonCandidatesAreNot() {
        val selected = linkedSetOf<String>()
        val newly = SelectionState.syncSelection(
            emptySet(), selected,
            listOf(
                result("dead1", Category.DEAD, true),
                result("gone1", Category.GONE, true),
                result("active1", Category.ACTIVE, false)
            )
        )
        assertEquals(setOf("dead1", "gone1"), selected)
        assertEquals(setOf("dead1", "gone1", "active1"), newly)
    }

    @Test
    fun laterCandidatesJoinExistingSelection() {
        // First load seeded 379; the audit then classifies more.
        val selected = linkedSetOf("old1")
        val known = setOf("old1")
        SelectionState.syncSelection(
            known, selected,
            listOf(
                result("old1", Category.DEAD, true),
                result("newDead", Category.DEAD, true)
            )
        )
        assertEquals(setOf("old1", "newdead"), selected)
    }

    @Test
    fun userUncheckSurvivesRefreshEvenWhenFileFlagStillTrue() {
        // The persisted flag lags the toggle (async save); the
        // in-memory choice must win on refresh.
        val selected = linkedSetOf<String>()
        val known = setOf("dead1")
        SelectionState.syncSelection(
            known, selected,
            listOf(result("dead1", Category.DEAD, true))
        )
        assertTrue(selected.isEmpty())
    }

    @Test
    fun emptiedSelectionStaysEmpty() {
        val selected = linkedSetOf<String>()
        val known = setOf("dead1", "dead2")
        SelectionState.syncSelection(
            known, selected,
            listOf(
                result("dead1", Category.DEAD, true),
                result("dead2", Category.DEAD, true)
            )
        )
        assertTrue(selected.isEmpty())
    }

    @Test
    fun unfollowedResultsLeaveSelection() {
        val selected = linkedSetOf("gone1", "dead1")
        SelectionState.syncSelection(
            setOf("gone1", "dead1"), selected,
            listOf(result("dead1", Category.DEAD, true))
        )
        assertEquals(setOf("dead1"), selected)
    }

    @Test
    fun recreationRestoresPersistedSelectionFromFlags() {
        // Fresh Activity: known is empty, flags are the truth.
        val selected = linkedSetOf<String>()
        SelectionState.syncSelection(
            emptySet(), selected,
            listOf(
                result("kept", Category.DEAD, true),
                result("uncheckedByUser", Category.DEAD, false)
            )
        )
        assertEquals(setOf("kept"), selected)
    }

    @Test
    fun applyToggleUpdatesFlagAndSet() {
        val results = listOf(
            result("dead1", Category.DEAD, true),
            result("dead2", Category.DEAD, true)
        )
        val selected = linkedSetOf("dead1", "dead2")
        val updated = SelectionState.applyToggle(results, selected, "DEAD1", false)
        assertEquals(setOf("dead2"), selected)
        assertEquals(false, updated[0].selectedForCleanup)
        assertEquals(true, updated[1].selectedForCleanup)
    }
}
