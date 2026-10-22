package com.hamdi.tiktokcleaner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5: executed tests for the pure navigation helpers behind the
 * Phase-1 Profile-tab search. On the user's phone (2026-10-22) v1.4's
 * single immediate read found no Profile tab at all; v1.5 polls with
 * these broadened markers. These tests pin the matching rules — in
 * particular that near-miss labels ("Messages", "Menu") must NOT match
 * the "Me" marker.
 */
class NavLogicTest {

    // ---------- isProfileTabLabel ----------

    @Test
    fun profileTab_exactProfile() {
        assertTrue(NavLogic.isProfileTabLabel("Profile"))
        assertTrue(NavLogic.isProfileTabLabel("profile"))
    }

    @Test
    fun profileTab_exactMe() {
        // The case from the user's phone: builds whose tab reads "Me".
        assertTrue(NavLogic.isProfileTabLabel("Me"))
        assertTrue(NavLogic.isProfileTabLabel("me"))
        assertTrue(NavLogic.isProfileTabLabel("  Me  "))
    }

    @Test
    fun profileTab_startsWithBoundary() {
        // Accessibility labels often carry suffixes after a separator.
        assertTrue(NavLogic.isProfileTabLabel("Profile, 5 of 5"))
        assertTrue(NavLogic.isProfileTabLabel("Profile:"))
        assertTrue(NavLogic.isProfileTabLabel("Me (3)"))
    }

    @Test
    fun profileTab_arabicExact() {
        assertTrue(NavLogic.isProfileTabLabel("الملف الشخصي"))
        assertTrue(NavLogic.isProfileTabLabel("حسابي"))
    }

    @Test
    fun profileTab_rejectsNearMisses() {
        // "Me" is a prefix of these — the boundary rule must reject them.
        assertFalse(NavLogic.isProfileTabLabel("Messages"))
        assertFalse(NavLogic.isProfileTabLabel("Menu"))
        assertFalse(NavLogic.isProfileTabLabel("Media"))
        assertFalse(NavLogic.isProfileTabLabel("Profilex"))
    }

    @Test
    fun profileTab_rejectsOtherTabsAndBlanks() {
        assertFalse(NavLogic.isProfileTabLabel("Home"))
        assertFalse(NavLogic.isProfileTabLabel("Discover"))
        assertFalse(NavLogic.isProfileTabLabel("Inbox"))
        assertFalse(NavLogic.isProfileTabLabel("Friends"))
        assertFalse(NavLogic.isProfileTabLabel(""))
        assertFalse(NavLogic.isProfileTabLabel("   "))
    }

    // ---------- formatBottomStrip ----------

    @Test
    fun bottomStrip_dedupsSkipsBlanksKeepsOrder() {
        val out = NavLogic.formatBottomStrip(
            listOf("Home", "", "Discover", "Home", "  ", "Inbox", "Me", "Discover")
        )
        assertEquals("\"Home\" | \"Discover\" | \"Inbox\" | \"Me\"", out)
    }

    @Test
    fun bottomStrip_emptyInput() {
        assertEquals("(لا عناوين مقروءة في الشريط السفلي)", NavLogic.formatBottomStrip(emptyList()))
        assertEquals("(لا عناوين مقروءة في الشريط السفلي)", NavLogic.formatBottomStrip(listOf("", "  ")))
    }

    @Test
    fun bottomStrip_capsItems() {
        val labels = (1..20).map { "Tab$it" }
        val out = NavLogic.formatBottomStrip(labels, maxItems = 5, maxLength = 500)
        assertTrue(out.contains("\"Tab1\""))
        assertTrue(out.contains("\"Tab5\""))
        assertFalse(out.contains("\"Tab6\""))
        assertTrue(out.endsWith("…"))
    }

    @Test
    fun bottomStrip_truncatesLongOutput() {
        val labels = (1..14).map { "AVeryLongTabLabelNumber$it" }
        val out = NavLogic.formatBottomStrip(labels)
        assertTrue(out.length <= 220 + 1)
        assertTrue(out.endsWith("…"))
    }
}
