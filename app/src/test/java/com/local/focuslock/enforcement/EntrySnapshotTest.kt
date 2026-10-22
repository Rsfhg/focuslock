package com.local.focuslock.enforcement
import com.local.focuslock.domain.*
import org.junit.Assert.*
import org.junit.Test
class EntrySnapshotTest {
    @Test fun memorySnapshotStillExpiresWithoutAnotherDatabaseEmission() {
        val clock = ClockSample(1000, 1000, 1)
        val rule = Rule("game", Mode.COOLDOWN)
        val session = SessionPolicy.create(rule, 1, clock, "session")
        val cache = EntrySnapshot(mapOf("game" to rule), mapOf("game" to session))
        assertEquals(Status(Phase.USING, 1), cache.status("game", ClockSample(60_999, 60_999, 1)))
        assertEquals(Status(Phase.COOLING, 900_000), cache.status("game", ClockSample(61_000, 61_000, 1)))
        assertEquals(Status(Phase.READY, 0), cache.status("other", clock))
    }
}
