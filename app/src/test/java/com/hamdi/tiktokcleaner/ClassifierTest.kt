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
        assertTrue(Classifier.looksLikeUsername("viruscorona_ml"))
        assertTrue(Classifier.looksLikeUsername("hellion189"))
        assertTrue(Classifier.looksLikeUsername("rockstargames"))
        assertFalse(Classifier.looksLikeUsername("Rockstar Games")) // space + capitals
        assertFalse(Classifier.looksLikeUsername("Following"))      // UI label
        assertFalse(Classifier.looksLikeUsername("Mah"))            // display-name-like
    }

    // ---------- v2.0: Arabic-Indic digits + Arabic date forms ----------

    @Test
    fun normalizeDigitsBothRanges() {
        assertEquals("2026-03-15", Classifier.normalizeDigits("٢٠٢٦-٠٣-١٥"))
        assertEquals("3", Classifier.normalizeDigits("۳"))
        assertEquals("قبل 3 أيام", Classifier.normalizeDigits("قبل ٣ أيام"))
        assertEquals("abc123", Classifier.normalizeDigits("abc123"))
    }

    @Test
    fun parseArabicDigitRelative() {
        assertEquals(now - 3 * day, Classifier.parseDateText("قبل ٣ أيام", now))
        assertEquals(now - 2 * day, Classifier.parseDateText("منذ ٢ يوم", now))
    }

    @Test
    fun parseSinceFormAndMinutesSeconds() {
        assertEquals(now - 5 * day, Classifier.parseDateText("منذ 5 أيام", now))
        assertEquals(now - 45 * 60_000L, Classifier.parseDateText("منذ 45 دقيقة", now))
        assertEquals(now - 30 * 1_000L, Classifier.parseDateText("قبل 30 ثانية", now))
    }

    @Test
    fun parseTodayYesterdayAndDuals() {
        assertEquals(now, Classifier.parseDateText("اليوم", now))
        assertEquals(now, Classifier.parseDateText("Just now", now))
        assertEquals(now - day, Classifier.parseDateText("أمس", now))
        assertEquals(now - 2 * day, Classifier.parseDateText("قبل يومين", now))
        assertEquals(now - 14 * day, Classifier.parseDateText("منذ أسبوعين", now))
        assertEquals(now - 2 * 3_600_000L, Classifier.parseDateText("قبل ساعتين", now))
    }

    @Test
    fun parseShortEnglishTokensExtended() {
        assertEquals(now - 45 * 60_000L, Classifier.parseDateText("45m", now))
        assertEquals(now - 2 * 2_592_000_000L, Classifier.parseDateText("2mo", now))
        assertEquals(now - 1 * 31_536_000_000L, Classifier.parseDateText("1y", now))
        assertEquals(now - 3 * day, Classifier.parseDateText("3d", now))
    }

    @Test
    fun parseSlashFullDate() {
        val expected = LocalDate.of(2023, 5, 14).atStartOfDay(ZoneOffset.UTC)
            .toInstant().toEpochMilli()
        assertEquals(expected, Classifier.parseDateText("2023/5/14", now))
    }

    @Test
    fun parseShortDateYearRollover() {
        // "now" is 2026-01-10 12:00 UTC: Dec 20 belongs to last year,
        // Jan 5 to this year.
        val janNow = LocalDate.of(2026, 1, 10).atStartOfDay(ZoneOffset.UTC)
            .toInstant().toEpochMilli() + 12 * 3_600_000L
        val dec20 = LocalDate.of(2025, 12, 20).atStartOfDay(ZoneOffset.UTC)
            .toInstant().toEpochMilli()
        val jan5 = LocalDate.of(2026, 1, 5).atStartOfDay(ZoneOffset.UTC)
            .toInstant().toEpochMilli()
        assertEquals(dec20, Classifier.parseDateText("12-20", janNow))
        assertEquals(jan5, Classifier.parseDateText("01-05", janNow))
        assertEquals(dec20, Classifier.parseDateText("12/20", janNow))
        assertNull(Classifier.parseDateText("13-40", janNow))
        assertNull(Classifier.parseDateText("2-30", janNow)) // Feb 30: invalid
    }

    @Test
    fun parseDateNeverMatchesHandlesOrLabels() {
        assertNull(Classifier.parseDateText("user123", now))
        assertNull(Classifier.parseDateText("Following 9,947", now))
        assertNull(Classifier.parseDateText("hello world, no date here", now))
    }
}
