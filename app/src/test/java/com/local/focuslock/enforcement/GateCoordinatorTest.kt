package com.local.focuslock.enforcement

import com.local.focuslock.domain.*
import org.junit.Assert.*
import org.junit.Test

class GateCoordinatorTest {
    private var now = ClockSample(1000, 1000, 1)
    private val rule = Rule("game", Mode.COOLDOWN)
    private class Host : GateHost {
        var gate: String? = null
        var phase: Phase? = null
        var layouts = 0
        var removals = 0
        var homeCalls = 0
        var actualForeground = "game"
        var forcedHomeResult: HomeResult? = null
        override fun show(pkg: String, rule: Rule, status: Status) { if (gate != pkg || phase != status.phase) layouts++; gate = pkg; phase = status.phase }
        override fun dismiss() { if (gate != null) removals++; gate = null; phase = null }
        override fun returnHomeIfForeground(pkg: String): HomeResult { forcedHomeResult?.let { return it }; if (actualForeground != pkg) return HomeResult.OTHER_APP; homeCalls++; return HomeResult.EXITED }
        override fun warn(session: Session) { }
    }
    private val host = Host()
    private val guard = GateCoordinator("ours", ClockSource { now }, host)
    private fun ready() = guard.updateSnapshot(EntrySnapshot(mapOf("game" to rule), emptyMap()))
    @Test fun openingWithoutWindowIdShowsRequestImmediatelyWithoutProbe() {
        ready(); guard.applicationOpened("game")
        assertEquals("game", host.gate); assertEquals(Phase.READY, host.phase)
    }
    @Test fun unknownAndOwnOverlayFocusNeverRemoveRequestOrResetInput() {
        ready(); guard.applicationOpened("game")
        repeat(200) { guard.observed(null); guard.observed("ours"); guard.tick() }
        assertEquals("game", host.gate); assertEquals(1, host.layouts); assertEquals(0, host.removals)
    }
    @Test fun coolingAppIsBlockedOnOpeningAndStaysBlockedAcrossFocusNoise() {
        val s = SessionPolicy.create(rule, 1, now, "s")
        now = now.copy(wallMs = 61_000, elapsedMs = 61_000)
        guard.updateSnapshot(EntrySnapshot(mapOf("game" to rule), mapOf("game" to s)))
        guard.applicationOpened("game")
        repeat(200) { guard.observed(null); guard.observed("ours") }
        assertEquals(Phase.COOLING, host.phase); assertEquals(0, host.removals); assertEquals(1, host.layouts)
    }
    @Test fun confirmedOtherApplicationDismissesGate() {
        ready(); guard.applicationOpened("game"); guard.applicationOpened("home")
        assertNull(host.gate); assertEquals(1, host.removals)
    }
    @Test fun realMainActivityCanBeOpenedButOwnWindowObservationCannotUnlock() {
        ready(); guard.applicationOpened("game"); guard.observed("ours"); assertEquals("game", host.gate)
        guard.applicationOpened("ours"); assertNull(host.gate)
    }
    @Test fun sessionRestorationReusesPermissionAndExpiresWithoutNewWindowEvent() {
        val s = SessionPolicy.create(rule, 1, now, "s")
        guard.updateSnapshot(EntrySnapshot(mapOf("game" to rule), mapOf("game" to s)))
        guard.applicationOpened("game"); assertNull(host.gate)
        now = now.copy(wallMs = 61_000, elapsedMs = 61_000)
        guard.tick(); assertEquals(1, host.homeCalls)
    }
    @Test fun expiredSessionCannotKickAnotherApplication() {
        val s = SessionPolicy.create(rule, 1, now, "s")
        guard.updateSnapshot(EntrySnapshot(mapOf("game" to rule), mapOf("game" to s)))
        guard.applicationOpened("game"); host.actualForeground = "other"
        now = now.copy(wallMs = 61_000, elapsedMs = 61_000)
        guard.tick(); assertEquals(0, host.homeCalls); assertNull(host.gate)
    }
    @Test fun pendingLaunchIsHandledAsSoonAsInitialSnapshotLoads() {
        guard.applicationOpened("game"); guard.observed("home"); guard.observed(null); ready()
        assertEquals("game", host.gate)
    }
    @Test fun staleLauncherProbeCannotUndoNewlyOwnedGate() {
        ready(); guard.applicationOpened("game")
        repeat(100) { guard.observed("home"); guard.observed(null); guard.tick() }
        assertEquals("game", host.gate); assertEquals(0, host.removals)
        guard.applicationOpened("home"); assertNull(host.gate)
    }
    @Test fun notificationShadeAtDeadlineDoesNotCreateANewGateOverSystemUi() {
        val s = SessionPolicy.create(rule, 1, now, "s")
        guard.updateSnapshot(EntrySnapshot(mapOf("game" to rule), mapOf("game" to s)))
        guard.applicationOpened("game")
        host.forcedHomeResult = HomeResult.UNCONFIRMED
        now = now.copy(wallMs = 61_000, elapsedMs = 61_000)
        guard.tick(); assertNull(host.gate); assertEquals(0, host.layouts)
        host.forcedHomeResult = null
        guard.tick(); assertEquals(1, host.homeCalls)
    }
    @Test fun explicitReentryAfterUnconfirmedExpiryImmediatelyShowsCooldown() {
        val s = SessionPolicy.create(rule, 1, now, "s")
        guard.updateSnapshot(EntrySnapshot(mapOf("game" to rule), mapOf("game" to s)))
        guard.applicationOpened("game")
        host.forcedHomeResult = HomeResult.UNCONFIRMED
        now = now.copy(wallMs = 61_000, elapsedMs = 61_000)
        guard.tick(); assertNull(host.gate)
        guard.applicationOpened("game"); assertEquals(Phase.COOLING, host.phase)
    }
    @Test fun sameAppPollingStillReturnsHomeAtDeadline() {
        val s = SessionPolicy.create(rule, 1, now, "s")
        guard.updateSnapshot(EntrySnapshot(mapOf("game" to rule), mapOf("game" to s)))
        guard.applicationOpened("game")
        now = now.copy(wallMs = 61_000, elapsedMs = 61_000)
        guard.observed("game")
        assertEquals(1, host.homeCalls); assertNull(host.gate)
    }
}
