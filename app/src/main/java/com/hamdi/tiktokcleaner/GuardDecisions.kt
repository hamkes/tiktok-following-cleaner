package com.hamdi.tiktokcleaner

/**
 * v1.4: the pure decision core behind the v1.3 wrong-list protection.
 *
 * v1.3 implemented these decisions inside CleanerAccessibilityService as
 * private methods over live AccessibilityNodeInfo trees, which made them
 * impossible to unit-test on a JVM. Here they operate on plain
 * [NodeSnapshot] data — text, contentDescription, a precomputed
 * selected-chain flag and integer bounds — with NO Android framework types,
 * so the real decision logic is executable in plain JUnit tests (see
 * GuardDecisionsTest). The service maps the live tree to snapshots and
 * calls this core; it never re-implements a decision.
 *
 * Semantics are v1.3's, EXACTLY:
 *  - Tab confirmation only via the Following tab's SELECTED state, or —
 *    when TikTok exposes no selected state at all — via the Following-only
 *    "Sort by" control. Presence of the bare labels proves nothing (they
 *    also appear as profile stats and per-row buttons).
 *  - A wide (>=35% of screen width) follow-state button in the
 *    upper-middle band (top 15%..62%) is the profile's primary button;
 *    narrow/icon-only/lower buttons never decide anything.
 *  - Ambiguity ALWAYS resolves to INCONCLUSIVE / UNKNOWN — never to
 *    CONFIRMED / FOLLOWED.
 *
 * v1.6 changes (matching only — the safety rules above are untouched):
 * on the user's TikTok build the list-header tabs CARRY COUNTS
 * ("Following 9,947", "Followers 18.9K", "Friends 226"), so v1.3–v1.5's
 * exact-match tab detection never recognised the tab row at all, the
 * Sort-by fallback stayed gated off, and a run standing on the CORRECT
 * Following list was refused (device log 2026-10-22 12:39: verification
 * NOT_LIST, diagnostics showing only a bare title "Following" while
 * Sort-by was present). Now: tab labels match count-aware (marker +
 * separator + a leading digit), the tab row is recognised by BAND
 * (>= 2 distinct tab kinds in the top 30% of the screen, when the root
 * height is known), and the fallback is un-gated accordingly. A suffix
 * that continues with a LETTER ("Following you") is NOT a tab label.
 */
object GuardDecisions {

    // ---------------- marker sets (EN primary, AR secondary) ----------------
    // v1.3 members are preserved verbatim; v1.4 additions are marked and are
    // itemized in BUILD_NOTES.md. Additions only ever ADD positive matches
    // for one specific state; they never weaken the ambiguity rules above.

    val TAB_FOLLOWING: Set<String> = setOf(
        "Following",
        "المتابَعة",
        // v1.4 additions: unvocalized AR + the AR "I follow" tab label
        "المتابعة",
        "أتابع"
    )

    val TAB_FOLLOWERS: Set<String> = setOf(
        "Followers",
        "المتابعون",
        // v1.4 additions: vocalized + article-less AR variants
        "المتابِعون",
        "متابعون"
    )

    val TAB_FRIENDS: Set<String> = setOf(
        "Friends",
        "الأصدقاء",
        // v1.4 addition: article-less AR variant
        "أصدقاء"
    )

    /** "Sort by" exists on the Following view only — the fallback signal. */
    val SORT_BY_MARKERS: Set<String> = setOf(
        "Sort by",
        "الترتيب حسب",
        // v1.4 additions: the explicit default-sort label (already subsumed
        // by the "Sort by" contains-match; kept explicit for documentation)
        // + an alternative AR phrasing of the same control.
        "Sort by: Default",
        "فرز حسب"
    )

    /** Primary-button labels proving the user FOLLOWS the opened profile. */
    val FOLLOWED_BUTTONS: Set<String> = setOf(
        "Following",
        "أتابع",
        "Friends",
        "الأصدقاء",
        // v1.4 addition: article-less AR variant
        "أصدقاء"
    )

