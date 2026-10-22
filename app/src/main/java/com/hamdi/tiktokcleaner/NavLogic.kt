package com.hamdi.tiktokcleaner

/**
 * v1.5: pure helpers for Phase-1 navigation (finding TikTok's Profile tab
 * in the bottom bar). Deliberately Android-free, like [GuardDecisions], so
 * the marker matching and the diagnostic formatting are executable in
 * plain JVM tests (see NavLogicTest). The service supplies live-tree data;
 * this object only decides "does this label name the Profile tab" and
 * formats what was seen for the log.
 *
 * Why this exists: on the user's phone (first real on-device log,
 * 2026-10-22) v1.4's navigation searched ONCE, immediately after TikTok
 * came to the foreground, for the exact marker set — and found nothing, so
 * navigation was skipped and the (correctly protective) verification
 * aborted the run. The matching here is broader ("Me" builds, suffixed
 * accessibility labels) and the service now polls instead of reading once.
 */
object NavLogic {

    /**
     * Labels that name TikTok's bottom-bar profile tab. EN primary (the
     * user's TikTok UI is English): "Profile" on most builds, "Me" on
     * others. AR secondary. The v1.3/v1.4 set was
     * {"Profile", "Me", "الملف الشخصي"}; v1.5 adds «حسابي».
     */
    val PROFILE_TAB_MARKERS: Set<String> = setOf(
        "Profile",
        "Me",
        "الملف الشخصي",
        // v1.5 addition: AR "my account" rendering of the same tab
        "حسابي"
    )

    /**
     * Does [label] name the Profile tab? True when the label EQUALS a
     * marker, or STARTS WITH a marker followed by a non-letter boundary
     * ("Profile", "Me", "Profile, 5 of 5", "Me:"). The boundary rule is
     * what keeps "Messages" / "Menu" from matching the "Me" marker.
     */
    fun isProfileTabLabel(label: String): Boolean {
        val l = label.trim()
        if (l.isEmpty()) return false
        return PROFILE_TAB_MARKERS.any { m ->
            l.equals(m, ignoreCase = true) ||
                (l.length > m.length && l.startsWith(m, ignoreCase = true) &&
                    !l[m.length].isLetter())
        }
    }

    /**
     * Format the labels seen in the bottom strip for the diagnostic log
     * line: blanks dropped, duplicates removed (first-seen order kept),
     * each label quoted, item count and total length capped so the line
     * stays screenshot-friendly.
     */
    fun formatBottomStrip(
        labels: List<String>,
        maxItems: Int = 14,
        maxLength: Int = 220
    ): String {
        val seen = LinkedHashSet<String>()
        for (raw in labels) {
            val l = raw.trim()
            if (l.isNotEmpty()) seen.add(l)
        }
        if (seen.isEmpty()) return "(لا عناوين مقروءة في الشريط السفلي)"
        var joined = seen.take(maxItems).joinToString(" | ") { "\"$it\"" }
        if (seen.size > maxItems) joined += " | …"
        if (joined.length > maxLength) joined = joined.substring(0, maxLength) + "…"
        return joined
    }
}
