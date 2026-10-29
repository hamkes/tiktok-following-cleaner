package com.hamdi.tiktokcleaner

import kotlin.math.abs

/**
 * Safety-critical pure decision layer.
 * Protects the user against auditing or unfollowing the wrong list (e.g. Followers or Friends).
 * Upgraded in v2.0 with:
 * - Multi-node tab merging (when TikTok separates "Following" and "9,946" into sibling Views)
 * - Following-exclusive sort/search header anchors (so collapsed tab bars during scroll don't abort runs)
 * - Suggested accounts section boundary detection
 * - Captcha / security verification screen detection
 */
object GuardDecisions {

    enum class TabVerdict {
        CONFIRMED_FOLLOWING,
        WRONG_TAB,
        NO_TABS
    }

    enum class FollowState {
        FOLLOWING,
        FRIENDS,
        FOLLOW_BACK,
        FOLLOW,
        REQUESTED,
        UNKNOWN
    }

    data class NodeSnapshot(
        val text: String? = null,
        val contentDescription: String? = null,
        val className: String? = null,
        val viewIdResourceName: String? = null,
        val isSelected: Boolean = false,
        // v2.0.1-gemini fix: true when this node OR any ancestor up to 4
        // hops up is marked selected (TikTok often puts the selected
        // state on the tab container / indicator, not the label node).
        // Defaulted so every existing construction is unaffected.
        val selectedInChain: Boolean = false,
        val isScrollable: Boolean = false,
        val isClickable: Boolean = false,
        val boundsLeft: Int = 0,
        val boundsTop: Int = 0,
        val boundsRight: Int = 0,
        val boundsBottom: Int = 0
    ) {
        val width: Int get() = (boundsRight - boundsLeft).coerceAtLeast(0)
        val height: Int get() = (boundsBottom - boundsTop).coerceAtLeast(0)
        val area: Long get() = width.toLong() * height.toLong()
        val centerY: Int get() = (boundsTop + boundsBottom) / 2
        val centerX: Int get() = (boundsLeft + boundsRight) / 2

        /** Selected on the node itself or via a selected ancestor. */
        val effectivelySelected: Boolean get() = isSelected || selectedInChain

        fun combinedLabel(): String {
            val t = text?.trim().orEmpty()
            val c = contentDescription?.trim().orEmpty()
            return when {
                t.isNotEmpty() && c.isNotEmpty() && !t.equals(c, ignoreCase = true) -> "$t $c"
                t.isNotEmpty() -> t
                else -> c
            }
        }
    }

    data class TabDecision(
        val verdict: TabVerdict,
        val reason: String,
        val detectedTabs: List<String> = emptyList()
    )

    private val FOLLOWING_TAB_WORDS = setOf(
        "following", "متابع", "متابَع", "المتابعون", "المتابَعون", "أتابع", "أتابعهم", "اتابعهم",
        "abonnements", "takip edilen", "siguiendo"
    )

    private val FOLLOWERS_TAB_WORDS = setOf(
        "followers", "متابعين", "المتابعين", "المتابِعون", "متابعونك", "يتابعونك",
        "abonnés", "takipçi", "seguidores"
    )

    private val FRIENDS_TAB_WORDS = setOf(
        "friends", "أصدقاء", "الأصدقاء", "اصدقاء", "amis", "arkadaşlar", "amigos"
    )

    private val SUGGESTED_TAB_WORDS = setOf(
        "suggested", "مقترح", "المقترحة", "مقترحة", "قد تعرفهم"
    )

    private val FOLLOWING_EXCLUSIVE_ANCHORS = listOf(
        "sort by",
        "date followed",
        "ترتيب حسب",
        "تاريخ المتابعة",
        "search following",
        "البحث في المتابعين",
        "البحث في المتابَعين",
        "الأحدث متابعة",
        "الأقدم متابعة"
    )

    private val SUGGESTED_SECTION_HEADERS = listOf(
        "suggested accounts",
        "accounts you may know",
        "حسابات مقترحة",
        "أشخاص قد تعرفهم",
        "حسابات قد تعجبك"
    )

    private val CAPTCHA_MARKERS = listOf(
        "drag the slider",
        "fit the puzzle",
        "security verification",
        "verify to continue",
        "select 2 objects",
        "اسحب شريط التمرير",
        "التحقق الأمني",
        "أكد أنك إنسان"
    )

    /** Which list tab a label names: "following" / "followers" / "friends" / "suggested", or null. */
    private fun tabKindOfLabel(raw: String): String? {
        val normalized = stripCountsAndPunctuation(raw)
        return when {
            matchesAnyWord(normalized, FOLLOWING_TAB_WORDS) -> "following"
            matchesAnyWord(normalized, FOLLOWERS_TAB_WORDS) -> "followers"
            matchesAnyWord(normalized, FRIENDS_TAB_WORDS) -> "friends"
            matchesAnyWord(normalized, SUGGESTED_TAB_WORDS) -> "suggested"
            else -> null
        }
    }

