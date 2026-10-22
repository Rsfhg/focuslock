package com.local.focuslock.domain

import org.junit.Assert.*
import org.junit.Test

class SessionPolicyTest {
    private val start = ClockSample(1_000_000, 50_000, 1)
    private fun sample(ms: Long) = start.copy(wallMs = start.wallMs + ms, elapsedMs = start.elapsedMs + ms)
    @Test fun freeSessionEndsAtExactBoundary() {
        val s = SessionPolicy.create(Rule("app", Mode.FREE), 10, start, "1")
        assertEquals(Status(Phase.USING, 1), SessionPolicy.status(s, sample(599_999)))
        assertEquals(Status(Phase.READY, 0), SessionPolicy.status(s, sample(600_000)))
    }
    @Test fun cooldownStartsAtOriginalDeadline() {
        val s = SessionPolicy.create(Rule("app", Mode.COOLDOWN), 10, start, "1")
        assertEquals(Status(Phase.COOLING, 900_000), SessionPolicy.status(s, sample(600_000)))
        assertEquals(Phase.READY, SessionPolicy.status(s, sample(1_500_000)).phase)
    }
    @Test fun rebootAt20MinutesLeaves5MinuteCooldown() {
        val s = SessionPolicy.create(Rule("app", Mode.COOLDOWN), 10, start, "1")
        val reboot = ClockSample(start.wallMs + 1_200_000, 1000, 2)
        val r = SessionPolicy.rebase(s, reboot)
        assertEquals(Status(Phase.COOLING, 300_000), SessionPolicy.status(r, reboot))
        assertEquals(Phase.READY, SessionPolicy.status(s, reboot.copy(wallMs = start.wallMs + 1_800_000)).phase)
    }
    @Test fun clockChangeKeepsRemainingTime() {
        val s = SessionPolicy.create(Rule("app", Mode.FREE), 10, start, "1")
        val changed = sample(300_000).copy(wallMs = 50_000_000)
        val r = SessionPolicy.rebase(s, changed)
        assertEquals(300_000, SessionPolicy.status(r, changed).remainingMs)
        assertEquals(changed.wallMs + 300_000, r.expiresWallMs)
    }
    @Test fun rejectsInvalidDurationAndHandlesMaximum() {
        for (n in listOf(0, -1, 16, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { SessionPolicy.create(Rule("app", Mode.FREE), n, start, "1") }
        }
        val s = SessionPolicy.create(Rule("app", Mode.FREE, 1440), 1440, start, "1")
        assertEquals(86_400_000L, SessionPolicy.status(s, start).remainingMs)
    }
}
