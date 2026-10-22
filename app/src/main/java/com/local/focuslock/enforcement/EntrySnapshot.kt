package com.local.focuslock.enforcement

import com.local.focuslock.domain.*

/** Immutable hot-path state. Room remains the authority for mutations. */
data class EntrySnapshot(val rules: Map<String, Rule>, val sessions: Map<String, Session>) {
    fun status(pkg: String, now: ClockSample): Status = SessionPolicy.status(sessions[pkg], now)
}
