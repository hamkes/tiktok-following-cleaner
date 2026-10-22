package com.hamdi.tiktokcleaner

import java.util.concurrent.ConcurrentHashMap

/**
 * Shared state between MainActivity (UI) and CleanerAccessibilityService
 * (automation). Both run in the same process, so a plain object works.
 */
object CleanerEngine {

    enum class RunState {
        IDLE, AUDIT_LIST, AUDIT_PROFILES, UNFOLLOW,
        PAUSED_CAPTCHA, CAP_REACHED, DONE, STOPPED, ERROR
    }

    @Volatile var state: RunState = RunState.IDLE
    @Volatile var statusDetail: String = ""
    @Volatile var stopRequested: Boolean = false

    // v1.2: floating-bubble pause state. `paused` is the user's pause toggle
    // (bubble panel or future UI); `panelOpen` is set by the overlay while its
    // expanded panel is on screen, which also auto-pauses the engine between
    // steps so the panel never races the automation.
    @Volatile var paused: Boolean = false
    @Volatile var panelOpen: Boolean = false

    fun isPaused(): Boolean = paused || panelOpen

    fun togglePause() {
        paused = !paused
        LogStore.log(if (paused) "إيقاف مؤقت من الأيقونة العائمة" else "استئناف من الأيقونة العائمة")
    }

    // Audit progress
    @Volatile var collectedCount: Int = 0
    @Volatile var auditedCount: Int = 0
    @Volatile var auditTotal: Int = 0
    val categoryCounts = ConcurrentHashMap<Category, Int>()

    // Unfollow setup + progress
    @Volatile var candidates: List<String> = emptyList()
    @Volatile var candidatesSource: String = ""
    @Volatile var candidatesConfirmed: Boolean = false
    @Volatile var unfollowProcessed: Int = 0
    @Volatile var unfollowSuccess: Int = 0
    @Volatile var unfollowTotal: Int = 0

    @Volatile var lastTikTokPackage: String? = null
    @Volatile var service: CleanerAccessibilityService? = null

    fun isRunning(): Boolean =
        state == RunState.AUDIT_LIST || state == RunState.AUDIT_PROFILES || state == RunState.UNFOLLOW

    fun requestStop() {
        stopRequested = true
        LogStore.log("طلب المستخدم إيقافاً فورياً (STOP)")
    }

    fun startAudit(resume: Boolean) {
        paused = false
        panelOpen = false
        val s = service
        if (s == null) {
            LogStore.log("خطأ: خدمة إمكانية الوصول غير متصلة. فعّلها من الإعدادات أولاً.")
            return
        }
        s.beginAudit(resume)
    }

    fun startUnfollow() {
        paused = false
        panelOpen = false
        val s = service
        if (s == null) {
            LogStore.log("خطأ: خدمة إمكانية الوصول غير متصلة. فعّلها من الإعدادات أولاً.")
            return
        }
        s.beginUnfollow()
    }
}
