package com.hamdi.tiktokcleaner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassifierTest {

    private val now = 1_760_000_000_000L

    @Test
    fun classifyGoneAccount() {
        val res = Classifier.classify("someuser", isGone = true, isPrivate = false, videoCount = null, lastPostTimestamp = null, currentTimestamp = now, inactivityThresholdDays = 180)
        assertEquals(Category.GONE, res.category)
    }

    @Test
    fun classifyFakeDefaultHandleZeroVideos() {
        val res = Classifier.classify("user987654321", isGone = false, isPrivate = false, videoCount = 0, lastPostTimestamp = null, currentTimestamp = now, inactivityThresholdDays = 180)
        assertEquals(Category.FAKE, res.category)
    }

    @Test
    fun classifyDeadOldVideos() {
        val oldTs = now - (220L * 86_400_000L)
        val res = Classifier.classify("creator_acc", isGone = false, isPrivate = false, videoCount = 12, lastPostTimestamp = oldTs, currentTimestamp = now, inactivityThresholdDays = 180)
        assertEquals(Category.DEAD, res.category)
    }

    @Test
    fun classifyActiveRecentVideos() {
        val recentTs = now - (14L * 86_400_000L)
        val res = Classifier.classify("active_creator", isGone = false, isPrivate = false, videoCount = 5, lastPostTimestamp = recentTs, currentTimestamp = now, inactivityThresholdDays = 180)
        assertEquals(Category.ACTIVE, res.category)
    }

    @Test
    fun parseArabicAndEnglishRelativeDates() {
        assertNotNull(Classifier.parseDateText("3d ago", now))
        assertNotNull(Classifier.parseDateText("منذ ٣ أيام", now))
        assertNotNull(Classifier.parseDateText("2024-05-10", now))
        assertTrue(Classifier.looksLikeUsername("@hamdi_dev"))
        assertFalse(Classifier.looksLikeUsername("Following"))
    }
}
