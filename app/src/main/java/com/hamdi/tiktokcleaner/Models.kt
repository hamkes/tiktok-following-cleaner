package com.hamdi.tiktokcleaner

import org.json.JSONObject

enum class Category {
    GONE,
    FAKE,
    DEAD,
    UNKNOWN,
    ACTIVE;

    fun isDefaultUnfollowCandidate(): Boolean =
        this == GONE || this == FAKE || this == DEAD
}

enum class ScanSpeedMode(val id: String, val stepDelayMs: Long, val profileTimeoutMs: Long) {
    TURBO_IN_LIST("turbo", 350L, 2200L),
    HYBRID_SMART("hybrid", 550L, 3200L),
    SAFE_DEEP_LINK("safe", 900L, 4800L);

    companion object {
        fun fromId(id: String?): ScanSpeedMode =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: HYBRID_SMART
    }
}

enum class RunPhase {
    IDLE,
    COLLECTING,
    CLASSIFYING,
    UNFOLLOWING,
    PAUSED,
    CAPTCHA_PAUSED
}

data class EngineStats(
    val phase: RunPhase = RunPhase.IDLE,
    val collectedCount: Int = 0,
    val auditedCount: Int = 0,
    val totalToAudit: Int = 0,
    val candidatesCount: Int = 0,
    val unfollowedCount: Int = 0,
    val totalToUnfollow: Int = 0,
    val currentUsername: String = "",
    val accountsPerMinute: Int = 0,
    val statusMessage: String = ""
)

data class AuditResult(
    val username: String,
    val category: Category,
    val detail: String,
    val videoCount: Int?,     // null = could not be read from the UI
    val lastVideoMs: Long?,   // null = unknown
    val checkedAtMs: Long,
    val selectedForCleanup: Boolean = category.isDefaultUnfollowCandidate()
) {
    fun toCsvRow(): String {
        fun esc(s: String) = s.replace(",", " ").replace("\n", " ").replace("\r", " ")
        return listOf(
            esc(username),
            category.name,
            esc(detail),
            videoCount?.toString() ?: "",
            lastVideoMs?.toString() ?: "",
            checkedAtMs.toString()
        ).joinToString(",")
    }

    fun toJsonLine(): String =
        JSONObject()
            .put("username", username)
            .put("category", category.name)
            .put("detail", detail)
            .put("videoCount", videoCount ?: JSONObject.NULL)
            .put("lastVideoMs", lastVideoMs ?: JSONObject.NULL)
            .put("checkedAtMs", checkedAtMs)
            .put("selectedForCleanup", selectedForCleanup)
            .toString()

    companion object {
        const val CSV_HEADER = "username,category,detail,videoCount,lastVideoMs,checkedAtMs"

        fun fromJsonLine(line: String): AuditResult? {
            return try {
                val obj = JSONObject(line.trim())
                val username = obj.optString("username", "").trim()
                if (username.isEmpty()) return null
                val catName = obj.optString("category", "UNKNOWN")
                val category = try {
                    Category.valueOf(catName)
                } catch (_: IllegalArgumentException) {
                    Category.UNKNOWN
                }
                val detail = obj.optString("detail", "")
                val videoCount = if (obj.isNull("videoCount")) null else obj.optInt("videoCount")
                val lastVideoMs = if (obj.isNull("lastVideoMs")) null else obj.optLong("lastVideoMs")
                val checkedAtMs = obj.optLong("checkedAtMs", System.currentTimeMillis())
                val selected = if (obj.has("selectedForCleanup")) {
                    obj.optBoolean("selectedForCleanup", category.isDefaultUnfollowCandidate())
                } else {
                    category.isDefaultUnfollowCandidate()
                }
                AuditResult(
                    username = username,
                    category = category,
                    detail = detail,
                    videoCount = videoCount,
                    lastVideoMs = lastVideoMs,
                    checkedAtMs = checkedAtMs,
                    selectedForCleanup = selected
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