    /** Primary-button labels proving the user does NOT follow the profile. */
    val NOT_FOLLOWED_BUTTONS: Set<String> = setOf(
        "Follow",
        "Follow back",
        "متابعة",
        // v1.4 addition: one-word rendering of the follow-back button
        "Followback"
    )

    // ---------------- snapshot model ----------------

    /**
     * One accessibility node flattened to plain data.
     *
     * [selectedInChain] replicates v1.3's isSelectedChain(): true when this
     * node OR any ancestor up to 4 hops up is marked selected (the tab
     * container may carry the state rather than the label node itself).
     * Bounds are absolute screen pixels, exactly as getBoundsInScreen
     * reports them.
     */
    data class NodeSnapshot(
        val text: String? = null,
        val contentDescription: String? = null,
        val selectedInChain: Boolean = false,
        val left: Int = 0,
        val top: Int = 0,
        val right: Int = 0,
        val bottom: Int = 0,
        // v1.8: needed by pickScrollTarget — which scrollable node a list
        // scroll may act on (see below). Defaulted so every existing
        // construction (and every pre-v1.8 test) is unaffected.
        val className: String = "",
        val scrollable: Boolean = false
    ) {
        /** Same definition as v1.3's nodeLabel(): text, else description, trimmed. */
        val label: String
            get() = (text ?: contentDescription ?: "").trim()

        val width: Int
            get() = right - left
    }

    // ---------------- tab verification ----------------

    /**
     * v1.6: does [label] name the tab [marker]? True when the label EQUALS
     * the marker, or starts with the marker followed by a COUNT: after the
     * marker, separator characters (space, "·", "•", "(", ":", … — anything
     * that is neither a letter nor a digit) may appear, and the first
     * meaningful character after them must be a DIGIT. So
     * "Following 9,947" and "Followers 18.9K" are tab labels, while
     * "Following you" — marker + space + a WORD — is not: a suffix that
     * continues with a letter is a different phrase, never a count.
     * Case-insensitive, like every match in this core.
     */
    fun matchesTabLabel(label: String, marker: String): Boolean {
        val l = label.trim()
        if (l.equals(marker, ignoreCase = true)) return true
        if (l.length <= marker.length || !l.startsWith(marker, ignoreCase = true)) return false
        var i = marker.length
        while (i < l.length && !l[i].isLetterOrDigit()) i++
        return i < l.length && l[i].isDigit()
    }

    /** Which list tab a label names: "following" / "followers" / "friends", or null. */
    fun tabKindOf(label: String): String? = when {
        TAB_FOLLOWING.any { matchesTabLabel(label, it) } -> "following"
        TAB_FOLLOWERS.any { matchesTabLabel(label, it) } -> "followers"
        TAB_FRIENDS.any { matchesTabLabel(label, it) } -> "friends"
        else -> null
    }

    /** Raw signals extracted from one screen snapshot. */
    data class TabScan(
        val tabRowPresent: Boolean,
        // v1.6: per-kind presence (needed by the un-gated fallback, which
        // must know the Following tab itself is in the row).
        val followingTabPresent: Boolean,
        val followersTabPresent: Boolean,
        val friendsTabPresent: Boolean,
        val followingSelected: Boolean,
        val followersSelected: Boolean,
        val friendsSelected: Boolean,
        val hasSortBy: Boolean,
        // v2.0: a Following-only header anchor (search/date-followed
        // control) is present. Defaulted so existing constructions
        // (tests included) are unaffected.
        val hasFollowingAnchor: Boolean = false
    )

    enum class TabVerdict { CONFIRMED, WRONG_TAB, INCONCLUSIVE }

    data class TabDecision(
        val verdict: TabVerdict,
        val evidence: String,
        val scan: TabScan
    )

