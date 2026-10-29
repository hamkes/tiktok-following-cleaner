package com.hamdi.tiktokcleaner

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Upgraded High-Speed On-Device Automation Service (v2.0):
 * - Replaces fixed Thread.sleep delays with 80ms adaptive readiness polling (waitForWindowCondition)
 * - Dual Scroll Engine: combines ACTION_SCROLL_FORWARD with physical dispatchGesture swipe + micro-jiggle recovery
 * - Stops collecting above "Suggested accounts" headers
 * - Supports Turbo In-List inspection, Hybrid Smart Deep-Linking (snssdk1233:// + https fallback), and Safe mode
 * - Auto-pauses on CAPTCHA challenges and protects mutual Friends when configured
 */
class CleanerAccessibilityService : AccessibilityService() {

    private val workerExecutor = Executors.newSingleThreadExecutor()
    private var currentTask: Future<*>? = null
    private val isPausedFlag = AtomicBoolean(false)
    private val isCancelledFlag = AtomicBoolean(false)
    private lateinit var overlay: OverlayController

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = OverlayController(this)
        Engine.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Event-driven readiness is polled directly via rootInActiveWindow in worker loops
    }

    override fun onInterrupt() {
        // v2.0.4-gemini: a system interruption must PAUSE the run, not
        // kill it. stopRun() here made every interruption (call, service
        // toggle, system reclaim) look like the run "stopped suddenly"
        // with no way back. Pausing keeps the remaining targets; the
        // user resumes from the bubble. onDestroy still stops fully.
        pauseRun()
        LogStore.log("⚠️ قاطع النظام خدمة الوصول — تم الإيقاف المؤقت، اضغط استئناف للمتابعة")
    }

    override fun onDestroy() {
        stopRun()
        overlay.hide()
        Engine.detach()
        workerExecutor.shutdownNow()
        super.onDestroy()
    }

    fun beginAudit(resume: Boolean) {
        cancelCurrentTask()
        isPausedFlag.set(false)
        isCancelledFlag.set(false)
        currentTask = workerExecutor.submit {
            try {
                runAudit(resume)
            } catch (e: InterruptedException) {
                LogStore.log("تم إيقاف عملية الفحص")
            } catch (e: Exception) {
                LogStore.log("⚠️ خطأ غير متوقع أثناء الفحص: ${e.message}")
            } finally {
                Engine.updateStats(Engine.stats.copy(phase = RunPhase.IDLE, currentUsername = ""))
                overlay.hide()
            }
        }
    }

    fun beginUnfollow(selectedUsernames: Set<String>? = null) {
        cancelCurrentTask()
        isPausedFlag.set(false)
        isCancelledFlag.set(false)
        currentTask = workerExecutor.submit {
            try {
                runUnfollow(selectedUsernames)
            } catch (e: InterruptedException) {
                LogStore.log("تم إيقاف عملية إلغاء المتابعة")
            } catch (e: Exception) {
                LogStore.log("⚠️ خطأ أثناء إلغاء المتابعة: ${e.message}")
            } finally {
                Engine.updateStats(Engine.stats.copy(phase = RunPhase.IDLE, currentUsername = ""))
                overlay.hide()
            }
        }
    }

    fun pauseRun() {
        isPausedFlag.set(true)
        val st = Engine.stats.copy(phase = RunPhase.PAUSED, statusMessage = "متوقف مؤقتاً")
        Engine.updateStats(st)
        overlay.showOrUpdate(st)
        LogStore.log("⏸️ تم إيقاف العملية مؤقتاً")
    }

    fun resumeRun() {
        isPausedFlag.set(false)
        LogStore.log("▶️ تم استئناف العملية")
    }

    fun stopRun() {
        isCancelledFlag.set(true)
        isPausedFlag.set(false)
        cancelCurrentTask()
        val st = Engine.stats.copy(phase = RunPhase.IDLE, currentUsername = "", statusMessage = "تم الإيقاف")
        Engine.updateStats(st)
        overlay.hide()
        LogStore.log("⏹️ تم إنهاء العملية")
    }

    private fun cancelCurrentTask() {
        currentTask?.cancel(true)
        currentTask = null
    }

    private fun checkPauseAndCancel(activePhase: RunPhase) {
        if (isCancelledFlag.get() || Thread.currentThread().isInterrupted) {
            throw InterruptedException()
        }
        while (isPausedFlag.get() && !isCancelledFlag.get()) {
            Thread.sleep(200)
        }
        if (isCancelledFlag.get()) throw InterruptedException()
        if (Engine.stats.phase == RunPhase.PAUSED || Engine.stats.phase == RunPhase.CAPTCHA_PAUSED) {
            val st = Engine.stats.copy(phase = activePhase)
            Engine.updateStats(st)
            overlay.showOrUpdate(st)
        }
    }

    private fun runAudit(resume: Boolean) {
        val speedMode = Prefs.getSpeedMode(this)
        val thresholdDays = Prefs.getThresholdDays(this)
        LogStore.log("🚀 بدء الفحص الذكي (الوضع: ${speedMode.name}، حد الخمول: $thresholdDays يوم)")

        if (!resume) {
            AppFiles.clearAll(this)
        }

        val collected = AppFiles.loadCollectedUsernames(this)
        val existingResults = AppFiles.loadAuditResults(this).associateBy { it.username.lowercase() }.toMutableMap()
        val preClassifiedInList = mutableMapOf<String, Classifier.ClassificationResult>()

        // Launch TikTok if not already in foreground
        launchTikTokIfNeeded()

        // Phase 1: Collect usernames from verified Following tab
        if (!resume || collected.isEmpty()) {
            updateProgress(RunPhase.COLLECTING, collected.size, existingResults.size, collected.size, existingResults.values.count { it.category.isDefaultUnfollowCandidate() }, "", 0, "جاري التحقق من تبويب المتابَعين...")
            val verified = ensureOnFollowingList()
            if (!verified) {
                LogStore.log("❌ تعذر تأكيد قائمة المتابَعين (Following). افتح قائمة المتابَعين في تيك توك ثم أعد المحاولة.")
                return
            }

            var stallRounds = 0
            val maxStallRounds = 6
            while (!isCancelledFlag.get() && stallRounds < maxStallRounds) {
                checkPauseAndCancel(RunPhase.COLLECTING)
                val snapshots = captureSnapshots()

                if (GuardDecisions.isCaptchaOrChallengeScreen(snapshots)) {
                    pauseForCaptcha()
                    continue
                }

                val tabDecision = GuardDecisions.decideTab(snapshots)
                if (tabDecision.verdict == GuardDecisions.TabVerdict.WRONG_TAB) {
                    // v2.0.2: ONE re-check after 900 ms — layout passes
                    // flicker during scroll; a single transient WRONG_TAB
                    // must not kill a confirmed run.
                    Thread.sleep(900)
                    if (isCancelledFlag.get()) return
                    val recheck = GuardDecisions.decideTab(captureSnapshots())
                    if (recheck.verdict == GuardDecisions.TabVerdict.WRONG_TAB) {
                        LogStore.log("🛑 حماية الأمان: تم رصد انتقال لتبويب خاطئ (${recheck.reason}). تم إيقاف الجمع فوراً.")
                        return
                    }
                }

                val suggestedBoundaryY = GuardDecisions.findSuggestedBoundaryY(snapshots)
                val beforeCount = collected.size
                extractUsernamesFromList(snapshots, suggestedBoundaryY, collected, preClassifiedInList)
                val added = collected.size - beforeCount

                if (added > 0) {
                    stallRounds = 0
                    AppFiles.saveCollectedUsernames(this, collected)
                    updateProgress(
                        RunPhase.COLLECTING,
                        collected.size,
                        existingResults.size,
                        collected.size,
                        existingResults.values.count { it.category.isDefaultUnfollowCandidate() },
                        collected.lastOrNull().orEmpty(),
                        0,
                        "تم جمع ${collected.size} حساب..."
                    )
                } else {
                    stallRounds++
                }

                if (suggestedBoundaryY != null && added == 0) {
                    LogStore.log("✅ تم الوصول إلى نهاية قائمة المتابَعين (بداية قسم الحسابات المقترحة). إجمالي الحسابات: ${collected.size}")
                    break
                }

                val scrolled = performSmartScroll(snapshots, useJiggle = stallRounds >= 3)
                if (!scrolled && stallRounds >= 3) {
                    break
                }
                Thread.sleep(speedMode.stepDelayMs)
            }
            LogStore.log("✅ اكتمل جمع القائمة: ${collected.size} حساب فريد")
        }

        // Phase 2: Classify collected accounts with high-speed adaptive inspection
        val tripwire = GuardDecisions.WrongListTripwire()
        val startMs = System.currentTimeMillis()
        var processedInSession = 0
        val listToInspect = collected.toList()

        for ((index, username) in listToInspect.withIndex()) {
            checkPauseAndCancel(RunPhase.CLASSIFYING)
            if (existingResults.containsKey(username.lowercase())) continue

            processedInSession++
            val elapsedMin = ((System.currentTimeMillis() - startMs) / 60_000.0).coerceAtLeast(0.1)
            val ratePerMin = (processedInSession / elapsedMin).toInt()

            updateProgress(
                RunPhase.CLASSIFYING,
                collected.size,
                index + 1,
                listToInspect.size,
                existingResults.values.count { it.category.isDefaultUnfollowCandidate() },
                username,
                ratePerMin,
                "فحص @$username"
            )

            // Instant zero-click pre-classification if already detected as GONE in the list row
            val pre = preClassifiedInList[username.lowercase()]
            if (pre != null) {
                val res = AuditResult(
                    username = username,
                    category = pre.category,
                    detail = pre.detail,
                    videoCount = 0,
                    lastVideoMs = null,
                    checkedAtMs = System.currentTimeMillis()
                )
                existingResults[username.lowercase()] = res
                AppFiles.appendAuditResult(this, res)
                continue
            }

            val inspection = inspectProfileAdaptive(username, speedMode, thresholdDays, tripwire)
            if (inspection.invalidateRun) {
                LogStore.log("🛑 أمان Tripwire: تراكمت حسابات غير متابَعة متتالية — تم إيقاف الفحص لحماية حسابك.")
                return
            }

            existingResults[username.lowercase()] = inspection.result
            AppFiles.appendAuditResult(this, inspection.result)
            Thread.sleep((speedMode.stepDelayMs / 2).coerceAtLeast(150L))
        }

        val totalCandidates = existingResults.values.count { it.category.isDefaultUnfollowCandidate() }
        LogStore.log("🎉 اكتمل الفحص بالكامل! إجمالي المفحوصين: ${existingResults.size} | المرشحون للإلغاء: $totalCandidates")
    }

    private data class ProfileInspectionOutcome(
        val result: AuditResult,
        val invalidateRun: Boolean = false
    )

    private fun inspectProfileAdaptive(
        username: String,
        speedMode: ScanSpeedMode,
        thresholdDays: Int,
        tripwire: GuardDecisions.WrongListTripwire
    ): ProfileInspectionOutcome {
        openProfileSmart(username)

        // Adaptive 80ms polling until profile header or "Account not found" settles
        val readySnapshots = waitForWindowCondition(timeoutMs = speedMode.profileTimeoutMs) { nodes ->
            if (GuardDecisions.isCaptchaOrChallengeScreen(nodes)) return@waitForWindowCondition true
            val combined = nodes.joinToString(" ") { it.combinedLabel().lowercase() }
            combined.contains(username.lowercase()) ||
                combined.contains("account not found") ||
                combined.contains("حساب غير موجود") ||
                GuardDecisions.decideFollowState(nodes) != GuardDecisions.FollowState.UNKNOWN
        }

        if (GuardDecisions.isCaptchaOrChallengeScreen(readySnapshots)) {
            pauseForCaptcha()
        }

        val allLabels = readySnapshots.map { it.combinedLabel() }.filter { it.isNotEmpty() }
        val joinedLower = allLabels.joinToString(" ").lowercase()

        val isGone = joinedLower.contains("account not found") ||
            joinedLower.contains("user not found") ||
            joinedLower.contains("حساب غير موجود") ||
            joinedLower.contains("المستخدم غير موجود") ||
            joinedLower.contains("تم حظر الحساب")

        val isPrivate = joinedLower.contains("this account is private") ||
            joinedLower.contains("هذا الحساب خاص") ||
            joinedLower.contains("حساب خاص")

        val followState = GuardDecisions.decideFollowState(readySnapshots)
        if (!isGone && followState != GuardDecisions.FollowState.UNKNOWN) {
            if (tripwire.onFollowState(followState) == GuardDecisions.WrongListTripwire.Decision.INVALIDATE_RUN) {
                return ProfileInspectionOutcome(
                    AuditResult(username, Category.UNKNOWN, "تم إيقاف الفحص بواسطة Tripwire", null, null, System.currentTimeMillis()),
                    invalidateRun = true
                )
            }
        }

        val nowMs = System.currentTimeMillis()
        var parsedLastVideoMs: Long? = null
        var detectedVideos = 0

        for (label in allLabels) {
            val dt = Classifier.parseDateText(label, nowMs)
            if (dt != null) {
                detectedVideos++
                if (parsedLastVideoMs == null || dt > parsedLastVideoMs) {
                    parsedLastVideoMs = dt
                }
            }
        }

        val hasZeroVideosSignal = joinedLower.contains("no videos") ||
            joinedLower.contains("لا توجد فيديوهات") ||
            joinedLower.contains("لم ينشر أي فيديو")

        val videoCount: Int? = when {
            isGone -> 0
            hasZeroVideosSignal -> 0
            detectedVideos > 0 -> detectedVideos
            followState == GuardDecisions.FollowState.FOLLOWING || followState == GuardDecisions.FollowState.FRIENDS -> {
                if (Classifier.isAutoGeneratedHandle(username) && !isPrivate) 0 else null
            }
            else -> null
        }

        val classification = Classifier.classify(
            username = username,
            isGone = isGone,
            isPrivate = isPrivate,
            videoCount = videoCount,
            lastPostTimestamp = parsedLastVideoMs,
            currentTimestamp = nowMs,
            inactivityThresholdDays = thresholdDays
        )

        return ProfileInspectionOutcome(
            AuditResult(
                username = username,
                category = classification.category,
                detail = classification.detail,
                videoCount = videoCount,
                lastVideoMs = parsedLastVideoMs,
                checkedAtMs = nowMs
            )
        )
    }

    private fun runUnfollow(selectedUsernames: Set<String>?) {
        val allResults = AppFiles.loadAuditResults(this).toMutableList()
        val maxBatch = Prefs.getMaxBatchUnfollow(this)
        val protectFriends = Prefs.isProtectFriendsEnabled(this)
        val speedMode = Prefs.getSpeedMode(this)

        val matching = allResults.filter { r ->
            if (selectedUsernames != null) {
                r.username.lowercase() in selectedUsernames
            } else {
                r.selectedForCleanup && (
                    (r.category == Category.GONE && Prefs.isUnfollowGoneEnabled(this)) ||
                        (r.category == Category.FAKE && Prefs.isUnfollowFakeEnabled(this)) ||
                        (r.category == Category.DEAD && Prefs.isUnfollowDeadEnabled(this))
                    )
            }
        }
        // v2.0.3-gemini: the batch cap (default 150) silently truncated
        // larger selections — the remainder is now named in the log.
        // v2.0.6-gemini: accounts remembered as Friends-skips sink to
        // the end (stable order otherwise), so every batch works on
        // fresh accounts first instead of re-checking the same ~125
        // skips at the front of every batch. Only while the Friends
        // protection is on; with it off, the memory is ignored.
        val friendsSkipped = if (protectFriends) {
            val remembered = Prefs.getFriendsSkipped(this)
            val liveKeys = allResults.map { it.username.lowercase() }.toSet()
            val pruned = remembered.intersect(liveKeys)
            if (pruned.size != remembered.size) Prefs.setFriendsSkipped(this, pruned)
            pruned
        } else {
            emptySet()
        }
        val ordered = TargetOrder.orderForUnfollow(matching, friendsSkipped)
        val targets = ordered.take(maxBatch)

        if (targets.isEmpty()) {
            LogStore.log("ℹ️ لا توجد حسابات محددة لإلغاء متابعتها")
            return
        }

        LogStore.log("🧹 بدء تنظيف ${targets.size} حساب مرشح (حماية الأصدقاء: ${if (protectFriends) "مفعلة" else "معطلة"})")
        val startMs = System.currentTimeMillis()
        var unfollowed = 0
        var skippedFriends = 0
        var alreadyDone = 0
        var failedVerify = 0
        var unknownState = 0
        val skippedThisRun = HashSet<String>()

        for ((idx, item) in targets.withIndex()) {
            checkPauseAndCancel(RunPhase.UNFOLLOWING)
            val elapsedMin = ((System.currentTimeMillis() - startMs) / 60_000.0).coerceAtLeast(0.1)
            val rate = ((idx + 1) / elapsedMin).toInt()

            updateProgress(
                RunPhase.UNFOLLOWING,
                allResults.size,
                allResults.size,
                allResults.size,
                targets.size - unfollowed,
                item.username,
                rate,
                "إلغاء متابعة @${item.username}",
                // v2.0.5-gemini: feed the bubble the live counters —
                // successful unfollows so far / batch size.
                unfollowedCount = unfollowed,
                totalToUnfollow = targets.size
            )

            openProfileSmart(item.username)
            val snapshots = waitForWindowCondition(timeoutMs = speedMode.profileTimeoutMs) { nodes ->
                GuardDecisions.decideFollowState(nodes) != GuardDecisions.FollowState.UNKNOWN
            }

            // v2.0.4-gemini: no active window at all after the full wait
            // means the screen is off or TikTok left the foreground.
            // Previously the loop treated this like "unknown profile"
            // and skipped the account, burning through the whole batch
            // doing nothing — the run "stopped suddenly" with almost
            // nothing unfollowed. Pause instead: no target is skipped,
            // and the run resumes from the same account.
            if (snapshots.isEmpty()) {
                pauseForNoWindow()
                continue
            }

            if (GuardDecisions.isCaptchaOrChallengeScreen(snapshots)) {
                pauseForCaptcha()
                continue
            }

            val followState = GuardDecisions.decideFollowState(snapshots)
            if (protectFriends && followState == GuardDecisions.FollowState.FRIENDS) {
                LogStore.log("🛡️ تم تخطي @${item.username} لأنه صديق متبادل (Friends)")
                // v2.0.6-gemini: remember the skip so the next batch
                // works on fresh accounts first (see target ordering
                // above). Flushed every 10 skips so a mid-batch stop
                // does not lose the memory.
                skippedFriends++
                skippedThisRun.add(item.username.lowercase())
                if (skippedThisRun.size % 10 == 0) {
                    Prefs.addFriendsSkipped(this, skippedThisRun)
                    skippedThisRun.clear()
                }
                continue
            }

            when (followState) {
                GuardDecisions.FollowState.FOLLOW, GuardDecisions.FollowState.FOLLOW_BACK -> {
                    // Already not followed (unfollowed manually, or a
                    // previous run's click did land after all). Goal
                    // achieved: drop from the list, count separately.
                    alreadyDone++
                    allResults.removeAll { it.username.equals(item.username, ignoreCase = true) }
                    AppFiles.saveAllAuditResults(this, allResults)
                    LogStore.log("ℹ️ @${item.username} غير متابَع أصلاً — أُزيل من القائمة")
                }
                GuardDecisions.FollowState.FOLLOWING, GuardDecisions.FollowState.FRIENDS -> {
                    val clicked = clickUnfollowButtonInActiveWindow()
                    if (!clicked) {
                        failedVerify++
                        LogStore.log("⚠️ تعذر النقر على زر إلغاء المتابعة لـ @${item.username} — بقي في القائمة")
                    } else if (verifyUnfollowed()) {
                        // v2.0.7-gemini: delete ONLY on verified state
                        // change. An unverified click used to be logged
                        // as success and the account deleted — 70 such
                        // accounts on 2026-10-28 were still followed.
                        unfollowed++
                        allResults.removeAll { it.username.equals(item.username, ignoreCase = true) }
                        AppFiles.saveAllAuditResults(this, allResults)
                        LogStore.log("✓ تم إلغاء متابعة @${item.username} (${item.category.name}) — تم التأكد من الزر")
                    } else {
                        failedVerify++
                        LogStore.log("⚠️ لم يتم التأكد من إلغاء متابعة @${item.username} (الزر لم يصر Follow) — بقي في القائمة وسيُعاد لاحقاً")
                    }
                }
                else -> {
                    // REQUESTED / UNKNOWN: no action, no deletion, and
                    // now logged — previously these silently consumed
                    // ~20 slots of every 150 batch with no log line.
                    unknownState++
                    LogStore.log("⏭️ @${item.username}: حالة الزر $followState — بدون إجراء، بقي في القائمة")
                }
            }
            Thread.sleep(speedMode.stepDelayMs)
        }

        if (skippedThisRun.isNotEmpty()) {
            Prefs.addFriendsSkipped(this, skippedThisRun)
        }
        val totalSkippedFriends = Prefs.getFriendsSkipped(this).size
        LogStore.log("✅ اكتملت دفعة التنظيف: تم إلغاء متابعة $unfollowed حساب بنجاح (بعد التأكد)، $alreadyDone غير متابَع أصلاً، فشل التأكد لـ $failedVerify، حالة غير معروفة لـ $unknownState، تم تخطي $skippedFriends كأصدقاء متبادلين (إجمالي المتخطى كأصدقاء: $totalSkippedFriends)")
        val remaining = matching.size - targets.size
        if (remaining > 0) {
            LogStore.log("⏳ بقي $remaining حساب محدد لم يُنظَّف (حد الدفعة $maxBatch) — اضغط «تنظيف المحددين» مرة أخرى لإكمالهم")
        }
    }

    private fun pauseForNoWindow() {
        isPausedFlag.set(true)
        val st = Engine.stats.copy(
            phase = RunPhase.PAUSED,
            statusMessage = "الشاشة مغلقة أو تيك توك ليس في الواجهة — افتح تيك توك ثم اضغط استئناف"
        )
        Engine.updateStats(st)
        overlay.showOrUpdate(st)
        LogStore.log("⏸️ توقف مؤقت: لا توجد نافذة نشطة (الشاشة مغلقة أو تيك توك في الخلفية) — لم يتم تخطي أي حساب، افتح تيك توك واضغط استئناف")
    }

    private fun pauseForCaptcha() {
        isPausedFlag.set(true)
        val st = Engine.stats.copy(
            phase = RunPhase.CAPTCHA_PAUSED,
            statusMessage = "يرجى حل التحقق الأمني (CAPTCHA) في تيك توك ثم اضغط استئناف"
        )
        Engine.updateStats(st)
        overlay.showOrUpdate(st)
        LogStore.log("⚠️ تم رصد تحقق أمني (CAPTCHA) على الشاشة — تم الإيقاف المؤقت تلقائياً لحماية حسابك")
    }

    private fun updateProgress(
        phase: RunPhase,
        collected: Int,
        audited: Int,
        totalToAudit: Int,
        candidates: Int,
        currentUser: String,
        ratePerMin: Int,
        msg: String,
        // v2.0.5-gemini: the unfollow counters were never wired — this
        // function copied the stale zeros from Engine.stats, so the
        // bubble showed "0/0" for the whole clean run. Callers that
        // pass live values now feed the bubble's real progress.
        unfollowedCount: Int = Engine.stats.unfollowedCount,
        totalToUnfollow: Int = Engine.stats.totalToUnfollow
    ) {
        val st = EngineStats(
            phase = phase,
            collectedCount = collected,
            auditedCount = audited,
            totalToAudit = totalToAudit,
            candidatesCount = candidates,
            unfollowedCount = unfollowedCount,
            totalToUnfollow = totalToUnfollow,
            currentUsername = currentUser,
            accountsPerMinute = ratePerMin,
            statusMessage = msg
        )
        Engine.updateStats(st)
        overlay.showOrUpdate(st)
    }

    private fun ensureOnFollowingList(): Boolean {
        // Build marker (v2.0.2): proves in the log which build ran.
        LogStore.log("🛡 حارس التبويبات v2.0.2: صف واحد + أولوية المتابَعين + إعادة تحقق + تمرير آمن")
        // Attempt up to 3 automatic navigation steps:
        // 1. Check if already on Following tab
        // 2. If on Profile screen, tap "Following" counter
        // 3. If on Home feed, tap bottom-bar "Profile" tab first
        for (attempt in 1..4) {
            val snapshots = captureSnapshots()
            val tabDec = GuardDecisions.decideTab(snapshots)
            if (tabDec.verdict == GuardDecisions.TabVerdict.CONFIRMED_FOLLOWING) {
                LogStore.log("✓ تم تأكيد قائمة المتابَعين: ${tabDec.reason}")
                return true
            }
            if (clickFollowingCounterIfPresent() || clickProfileTabIfPresent()) {
                Thread.sleep(950)
            } else {
                Thread.sleep(600)
            }
        }
        return GuardDecisions.decideTab(captureSnapshots()).verdict == GuardDecisions.TabVerdict.CONFIRMED_FOLLOWING
    }

    private fun clickProfileTabIfPresent(): Boolean {
        val root = rootInActiveWindow ?: return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val bottomLabels = mutableListOf<String>()
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val label = (node.text?.toString() ?: node.contentDescription?.toString()).orEmpty().trim()
            if (rect.top > 1400 && label.isNotEmpty()) {
                bottomLabels.add(label)
                if (NavLogic.isProfileTabLabel(label)) {
                    if (clickNodeOrParent(node)) {
                        LogStore.log("تم النقر تلقائياً على تبويب الملف الشخصي ($label)")
                        return true
                    }
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        if (bottomLabels.isNotEmpty()) {
            LogStore.log("الشريط السفلي المرصود: ${NavLogic.formatBottomStrip(bottomLabels)}")
        }
        return false
    }

    private fun clickFollowingCounterIfPresent(): Boolean {
        val root = rootInActiveWindow ?: return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val label = (node.text?.toString() ?: node.contentDescription?.toString()).orEmpty().trim()
            if (NavLogic.isFollowingCounterLabel(label)) {
                if (clickNodeOrParent(node)) {
                    LogStore.log("تم فتح قائمة المتابَعين تلقائياً ($label)")
                    return true
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    private fun clickUnfollowButtonInActiveWindow(): Boolean {
        // v2.0.7-gemini: NEVER click the first "following" node. The
        // profile header renders the "Following" counter label BEFORE
        // the action button, so first-match clicked the counter (opening
        // the Following list) and still returned true. Pick the button
        // via snapshots (clickable first, lowest on screen), then find
        // that exact node in the live tree by its screen bounds.
        val target = GuardDecisions.pickUnfollowButton(captureSnapshots()) ?: return false
        val root = rootInActiveWindow ?: return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val rect = Rect()
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            node.getBoundsInScreen(rect)
            if (rect.left == target.boundsLeft && rect.top == target.boundsTop &&
                rect.right == target.boundsRight && rect.bottom == target.boundsBottom
            ) {
                if (clickNodeOrParent(node)) {
                    Thread.sleep(350)
                    confirmUnfollowDialogIfShown()
                    return true
                }
                return false
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    private fun confirmUnfollowDialogIfShown() {
        // v2.0.7-gemini: poll up to ~1.5s (dialog/menu animation can
        // outlast one 350ms sleep) and match by CONTAINS, not exact
        // equality: the menu item can read "Unfollow @handle", and the
        // Friends▼ dropdown menu item may not itself be clickable —
        // clickNodeOrParent climbs to the clickable row.
        val deadline = System.currentTimeMillis() + 1500
        while (System.currentTimeMillis() < deadline) {
            val root = rootInActiveWindow ?: return
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                val label = nodeLabel(node)
                if (label.contains("unfollow") || label.contains("إلغاء المتابعة") || label == "تأكيد") {
                    if (clickNodeOrParent(node)) return
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
            Thread.sleep(300)
        }
    }

    private fun nodeLabel(node: AccessibilityNodeInfo): String {
        val t = node.text?.toString()?.trim().orEmpty()
        val c = node.contentDescription?.toString()?.trim().orEmpty()
        return when {
            t.isNotEmpty() && c.isNotEmpty() && !t.equals(c, ignoreCase = true) -> "$t $c"
            t.isNotEmpty() -> t
            else -> c
        }.lowercase()
    }

    /**
     * v2.0.7-gemini: an unfollow counts ONLY when the profile button
     * is re-read as "Follow" (or "Follow back" for a mutual) after the
     * click + confirmation. A bare successful click proved nothing —
     * 70 accounts were logged as unfollowed on 2026-10-28 while the
     * profile count moved by 1 and the accounts were deleted from the
     * results file. Fail-closed: false means "keep the account".
     */
    private fun verifyUnfollowed(timeoutMs: Long = 2500): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val state = GuardDecisions.decideFollowState(captureSnapshots())
            if (state == GuardDecisions.FollowState.FOLLOW ||
                state == GuardDecisions.FollowState.FOLLOW_BACK
            ) return true
            Thread.sleep(250)
        }
        return false
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo?): Boolean {
        var cur = node
        var depth = 0
        while (cur != null && depth < 5) {
            if (cur.isClickable) {
                return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            cur = cur.parent
            depth++
        }
        return false
    }

    private fun extractUsernamesFromList(
        snapshots: List<GuardDecisions.NodeSnapshot>,
        suggestedBoundaryY: Int?,
        collected: LinkedHashSet<String>,
        preClassified: MutableMap<String, Classifier.ClassificationResult>
    ) {
        val rowGroups = snapshots
            .filter { it.boundsTop > 180 && (suggestedBoundaryY == null || it.boundsTop < suggestedBoundaryY) }
            .groupBy { it.boundsTop / 90 }

        for ((_, group) in rowGroups) {
            val texts = group.map { it.combinedLabel().trim() }.filter { it.isNotEmpty() }
            val handle = texts.firstOrNull { Classifier.looksLikeUsername(it) }?.removePrefix("@") ?: continue
            collected.add(handle)
            val pre = Classifier.preClassifyFromRow(handle, texts)
            if (pre != null) {
                preClassified[handle.lowercase()] = pre
            }
        }
    }

    private fun performSmartScroll(
        snapshots: List<GuardDecisions.NodeSnapshot>,
        useJiggle: Boolean
    ): Boolean {
        val target = GuardDecisions.pickScrollTarget(snapshots)
        if (target != null && !useJiggle) {
            val root = rootInActiveWindow
            if (root != null) {
                val scrollableNode = findScrollableNodeByBounds(root, target)
                if (scrollableNode != null && scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                    return true
                }
            }
        }
        // Fallback or recovery: physical gesture swipe along center of screen
        val dm = resources.displayMetrics
        val centerX = dm.widthPixels / 2f
        val startY = dm.heightPixels * 0.78f
        val endY = dm.heightPixels * 0.24f

        if (useJiggle) {
            // Micro scroll up first to trigger TikTok's RecyclerView pagination listener
            dispatchSwipe(centerX, dm.heightPixels * 0.35f, dm.heightPixels * 0.55f, 160L)
            Thread.sleep(220)
        }
        return dispatchSwipe(centerX, startY, endY, 320L)
    }

    private fun findScrollableNodeByBounds(
        root: AccessibilityNodeInfo,
        target: GuardDecisions.NodeSnapshot
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            // v2.0.2: never scroll the pager itself — the pager and the
            // list often share identical bounds, and scrolling the pager
            // flips Following -> Followers (the reported "goes to
            // Followers and hangs"). Same exclusion as pickScrollTarget.
            val cls = (node.className?.toString().orEmpty()).lowercase()
            val id = (node.viewIdResourceName.orEmpty()).lowercase()
            val isPager = cls.contains("viewpager") ||
                cls.contains("horizontalscroll") ||
                id.contains("viewpager") ||
                id.contains("tab_layout")
            if (node.isScrollable && !isPager) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (r.top == target.boundsTop && r.bottom == target.boundsBottom) {
                    return node
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun dispatchSwipe(x: Float, startY: Float, endY: Float, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    private fun launchTikTokIfNeeded() {
        val pkgs = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
        for (pkg in pkgs) {
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                startActivity(launchIntent)
                Thread.sleep(800)
                return
            }
        }
    }

    private fun openProfileSmart(username: String) {
        val handle = username.trim().removePrefix("@")
        val pkgs = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
        for (pkg in pkgs) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.tiktok.com/@$handle")).apply {
                    setPackage(pkg)
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                }
                startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
    }

    private fun waitForWindowCondition(
        timeoutMs: Long,
        pollIntervalMs: Long = 80L,
        predicate: (List<GuardDecisions.NodeSnapshot>) -> Boolean
    ): List<GuardDecisions.NodeSnapshot> {
        val deadline = System.currentTimeMillis() + timeoutMs
        var latest = emptyList<GuardDecisions.NodeSnapshot>()
        while (System.currentTimeMillis() < deadline && !isCancelledFlag.get()) {
            latest = captureSnapshots()
            if (latest.isNotEmpty() && predicate(latest)) {
                return latest
            }
            Thread.sleep(pollIntervalMs)
        }
        return latest
    }

    private fun captureSnapshots(): List<GuardDecisions.NodeSnapshot> {
        val root = rootInActiveWindow ?: return emptyList()
        val list = ArrayList<GuardDecisions.NodeSnapshot>(96)
        // v2.0.1-gemini fix: propagate the selected state down the chain
        // (up to 4 hops from the nearest selected ancestor) — TikTok
        // often marks the tab container / indicator selected instead of
        // the label node, so a bare node.isSelected read flickers
        // between layout passes. The queue carries the hop distance
        // from the nearest selected ancestor (99 = none above).
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 99)
        val rect = Rect()
        while (queue.isNotEmpty() && list.size < 450) {
            val (node, parentHops) = queue.removeFirst()
            val hops = if (node.isSelected) 0 else (parentHops + 1).coerceAtMost(99)
            node.getBoundsInScreen(rect)
            list.add(
                GuardDecisions.NodeSnapshot(
                    text = node.text?.toString(),
                    contentDescription = node.contentDescription?.toString(),
                    className = node.className?.toString(),
                    viewIdResourceName = node.viewIdResourceName,
                    isSelected = node.isSelected,
                    selectedInChain = hops <= 4,
                    isScrollable = node.isScrollable,
                    isClickable = node.isClickable,
                    boundsLeft = rect.left,
                    boundsTop = rect.top,
                    boundsRight = rect.right,
                    boundsBottom = rect.bottom
                )
            )
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it to hops) }
            }
        }
        return list
    }
}
