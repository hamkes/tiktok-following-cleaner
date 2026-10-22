package com.hamdi.tiktokcleaner

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("cleaner_prefs", Context.MODE_PRIVATE)

    // ---- Settings (defaults from the design; caps locked to sane maximums) ----

    var thresholdDays: Int
        get() = sp.getInt("thresholdDays", Classifier.DEFAULT_THRESHOLD_DAYS)
        set(v) = sp.edit().putInt("thresholdDays", v.coerceIn(7, 3650)).apply()

    var dayCap: Int
        get() = sp.getInt("dayCap", 60)
        set(v) = sp.edit().putInt("dayCap", v.coerceIn(1, MAX_DAY_CAP)).apply()

    var hourCap: Int
        get() = sp.getInt("hourCap", 12)
        set(v) = sp.edit().putInt("hourCap", v.coerceIn(1, MAX_HOUR_CAP)).apply()

    var gapMinSec: Int
        get() = sp.getInt("gapMinSec", 30)
        set(v) = sp.edit().putInt("gapMinSec", v.coerceIn(5, 3600)).apply()

    var gapMaxSec: Int
        get() = sp.getInt("gapMaxSec", 120)
        set(v) = sp.edit().putInt("gapMaxSec", v.coerceIn(gapMinSec, 7200)).apply()

    var batchSize: Int
        get() = sp.getInt("batchSize", 12)
        set(v) = sp.edit().putInt("batchSize", v.coerceIn(5, 30)).apply()

    // ---- v1.2: floating control bubble (overlay) ----

    var bubbleEnabled: Boolean
        get() = sp.getBoolean("bubbleEnabled", true)
        set(v) = sp.edit().putBoolean("bubbleEnabled", v).apply()

    // ---- Audit progress (for stop/resume) ----

    var auditProfileIndex: Int
        get() = sp.getInt("auditProfileIndex", 0)
        set(v) = sp.edit().putInt("auditProfileIndex", v).apply()

    // ---- Rolling unfollow counters (persisted timestamps) ----

    fun unfollowTimestamps(): MutableList<Long> {
        val raw = sp.getString("unfollowTimes", "") ?: ""
        if (raw.isEmpty()) return mutableListOf()
        return raw.split(",").mapNotNull { it.toLongOrNull() }.toMutableList()
    }

    fun recordUnfollow(nowMs: Long) {
        val list = unfollowTimestamps()
        list.add(nowMs)
        // Keep at most the last 48h of history.
        val pruned = list.filter { nowMs - it < 48L * 3_600_000L }
        sp.edit().putString("unfollowTimes", pruned.joinToString(",")).apply()
    }

    fun countInLast(hours: Long, nowMs: Long): Int {
        val window = hours * 3_600_000L
        return unfollowTimestamps().count { nowMs - it < window }
    }

    fun oldestInLast(hours: Long, nowMs: Long): Long? {
        val window = hours * 3_600_000L
        return unfollowTimestamps().filter { nowMs - it < window }.minOrNull()
    }

    companion object {
        const val MAX_DAY_CAP = 150   // hard ceiling, cannot be raised in settings
        const val MAX_HOUR_CAP = 20   // hard ceiling, cannot be raised in settings
    }
}
