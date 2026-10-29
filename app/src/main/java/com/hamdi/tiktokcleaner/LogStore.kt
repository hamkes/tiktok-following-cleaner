package com.hamdi.tiktokcleaner

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-safe diagnostic ring buffer (up to 600 log entries) with real-time UI listeners.
 * Fixed ConcurrentModificationException from v1.9 by synchronizing all deque mutations and snapshots.
 */
object LogStore {
    private const val MAX_LINES = 600
    private val lines = ArrayDeque<String>(MAX_LINES + 16)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun log(message: String) {
        val ts = synchronized(timeFormat) { timeFormat.format(Date()) }
        val entry = "[$ts] $message"
        synchronized(lines) {
            if (lines.size >= MAX_LINES) {
                lines.removeFirst()
            }
            lines.addLast(entry)
        }
        for (l in listeners) {
            try {
                l.invoke()
            } catch (_: Exception) {
            }
        }
    }

    fun snapshot(): List<String> = synchronized(lines) {
        ArrayList(lines)
    }

    fun joinText(maxLines: Int = 250): String {
        val snap = snapshot()
        val tail = if (snap.size > maxLines) snap.subList(snap.size - maxLines, snap.size) else snap
        return tail.joinToString("\n")
    }

    fun clear() {
        synchronized(lines) {
            lines.clear()
        }
        for (l in listeners) {
            try {
                l.invoke()
            } catch (_: Exception) {
            }
        }
    }
}