    /**
     * One pass over the snapshot collecting the list-tab signals.
     *
     * Tab labels match count-aware (see [matchesTabLabel]) because the
     * header tabs carry counts on current TikTok builds. When [rootHeight]
     * is known (> 0), a node only counts as a tab label if it sits in the
     * TOP BAND — its top edge within the top 30% of the screen — and the
     * tab ROW is recognised when >= 2 DISTINCT tab kinds appear in that
     * band, whatever container holds them (v1.6; v1.3–v1.5 required exact
     * "Following"+"Followers" labels anywhere on screen). With
     * [rootHeight] = 0 (the legacy call shape) no band filter is applied.
     * The bare labels also appear as profile stats and per-row buttons, so
     * presence alone proves nothing — the SELECTED state is the signal.
     */
    fun scanTabs(nodes: List<NodeSnapshot>, rootHeight: Int = 0): TabScan {
        // v2.0: merged sibling labels are ADDED to the originals, so a
        // split "Following" + "9,946" tab is seen as one count label.
        val effective = mergeSiblingLabels(nodes, rootHeight)
        var followingTab = false
        var followersTab = false
        var friendsTab = false
        var followingSel = false
        var followersSel = false
        var friendsSel = false
        var sortBy = false
        var anchor = false
        for (n in effective) {
            val label = n.label
            if (label.isEmpty()) continue
            val inBand = rootHeight <= 0 || n.top <= rootHeight * TAB_BAND_FRACTION
            if (inBand) {
                when (tabKindOf(label)) {
                    "following" -> {
                        followingTab = true
                        if (n.selectedInChain) followingSel = true
                    }
                    "followers" -> {
                        followersTab = true
                        if (n.selectedInChain) followersSel = true
                    }
                    "friends" -> {
                        friendsTab = true
                        if (n.selectedInChain) friendsSel = true
                    }
                }
            }
            if (SORT_BY_MARKERS.any { label.contains(it, true) }) sortBy = true
            if (FOLLOWING_ANCHOR_MARKERS.any { label.contains(it, true) }) anchor = true
        }
        val kinds = (if (followingTab) 1 else 0) + (if (followersTab) 1 else 0) +
            (if (friendsTab) 1 else 0)
        return TabScan(
            tabRowPresent = kinds >= 2,
            followingTabPresent = followingTab,
            followersTabPresent = followersTab,
            friendsTabPresent = friendsTab,
            followingSelected = followingSel,
            followersSelected = followersSel,
            friendsSelected = friendsSel,
            hasSortBy = sortBy,
            hasFollowingAnchor = anchor
        )
    }

    /**
     * v2.0: full-phrase controls that exist only on the Following list
     * screen ("Search following", "Date followed", ...). Like Sort-by,
     * they are fallback evidence only: they can confirm a screen that
     * already shows the Following tab, never override a selected
     * Followers/Friends tab.
     */
    val FOLLOWING_ANCHOR_MARKERS: Set<String> = setOf(
        "search following",
        "البحث في المتابَعين",
        "البحث في المتابعين",
        "date followed",
        "تاريخ المتابعة",
        "الأحدث متابعة",
        "الأقدم متابعة"
    )

    /**
     * v2.0 (Gemini idea, re-implemented on this core's snapshot shape):
     * TikTok sometimes splits a tab label across two sibling nodes —
     * "Following" in one and its count "9,946" in the next. The
     * count-aware matcher already accepts "Following 9,946" as ONE
     * label, but never sees it when the words arrive separately, so
     * verification fails on an actually-correct screen. Merge an
     * adjacent same-row pair ONLY when the concatenation names a tab
     * kind; merged nodes are ADDED to the originals, never replacing
     * them. The left node must be a BARE tab word and the right node a
     * bare count (starts with a digit, no letters except a K/M suffix),
     * so two neighbouring full tab labels can never fuse into a false
     * signal — a selected "Followers 18.9K" next to "Following" must
     * still read WRONG_TAB. Pairs must be strictly rightward with a
     * small gap, which also keeps geometry-less fixtures unmerged.
     */
    fun mergeSiblingLabels(
        nodes: List<NodeSnapshot>,
        rootHeight: Int = 0
    ): List<NodeSnapshot> {
        val inBand = nodes.filter { n ->
            n.label.isNotEmpty() && (rootHeight <= 0 || n.top <= rootHeight * TAB_BAND_FRACTION)
        }.sortedWith(compareBy({ (it.top + it.bottom) / 2 }, { it.left }))
        val merged = ArrayList<NodeSnapshot>()
        var i = 0
        while (i + 1 < inBand.size) {
            val a = inBand[i]
            val b = inBand[i + 1]
            val dy = (a.top + a.bottom) / 2 - (b.top + b.bottom) / 2
            val gap = b.left - a.right
            val bText = b.label.trim()
            val bIsCount = bText.isNotEmpty() && bText[0].isDigit() &&
                bText.none { it.isLetter() && it != 'K' && it != 'k' && it != 'M' && it != 'm' }
            val aIsBareTabWord = (TAB_FOLLOWING + TAB_FOLLOWERS + TAB_FRIENDS)
                .any { a.label.equals(it, ignoreCase = true) }
            val combined = "${a.label} ${b.label}"
            if (dy in -24..24 && b.left > a.left && gap in -8..80 &&
                aIsBareTabWord && bIsCount && tabKindOf(combined) != null
            ) {
                merged.add(
                    a.copy(
                        text = combined,
                        right = b.right,
                        bottom = maxOf(a.bottom, b.bottom),
                        selectedInChain = a.selectedInChain || b.selectedInChain
                    )
                )
                i += 2
            } else {
                i += 1
            }
        }
        return if (merged.isEmpty()) nodes else nodes + merged
    }

