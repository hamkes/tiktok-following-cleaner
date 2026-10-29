package com.hamdi.tiktokcleaner

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val PREFS_NAME = "tiktok_cleaner_prefs_v2"
    private const val KEY_THRESHOLD_DAYS = "threshold_days"
    private const val KEY_SPEED_MODE = "speed_mode"
    private const val KEY_UNFOLLOW_GONE = "unfollow_gone"
    private const val KEY_UNFOLLOW_FAKE = "unfollow_fake"
    private const val KEY_UNFOLLOW_DEAD = "unfollow_dead"
    private const val KEY_PROTECT_FRIENDS = "protect_friends"
    private const val KEY_FRIENDS_SKIPPED = "friends_skipped"
    private const val KEY_MAX_BATCH_UNFOLLOW = "max_batch_unfollow"
    private const val KEY_LANG = "app_lang"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getThresholdDays(context: Context): Int =
        prefs(context).getInt(KEY_THRESHOLD_DAYS, 180).coerceIn(7, 3650)

    fun setThresholdDays(context: Context, days: Int) {
        prefs(context).edit().putInt(KEY_THRESHOLD_DAYS, days.coerceIn(7, 3650)).apply()
    }

    fun getSpeedMode(context: Context): ScanSpeedMode =
        ScanSpeedMode.fromId(prefs(context).getString(KEY_SPEED_MODE, ScanSpeedMode.HYBRID_SMART.id))

    fun setSpeedMode(context: Context, mode: ScanSpeedMode) {
        prefs(context).edit().putString(KEY_SPEED_MODE, mode.id).apply()
    }

    fun isUnfollowGoneEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_UNFOLLOW_GONE, true)

    fun setUnfollowGoneEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_UNFOLLOW_GONE, enabled).apply()
    }

    fun isUnfollowFakeEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_UNFOLLOW_FAKE, true)

    fun setUnfollowFakeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_UNFOLLOW_FAKE, enabled).apply()
    }

    fun isUnfollowDeadEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_UNFOLLOW_DEAD, true)

    fun setUnfollowDeadEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_UNFOLLOW_DEAD, enabled).apply()
    }

    fun isProtectFriendsEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PROTECT_FRIENDS, true)

    fun setProtectFriendsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PROTECT_FRIENDS, enabled).apply()
    }

    // v2.0.6-gemini: usernames skipped as mutual Friends, lowercased.
    // Remembering them lets each batch work on fresh accounts first
    // instead of re-checking the same skips at the front of every
    // batch. Stale entries (accounts no longer in the results) are
    // harmless: they simply never match. Only consulted while the
    // Friends protection is on.
    fun getFriendsSkipped(context: Context): Set<String> =
        HashSet(prefs(context).getStringSet(KEY_FRIENDS_SKIPPED, emptySet()) ?: emptySet())

    fun addFriendsSkipped(context: Context, usernames: Collection<String>) {
        if (usernames.isEmpty()) return
        val merged = getFriendsSkipped(context).toMutableSet()
        merged.addAll(usernames.map { it.lowercase() })
        prefs(context).edit().putStringSet(KEY_FRIENDS_SKIPPED, merged).apply()
    }

    fun setFriendsSkipped(context: Context, usernames: Set<String>) {
        prefs(context).edit().putStringSet(KEY_FRIENDS_SKIPPED, usernames.map { it.lowercase() }.toSet()).apply()
    }

    fun getMaxBatchUnfollow(context: Context): Int =
        prefs(context).getInt(KEY_MAX_BATCH_UNFOLLOW, 150).coerceIn(10, 2000)

    fun setMaxBatchUnfollow(context: Context, limit: Int) {
        prefs(context).edit().putInt(KEY_MAX_BATCH_UNFOLLOW, limit.coerceIn(10, 2000)).apply()
    }

    fun getLanguage(context: Context): String =
        prefs(context).getString(KEY_LANG, "ar") ?: "ar"

    fun setLanguage(context: Context, lang: String) {
        prefs(context).edit().putString(KEY_LANG, lang).apply()
    }
}
