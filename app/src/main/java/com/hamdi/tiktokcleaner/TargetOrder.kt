package com.hamdi.tiktokcleaner

/**
 * v2.0.6-gemini: pure ordering rule for unfollow targets.
 *
 * Live log 2026-10-28: a 150-account batch unfollowed only 25 and
 * skipped ~125 as mutual Friends; the skipped accounts stayed at the
 * front of the results file, so the next batch re-checked the same
 * ~125 skips before reaching anything new — ~25 unfollows per batch
 * forever. Ordering remembered Friends-skips LAST (stable order
 * otherwise) makes every batch do fresh work first; the skipped pile
 * accumulates at the end for one explicit decision (turn the Friends
 * protection off, or leave them). Pure; unit-tested.
 */
object TargetOrder {

    fun orderForUnfollow(
        matching: List<AuditResult>,
        friendsSkipped: Set<String>
    ): List<AuditResult> {
        if (friendsSkipped.isEmpty()) return matching
        return matching.sortedWith(
            compareBy { it.username.lowercase() in friendsSkipped }
        )
    }
}
