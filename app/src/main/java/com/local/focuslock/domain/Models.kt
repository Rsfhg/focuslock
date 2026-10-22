package com.local.focuslock.domain

enum class Mode { FREE, COOLDOWN }
enum class Phase { READY, USING, COOLING }
data class Rule(val packageName: String, val mode: Mode, val maxMinutes: Int = 15, val cooldownMinutes: Int = 15)
data class ClockSample(val wallMs: Long, val elapsedMs: Long, val bootId: Long)
data class Session(
    val packageName: String, val id: String, val rule: Rule,
    val startedWallMs: Long, val expiresWallMs: Long, val cooldownWallMs: Long,
    val expiresElapsedMs: Long, val cooldownElapsedMs: Long, val bootId: Long,
    val warned: Boolean = false
)
data class Status(val phase: Phase, val remainingMs: Long)
enum class MutationResult { APPLIED, ACTIVE_SESSION, INVALID_INPUT, STORAGE_ERROR, NOT_FOUND }
fun interface ClockSource { fun sample(): ClockSample }
fun interface IdSource { fun next(): String }
