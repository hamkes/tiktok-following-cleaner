package com.hamdi.tiktokcleaner

/**
 * Pure helpers for Phase-1 navigation (finding TikTok's Profile tab in the bottom bar
 * and locating the Following counter on the user's own profile screen).
 * Deliberately Android-free so all marker matching and diagnostics run in JVM unit tests.
 */
object NavLogic {

    val PROFILE_TAB_MARKERS: Set<String> = setOf(
        "Profile",
        "Me",
        "الملف الشخصي",
        "صفحتي",
        "حسابي",
        "Profil",
        "Moi"
    )

    val FOLLOWING_COUNTER_MARKERS: Set<String> = setOf(
        "Following",
        "أتابع",
        "أتابعهم",
        "المتابَعون",
        "متابَع",
        "Abonnements",
        "Takip edilen"
    )

    fun isProfileTabLabel(label: String): Boolean {
        val l = label.trim()
        if (l.isEmpty()) return false
        return PROFILE_TAB_MARKERS.any { m ->
            l.equals(m, ignoreCase = true) ||
                (l.length > m.length && l.startsWith(m, ignoreCase = true) &&
                    !l[m.length].isLetter())
        }
    }

    fun isFollowingCounterLabel(label: String): Boolean {
        val l = label.trim()
        if (l.isEmpty()) return false
        return FOLLOWING_COUNTER_MARKERS.any { m ->
            l.equals(m, ignoreCase = true) ||
                l.endsWith(" $m", ignoreCase = true) ||
                l.startsWith("$m ", ignoreCase = true)
        }
    }

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
        if (seen.isEmpty()) return "(لا توجد عناصر نصية في الشريط)"
        var joined = seen.take(maxItems).joinToString(" | ") { "\"$it\"" }
        if (seen.size > maxItems) joined += " | ..."
        if (joined.length > maxLength) joined = joined.substring(0, maxLength) + "..."
        return joined
    }
}
