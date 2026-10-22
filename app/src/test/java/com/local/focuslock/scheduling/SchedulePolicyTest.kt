package com.local.focuslock.scheduling
import com.local.focuslock.domain.*
import org.junit.Assert.*
import org.junit.Test
class SchedulePolicyTest {
    private val now = ClockSample(1_000_000, 1000, 1)
    @Test fun warningOnceAtOneMinuteRemaining() {
        val s = SessionPolicy.create(Rule("a", Mode.FREE), 10, now, "new")
        assertFalse(SchedulePolicy.shouldWarn(s, now))
        val at9 = now.copy(wallMs = now.wallMs + 540_000, elapsedMs = now.elapsedMs + 540_000)
        assertTrue(SchedulePolicy.shouldWarn(s, at9))
        assertFalse(SchedulePolicy.shouldWarn(s.copy(warned = true), at9))
        assertTrue(SchedulePolicy.shouldWarn(SessionPolicy.create(Rule("a", Mode.FREE), 1, now, "1"), now))
    }
    @Test fun oldAlarmCannotMatchNewSession() {
        val s = SessionPolicy.create(Rule("a", Mode.FREE), 1, now, "new")
        assertFalse(SchedulePolicy.matches(s, "old")); assertTrue(SchedulePolicy.matches(s, "new"))
    }
}
