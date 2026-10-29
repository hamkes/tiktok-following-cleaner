package com.hamdi.tiktokcleaner

import android.content.Context
import java.io.File

/**
 * Atomic file persistence for collected usernames, audit results (JSONL), and CSV exports.
 */
object AppFiles {
    private const val FILE_COLLECTED = "collected_usernames.txt"
    private const val FILE_AUDIT_JSONL = "audit_results.jsonl"
    private const val FILE_EXPORT_CSV = "tiktok_audit_export.csv"

    @Synchronized
    fun saveCollectedUsernames(context: Context, usernames: Collection<String>) {
        val file = File(context.filesDir, FILE_COLLECTED)
        file.writeText(usernames.joinToString("\n"))
    }

    @Synchronized
    fun loadCollectedUsernames(context: Context): LinkedHashSet<String> {
        val file = File(context.filesDir, FILE_COLLECTED)
        val set = LinkedHashSet<String>()
        if (!file.exists()) return set
        file.forEachLine { line ->
            val u = line.trim()
            if (u.isNotEmpty()) set.add(u)
        }
        return set
    }

    @Synchronized
    fun appendAuditResult(context: Context, result: AuditResult) {
        val file = File(context.filesDir, FILE_AUDIT_JSONL)
        file.appendText(result.toJsonLine() + "\n")
    }

    @Synchronized
    fun saveAllAuditResults(context: Context, results: List<AuditResult>) {
        val file = File(context.filesDir, FILE_AUDIT_JSONL)
        val sb = StringBuilder()
        for (r in results) {
            sb.append(r.toJsonLine()).append('\n')
        }
        file.writeText(sb.toString())
    }

    @Synchronized
    fun loadAuditResults(context: Context): List<AuditResult> {
        val file = File(context.filesDir, FILE_AUDIT_JSONL)
        if (!file.exists()) return emptyList()
        val map = LinkedHashMap<String, AuditResult>()
        file.forEachLine { line ->
            val parsed = AuditResult.fromJsonLine(line)
            if (parsed != null) {
                map[parsed.username.lowercase()] = parsed
            }
        }
        return map.values.toList()
    }

    @Synchronized
    fun exportCsvFile(context: Context, results: List<AuditResult>): File {
        val file = File(context.getExternalFilesDir(null) ?: context.filesDir, FILE_EXPORT_CSV)
        val sb = StringBuilder()
        sb.append(AuditResult.CSV_HEADER).append('\n')
        for (r in results) {
            sb.append(r.toCsvRow()).append('\n')
        }
        file.writeText(sb.toString())
        return file
    }

    @Synchronized
    fun clearAll(context: Context) {
        File(context.filesDir, FILE_COLLECTED).delete()
        File(context.filesDir, FILE_AUDIT_JSONL).delete()
    }
}
