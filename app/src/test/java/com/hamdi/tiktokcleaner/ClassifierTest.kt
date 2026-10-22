package com.hamdi.tiktokcleaner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class ClassifierTest {

    private val now = 1_761_000_000_000L // fixed "now" for deterministic tests
    private val day = 86_400_000L

    @Test
    fun goneAccount() {
        val (cat, _) = Classifier.classify("someone", true, false, null, null, now, 180)
        assertEquals(Category.GONE, cat)
    }

    @Test
    fun privateIsUnknown() {
        val (cat, _) = Classifier.classify("someone", false, true, 10, now, now, 180)
        assertEquals(Category.UNKNOWN, cat)
    }

    @Test
    fun generatedUsernameWithZeroVideosIsFake() {
        val (cat, _) = Classifier.classify("user5538705522279", false, false, 0, null, now, 180)
        assertEquals(Category.FAKE, cat)
    }

    @Test
    fun normalNameWithZeroVideosIsDeadNotFake() {
        val (cat, _) = Classifier.classify("ahmed.plays", false, false, 0, null, now, 180)
        assertEquals(Category.DEAD, cat)
    }

    @Test
    fun shortGeneratedNameIsNotFake() {
        // regex requires 6+ digits
        val (cat, _) = Classifier.classify("user123", false, false, 0, null, now, 180)
        assertEquals(Category.DEAD, cat)
    }

    @Test
    fun oldNewestVideoIsDead() {
        val (cat, _) = Classifier.classify("creator", false, false, 42, now - 200 * day, now, 180)
        assertEquals(Category.DEAD, cat)
    }

    @Test
    fun recentVideoIsActive() {
        val (cat, _) = Classifier.classify("creator", false, false, 42, now - 3 * day, now, 180)
        assertEquals(Category.ACTIVE, cat)
    }

    @Test
    fun videosButNoDateIsUnknown() {
        val (cat, _) = Classifier.classify("creator", false, false, 7, null, now, 180)
        assertEquals(Category.UNKNOWN, cat)
    }

    @Test
    fun unreadableCountIsUnknown() {
        val (cat, _) = Classifier.classify("creator", false, false, null, now, now, 180)
        assertEquals(Category.UNKNOWN, cat)
    }

    @Test
    fun parseFullDate() {
        val expected = LocalDate.of(2024, 1, 15).atStartOfDay(ZoneOffset.UTC)
            .toInstant().toEpochMilli()
        assertEquals(expected, Classifier.parseDateText("2024-01-15", now))
        assertEquals(expected, Classifier.parseDateText("posted on 2024-01-15 ok", now))
    }

    @Test
    fun parseRelativeEnglish() {
        assertEquals(now - 3 * day, Classifier.parseDateText("3 days ago", now))
        assertEquals(now - 2 * 604_800_000L, Classifier.parseDateText("2 weeks ago", now))
    }

    @Test
    fun parseRelativeArabic() {
        assertEquals(now - 5 * day, Classifier.parseDateText("قبل 5 أيام", now))
    }

    @Test
    fun parseGarbageIsNull() {
        assertNull(Classifier.parseDateText("hello world, no date here", now))
        assertNull(Classifier.parseDateText("2024-13-40", now))
    }

    @Test
    fun usernameHeuristic() {
        assertTrue(Classifier.looksLikeUsername("some.user_ml"))
        assertTrue(Classifier.looksLikeUsername("hellion189"))
        assertTrue(Classifier.looksLikeUsername("rockstargames"))
        assertFalse(Classifier.looksLikeUsername("Rockstar Games")) // space + capitals
        assertFalse(Classifier.looksLikeUsername("Following"))      // UI label
        assertFalse(Classifier.looksLikeUsername("Mah"))            // display-name-like
    }
}
