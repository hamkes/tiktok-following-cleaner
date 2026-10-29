package com.hamdi.tiktokcleaner

import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Central state coordinator bridging UI components (MainActivity, OverlayController)
 * and CleanerAccessibilityService without memory leaks.
 */
object Engine {
    private var serviceRef: WeakReference<CleanerAccessibilityService>? = null
    private val listeners = CopyOnWriteArrayList<(EngineStats) -> Unit>()

    @Volatile
    var stats: EngineStats = EngineStats()
        private set

    fun attach(service: CleanerAccessibilityService) {
        serviceRef = WeakReference(service)
        LogStore.log("تم ربط محرك الفحص بخدمة الوصول بنجاح (Engine v2.0)")
        notifyListeners()
    }

    fun detach() {
        serviceRef = null
        updateStats(EngineStats(phase = RunPhase.IDLE, statusMessage = "خدمة الوصول غير متصلة"))
    }

    fun getService(): CleanerAccessibilityService? = serviceRef?.get()

    fun isServiceConnected(): Boolean = serviceRef?.get() != null

    fun isRunning(): Boolean {
        val p = stats.phase
        return p == RunPhase.COLLECTING || p == RunPhase.CLASSIFYING ||
            p == RunPhase.UNFOLLOWING || p == RunPhase.PAUSED || p == RunPhase.CAPTCHA_PAUSED
    }

    fun updateStats(newStats: EngineStats) {
        stats = newStats
        notifyListeners()
    }

    fun addStatsListener(listener: (EngineStats) -> Unit) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
        listener.invoke(stats)
    }

    fun removeStatsListener(listener: (EngineStats) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        val snapshot = stats
        for (l in listeners) {
            try {
                l.invoke(snapshot)
            } catch (_: Exception) {
            }
        }
    }

    fun startAudit(resume: Boolean = false): Boolean {
        val svc = getService()
        if (svc == null) {
            LogStore.log("⚠️ تعذر بدء الفحص: يرجى تفعيل خدمة إمكانية الوصول أولاً")
            return false
        }
        svc.beginAudit(resume)
        return true
    }

    fun startUnfollow(selectedUsernames: Set<String>? = null): Boolean {
        val svc = getService()
        if (svc == null) {
            LogStore.log("⚠️ تعذر بدء إلغاء المتابعة: خدمة الوصول غير مفعلة")
            return false
        }
        svc.beginUnfollow(selectedUsernames)
        return true
    }

    fun pause() {
        getService()?.pauseRun()
    }

    fun resume() {
        getService()?.resumeRun()
    }

    fun stop() {
        getService()?.stopRun()
    }
}