    private val SUGGESTED_SECTION_HEADERS = listOf(
        "suggested accounts",
        "accounts you may know",
        "suggested for you",
        "حسابات مقترحة",
        "أشخاص قد تعرفهم",
        "حسابات قد تعجبك",
        "اقتراحات"
    )

    /**
     * v2.0 (Gemini idea, re-implemented): the top edge of the
     * "Suggested accounts" section header on the Following list
     * screen, or null when no such section is visible. Collection
     * stops above this line, so suggested/LIVE handles can never
     * enter the list. Headers above the top 15% of the screen are
     * ignored (a section header is never that high), and over-long
     * labels are ignored so a bio mentioning "suggested" cannot fake
     * a boundary.
     */
    fun findSuggestedBoundaryY(nodes: List<NodeSnapshot>, rootHeight: Int): Int? {
        if (rootHeight <= 0) return null
        val floor = (rootHeight * 0.15).toInt()
        var best: Int? = null
        for (n in nodes) {
            val label = n.label
            if (label.isEmpty() || label.length > 60 || n.top <= floor) continue
            if (SUGGESTED_SECTION_HEADERS.any { label.contains(it, true) }) {
                if (best == null || n.top < best) best = n.top
            }
        }
        return best
    }

    /** Top band of the screen the list-header tab row lives in (v1.6). */
    const val TAB_BAND_FRACTION: Double = 0.30

    /**
     * Decide whether the scanned screen is the Following list.
     *
     * Branch order is v1.3's, verbatim:
     *  1. No recognisable tab row -> INCONCLUSIVE (v1.3's NOT_LIST; the
     *     service maps it back via [TabScan.tabRowPresent]).
     *  2. Following selected -> CONFIRMED.
     *  3. Followers or Friends selected -> WRONG_TAB. Unchanged hard
     *     safety: a selected wrong tab beats the Sort-by fallback below —
     *     it is NEVER overridden by presence or by Sort-by.
     *  4. Fallback (v1.3 intent, un-gated in v1.6; v2.0 adds the
     *     Following-only header anchors): row recognised, no tab exposes
     *     a selected state, the Following tab is IN the row, and the
     *     Following-only "Sort by" control or a Following-only header
     *     anchor is on screen -> CONFIRMED. A fallback signal remains
     *     mandatory: without it, presence proves nothing.
     *  5. Anything else -> INCONCLUSIVE. Never CONFIRMED on ambiguity.
     */
    fun decideTab(scan: TabScan): TabDecision {
        if (!scan.tabRowPresent) {
            return TabDecision(
                TabVerdict.INCONCLUSIVE,
                "tab row missing — not a user-list screen (or labels unreadable)",
                scan
            )
        }
        if (scan.followingSelected) {
            return TabDecision(TabVerdict.CONFIRMED, "Following tab is selected", scan)
        }
        if (scan.followersSelected || scan.friendsSelected) {
            val which = if (scan.followersSelected) "Followers" else "Friends"
            return TabDecision(TabVerdict.WRONG_TAB, "$which tab is selected", scan)
        }
        if (scan.followingTabPresent && (scan.hasSortBy || scan.hasFollowingAnchor)) {
            val via = if (scan.hasSortBy) {
                "the Following-only 'Sort by' control"
            } else {
                "a Following-only header anchor ('Search following' / 'Date followed')"
            }
            return TabDecision(
                TabVerdict.CONFIRMED,
                "no selected state exposed — confirmed via Following tab in the header row + $via (fallback)",
                scan
            )
        }
        return TabDecision(
            TabVerdict.INCONCLUSIVE,
            "tab row present but no tab reports selected and the 'Sort by' fallback is unavailable or the Following tab is not in the row",
            scan
        )
    }