    /**
     * v2.0.1-gemini fix: TikTok renders the tab row TWICE (the live log
     * shows "Following 9948" AND "Following 9,948" plus bare "Following"
     * repeats) and the off-screen copy carries STALE selected flags, so
     * collapsing all rows into one slot per kind lets a stale flag on
     * the duplicate row veto the visible row. Decide from ONE row only:
     * cluster tab-kind nodes into horizontal bands (centerY within
     * 40 px), keep the band holding the most distinct tab kinds
     * (ties: the topmost). v2.0.2: the band where Following is
     * effectively selected IS the visible band and outranks a fuller
     * band whose selected flag is stale. Pure; unit-tested.
     */
    fun pickTabRow(nodes: List<NodeSnapshot>): List<NodeSnapshot> {
        val kinded = nodes.mapNotNull { n ->
            val label = n.combinedLabel().trim()
            if (label.isEmpty() || label.length > 42) null
            else tabKindOfLabel(label)?.let { n to it }
        }
        if (kinded.isEmpty()) return emptyList()
        val sorted = kinded.sortedBy { it.first.centerY }
        val clusters = ArrayList<MutableList<Pair<NodeSnapshot, String>>>()
        for (entry in sorted) {
            val last = clusters.lastOrNull()
            if (last != null && entry.first.centerY - last.last().first.centerY <= 40) {
                last.add(entry)
            } else {
                clusters.add(mutableListOf(entry))
            }
        }
        val best = clusters.maxWithOrNull(
            compareBy(
                { cluster: List<Pair<NodeSnapshot, String>> ->
                    if (cluster.any { it.second == "following" && it.first.effectivelySelected }) 1 else 0
                },
                { cluster: List<Pair<NodeSnapshot, String>> -> cluster.map { it.second }.toSet().size },
                { cluster: List<Pair<NodeSnapshot, String>> -> -cluster.minOf { it.first.centerY } }
            )
        )
        return best?.map { it.first }.orEmpty()
    }

