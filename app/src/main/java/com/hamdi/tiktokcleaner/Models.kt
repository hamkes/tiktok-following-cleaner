package com.hamdi.tiktokcleaner

enum class Category { GONE, FAKE, DEAD, UNKNOWN, ACTIVE }

data class AuditResult(
    val username: String,
    val category: Category,
    val detail: String,
    val videoCount: Int?,      // null = could not be read from the UI
    val lastVideoMs: Long?,    // null = unknown
    val checkedAtMs: Long
) {
    fun toCsvRow(): String {
        fun esc(s: String) = s.replace(",", ";").replace("\n", " ")
        return listOf(
            esc(username), category.name, esc(detail),
            videoCount?.toString() ?: "", lastVideoMs?.toString() ?: "", checkedAtMs.toString()
        ).joinToString(",")
    }

    fun toJsonLine(): String =
        org.json.JSONObject()
            .put("username", username)
            .put("category", category.name)
            .put("detail", detail)
            .put("videoCount", videoCount ?: org.json.JSONObject.NULL)
            .put("lastVideoMs", lastVideoMs ?: org.json.JSONObject.NULL)
            .put("checkedAtMs", checkedAtMs)
            .toString()

    companion object {
        const val CSV_HEADER = "username,category,detail,videoCount,lastVideoMs,checkedAtMs"
    }
}
