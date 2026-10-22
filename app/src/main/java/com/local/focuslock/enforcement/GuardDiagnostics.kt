package com.local.focuslock.enforcement

import android.os.Build
import android.os.SystemClock

/** Local process-only trace: package/window identifiers and guard state, never page text. */
object GuardDiagnostics {
    private val lines = ArrayDeque<String>()
    private var last = ""
    private val operations = mutableMapOf<String, Long>()
    @Volatile private var lastMainPulse = SystemClock.uptimeMillis()
    fun mainPulse() { lastMainPulse = SystemClock.uptimeMillis() }
    fun checkMainPulse() {
        val gap = SystemClock.uptimeMillis() - lastMainPulse
        if (gap >= 1000) record("main queue gap=${gap}ms active=${pending()}")
    }
    fun <T> measure(label: String, block: () -> T): T {
        val key = "${Thread.currentThread().name}:$label"
        val start = SystemClock.uptimeMillis()
        synchronized(this) { operations[key] = start }
        try { return block() }
        finally {
            val duration = SystemClock.uptimeMillis() - start
            synchronized(this) { operations.remove(key) }
            if (duration >= 200) record("slow $key duration=${duration}ms")
        }
    }
    @Synchronized private fun pending(): String = operations.entries.joinToString { "${it.key} ${SystemClock.uptimeMillis() - it.value}ms" }
    @Synchronized fun record(message: String) {
        if (message == last) return
        last = message
        if (lines.size >= 120) lines.removeFirst()
        lines.addLast("${SystemClock.elapsedRealtime()}ms $message")
    }
    @Synchronized fun export(): String = "FocusLock 0.1.4\n${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.SDK_INT}\n" + lines.joinToString("\n") + "\npending=${pending()}"
}