    fun decideTab(nodes: List<NodeSnapshot>): TabDecision {
        if (nodes.isEmpty()) {
            return TabDecision(TabVerdict.NO_TABS, "شجرة العناصر فارغة")
        }

        // Scan top region of screen for tab bar candidates (top 35% or top 650px)
        val topNodes = nodes.filter { it.boundsBottom in 1..750 || it.boundsTop == 0 }
        // v2.0.1-gemini fix: scope the decision to ONE tab row (see
        // pickTabRow) — duplicate rows with stale selected flags must
        // not veto the visible row.
        val rowNodes = pickTabRow(topNodes)
        var followingNode: NodeSnapshot? = null
        var followersNode: NodeSnapshot? = null
        var friendsNode: NodeSnapshot? = null
        var suggestedNode: NodeSnapshot? = null
        val labelsFound = mutableListOf<String>()

        for (node in rowNodes) {
            val raw = node.combinedLabel().trim()
            if (raw.isEmpty() || raw.length > 42) continue
            val normalized = stripCountsAndPunctuation(raw)
            when {
                matchesAnyWord(normalized, FOLLOWING_TAB_WORDS) -> {
                    labelsFound.add(raw)
                    if (followingNode == null || node.effectivelySelected) followingNode = node
                }
                matchesAnyWord(normalized, FOLLOWERS_TAB_WORDS) -> {
                    labelsFound.add(raw)
                    if (followersNode == null || node.effectivelySelected) followersNode = node
                }
                matchesAnyWord(normalized, FRIENDS_TAB_WORDS) -> {
                    labelsFound.add(raw)
                    if (friendsNode == null || node.effectivelySelected) friendsNode = node
                }
                matchesAnyWord(normalized, SUGGESTED_TAB_WORDS) -> {
                    labelsFound.add(raw)
                    if (suggestedNode == null || node.effectivelySelected) suggestedNode = node
                }
            }
        }

        val hasExclusiveAnchor = nodes.any { n ->
            val lbl = n.combinedLabel().lowercase()
            FOLLOWING_EXCLUSIVE_ANCHORS.any { anchor -> lbl.contains(anchor) }
        }

        // v2.0.2: Following-selected wins. The selected flag may sit
        // on a nearby indicator sibling (no text of its own), so
        // proximity over the whole top band counts too — evaluated
        // BEFORE any wrong-tab verdict, so a stale flag on a duplicate
        // row can never veto the visible row. The indicator must not
        // itself be another tab's label.
        val selectedNearFollowing = run {
            val f = followingNode ?: return@run false
            topNodes.any { n ->
                n.effectivelySelected &&
                    tabKindOfLabel(n.combinedLabel().trim()) == null &&
                    abs(n.centerX - f.centerX) <= 140 &&
                    abs(n.centerY - f.centerY) <= 110
            }
        }
        if (followingNode?.effectivelySelected == true || selectedNearFollowing) {
            return TabDecision(
                TabVerdict.CONFIRMED_FOLLOWING,
                "تم تأكيد تبويب المتابَعين (isSelected=true)",
                labelsFound
            )
        }

        // Case 1: Explicit selection flag on one of the tabs, while
        // Following is NOT selected anywhere sensible.
        if (followersNode?.effectivelySelected == true || friendsNode?.effectivelySelected == true || suggestedNode?.effectivelySelected == true) {
            val selectedLabels = rowNodes.filter { it.effectivelySelected }
                .map { it.combinedLabel().trim() }
            return TabDecision(
                TabVerdict.WRONG_TAB,
                "تم رصد تحديد تبويب آخر غير المتابَعين (${labelsFound.joinToString()}) [المحدد: ${selectedLabels.joinToString()}]",
                labelsFound
            )
        }

        // Case 2: Tabs are visible with a Following-only anchor and no
        // tab exposes a selected state (proximity handled above).
        val distinctTabTypes = listOfNotNull(followingNode, followersNode, friendsNode, suggestedNode).size
        if (followingNode != null && distinctTabTypes >= 2 && hasExclusiveAnchor) {
            return TabDecision(
                TabVerdict.CONFIRMED_FOLLOWING,
                "تم تأكيد تبويب المتابَعين عبر المؤشر المدمج/شريط الفرز",
                labelsFound
            )
        }

        // Case 3: Single "Following" title header or collapsed tab bar with exclusive sort/search anchor
        if (hasExclusiveAnchor && followersNode?.effectivelySelected != true && friendsNode?.effectivelySelected != true) {
            return TabDecision(
                TabVerdict.CONFIRMED_FOLLOWING,
                "تم تأكيد قائمة المتابَعين عبر عناصر الفرز/البحث الحصرية",
                labelsFound
            )
        }

        if (distinctTabTypes >= 2) {
            return TabDecision(
                TabVerdict.WRONG_TAB,
                "شريط التبويبات ظاهر لكن تبويب المتابَعين غير محدد",
                labelsFound
            )
        }

        return TabDecision(TabVerdict.NO_TABS, "لم يتم العثور على شريط تبويبات المتابعة", labelsFound)
    }

    /**
     * Picks the true vertical list container (RecyclerView / ListView / ScrollView)
     * and strictly excludes ViewPager / ViewPager2 / HorizontalScrollView so scrolling
     * never flips horizontally between Following and Followers tabs!
     */
    fun pickScrollTarget(nodes: List<NodeSnapshot>): NodeSnapshot? {
        val scrollableCandidates = nodes.filter { node ->
            if (!node.isScrollable) return@filter false
            val cls = node.className?.lowercase().orEmpty()
            val id = node.viewIdResourceName?.lowercase().orEmpty()
            val isPager = cls.contains("viewpager") ||
                cls.contains("horizontalscroll") ||
                id.contains("viewpager") ||
                id.contains("tab_layout")
            !isPager && node.height >= 200 && node.width >= 200
        }
        return scrollableCandidates.maxByOrNull { it.area }
    }

    /**
     * Detects the Y-coordinate where TikTok's "Suggested accounts" footer begins,
     * so the collector ignores any handles below this coordinate.
     */
    fun findSuggestedBoundaryY(nodes: List<NodeSnapshot>): Int? {
        for (node in nodes) {
            val lbl = node.combinedLabel().trim().lowercase()
            if (lbl.isEmpty() || lbl.length > 60) continue
            if (SUGGESTED_SECTION_HEADERS.any { lbl.contains(it) } && node.boundsTop > 180) {
                return node.boundsTop
            }
        }
        return null
    }

    fun isCaptchaOrChallengeScreen(nodes: List<NodeSnapshot>): Boolean {
        return nodes.any { node ->
            val lbl = node.combinedLabel().lowercase()
            lbl.isNotEmpty() && CAPTCHA_MARKERS.any { lbl.contains(it) }
        }
    }