    fun decideTab(nodes: List<NodeSnapshot>): TabDecision = decideTab(scanTabs(nodes))

    /** v1.6: same decision with the screen height known (band-checked row). */
    fun decideTab(nodes: List<NodeSnapshot>, rootHeight: Int): TabDecision =
        decideTab(scanTabs(nodes, rootHeight))

    // ---------------- scroll-target selection (v1.8) ----------------

    /**
     * Class-name fragments marking a horizontal TAB PAGER (the container
     * that hosts the Following / Followers / Friends pages side by side).
     * Covers androidx ViewPager and ViewPager2.
     */
    private val PAGER_CLASS_MARKERS = listOf("ViewPager")

    /** True when [className] belongs to a tab pager rather than a list. */
    fun isPagerClass(className: String): Boolean =
        PAGER_CLASS_MARKERS.any { className.contains(it) }

    /**
     * v1.8 — choose which scrollable node a "scroll the list forward"
     * action may target. Returns an index into [nodes], or null when no
     * safe target exists (the caller must then NOT scroll at all).
     *
     * Why this exists (device evidence, 2026-10-22): up to v1.7.1 the
     * service scrolled the LARGEST scrollable node on screen. On TikTok's
     * user-list screen that node is the tab PAGER, and
     * ACTION_SCROLL_FORWARD on a pager means "show the next page" — the
     * tool itself flipped the Following tab to Followers mid-run, the
     * mid-run guard then (correctly) stopped the audit as WRONG_TAB after
     * a single collected name. Reproduced identically in two device runs.
     *
     * Rule: candidates are the scrollable nodes; pager-class nodes are
     * excluded unconditionally; among the rest the largest on-screen area
     * wins (ties keep the earliest in traversal order). If only pagers are
     * scrollable, the answer is null — never scroll a pager.
     */
    fun pickScrollTarget(nodes: List<NodeSnapshot>): Int? {
        var best: Int? = null
        var bestArea = -1L
        nodes.forEachIndexed { i, n ->
            if (!n.scrollable) return@forEachIndexed
            if (isPagerClass(n.className)) return@forEachIndexed
            val area = n.width.toLong() * (n.bottom - n.top).toLong()
            if (area > bestArea) {
                bestArea = area
                best = i
            }
        }
        return best
    }

    // ---------------- header diagnostics (v1.6) ----------------

    /**
     * Format the verbatim labels of the text-bearing nodes in the top
     * band of the screen for the «تشخيص التبويبات» log line: deduped,
     * ordered left-to-right (then top-to-bottom), each quoted, item count
     * and total length capped like the v1.5 bottom-strip diagnostic — so
     * that when verification still fails, the log shows the header
     * EXACTLY as TikTok renders it (counts and all).
     */
    fun formatHeaderTexts(
        nodes: List<NodeSnapshot>,
        rootHeight: Int,
        maxItems: Int = 14,
        maxLength: Int = 220
    ): String {
        val inBand = nodes.filter { n ->
            n.label.isNotEmpty() && (rootHeight <= 0 || n.top <= rootHeight * TAB_BAND_FRACTION)
        }.sortedWith(compareBy({ it.left }, { it.top }))
        val seen = LinkedHashSet<String>()
        for (n in inBand) seen.add(n.label)
        if (seen.isEmpty()) return "(لا نصوص مقروءة في أعلى الشاشة)"
        var joined = seen.take(maxItems).joinToString(" | ") { "\"$it\"" }
        if (seen.size > maxItems) joined += " | …"
        if (joined.length > maxLength) joined = joined.substring(0, maxLength) + "…"
        return joined
    }

