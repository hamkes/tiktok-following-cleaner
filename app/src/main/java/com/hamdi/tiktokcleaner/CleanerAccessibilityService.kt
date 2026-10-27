package com.hamdi.tiktokcleaner

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.random.Random

/**
 * The automation engine. Runs a single worker thread that polls the
 * accessibility tree of the official TikTok app and acts on it.
 *
 * Two modes:
 *  - AUDIT (read-only): collect the Following list, then open each profile,
 *    read visible signals, classify, save CSV/JSON. NEVER unfollows.
 *  - UNFOLLOW: only usernames from the user-confirmed candidates list;
 *    conservative pacing, rolling counters, hard stops. See runUnfollow().
 *
 * All TikTok UI matching is TEXT-based (English + Arabic variants), because
 * TikTok view ids are obfuscated and change between app versions. This is
 * the most fragile part of the tool and is expected to need a first-run
 * adjustment on the user's phone (see BUILD_NOTES.md).
 */
class CleanerAccessibilityService : AccessibilityService() {

    private lateinit var prefs: Prefs
    private lateinit var files: AppFiles
    private var worker: Thread? = null

    // ---------------- lifecycle ----------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        files = AppFiles(this)
        LogStore.init(files.logFile)
        CleanerEngine.service = this
        LogStore.log("خدمة إمكانية الوصول متصلة وجاهزة")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg in TIKTOK_PKGS) CleanerEngine.lastTikTokPackage = pkg
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        OverlayController.hide()
        CleanerEngine.service = null
        super.onDestroy()
    }

    // ---------------- entry points (called via CleanerEngine) ----------------

    @Synchronized
    fun beginAudit(resume: Boolean) {
        if (worker?.isAlive == true) {
            LogStore.log("هناك عملية تعمل بالفعل. أوقفها أولاً.")
            return
        }
        CleanerEngine.stopRequested = false
        worker = Thread { runAudit(resume) }.apply { isDaemon = true; start() }
    }

    @Synchronized
    fun beginUnfollow() {
        if (worker?.isAlive == true) {
            LogStore.log("هناك عملية تعمل بالفعل. أوقفها أولاً.")
            return
        }
        CleanerEngine.stopRequested = false
        worker = Thread { runUnfollow() }.apply { isDaemon = true; start() }
    }

    // ---------------- node helpers ----------------

    /**
     * v1.2: root of the TikTok app window specifically. Iterates the service
     * windows and picks the largest window whose root belongs to a TikTok
     * package, falling back to the active window only if IT is TikTok. This
     * guarantees our own floating bubble/panel (our package) can never be
     * picked up as the automation root and pollute node detection (e.g. the
     * panel could otherwise be misread as a CAPTCHA/verification screen).
     */
    private fun tiktokRoot(): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = -1.0
        try {
            for (w in windows) {
                val r = try {
                    w.root
                } catch (_: Exception) {
                    null
                } ?: continue
                if (r.packageName?.toString() in TIKTOK_PKGS) {
                    val b = Rect().also { r.getBoundsInScreen(it) }
                    val area = b.width().toDouble() * b.height()
                    if (area > bestArea) {
                        best?.recycle()
                        best = r
                        bestArea = area
                    } else {
                        r.recycle()
                    }
                } else {
                    r.recycle()
                }
            }
        } catch (_: Exception) {
        }
        if (best != null) return best
        val active = rootInActiveWindow ?: return null
        return if (active.packageName?.toString() in TIKTOK_PKGS) {
            active
        } else {
            try {
                active.recycle()
            } catch (_: Exception) {
            }
            null
        }
    }

    private inline fun <T> withRoot(block: (AccessibilityNodeInfo) -> T): T? {
        val root = tiktokRoot() ?: return null
        try {
            return block(root)
        } finally {
            try {
                root.recycle()
            } catch (_: Exception) {
            }
        }
    }

    private fun collectTexts(root: AccessibilityNodeInfo): List<String> {
        val out = ArrayList<String>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            val t = n.text?.toString() ?: n.contentDescription?.toString()
            if (!t.isNullOrBlank()) out.add(t.trim())
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        return out
    }

    fun currentTexts(): List<String> = withRoot { collectTexts(it) } ?: emptyList()

    fun currentPackage(): String? = withRoot { it.packageName?.toString() }

    private fun nodeLabel(n: AccessibilityNodeInfo): String =
        (n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim()

    /**
     * Click a node whose label equals (pass 1) or contains (pass 2) any variant.
     * Returns true on click. [minTopFraction]/[maxTopFraction] restrict the
     * vertical band (as a fraction of the root height) a match may sit in —
     * v1.3 uses the band to hit the tab strip / stats row instead of list rows
     * or the big profile buttons that share the same labels.
     */
    private fun clickByText(
        variants: Set<String>,
        minTopFraction: Double = 0.0,
        maxTopFraction: Double = 1.0
    ): Boolean {
        return withRoot { root ->
            val rootBounds = Rect().also { root.getBoundsInScreen(it) }
            fun findAndClick(exact: Boolean): Boolean {
                val stack = ArrayDeque<AccessibilityNodeInfo>()
                stack.add(root)
                while (stack.isNotEmpty()) {
                    val n = stack.removeLast()
                    val label = nodeLabel(n)
                    val match = if (exact) variants.any { it.equals(label, true) }
                    else variants.any { label.contains(it, true) }
                    if (match) {
                        val b = Rect().also { n.getBoundsInScreen(it) }
                        val inBand = rootBounds.height() == 0 ||
                            (b.top >= rootBounds.height() * minTopFraction &&
                                b.top <= rootBounds.height() * maxTopFraction)
                        if (inBand) {
                            var target: AccessibilityNodeInfo? = n
                            var hops = 0
                            while (target != null && !target.isClickable && hops < 6) {
                                target = target.parent; hops++
                            }
                            if (target != null && target.isClickable) {
                                return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            }
                        }
                    }
                    for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
                }
                return false
            }
            findAndClick(true) || findAndClick(false)
        } ?: false
    }

    /**
     * v1.8: scroll THE LIST forward — never the tab pager. The target is
     * chosen by the pure, unit-tested [GuardDecisions.pickScrollTarget]
     * over a snapshot taken in the same traversal order as the live-node
     * list built alongside it, so the returned index addresses the live
     * node directly. (Up to v1.7.1 this scrolled the largest scrollable
     * node, which on the list screen is the ViewPager — the "scroll"
     * flipped Following → Followers and the mid-run guard stopped the
     * audit; see GuardDecisions.pickScrollTarget.)
     */
    private fun scrollForward(): Boolean = withRoot { root ->
        val live = ArrayList<AccessibilityNodeInfo>()
        val snaps = ArrayList<GuardDecisions.NodeSnapshot>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            val b = Rect().also { n.getBoundsInScreen(it) }
            live.add(n)
            snaps.add(
                GuardDecisions.NodeSnapshot(
                    text = n.text?.toString(),
                    contentDescription = n.contentDescription?.toString(),
                    selectedInChain = isSelectedChain(n),
                    left = b.left, top = b.top, right = b.right, bottom = b.bottom,
                    className = n.className?.toString() ?: "",
                    scrollable = n.isScrollable
                )
            )
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        val idx = GuardDecisions.pickScrollTarget(snaps)
        if (idx == null) {
            LogStore.log("تمرير: لا توجد قائمة قابلة للتمرير (المتاح مُبدِّل تبويبات فقط) — لن يتم التمرير")
            false
        } else {
            live[idx].performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        }
    } ?: false

    /** Grid tiles on a profile: clickable, squarish nodes in the videos region. */
    private fun videoTiles(root: AccessibilityNodeInfo): List<Rect> {
        val rootBounds = Rect().also { root.getBoundsInScreen(it) }
        val h = rootBounds.height().toDouble()
        val out = ArrayList<Rect>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            if (n.isClickable) {
                val b = Rect().also { n.getBoundsInScreen(it) }
                val inGridBand = b.top > h * 0.30 && b.top < h * 0.93
                val squarish = b.height() > 0 && (b.width().toDouble() / b.height()) in 0.5..1.6
                if (inGridBand && squarish && b.width() > 60) out.add(b)
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        return out.sortedWith(compareBy({ it.top }, { it.left }))
    }

    private fun countVideoTiles(): Int? = withRoot { videoTiles(it).size }

    private fun tapFirstVideoTile(): Boolean = withRoot { root ->
        val tiles = videoTiles(root)
        if (tiles.isEmpty()) return@withRoot false
        val first = tiles.first()
        // Find the node again by bounds and click it.
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            if (n.isClickable) {
                val b = Rect().also { n.getBoundsInScreen(it) }
                if (b == first) return@withRoot n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        false
    } ?: false

    /**
     * v2.0: snapshot-based collection that stops at the "Suggested
     * accounts" section boundary (Gemini idea, re-implemented on the
     * v1.9 snapshot shape). Handles at or below the boundary are never
     * collected, so a suggested/LIVE strip on the Following screen
     * cannot contaminate the list — the contamination that killed the
     * 2026-10-22 run. Falls back to the legacy text scan when no
     * snapshot is available; when no boundary exists the result is
     * identical to the legacy scan.
     */
    private fun collectListUsernames(): Set<String> {
        val tree = collectSnapshots() ?: return extractVisibleUsernames()
        val boundary = GuardDecisions.findSuggestedBoundaryY(tree.nodes, tree.rootHeight)
        var dropped = 0
        val names = LinkedHashSet<String>()
        for (n in tree.nodes) {
            val label = n.label
            if (label.isEmpty() || !Classifier.looksLikeUsername(label)) continue
            if (boundary != null && n.top >= boundary) {
                dropped++
                continue
            }
            names.add(label)
        }
        if (dropped > 0) {
            LogStore.log("تجاهل $dropped اسماً تحت حدّ قسم «حسابات مقترحة» — لن تدخل القائمة")
        }
        return names
    }

    private fun extractVisibleUsernames(): Set<String> {
        val texts = currentTexts()
        return texts.filter { Classifier.looksLikeUsername(it) }.toSet()
    }

    // ---------------- v1.3: wrong-list protection ----------------

    /**
     * Thrown by auditOneProfile() when an opened profile's primary button
     * reads "Follow" / "Follow back": the account is NOT followed by the
     * user, so the collected list cannot be the Following list. Caught in
     * runAudit(), which invalidates everything the run produced.
     */
    private class WrongListException(val username: String) :
        Exception("wrong-list profile: @$username")

    /** Result of checking which list/tab the screen is really showing. */
    private enum class ListTab { CONFIRMED, WRONG_TAB, NOT_LIST, UNCONFIRMED }

    /**
     * v1.5: human-readable diagnostics captured by the most recent
     * [verifyFollowingTabOnce] call (tab labels seen + Sort-by presence),
     * logged when a verification outcome is NOT_LIST / UNCONFIRMED so the
     * next screenshot explains itself. Worker-thread only.
     */
    private var lastTabDiag: String = ""

    /**
     * v1.6: header diagnostics v2 — the verbatim texts of the top-band
     * nodes captured by the most recent [verifyFollowingTabOnce] call,
     * logged (as «تشخيص التبويبات») alongside [lastTabDiag] whenever a
     * verification outcome is NOT_LIST / UNCONFIRMED, so any remaining
     * label mismatch shows the header exactly as TikTok renders it
     * (counts included). Worker-thread only.
     */
    private var lastHeaderDiag: String = ""

    /**
     * v1.8: the pure core's own evidence sentence for the most recent
     * verification (e.g. "Followers tab is selected"), captured alongside
     * the diagnostics so a WRONG_TAB stop names the tab it actually saw —
     * up to v1.7.1 the verdict reached the log without its reason.
     */
    private var lastTabEvidence: String = ""

    /**
     * v1.9: wrong-list tripwire + contaminated-name skip count for the
     * current audit run. The tripwire replaces "one stray handle nukes
     * the run": isolated NOT_FOLLOWED profiles are skipped (and their
     * results saved, so progress is never lost), while a statistically
     * wrong list still invalidates via WrongListException.
     */
    private var tripwire = GuardDecisions.WrongListTripwire()
    private var skippedNames: Int = 0

    /** Detail string marking a skipped-contaminant result (replay key). */
    private val skipDetailMarker = "not-followed: contaminated name skipped"

    /**
     * v1.4: one flattened snapshot of the TikTok window — the live tree
     * mapped to plain data for [GuardDecisions]. The decision logic itself
     * lives in the pure core (unit-tested); this class only carries the
     * mapping result.
     */
    private class SnapTree(
        val rootWidth: Int,
        val rootHeight: Int,
        val nodes: List<GuardDecisions.NodeSnapshot>
    )

    /** Is this node, or an ancestor up to 4 hops (tab container), marked selected? */
    private fun isSelectedChain(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        var hops = 0
        while (cur != null && hops <= 4) {
            if (cur.isSelected) return true
            cur = cur.parent
            hops++
        }
        return false
    }

    /**
     * v1.4: map the live TikTok window tree to a plain [SnapTree] for
     * [GuardDecisions] — same traversal order as the v1.3 scans used, with
     * each node's selected-chain flag precomputed via [isSelectedChain]
     * (v1.3 evaluated it per tab-labelled node; evaluating it for every
     * node during the mapping yields identical values for those nodes).
     */
    private fun collectSnapshots(): SnapTree? = withRoot { root ->
        val rb = Rect().also { root.getBoundsInScreen(it) }
        val out = ArrayList<GuardDecisions.NodeSnapshot>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            val b = Rect().also { n.getBoundsInScreen(it) }
            out.add(
                GuardDecisions.NodeSnapshot(
                    text = n.text?.toString(),
                    contentDescription = n.contentDescription?.toString(),
                    selectedInChain = isSelectedChain(n),
                    left = b.left, top = b.top, right = b.right, bottom = b.bottom,
                    // v1.8: carried for GuardDecisions.pickScrollTarget.
                    className = n.className?.toString() ?: "",
                    scrollable = n.isScrollable
                )
            )
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        SnapTree(rb.width(), rb.height(), out)
    }

    /**
     * Verify the current screen is the list screen with the FOLLOWING tab
     * selected. The decision itself is [GuardDecisions.decideTab] (v1.4,
     * unit-tested); this adapter only maps its verdict back to the
     * service's ListTab — INCONCLUSIVE with a missing tab row is v1.3's
     * NOT_LIST, any other INCONCLUSIVE is v1.3's UNCONFIRMED. Callers must
     * treat everything but CONFIRMED as "do not collect".
     */
    private fun verifyFollowingTabOnce(): ListTab {
        val tree = collectSnapshots()
        if (tree == null) {
            // v1.5 diagnostics: no TikTok window could be read at all.
            lastTabDiag = "تعذّرت قراءة نافذة TikTok أصلاً"
            lastHeaderDiag = "(تعذّرت قراءة نافذة TikTok أصلاً)"
            return ListTab.NOT_LIST
        }
        // v1.6: pass the real screen height so the tab row is recognised
        // by BAND (top 30%) with count-aware labels — the v1.5 call saw
        // only the bare title "Following" on the user's build because the
        // header tabs read "Following 9,947" etc. and matched nothing.
        val decision = GuardDecisions.decideTab(tree.nodes, tree.rootHeight)
        // v1.8: keep the core's evidence sentence for stop logs.
        lastTabEvidence = decision.evidence
        // v1.5 diagnostics: which tab-marker labels were actually seen on
        // this screen (raw values, as TikTok renders them) and whether the
        // Following-only "Sort by" control was among them. Only labels from
        // TikTok's own window are in the snapshot, so nothing else can leak
        // into the log. v1.6: label detection is count-aware too
        // (GuardDecisions.tabKindOf), so "Following 9,947" is listed.
        val labelsSeen = tree.nodes.map { it.label }
            .filter { GuardDecisions.tabKindOf(it) != null }
            .distinct()
        lastTabDiag = "عناوين التبويبات المرئية: " +
            (if (labelsSeen.isEmpty()) "لا شيء"
            else labelsSeen.joinToString("، ") { "\"$it\"" }) +
            " — علامة Sort by: " + (if (decision.scan.hasSortBy) "موجودة" else "غير موجودة")
        // v1.6 diagnostics v2: the header verbatim, whatever it contains.
        lastHeaderDiag = GuardDecisions.formatHeaderTexts(tree.nodes, tree.rootHeight)
        return when (decision.verdict) {
            GuardDecisions.TabVerdict.CONFIRMED -> ListTab.CONFIRMED
            GuardDecisions.TabVerdict.WRONG_TAB -> ListTab.WRONG_TAB
            GuardDecisions.TabVerdict.INCONCLUSIVE ->
                if (!decision.scan.tabRowPresent) ListTab.NOT_LIST else ListTab.UNCONFIRMED
        }
    }

    /** Poll verification for a while (screen transitions settle) and report the outcome. */
    private fun awaitFollowingTab(timeoutMs: Long): ListTab {
        var waited = 0L
        var last = verifyFollowingTabOnce()
        while (waited < timeoutMs) {
            if (CleanerEngine.stopRequested) return ListTab.UNCONFIRMED
            if (last == ListTab.CONFIRMED || last == ListTab.WRONG_TAB) return last
            if (!sleepInterruptible(700)) return ListTab.UNCONFIRMED
            waited += 700
            last = verifyFollowingTabOnce()
        }
        return last
    }

    /** Tap the Following TAB — restricted to the top tab strip so a list-row button is never hit. */
    private fun tapFollowingTab(): Boolean =
        clickByText(TAB_FOLLOWING, minTopFraction = 0.0, maxTopFraction = 0.40)

    /**
     * Is a user-list screen showing (as opposed to a profile)? Tab labels
     * exist on profile screens too (the stats row), so also require that the
     * own-profile markers ("Edit profile" …) are absent.
     */
    private fun isOnListScreen(): Boolean {
        val tree = collectSnapshots() ?: return false
        // v1.6: same band-checked, count-aware row definition as the
        // verifier, so navigation and verification can no longer disagree
        // about whether this is a list screen (the v1.5 log showed exactly
        // that split: "list screen visible" here, NOT_LIST there).
        if (!GuardDecisions.scanTabs(tree.nodes, tree.rootHeight).tabRowPresent) return false
        val texts = tree.nodes.map { it.label }.filter { it.isNotEmpty() }
        return !anyTextContains(texts, OWN_PROFILE_MARKERS)
    }

    /** Outcome of one scan of TikTok's bottom strip. */
    private data class StripScan(
        val labels: List<String>,
        val tappedLabel: Boolean,
        val tappedPositional: Boolean
    )

    /**
     * v1.5: ONE scan of the bottom strip (bottom 15% of the window — the
     * same band the v1.3 click used). Label pass first: a node whose label
     * names the Profile tab per [NavLogic.isProfileTabLabel] is tapped
     * (climbing to a clickable ancestor, like clickByText). Positional
     * fallback second: if the strip holds >= 4 clickable tab items but no
     * label matched, tap the RIGHTMOST one — TikTok's profile-tab slot.
     * The fallback is permitted ONLY because the caller confirms the
     * own-profile screen right after this and abandons navigation when
     * that confirmation fails; it never leads anywhere on its own.
     */
    private fun scanBottomStripOnce(): StripScan {
        val fallback = StripScan(emptyList(), tappedLabel = false, tappedPositional = false)
        return withRoot { root ->
            val rb = Rect().also { root.getBoundsInScreen(it) }
            val stripTop = rb.height() * 0.85
            val labels = ArrayList<Pair<String, Int>>() // label -> left edge
            var labelTarget: AccessibilityNodeInfo? = null
            val clickables = ArrayList<AccessibilityNodeInfo>()
            val seenBounds = HashSet<String>()
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.add(root)
            while (stack.isNotEmpty()) {
                val n = stack.removeLast()
                val b = Rect().also { n.getBoundsInScreen(it) }
                if (rb.height() == 0 || b.top >= stripTop) {
                    val label = nodeLabel(n)
                    if (label.isNotEmpty()) labels.add(label to b.left)
                    if (labelTarget == null && NavLogic.isProfileTabLabel(label)) {
                        labelTarget = n
                    }
                    if (n.isClickable && b.width() > 0) {
                        val key = "${b.left},${b.top},${b.right},${b.bottom}"
                        if (seenBounds.add(key)) clickables.add(n)
                    }
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
            }
            val orderedLabels = labels.sortedBy { it.second }.map { it.first }
            if (labelTarget != null) {
                var t: AccessibilityNodeInfo? = labelTarget
                var hops = 0
                while (t != null && !t.isClickable && hops < 6) {
                    t = t.parent; hops++
                }
                if (t != null && t.isClickable &&
                    t.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ) {
                    return@withRoot StripScan(orderedLabels, tappedLabel = true, tappedPositional = false)
                }
            }
            if (clickables.size >= 4) {
                val rightmost = clickables.maxByOrNull { node ->
                    val b = Rect().also { node.getBoundsInScreen(it) }
                    b.left + b.right
                }
                if (rightmost != null &&
                    rightmost.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ) {
                    LogStore.log("تنقّل: لم تُطابِق أي عناوين تبويبَ Profile — استُخدم الاحتياط الموضعي (أيمن تبويب في الشريط السفلي)، وسيُتحقق من شاشة الملف الشخصي قبل أي خطوة أخرى")
                    return@withRoot StripScan(orderedLabels, tappedLabel = false, tappedPositional = true)
                }
            }
            StripScan(orderedLabels, tappedLabel = false, tappedPositional = false)
        } ?: fallback
    }

    /**
     * v1.5: poll the bottom strip for up to ~4 seconds instead of v1.3's
     * single immediate read — on the user's phone the first read raced a
     * still-settling TikTok UI and found no Profile tab at all. On total
     * failure, logs the labels that WERE seen in the strip (from TikTok's
     * window only) so the next screenshot explains itself.
     */
    private fun tapProfileTabPolled(): Boolean {
        var waited = 0L
        var lastLabels: List<String> = emptyList()
        while (true) {
            if (CleanerEngine.stopRequested) return false
            val scan = scanBottomStripOnce()
            lastLabels = scan.labels
            if (scan.tappedLabel || scan.tappedPositional) return true
            if (waited >= 4_000L) break
            if (!sleepInterruptible(650)) return false
            waited += 650
        }
        LogStore.log("تنقّل: العناوين المرصودة في الشريط السفلي: ${NavLogic.formatBottomStrip(lastLabels)}")
        return false
    }

    /**
     * Best-effort navigation to the Following list: TikTok to the
     * foreground → bottom-nav Profile tab → the "Following" stat on the
     * user's own profile. Every step is confirmed by the next screen's
     * markers; an unconfirmed step abandons navigation (the caller then
     * verifies whatever screen is present and aborts if it is not the
     * Following list).
     *
     * v1.5: the Profile-tab step polls the bottom strip (~4 s) with
     * broadened markers and a positional fallback — see
     * [tapProfileTabPolled] / [scanBottomStripOnce]. The own-profile
     * confirmation that follows it is unchanged, and remains the gate the
     * fallback depends on.
     */
    private fun navigateToFollowingList() {
        if (currentPackage() !in TIKTOK_PKGS) {
            LogStore.log("تنقّل: فتح TikTok في المقدمة…")
            openTikTokApp()
            waitFor(10_000L) { currentPackage() in TIKTOK_PKGS }
        }
        if (CleanerEngine.stopRequested) return
        if (isOnListScreen()) {
            LogStore.log("تنقّل: شاشة قوائم ظاهرة أصلاً — تخطي التنقّل والانتقال إلى التحقق")
            return
        }
        if (!tapProfileTabPolled()) {
            LogStore.log("تنقّل: لم يتم العثور على تبويب Profile في الشريط السفلي — تخطي التنقّل")
            return
        }
        val onProfile = waitFor(8_000L) { anyTextContains(currentTexts(), OWN_PROFILE_MARKERS) }
        if (!onProfile) {
            LogStore.log("تنقّل: لم يتم تأكيد شاشة الملف الشخصي — تخطي التنقّل")
            return
        }
        if (CleanerEngine.stopRequested) return
        if (!clickByText(TAB_FOLLOWING, maxTopFraction = 0.55)) {
            LogStore.log("تنقّل: لم يتم العثور على عنوان Following في الملف الشخصي — تخطي التنقّل")
            return
        }
        val onList = waitFor(8_000L) { isOnListScreen() }
        LogStore.log(
            if (onList) "تنقّل: تم فتح شاشة القوائم"
            else "تنقّل: لم يتم تأكيد شاشة القوائم بعد الضغط على Following"
        )
    }

    /**
     * Verify the Following tab is the selected one; if a different tab is
     * selected, switch to Following once and re-verify. False means the
     * caller must abort — never collect from an unverified list.
     */
    private fun ensureFollowingList(): Boolean {
        var v = awaitFollowingTab(8_000L)
        if (v == ListTab.WRONG_TAB) {
            LogStore.log("التبويب المحدد ليس Following — محاولة التبديل إلى تبويب Following")
            if (tapFollowingTab()) {
                sleepInterruptible(1_200)
                v = awaitFollowingTab(6_000L)
            }
        }
        return if (v == ListTab.CONFIRMED) {
            LogStore.log("تم التأكد: القائمة المعروضة هي قائمة Following")
            true
        } else {
            // v1.8: the verdict now carries the core's evidence (which tab
            // was seen selected, or why the read was inconclusive).
            LogStore.log("نتيجة التحقق من القائمة: $v — $lastTabEvidence")
            // v1.5 diagnostics: say WHAT was seen, not just the verdict.
            // v1.6: plus the verbatim header line (diagnostics v2).
            // v1.8: diagnostics also on WRONG_TAB, not only inconclusive.
            if (v == ListTab.NOT_LIST || v == ListTab.UNCONFIRMED || v == ListTab.WRONG_TAB) {
                LogStore.log("تشخيص التحقق: $lastTabDiag")
                LogStore.log("تشخيص التبويبات: $lastHeaderDiag")
            }
            false
        }
    }

    /**
     * Read the opened profile's PRIMARY follow-state button. The decision
     * is [GuardDecisions.followState] (v1.4, unit-tested) over the mapped
     * snapshot: only a wide button in the upper-middle band qualifies —
     * that excludes the narrow stats labels at the top and the small
     * per-row buttons of any suggested accounts strip lower down.
     * FOLLOWED = "Following"/"Friends", NOT_FOLLOWED = "Follow"/"Follow
     * back", UNKNOWN = no such button (e.g. gone accounts, icon-only
     * buttons, own profile).
     */
    private fun profileFollowState(): GuardDecisions.FollowState {
        val tree = collectSnapshots() ?: return GuardDecisions.FollowState.UNKNOWN
        return GuardDecisions.followState(tree.nodes, tree.rootWidth, tree.rootHeight)
    }

    // ---------------- generic helpers ----------------

    /** Sleep in small chunks; returns false if a stop was requested. */
    private fun sleepInterruptible(ms: Long): Boolean {
        var left = ms
        while (left > 0) {
            if (CleanerEngine.stopRequested) return false
            val chunk = minOf(left, 400L)
            try {
                Thread.sleep(chunk)
            } catch (_: InterruptedException) {
                return false
            }
            left -= chunk
        }
        return !CleanerEngine.stopRequested
    }

    /**
     * v1.2 pause gate, checked BETWEEN steps: while the user paused from the
     * floating bubble (or its panel is open), no new scroll/tap/unfollow
     * starts. A pacing sleep already in progress is not interrupted — the
     * gate is evaluated before the next action. Returns false only when a
     * stop was requested while waiting. STOP itself stays immediate via
     * CleanerEngine.stopRequested, checked inside sleepInterruptible().
     */
    private fun awaitIfPaused(): Boolean {
        var announced = false
        while (!CleanerEngine.stopRequested && CleanerEngine.isPaused()) {
            if (!announced) {
                LogStore.log("إيقاف مؤقت من الأيقونة العائمة — لن تبدأ أي خطوة جديدة حتى الاستئناف")
                announced = true
            }
            try {
                Thread.sleep(350)
            } catch (_: InterruptedException) {
                return false
            }
        }
        if (announced && !CleanerEngine.stopRequested) LogStore.log("تم الاستئناف من الأيقونة العائمة")
        return !CleanerEngine.stopRequested
    }

    // v2.0: poll interval is a parameter (default keeps every existing
    // caller's timing); the profile-load wait polls faster so a loaded
    // profile is noticed in ~200 ms instead of up to 600 ms later.
    private fun waitFor(timeoutMs: Long, stepMs: Long = 600, cond: () -> Boolean): Boolean {
        var waited = 0L
        while (waited < timeoutMs) {
            if (CleanerEngine.stopRequested) return false
            if (cond()) return true
            if (!sleepInterruptible(stepMs)) return false
            waited += stepMs
        }
        return cond()
    }

    private fun anyTextContains(texts: List<String>, markers: Set<String>): Boolean =
        texts.any { t -> markers.any { m -> t.contains(m, ignoreCase = true) } }

    private fun captchaVisible(texts: List<String>): Boolean = anyTextContains(texts, CAPTCHA_MARKERS)

    /**
     * Audit mode is read-only, so a CAPTCHA is not fatal: pause and wait for
     * the user to solve it in the TikTok app. Returns false on stop/timeout.
     */
    private fun waitOutCaptcha(context: String): Boolean {
        if (!captchaVisible(currentTexts())) return true
        CleanerEngine.state = CleanerEngine.RunState.PAUSED_CAPTCHA
        LogStore.log("ظهرت شاشة تحقق CAPTCHA أثناء $context. الأداة متوقفة — حلّ التحقق يدوياً داخل TikTok وستكمل الأداة وحدها.")
        val ok = waitFor(5 * 60_000L) { !captchaVisible(currentTexts()) }
        if (ok) {
            LogStore.log("تم حل التحقق. استئناف العمل.")
            CleanerEngine.state = CleanerEngine.RunState.AUDIT_PROFILES
        }
        return ok
    }

    private fun isPackageInstalled(pkg: String): Boolean = try {
        packageManager.getPackageInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun tiktokPackage(): String? =
        CleanerEngine.lastTikTokPackage ?: TIKTOK_PKGS.firstOrNull { isPackageInstalled(it) }

    fun openTikTokApp(): Boolean {
        val pkg = tiktokPackage() ?: return false
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(intent); true
        } catch (_: Exception) {
            false
        }
    }

    private fun openProfile(username: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.tiktok.com/@$username"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pkg = tiktokPackage()
        try {
            if (pkg != null) intent.setPackage(pkg)
            startActivity(intent)
        } catch (_: Exception) {
            try {
                intent.setPackage(null)
                startActivity(intent)
            } catch (e: Exception) {
                LogStore.log("تعذّر فتح بروفايل @$username : ${e.message}")
            }
        }
    }

    // ================= AUDIT MODE (read-only, never unfollows) =================

    private fun runAudit(resume: Boolean) {
        val engine = CleanerEngine
        try {
            OverlayController.show(this)
            if (!resume) {
                files.resetAuditFiles()
                prefs.auditProfileIndex = 0
                engine.categoryCounts.clear()
                LogStore.log("بدء فحص جديد من الصفر")
            } else {
                LogStore.log("استئناف الفحص من حيث توقف")
            }

            // ---- Phase A: collect the Following list ----
            var following = files.readFollowing()
            if (following.isEmpty()) {
                engine.state = CleanerEngine.RunState.AUDIT_LIST
                // v1.3: v1.2 trusted whatever list happened to be on screen
                // and audited the user's FOLLOWERS. Now: navigate to the
                // Following list, then VERIFY the Following tab is the
                // selected one before a single username is collected. If it
                // cannot be confirmed, abort — never fall back to processing
                // whatever list is visible.
                LogStore.log("المرحلة ١: الانتقال إلى قائمة Following والتحقق منها قبل جمع أي اسم")
                navigateToFollowingList()
                if (CleanerEngine.stopRequested) {
                    engine.state = CleanerEngine.RunState.STOPPED
                    return
                }
                if (!ensureFollowingList()) {
                    engine.state = CleanerEngine.RunState.STOPPED
                    engine.statusDetail = "تعذّر التأكد من قائمة Following"
                    LogStore.log("توقف: تعذّر التأكد من قائمة Following — افتح قائمة Following بنفسك واضغط بدء الفحص")
                    return
                }
                val collected = LinkedHashSet<String>()
                var noNew = 0
                var iterations = 0
                while (!CleanerEngine.stopRequested && noNew < 6 && iterations < 4000) {
                    if (!awaitIfPaused()) break
                    // v1.3 mid-run guard: re-verify the Following tab before
                    // every new collection batch — the user (or TikTok) may
                    // have switched tabs mid-run. One inconclusive read gets a
                    // single re-check; anything but CONFIRMED stops the run.
                    // Names collected so far stay saved (they came from
                    // verified batches) and the run simply stops here.
                    var tabNow = verifyFollowingTabOnce()
                    if (tabNow != ListTab.CONFIRMED) {
                        if (!sleepInterruptible(900)) break
                        tabNow = verifyFollowingTabOnce()
                    }
                    if (tabNow != ListTab.CONFIRMED) {
                        // v1.5 diagnostics: same seen-labels line as at start.
                        // v1.6: plus the verbatim header line.
                        // v1.8: also on WRONG_TAB, and the stop line below
                        // carries the core's evidence (which tab was seen).
                        if (tabNow == ListTab.NOT_LIST || tabNow == ListTab.UNCONFIRMED || tabNow == ListTab.WRONG_TAB) {
                            LogStore.log("تشخيص التحقق أثناء التشغيل: $lastTabDiag")
                            LogStore.log("تشخيص التبويبات أثناء التشغيل: $lastHeaderDiag")
                        }
                        LogStore.log("حماية أثناء التشغيل: القائمة المعروضة لم تعد مؤكدة كـ Following (الحالة: $tabNow — $lastTabEvidence). إيقاف الجمع هنا — لن تُعالج أي قائمة غير مؤكدة.")
                        files.writeFollowing(collected.toList())
                        engine.state = CleanerEngine.RunState.STOPPED
                        engine.statusDetail = "توقف: القائمة لم تعد مؤكدة كـ Following"
                        return
                    }
                    val visible = collectListUsernames()
                    val before = collected.size
                    // Own username sits at the top of the profile, not in the list;
                    // it can leak in via headers, so drop the account owner if seen
                    // in the first screen only is not reliable — keep all, the user
                    // reviews candidates anyway. (v1 heuristic, see BUILD_NOTES.)
                    collected.addAll(visible)
                    val added = collected.size - before
                    if (added == 0) noNew++ else noNew = 0
                    engine.collectedCount = collected.size
                    if (iterations % 5 == 0 || added > 0) files.writeFollowing(collected.toList())
                    if (!scrollForward()) noNew++
                    if (!sleepInterruptible(Random.nextLong(1100, 1900))) break
                    iterations++
                }
                files.writeFollowing(collected.toList())
                following = collected.toList()
                LogStore.log("انتهى جمع القائمة: ${following.size} اسم مستخدم")
            } else {
                engine.collectedCount = following.size
                LogStore.log("تم تحميل قائمة محفوظة: ${following.size} اسم (استئناف)")
            }
            if (CleanerEngine.stopRequested) {
                engine.state = CleanerEngine.RunState.STOPPED
                return
            }

            // ---- Phase B: open each profile, read signals, classify ----
            engine.state = CleanerEngine.RunState.AUDIT_PROFILES
            engine.auditTotal = following.size
            val results = ArrayList<AuditResult>()
            // v1.9: fresh tripwire for this run; rebuilt from saved
            // results below when resuming.
            tripwire = GuardDecisions.WrongListTripwire()
            skippedNames = 0
            // Reload previously saved results so the candidates file stays complete on resume.
            if (files.auditJsonl.exists()) {
                for (line in files.auditJsonl.readLines()) {
                    try {
                        val o = org.json.JSONObject(line)
                        val r = AuditResult(
                            o.getString("username"),
                            Category.valueOf(o.getString("category")),
                            o.optString("detail"),
                            if (o.isNull("videoCount")) null else o.getInt("videoCount"),
                            if (o.isNull("lastVideoMs")) null else o.getLong("lastVideoMs"),
                            o.optLong("checkedAtMs")
                        )
                        results.add(r)
                        engine.categoryCounts.merge(r.category, 1, Int::plus)
                    } catch (_: Exception) {
                    }
                }
            }
            // v1.9: replay saved results through the tripwire so a resumed
            // run keeps the original run's evidence state, and restore the
            // skip count. (Replay approximation: a decisive non-UNKNOWN
            // result counts as FOLLOWED — exact for every result produced
            // through the follow-state gate except gone accounts, whose
            // button was unreadable; that only makes a resumed tripwire
            // marginally more lenient, never stricter.)
            for (r in results) {
                when {
                    r.detail == skipDetailMarker -> {
                        tripwire.onFollowState(GuardDecisions.FollowState.NOT_FOLLOWED)
                        skippedNames++
                    }
                    r.category != Category.UNKNOWN ->
                        tripwire.onFollowState(GuardDecisions.FollowState.FOLLOWED)
                    else -> Unit
                }
            }
            if (skippedNames > 0) {
                LogStore.log("استئناف: تم استرجاع $skippedNames اسماً ملوّثاً تم تخطيه في الجولات السابقة")
            }
            // v1.9: progress ground truth = the saved results themselves;
            // trust the larger of the persisted index and the result count.
            var idx = (if (resume) maxOf(prefs.auditProfileIndex, results.size)
                else results.size).coerceAtMost(following.size)
            while (idx < following.size) {
                if (CleanerEngine.stopRequested) {
                    prefs.auditProfileIndex = idx
                    engine.state = CleanerEngine.RunState.STOPPED
                    LogStore.log("توقف الفحص عند $idx من ${following.size}. اضغط «استئناف الفحص» للإكمال.")
                    return
                }
                if (!awaitIfPaused()) {
                    prefs.auditProfileIndex = idx
                    engine.state = CleanerEngine.RunState.STOPPED
                    LogStore.log("توقف الفحص عند $idx من ${following.size}. اضغط «استئناف الفحص» للإكمال.")
                    return
                }
                val username = following[idx]
                val r = auditOneProfile(username)
                files.appendResult(r)
                results.add(r)
                engine.categoryCounts.merge(r.category, 1, Int::plus)
                idx++
                engine.auditedCount = idx
                prefs.auditProfileIndex = idx
                if (!sleepInterruptible(Random.nextLong(1200, 3200))) {
                    prefs.auditProfileIndex = idx
                    engine.state = CleanerEngine.RunState.STOPPED
                    return
                }
            }
            files.writeCandidates(results)
            engine.state = CleanerEngine.RunState.DONE
            // v1.9: the Audit tab's status line shows the outcome too,
            // not only the log.
            engine.statusDetail = "اكتمل الفحص: ${results.size} مصنّف"
            val c = engine.categoryCounts
            LogStore.log(
                "اكتمل الفحص. المجموع ${results.size} — " +
                    "GONE: ${c[Category.GONE] ?: 0}، FAKE: ${c[Category.FAKE] ?: 0}، " +
                    "DEAD: ${c[Category.DEAD] ?: 0}، UNKNOWN: ${c[Category.UNKNOWN] ?: 0}، " +
                    "ACTIVE: ${c[Category.ACTIVE] ?: 0}. تم حفظ ملف المرشحين (GONE/FAKE/DEAD) داخل بيانات التطبيق." +
                    if (skippedNames > 0) " تم تخطي $skippedNames اسماً ملوّثاً (حسابات لا تتابعها تسرّبت إلى الجمع)." else ""
            )
        } catch (e: WrongListException) {
            // v1.3 run invalidation: a "Follow"/"Follow back" button on an
            // opened profile proves the collected list is NOT the Following
            // list. Void everything this run produced — the candidates file
            // (the spec-named target) plus this run's audit results and the
            // collected list itself, which are equally products of the
            // invalid run and would otherwise poison every resume. The raw
            // LogStore log (cleaner.log) is KEPT for diagnosis.
            engine.state = CleanerEngine.RunState.STOPPED
            engine.statusDetail = "أُبطل الفحص: القائمة ليست Following"
            LogStore.log("⚠⚠ إبطال الفحص بالكامل: بروفايل @${e.username} يُظهر زر «Follow / Follow back» — هذا الحساب لا تتابعه أنت، فالقائمة التي جُمعت ليست قائمة Following (غالباً قائمة Followers). تم حذف ملف مرشحي هذه الجولة ونتائجها وقائمتها، ولن يُستخدم أي اسم منها. سجل التشخيص (cleaner.log) محفوظ.")
            try {
                files.candidatesCsv.delete()
                files.auditCsv.delete()
                files.auditJsonl.delete()
                files.followingList.delete()
            } catch (_: Exception) {
            }
            prefs.auditProfileIndex = 0
        } catch (e: Exception) {
            CleanerEngine.state = CleanerEngine.RunState.ERROR
            CleanerEngine.statusDetail = e.message ?: "unknown error"
            LogStore.log("خطأ أثناء الفحص: ${e.message}")
        } finally {
            OverlayController.hide()
        }
    }

    /** Open one profile and classify it from visible UI signals only. */
    private fun auditOneProfile(username: String): AuditResult {
        val now = System.currentTimeMillis()
        fun unknown(detail: String) =
            AuditResult(username, Category.UNKNOWN, detail, null, null, now)

        if (!waitOutCaptcha("فحص @$username")) return unknown("captcha not solved in time")

        openProfile(username)
        val loaded = waitFor(9_000L, 200L) {
            val t = currentTexts()
            anyTextContains(t, GONE_MARKERS) || anyTextContains(t, PRIVATE_MARKERS) ||
                t.any { it.contains("Followers", true) || it.contains("المتابعون") } ||
                t.any { it.equals(username, true) }
        }
        if (!loaded) return unknown("profile did not load in time")
        if (!sleepInterruptible(700)) return unknown("stopped")

        val texts = currentTexts()
        if (captchaVisible(texts)) {
            if (!waitOutCaptcha("فحص @$username")) return unknown("captcha not solved in time")
        }
        // v1.3 follow-state sanity (READ-ONLY — the button is only read,
        // never tapped): every profile opened from the genuine Following
        // list is an account the user FOLLOWS, so its primary button reads
        // "Following"/"Friends". A "Follow"/"Follow back" button is
        // evidence the name does not belong to the Following list.
        // v1.9: the evidence goes through the tripwire — a statistically
        // wrong LIST still throws (runAudit invalidates, as in v1.3),
        // while an isolated contaminant is skipped, counted, logged and
        // SAVED (category UNKNOWN, never a candidate), so one stray name
        // can no longer destroy a long run's progress.
        when (val fs = profileFollowState()) {
            GuardDecisions.FollowState.NOT_FOLLOWED -> {
                if (tripwire.onFollowState(fs) ==
                    GuardDecisions.WrongListTripwire.Decision.INVALIDATE
                ) throw WrongListException(username)
                skippedNames++
                LogStore.log("تخطي @$username: لا تتابعه (زر «Follow back») — اسم ملوّث رقم $skippedNames في هذه الجولة، والفحص مستمر")
                return unknown(skipDetailMarker)
            }
            GuardDecisions.FollowState.FOLLOWED -> tripwire.onFollowState(fs)
            GuardDecisions.FollowState.UNKNOWN -> Unit
        }
        val gone = anyTextContains(texts, GONE_MARKERS)
        val priv = anyTextContains(texts, PRIVATE_MARKERS)
        if (gone || priv) {
            val (cat, detail) = Classifier.classify(username, gone, priv, null, null, now, prefs.thresholdDays)
            return AuditResult(username, cat, detail, null, null, now)
        }

        var videoCount: Int?
        var lastMs: Long? = null
        if (anyTextContains(texts, NO_VIDEO_MARKERS)) {
            videoCount = 0
        } else {
            videoCount = countVideoTiles()
            if (videoCount != null && videoCount > 0) {
                // Open the newest video and try to read its date.
                if (tapFirstVideoTile()) {
                    sleepInterruptible(1600)
                    val vtexts = currentTexts()
                    lastMs = Classifier.parseDateFromTexts(vtexts, now)
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    sleepInterruptible(800)
                }
            }
        }
        val (cat, detail) = Classifier.classify(
            username, false, false, videoCount, lastMs, now, prefs.thresholdDays
        )
        LogStore.log("فحص @$username → $cat ($detail)")
        return AuditResult(username, cat, detail, videoCount, lastMs, now)
    }

    // ================= UNFOLLOW MODE (approved candidates only) =================

    private fun runUnfollow() {
        val engine = CleanerEngine
        try {
            OverlayController.show(this)
            val candidates = engine.candidates
            if (candidates.isEmpty()) {
                LogStore.log("لا توجد قائمة مرشحين. اختر ملفاً أولاً.")
                engine.state = CleanerEngine.RunState.STOPPED
                return
            }
            if (!engine.candidatesConfirmed) {
                LogStore.log("يجب تفعيل تأكيد مراجعة القائمة قبل بدء الإلغاء.")
                engine.state = CleanerEngine.RunState.STOPPED
                return
            }
            engine.state = CleanerEngine.RunState.UNFOLLOW
            engine.unfollowTotal = candidates.size
            val done = files.unfollowDoneSet()
            var processed = 0
            var success = 0
            var consecutiveFailures = 0
            var sinceBatchBreak = 0
            LogStore.log("بدء إلغاء المتابعة: ${candidates.size} مرشح (تمت معالجة ${done.size} منهم سابقاً)")

            for (username in candidates) {
                if (CleanerEngine.stopRequested) break
                if (!awaitIfPaused()) break
                if (username in done) {
                    processed++; engine.unfollowProcessed = processed
                    continue
                }

                // CAPTCHA = hard stop in unfollow mode.
                if (captchaVisible(currentTexts())) {
                    engine.state = CleanerEngine.RunState.STOPPED
                    engine.statusDetail = "توقف صارم: ظهرت شاشة تحقق CAPTCHA"
                    LogStore.log("توقف صارم: ظهرت شاشة تحقق CAPTCHA. لن تكمل الأداة. عالج الأمر يدوياً ثم استأنف لاحقاً.")
                    return
                }

                // Rolling caps: wait for the hourly window, stop for the day cap.
                if (!respectCaps()) {
                    engine.state = CleanerEngine.RunState.CAP_REACHED
                    LogStore.log("توقف: تم بلوغ الحد اليومي (${prefs.dayCap}). أكمل في يوم آخر.")
                    return
                }
                if (CleanerEngine.stopRequested) break

                val outcome = unfollowOne(username)
                processed++; engine.unfollowProcessed = processed
                when (outcome) {
                    Outcome.SUCCESS -> {
                        prefs.recordUnfollow(System.currentTimeMillis())
                        files.markUnfollowDone(username)
                        done.add(username)
                        success++; engine.unfollowSuccess = success
                        consecutiveFailures = 0
                        sinceBatchBreak++
                        LogStore.log("تم إلغاء متابعة @$username (ناجح $success من أصل ${candidates.size})")
                        if (sinceBatchBreak >= prefs.batchSize) {
                            sinceBatchBreak = 0
                            val brk = Random.nextLong(300_000, 600_000)
                            LogStore.log("استراحة دفعة: ${brk / 60000} دقيقة")
                            if (!sleepInterruptible(brk)) break
                        } else {
                            val gap = Random.nextLong(prefs.gapMinSec * 1000L, prefs.gapMaxSec * 1000L)
                            if (!sleepInterruptible(gap)) break
                        }
                    }
                    Outcome.ALREADY_GONE_OR_NOT_FOLLOWING -> {
                        files.markUnfollowDone(username)
                        done.add(username)
                        consecutiveFailures = 0
                        LogStore.log("@$username : الحساب غير موجود أو لا تتابعه أصلاً — تم تخطيه")
                        if (!sleepInterruptible(Random.nextLong(3000, 7000))) break
                    }
                    Outcome.CAPTCHA -> {
                        engine.state = CleanerEngine.RunState.STOPPED
                        engine.statusDetail = "توقف صارم: CAPTCHA"
                        LogStore.log("توقف صارم: ظهرت شاشة تحقق أثناء معالجة @$username")
                        return
                    }
                    Outcome.FAILURE -> {
                        consecutiveFailures++
                        LogStore.log("فشل إلغاء متابعة @$username (إخفاقات متتالية: $consecutiveFailures)")
                        if (consecutiveFailures >= 3) {
                            engine.state = CleanerEngine.RunState.STOPPED
                            engine.statusDetail = "توقف صارم: 3 إخفاقات متتالية"
                            LogStore.log("توقف صارم: 3 إخفاقات متتالية. راجع الشاشة يدوياً.")
                            return
                        }
                        if (!sleepInterruptible(Random.nextLong(8000, 15000))) break
                    }
                }
            }
            if (engine.state == CleanerEngine.RunState.UNFOLLOW) {
                engine.state =
                    if (CleanerEngine.stopRequested) CleanerEngine.RunState.STOPPED else CleanerEngine.RunState.DONE
            }
            LogStore.log("انتهت جلسة الإلغاء: عولج $processed، ناجح $success")
        } catch (e: Exception) {
            CleanerEngine.state = CleanerEngine.RunState.ERROR
            CleanerEngine.statusDetail = e.message ?: "unknown error"
            LogStore.log("خطأ أثناء الإلغاء: ${e.message}")
        } finally {
            OverlayController.hide()
        }
    }

    private enum class Outcome { SUCCESS, ALREADY_GONE_OR_NOT_FOLLOWING, CAPTCHA, FAILURE }

    /** Returns false when the daily cap is hit or a stop was requested while waiting. */
    private fun respectCaps(): Boolean {
        while (!CleanerEngine.stopRequested) {
            if (!awaitIfPaused()) return false
            val now = System.currentTimeMillis()
            if (prefs.countInLast(24, now) >= prefs.dayCap) return false
            if (prefs.countInLast(1, now) >= prefs.hourCap) {
                val oldest = prefs.oldestInLast(1, now) ?: return true
                val waitMs = (oldest + 3_600_000L - now + 5_000).coerceIn(10_000, 3_660_000)
                LogStore.log("تم بلوغ حد الساعة (${prefs.hourCap}). انتظار ${waitMs / 1000} ثانية…")
                if (!sleepInterruptible(waitMs)) return false
                continue
            }
            return true
        }
        return false
    }

    private fun unfollowOne(username: String): Outcome {
        openProfile(username)
        val loaded = waitFor(9_000L, 200L) {
            val t = currentTexts()
            anyTextContains(t, GONE_MARKERS) ||
                t.any { it.contains("Followers", true) || it.contains("المتابعون") }
        }
        if (!loaded) return Outcome.FAILURE
        if (!sleepInterruptible(700)) return Outcome.FAILURE

        val texts = currentTexts()
        if (captchaVisible(texts)) return Outcome.CAPTCHA
        if (anyTextContains(texts, GONE_MARKERS)) return Outcome.ALREADY_GONE_OR_NOT_FOLLOWING

        // If a plain Follow button is showing, we are not following this account.
        val hasFollowingBtn = containsButton(FOLLOWING_BUTTONS)
        if (!hasFollowingBtn && containsButton(FOLLOW_BUTTONS)) {
            return Outcome.ALREADY_GONE_OR_NOT_FOLLOWING
        }

        // The follow-state button sits BELOW the stats row; restrict the search
        // to the lower part of the screen so we never tap the stats "Following"
        // label (which would open the Following list instead).
        if (!clickByText(FOLLOWING_BUTTONS, minTopFraction = 0.28)) return Outcome.FAILURE
        if (!sleepInterruptible(900)) return Outcome.FAILURE

        if (captchaVisible(currentTexts())) return Outcome.CAPTCHA

        if (!clickByText(UNFOLLOW_MENU)) {
            // No confirmation dialog appeared: maybe we opened the wrong screen.
            performGlobalAction(GLOBAL_ACTION_BACK)
            return Outcome.FAILURE
        }
        if (!sleepInterruptible(1300)) return Outcome.FAILURE

        // Verify: the button should now read Follow.
        val verified = waitFor(4_000L) { containsButton(FOLLOW_BUTTONS) && !containsButton(FOLLOWING_BUTTONS) }
        return if (verified) Outcome.SUCCESS else Outcome.FAILURE
    }

    /** Is there a node whose label exactly equals one of the variants in the button region? */
    private fun containsButton(variants: Set<String>): Boolean = withRoot { root ->
        val rootBounds = Rect().also { root.getBoundsInScreen(it) }
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            val label = nodeLabel(n)
            if (variants.any { it.equals(label, true) }) {
                val b = Rect().also { n.getBoundsInScreen(it) }
                if (rootBounds.height() == 0 || b.top >= rootBounds.height() * 0.20) return@withRoot true
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        false
    } ?: false

    companion object {
        val TIKTOK_PKGS = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")

        val GONE_MARKERS = setOf(
            "Couldn't find this account", "couldn't find this account",
            "Account not found", "User not found",
            "هذا الحساب غير موجود", "تعذر العثور على هذا الحساب", "الحساب غير متاح"
        )
        val PRIVATE_MARKERS = setOf(
            "This account is private", "Private account",
            "هذا الحساب خاص", "حساب خاص"
        )
        val NO_VIDEO_MARKERS = setOf(
            "No videos yet", "No videos", "No content yet", "No posts yet",
            "لا توجد فيديوهات", "لم يتم نشر أي فيديو"
        )
        val CAPTCHA_MARKERS = setOf(
            "Verify it's really you", "Verify to continue", "Drag the slider",
            "Slide to verify", "Security check", "Drag the slider to fit the puzzle",
            "تحقق من أنك لست روبوت", "اسحب شريط التمرير", "التحقق الأمني", "أكمل التحقق"
        )
        // v1.4: the guard marker sets live in the pure core (GuardDecisions)
        // so the JVM tests exercise the very sets the service runs with;
        // these aliases keep every v1.3 call site unchanged. FOLLOW_BUTTONS
        // keeps its own copy for the unfollow flow (unchanged from v1.3).
        val FOLLOWING_BUTTONS = GuardDecisions.FOLLOWED_BUTTONS
        val FOLLOW_BUTTONS = setOf("Follow", "Follow back", "متابعة")
        val UNFOLLOW_MENU = setOf("Unfollow", "إلغاء المتابعة", "إلغاء متابعة")

        // v1.3 wrong-list protection markers (now owned by GuardDecisions).
        // EN is PRIMARY (the user's TikTok UI is English: "Following /
        // Followers / Friends / Follow back / Message"); AR variants kept
        // as secondary.
        val TAB_FOLLOWING = GuardDecisions.TAB_FOLLOWING
        val TAB_FOLLOWERS = GuardDecisions.TAB_FOLLOWERS
        val TAB_FRIENDS = GuardDecisions.TAB_FRIENDS
        // The "Sort by" control exists on the Following view only — the
        // fallback confirmation signal when no tab exposes a selected state.
        val SORT_BY_MARKERS = GuardDecisions.SORT_BY_MARKERS
        // v1.5: owned by the pure NavLogic core (JVM-tested) so the service
        // and the tests share one set; v1.5 adds «حسابي» to the v1.3 set.
        val PROFILE_TAB_MARKERS = NavLogic.PROFILE_TAB_MARKERS
        val OWN_PROFILE_MARKERS = setOf(
            "Edit profile", "Share profile", "تعديل الملف الشخصي", "مشاركة الملف الشخصي"
        )
        // Primary-button labels that prove the user does NOT follow the
        // opened profile (named for the audit sanity check that reads them).
        val NOT_FOLLOWED_BUTTONS = GuardDecisions.NOT_FOLLOWED_BUTTONS
    }
}
