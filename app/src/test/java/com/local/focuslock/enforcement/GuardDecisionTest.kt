package com.local.focuslock.enforcement
import com.local.focuslock.domain.*
import org.junit.Assert.*
import org.junit.Test
class GuardDecisionTest {
    @Test fun stationaryForegroundExpiresWithoutWindowEvent() {
        assertEquals(GuardAction.EXIT, GuardDecision.decide("a", "a", Status(Phase.COOLING, 100), true))
        assertEquals(GuardAction.EXIT, GuardDecision.decide("a", "a", Status(Phase.READY, 0), true))
    }
    @Test fun staleDeadlineDoesNotExitAnotherApp() {
        assertEquals(GuardAction.ALLOW, GuardDecision.decide("a", "b", Status(Phase.READY, 0), true))
    }
    @Test fun entryRequestsOrResumesOrCools() {
        assertEquals(GuardAction.REQUEST, GuardDecision.decide("a", "a", Status(Phase.READY, 0), false))
        assertEquals(GuardAction.ALLOW, GuardDecision.decide("a", "a", Status(Phase.USING, 2), false))
        assertEquals(GuardAction.COOLING, GuardDecision.decide("a", "a", Status(Phase.COOLING, 2), false))
    }
}
