package com.local.focuslock.domain

object SessionPolicy {
    fun status(session: Session?, now: ClockSample): Status {
        if (session == null) return Status(Phase.READY, 0)
        val sameBoot = now.bootId >= 0 && session.bootId == now.bootId
        val useLeft = if (sameBoot) session.expiresElapsedMs - now.elapsedMs else session.expiresWallMs - now.wallMs
        val coolLeft = if (sameBoot) session.cooldownElapsedMs - now.elapsedMs else session.cooldownWallMs - now.wallMs
        return when { useLeft > 0 -> Status(Phase.USING, useLeft); coolLeft > 0 -> Status(Phase.COOLING, coolLeft); else -> Status(Phase.READY, 0) }
    }
    fun create(rule: Rule, minutes: Int, now: ClockSample, id: String): Session {
        require(rule.maxMinutes in 1..1440 && rule.cooldownMinutes in 1..1440 && minutes in 1..rule.maxMinutes)
        val use = minutes * 60_000L
        val cool = if (rule.mode == Mode.COOLDOWN) rule.cooldownMinutes * 60_000L else 0L
        return Session(rule.packageName, id, rule, now.wallMs, now.wallMs + use, now.wallMs + use + cool, now.elapsedMs + use, now.elapsedMs + use + cool, now.bootId)
    }
    fun rebase(session: Session, now: ClockSample): Session {
        val same = now.bootId >= 0 && session.bootId == now.bootId
        val u = if (same) session.expiresElapsedMs - now.elapsedMs else session.expiresWallMs - now.wallMs
        val c = if (same) session.cooldownElapsedMs - now.elapsedMs else session.cooldownWallMs - now.wallMs
        return session.copy(expiresWallMs = now.wallMs + u, cooldownWallMs = now.wallMs + c,
            expiresElapsedMs = now.elapsedMs + u, cooldownElapsedMs = now.elapsedMs + c, bootId = now.bootId)
    }
}
