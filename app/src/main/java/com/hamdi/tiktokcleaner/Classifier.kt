package com.hamdi.tiktokcleaner

import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Pure classification + date-parsing logic. No Android dependencies except
 * java.time (API 26+), so this is unit-testable on the JVM.
 *
 * Rules (from the tool design):
 *  - GONE    : the profile shows an account-not-found text.
 *  - FAKE    : username matches ^user\d{6,}$ AND the account has 0 videos.
 *  - DEAD    : 0 videos, or the newest video is older than the inactivity threshold.
 *  - UNKNOWN : private account, or signals could not be read reliably.
 *  - ACTIVE  : everything else.
 */
object Classifier {

    val GENERATED_USERNAME = Regex("^user\\d{6,}$")

    const val DEFAULT_THRESHOLD_DAYS = 180

    fun classify(
        username: String,
        gone: Boolean,
        privateAccount: Boolean,
        videoCount: Int?,
        lastVideoMs: Long?,
        nowMs: Long,
        thresholdDays: Int
    ): Pair<Category, String> {
        if (gone) return Category.GONE to "account not found on profile screen"
        if (privateAccount) return Category.UNKNOWN to "private account - cannot verify activity"

        if (videoCount == null) return Category.UNKNOWN to "video count unreadable from UI"

        if (videoCount == 0) {
            return if (GENERATED_USERNAME.matches(username)) {
                Category.FAKE to "generated username (user+digits) and 0 videos"
            } else {
                Category.DEAD to "no videos at all"
            }
        }

        if (lastVideoMs == null) {
            return Category.UNKNOWN to "has videos but newest video date unreadable"
        }

        val ageDays = (nowMs - lastVideoMs) / 86_400_000L
        return if (ageDays >= thresholdDays) {
            Category.DEAD to "newest video is $ageDays days old (threshold $thresholdDays)"
        } else {
            Category.ACTIVE to "newest video is $ageDays days old"
        }
    }

    // ---- Date parsing from TikTok UI texts (best effort) ----

    private val FULL_DATE = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})""")
    private val REL_EN = Regex(
        """(\d+)\s*(seconds?|secs?|minutes?|mins?|hours?|days?|weeks?|months?|years?)\s+ago""",
        RegexOption.IGNORE_CASE
    )
    private val REL_EN_SHORT = Regex("""^(\d+)([hdw])$""")
    private val REL_AR = Regex("""قبل\s+(\d+)\s+(يوم|أيام|أسبوع|أسابيع|شهر|أشهر|سنة|سنوات|ساعة|ساعات)""")

    /** Scan a list of visible UI texts and return the first parseable date as epoch ms. */
    fun parseDateFromTexts(texts: List<String>, nowMs: Long): Long? {
        for (t in texts) {
            parseDateText(t, nowMs)?.let { return it }
        }
        return null
    }

    fun parseDateText(text: String, nowMs: Long): Long? {
        val s = text.trim()

        FULL_DATE.find(s)?.let { m ->
            val y = m.groupValues[1].toInt()
            val mo = m.groupValues[2].toInt()
            val d = m.groupValues[3].toInt()
            return try {
                LocalDate.of(y, mo, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            } catch (e: Exception) {
                null
            }
        }

        REL_EN.find(s)?.let { m ->
            val n = m.groupValues[1].toLong()
            val unit = m.groupValues[2].lowercase()
            val unitMs = when {
                unit.startsWith("sec") -> 1_000L
                unit.startsWith("min") -> 60_000L
                unit.startsWith("hour") -> 3_600_000L
                unit.startsWith("day") -> 86_400_000L
                unit.startsWith("week") -> 604_800_000L
                unit.startsWith("month") -> 2_592_000_000L   // ~30 days
                unit.startsWith("year") -> 31_536_000_000L  // ~365 days
                else -> return null
            }
            return nowMs - n * unitMs
        }

        REL_AR.find(s)?.let { m ->
            val n = m.groupValues[1].toLong()
            val unit = m.groupValues[2]
            val unitMs = when (unit) {
                "يوم", "أيام" -> 86_400_000L
                "أسبوع", "أسابيع" -> 604_800_000L
                "شهر", "أشهر" -> 2_592_000_000L
                "سنة", "سنوات" -> 31_536_000_000L
                "ساعة", "ساعات" -> 3_600_000L
                else -> return null
            }
            return nowMs - n * unitMs
        }

        // TikTok sometimes shows a bare "3d" / "2w" token next to the author name.
        if (s.length <= 8) {
            REL_EN_SHORT.matchEntire(s)?.let { m ->
                val n = m.groupValues[1].toLong()
                val unitMs = when (m.groupValues[2]) {
                    "h" -> 3_600_000L
                    "d" -> 86_400_000L
                    "w" -> 604_800_000L
                    else -> return null
                }
                return nowMs - n * unitMs
            }
        }

        return null
    }

    /** TikTok username charset heuristic used when scanning list rows. */
    val USERNAME_TEXT = Regex("^[a-zA-Z0-9._]{2,30}$")

    fun looksLikeUsername(text: String): Boolean {
        if (!USERNAME_TEXT.matches(text)) return false
        if (text in UI_LABELS) return false
        // Display names are usually capitalised / multi-word; usernames are
        // lowercase, or contain a digit, '.' or '_'. Deliberately conservative.
        val hasSignal = text.any { it.isDigit() || it == '.' || it == '_' } ||
            text == text.lowercase()
        return hasSignal
    }

    private val UI_LABELS = setOf(
        "following", "followers", "friends", "manage", "default", "sort",
        "videos", "liked", "profile", "share", "edit", "add", "search",
        "متابعة", "أتابع", "الأصدقاء", "مشاركة", "بحث"
    )
}
