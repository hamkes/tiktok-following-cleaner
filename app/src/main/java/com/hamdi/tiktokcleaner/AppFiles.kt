package com.hamdi.tiktokcleaner

import android.content.Context
import java.io.File

/** All tool data lives in the app-private files dir. Nothing leaves the device. */
class AppFiles(context: Context) {
    private val dir: File = File(context.filesDir, "cleaner").apply { mkdirs() }

    val followingList: File = File(dir, "following_list.txt")     // collected usernames, one per line
    val auditCsv: File = File(dir, "audit_results.csv")
    val auditJsonl: File = File(dir, "audit_results.jsonl")
    val candidatesCsv: File = File(dir, "candidates.csv")          // GONE/FAKE/DEAD from last audit
    val unfollowDone: File = File(dir, "unfollow_done.txt")        // processed usernames (resume)
    val logFile: File = File(dir, "cleaner.log")

    fun resetAuditFiles() {
        followingList.delete(); auditCsv.delete(); auditJsonl.delete(); candidatesCsv.delete()
    }

    fun readFollowing(): List<String> =
        if (followingList.exists()) followingList.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        else emptyList()

    fun writeFollowing(list: List<String>) {
        followingList.writeText(list.joinToString("\n"))
    }

    fun appendResult(r: AuditResult) {
        if (!auditCsv.exists()) auditCsv.writeText(AuditResult.CSV_HEADER + "\n")
        auditCsv.appendText(r.toCsvRow() + "\n")
        auditJsonl.appendText(r.toJsonLine() + "\n")
    }

    fun writeCandidates(results: List<AuditResult>) {
        val cand = results.filter {
            it.category == Category.GONE || it.category == Category.FAKE || it.category == Category.DEAD
        }
        val sb = StringBuilder("username,category,detail\n")
        for (r in cand) {
            sb.append(r.username).append(",").append(r.category.name).append(",")
                .append(r.detail.replace(",", ";")).append("\n")
        }
        candidatesCsv.writeText(sb.toString())
    }

    fun readCandidatesFrom(file: File): List<String> {
        if (!file.exists()) return emptyList()
        return parseCandidateUsernames(file.readText())
    }

    fun unfollowDoneSet(): MutableSet<String> =
        if (unfollowDone.exists())
            unfollowDone.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
        else mutableSetOf()

    fun markUnfollowDone(username: String) {
        unfollowDone.appendText(username + "\n")
    }

    companion object {
        /**
         * Parse a candidates file: accepts plain username-per-line files and
         * CSV files whose first column is the username (with or without header).
         */
        fun parseCandidateUsernames(content: String): List<String> {
            val out = LinkedHashSet<String>()
            for (raw in content.lineSequence()) {
                var line = raw.trim().trim('"')
                if (line.isEmpty()) continue
                if (line.startsWith("username", ignoreCase = true)) continue // header
                if (line.contains(",")) line = line.substringBefore(",").trim().trim('"')
                line = line.removePrefix("@").trim()
                if (line.isNotEmpty()) out.add(line)
            }
            return out.toList()
        }
    }
}
