package com.hamdi.tiktokcleaner

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-memory ring buffer + append-only log file. The UI polls [tail]. */
object LogStore {
    private const val MAX_LINES = 500
    private val lines = ArrayDeque<String>()
    private val levels = ArrayDeque<Level>()
    private var file: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    /**
     * v1.4: display level per line, used ONLY to colour the on-screen log
     * (info = light, warning = amber, error = red). Inferred from the
     * message text by [inferLevel]; the stored line and the log file are
     * byte-identical to before — this is presentation metadata only.
     */
    enum class Level { INFO, WARN, ERROR }

    private val ERROR_HINTS = listOf("خطأ", "توقف صارم", "إبطال", "تعذّر", "فشل", "⚠")
    private val WARN_HINTS =
        listOf("تحذير", "CAPTCHA", "كابتشا", "إيقاف مؤقت", "لم يتم", "لم تعد مؤكدة")

    private fun inferLevel(msg: String): Level = when {
        ERROR_HINTS.any { msg.contains(it) } -> Level.ERROR
        WARN_HINTS.any { msg.contains(it) } -> Level.WARN
        else -> Level.INFO
    }

    @Synchronized
    fun init(f: File) {
        file = f
    }

    @Synchronized
    fun log(msg: String) {
        val line = "[${fmt.format(Date())}] $msg"
        lines.addLast(line)
        levels.addLast(inferLevel(msg))
        while (lines.size > MAX_LINES) {
            lines.removeFirst()
            levels.removeFirst()
        }
        try {
            file?.appendText(line + "\n")
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun tail(): String = lines.joinToString("\n")

    /** v1.4: (line, level) pairs in order, for the colourised on-screen log. */
    @Synchronized
    fun entries(): List<Pair<String, Level>> = lines.zip(levels)

    @Synchronized
    fun clearScreen() {
        lines.clear()
        levels.clear()
    }
}