    /**
     * v2.0.7-gemini: LAST match wins, not first. Live proof 2026-10-28
     * (@jaxvalormlbb): the profile header renders the "Following"
     * COUNTER label ("80 / Following") BEFORE the real action button
     * ("Friends▼"), and captureSnapshots() is BFS order, so the old
     * first-match code read every Friends profile as FOLLOWING, the
     * Friends protection never fired, and the click landed on the
     * counter (opening the Following list) while the run logged a
     * success and deleted the account. The button row sits BELOW the
     * counter row, so the last state label in the header is the
     * button's own state. Pure; unit-tested (FollowStateTest).
     */
    fun decideFollowState(
        nodes: List<NodeSnapshot>,
        screenWidth: Int = 1080,
        screenHeight: Int = 2400
    ): FollowState {
        // Focus on the profile header area (top 60% of the screen)
        val headerLimitY = (screenHeight * 0.65).toInt().coerceAtLeast(900)
        var result = FollowState.UNKNOWN
        for (node in nodes) {
            if (node.boundsTop > headerLimitY) continue
            val raw = node.combinedLabel().trim().lowercase()
            if (raw.isEmpty() || raw.length > 35) continue
            when {
                raw == "friends" || raw == "أصدقاء" || raw == "اصدقاء" || raw == "amis" ->
                    result = FollowState.FRIENDS
                raw == "following" || raw == "أتابعه" || raw == "متابَع" || raw == "تتابعه" || raw == "abonné" || raw == "takip ediliyor" ->
                    result = FollowState.FOLLOWING
                raw == "requested" || raw == "تم الطلب" || raw == "معلق" ->
                    result = FollowState.REQUESTED
                raw == "follow back" || raw == "رد المتابعة" || raw == "متابعة أيضاً" ->
                    result = FollowState.FOLLOW_BACK
                raw == "follow" || raw == "متابعة" || raw == "suivre" || raw == "takip et" ->
                    result = FollowState.FOLLOW
            }
        }
        return result
    }

    /** Labels of the profile action button when the account IS followed. */
    val UNFOLLOW_BUTTON_LABELS = setOf(
        "following", "friends", "أتابعه", "متابَع", "أصدقاء",
        "abonné", "takip ediliyor"
    )

    /**
     * v2.0.7-gemini: pick the real action button, never the "Following"
     * counter label. Rank: clickable nodes first (the counter label
     * itself is not clickable), then the lowest node on screen (the
     * button row is below the counter row). Pure; unit-tested.
     */
    fun pickUnfollowButton(nodes: List<NodeSnapshot>): NodeSnapshot? {
        val candidates = nodes.filter {
            it.combinedLabel().trim().lowercase() in UNFOLLOW_BUTTON_LABELS
        }
        return candidates.maxWithOrNull(
            compareBy({ it.isClickable }, { it.boundsTop })
        )
    }

    private fun stripCountsAndPunctuation(raw: String): String {
        return Classifier.normalizeDigits(raw)
            .lowercase()
            .replace(Regex("[0-9.,kmb+()\\[\\]•|\\-_:]+"), " ")
            .trim()
    }

    private fun matchesAnyWord(normalized: String, words: Set<String>): Boolean {
        if (normalized.isEmpty()) return false
        return words.any { w ->
            normalized == w ||
                normalized.startsWith("$w ") ||
                normalized.endsWith(" $w")
        }
    }

    /**
     * Statistical tripwire: prevents a single stray "Suggested" or unfollowed handle from
     * aborting a multi-thousand account run, while immediately halting if the user actually
     * lands on a non-followed list.
     */
    class WrongListTripwire(
        private val maxConsecutiveNotFollowed: Int = 4,
        private val minSampleForRatioCheck: Int = 10,
        private val maxNotFollowedRatio: Double = 0.45
    ) {
        enum class Decision {
            CONTINUE,
            INVALIDATE_RUN
        }

        private var consecutiveNotFollowed = 0
        private var totalChecked = 0
        private var totalNotFollowed = 0

        @Synchronized
        fun onFollowState(state: FollowState): Decision {
            when (state) {
                FollowState.FOLLOWING, FollowState.FRIENDS, FollowState.REQUESTED -> {
                    consecutiveNotFollowed = 0
                    totalChecked++
                }
                FollowState.FOLLOW, FollowState.FOLLOW_BACK -> {
                    consecutiveNotFollowed++
                    totalChecked++
                    totalNotFollowed++
                    if (consecutiveNotFollowed >= maxConsecutiveNotFollowed) {
                        return Decision.INVALIDATE_RUN
                    }
                    if (totalChecked >= minSampleForRatioCheck) {
                        val ratio = totalNotFollowed.toDouble() / totalChecked.toDouble()
                        if (ratio >= maxNotFollowedRatio) {
                            return Decision.INVALIDATE_RUN
                        }
                    }
                }
                FollowState.UNKNOWN -> {
                    // Do not penalize transient loading timeouts
                }
            }
            return Decision.CONTINUE
        }

        @Synchronized
        fun reset() {
            consecutiveNotFollowed = 0
            totalChecked = 0
            totalNotFollowed = 0
        }
    }
}
