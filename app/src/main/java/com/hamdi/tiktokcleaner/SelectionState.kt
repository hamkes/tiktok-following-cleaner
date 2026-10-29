package com.hamdi.tiktokcleaner

/**
 * v2.0.3-gemini: pure selection-sync rules for the results list.
 *
 * The bug (live report 2026-10-28): the "تنظيف المحددين" count froze
 * at 379 while candidates grew to 742, and newly classified DEAD rows
 * showed unchecked — because MainActivity seeded the selection ONCE
 * (`if (selectedHandles.isEmpty())`) from a mid-audit snapshot and
 * never added later candidates; and checkbox toggles lived only in
 * memory, so any Activity recreation silently reverted them to the
 * saved file's defaults. These rules fix both:
 *  - results never seen before join the selection iff their persisted
 *    selectedForCleanup flag is true (new candidates are pre-selected);
 *  - results seen before keep the user's in-memory choice — a refresh
 *    never undoes a toggle;
 *  - results absent from the file (already unfollowed) leave the set;
 *  - an emptied selection stays empty (no resurrection on refresh).
 * Pure; unit-tested.
 */
object SelectionState {

    fun syncSelection(
        known: Set<String>,
        selected: MutableSet<String>,
        loaded: List<AuditResult>
    ): Set<String> {
        val loadedKeys = loaded.map { it.username.lowercase() }.toSet()
        selected.retainAll(loadedKeys)
        val newlySeen = HashSet<String>()
        for (r in loaded) {
            val key = r.username.lowercase()
            if (key !in known) {
                newlySeen.add(key)
                if (r.selectedForCleanup) selected.add(key)
            }
        }
        return newlySeen
    }

    fun applyToggle(
        results: List<AuditResult>,
        selected: MutableSet<String>,
        username: String,
        checked: Boolean
    ): List<AuditResult> {
        val key = username.lowercase()
        if (checked) selected.add(key) else selected.remove(key)
        return results.map { r ->
            if (r.username.lowercase() == key) r.copy(selectedForCleanup = checked) else r
        }
    }
}
