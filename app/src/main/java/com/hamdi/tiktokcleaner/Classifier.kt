package com.hamdi.tiktokcleaner

import java.time.Instant
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

    /**
     * v2.0 (salvaged from the Gemini draft, hardened): TikTok renders
     * numbers in Arabic-Indic digits (٠-٩) and Extended Arabic-Indic
     * digits (۰-۹) on Arabic-UI builds. Normalize both ranges to ASCII
     * before any date regex runs, so "قبل ٣ أيام" parses exactly like
     * "قبل 3 أيام". Pure; unit-tested.
     */
    fun normalizeDigits(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            when (ch) {
                in '٠'..'٩' -> sb.append((ch - '٠' + '0'.code).toChar())
                in '۰'..'۹' -> sb.append((ch - '۰' + '0'.code).toChar())
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

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

    private val FULL_DATE = Regex("""(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})""")
    private val REL_EN = Regex(
        """(\d+)\s*(seconds?|secs?|minutes?|mins?|hours?|days?|weeks?|months?|years?)\s+ago""",
        RegexOption.IGNORE_CASE
    )
    private val REL_EN_SHORT = Regex("""^(\d+)(mo|m|h|d|w|y)$""")
    private val REL_AR = Regex("""قبل\s+(\d+)\s+(يوم|أيام|أسبوع|أسابيع|شهر|أشهر|سنة|سنوات|ساعة|ساعات)""")

    // v2.0: anchored Arabic forms (Gemini idea, hardened — full-string
    // matches only, so a bio or handle can never accidentally parse as a
    // date). Covers منذ as well as قبل, seconds/minutes, and the dual
    // forms TikTok actually shows ("قبل ساعتين", "منذ يومين").
    private val REL_AR_SINCE = Regex(
        """^(?:منذ|قبل)?\s*(\d+)\s*(ثانية|ثواني|دقيقة|دقائق|ساعة|ساعات|يوم|أيام|أسبوع|أسابيع|شهر|أشهر|سنة|سنوات)\s*$"""
    )
    private val REL_AR_DUAL = Regex(
        """^(?:قبل|منذ)?\s*(يومان|يومين|أسبوعان|أسبوعين|اسبوعين|شهرين|سنتين|ساعتين|دقيقتين)\s*$"""
    )

    // v2.0: short month-day token ("5-3", "10/12") with year rollover:
    // TikTok shows no year; if the date in the current UTC year would be
    // in the future, it belongs to the previous year.
    private val SHORT_DATE = Regex("""^(\d{1,2})[-/.](\d{1,2})$""")

    /** Scan a list of visible UI texts and return the first parseable date as epoch ms. */
    fun parseDateFromTexts(texts: List<String>, nowMs: Long): Long? {
        for (t in texts) {
            parseDateText(t, nowMs)?.let { return it }
        }
        return null
    }

    fun parseDateText(text: String, nowMs: Long): Long? {
        // v2.0: normalize Arabic-Indic digits + lowercase once, up front.
        val s = normalizeDigits(text).trim().lowercase()

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

        // v2.0: today / yesterday / dual forms.
        when (s) {
            "الآن", "now", "just now", "today", "اليوم" -> return nowMs
            "أمس", "امس", "yesterday" -> return nowMs - 86_400_000L
        }
        REL_AR_DUAL.matchEntire(s)?.let { m ->
            val deltaMs = when (m.groupValues[1]) {
                "يومان", "يومين" -> 2L * 86_400_000L
                "أسبوعان", "أسبوعين", "اسبوعين" -> 14L * 86_400_000L
                "شهرين" -> 60L * 86_400_000L
                "سنتين" -> 730L * 86_400_000L
                "ساعتين" -> 2L * 3_600_000L
                "دقيقتين" -> 2L * 60_000L
                else -> return null
            }
            return nowMs - deltaMs
        }
        REL_AR_SINCE.matchEntire(s)?.let { m ->
            val n = m.groupValues[1].toLong()
            val unitMs = when (m.groupValues[2]) {
                "ثانية", "ثواني" -> 1_000L
                "دقيقة", "دقائق" -> 60_000L
                "ساعة", "ساعات" -> 3_600_000L
                "يوم", "أيام" -> 86_400_000L
                "أسبوع", "أسابيع" -> 604_800_000L
                "شهر", "أشهر" -> 2_592_000_000L   // ~30 days
                "سنة", "سنوات" -> 31_536_000_000L  // ~365 days
                else -> return null
            }
            return nowMs - n * unitMs
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

        // v2.0: short month-day token with year rollover (see SHORT_DATE).
        // Assumes TikTok's month-first order, as on the English UI.
        SHORT_DATE.matchEntire(s)?.let { m ->
            val mo = m.groupValues[1].toIntOrNull() ?: return null
            val d = m.groupValues[2].toIntOrNull() ?: return null
            return try {
                val today = LocalDate.ofInstant(Instant.ofEpochMilli(nowMs), ZoneOffset.UTC)
                var candidate = LocalDate.of(today.year, mo, d)
                    .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                if (candidate > nowMs + 86_400_000L) {
                    candidate = LocalDate.of(today.year - 1, mo, d)
                        .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                }
                candidate
            } catch (e: Exception) {
                null
            }
        }

        // TikTok sometimes shows a bare "3d" / "2w" token next to the author name.
        if (s.length <= 8) {
            REL_EN_SHORT.matchEntire(s)?.let { m ->
                val n = m.groupValues[1].toLong()
                val unitMs = when (m.groupValues[2]) {
                    "m" -> 60_000L
                    "h" -> 3_600_000L
                    "d" -> 86_400_000L
                    "w" -> 604_800_000L
                    "mo" -> 2_592_000_000L   // ~30 days
                    "y" -> 31_536_000_000L   // ~365 days
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