    // ---------------- profile follow-state ----------------

    enum class FollowState { FOLLOWED, NOT_FOLLOWED, UNKNOWN }

    /**
     * Read the opened profile's PRIMARY follow-state button from a snapshot.
     * Only a wide button (>=35% of the root/screen width) in the
     * upper-middle band (top 15%..62% of the height) qualifies — that
     * excludes the narrow stats labels at the top and the small per-row
     * buttons of any suggested-accounts strip lower down. When several
     * qualify, the topmost wins (v1.3 rule). No qualifying button — gone
     * accounts, icon-only buttons, the user's own profile — is UNKNOWN.
     */
    fun followState(nodes: List<NodeSnapshot>, rootWidth: Int, rootHeight: Int): FollowState {
        if (rootWidth == 0 || rootHeight == 0) return FollowState.UNKNOWN
        var best = FollowState.UNKNOWN
        var bestTop = Int.MAX_VALUE
        for (n in nodes) {
            val label = n.label
            val st = when {
                FOLLOWED_BUTTONS.any { it.equals(label, true) } -> FollowState.FOLLOWED
                NOT_FOLLOWED_BUTTONS.any { it.equals(label, true) } -> FollowState.NOT_FOLLOWED
                else -> null
            } ?: continue
            val wide = n.width >= rootWidth * 0.35
            val inBand = n.top >= rootHeight * 0.15 && n.top <= rootHeight * 0.62
            if (wide && inBand && n.top < bestTop) {
                best = st
                bestTop = n.top
            }
        }
        return best
    }

    // ---------------- wrong-list tripwire (v1.9) ----------------

    /**
     * v1.9 — evidence-based replacement for "one NOT_FOLLOWED profile
     * nukes the whole audit". Device evidence (2026-10-22): a single stray
     * handle (@itsvira_, a follower who is LIVE — collected from a
     * non-row element on the verified Following screen) reached Phase B,
     * read "Follow back", and the v1.3 rule invalidated the ENTIRE run
     * and deleted all progress, repeatedly on every resume.
     *
     * The protection's PURPOSE is unchanged: never classify a wrong list
     * (the v1.2 disaster audited the Followers list). But a wrong LIST
     * betrays itself statistically — profile after profile reads
     * NOT_FOLLOWED — while a genuine Following list with a stray name or
     * two shows NOT_FOLLOWED only as rare exceptions among FOLLOWED
     * reads. So the decision is now taken on accumulated evidence:
     *
     *  INVALIDATE when (a) the first two decisive reads are both
     *  NOT_FOLLOWED (no followed profile seen yet), or (b) at least 5
     *  decisive reads exist and NOT_FOLLOWED outnumber FOLLOWED.
     *  Otherwise CONTINUE — the caller classifies that one profile as a
     *  skipped contaminant and the run (and its saved progress) lives on.
     *
     * UNKNOWN reads (no readable button) are not evidence either way and
     * change nothing. Pure and deterministic; the service replays saved
     * results through the same class on resume, so a resumed run keeps
     * exactly the tripwire state the original run had.
     */
    class WrongListTripwire {
        var followedCount: Int = 0
            private set
        var notFollowedCount: Int = 0
            private set

        /** Decisive reads so far (UNKNOWN reads are not counted). */
        val decidedCount: Int get() = followedCount + notFollowedCount

        enum class Decision { CONTINUE, INVALIDATE }

        fun onFollowState(state: FollowState): Decision {
            when (state) {
                FollowState.FOLLOWED -> followedCount++
                FollowState.NOT_FOLLOWED -> notFollowedCount++
                FollowState.UNKNOWN -> return Decision.CONTINUE
            }
            return if (shouldInvalidate()) Decision.INVALIDATE else Decision.CONTINUE
        }

        fun shouldInvalidate(): Boolean =
            (notFollowedCount >= 2 && followedCount == 0) ||
                (decidedCount >= 5 && notFollowedCount > followedCount)
    }
}
