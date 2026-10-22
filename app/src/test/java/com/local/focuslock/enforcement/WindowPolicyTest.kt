package com.local.focuslock.enforcement
import org.junit.Assert.*
import org.junit.Test
class WindowPolicyTest {
    @Test fun ownFocusedOverlayKeepsUnderlyingApp() {
        assertEquals("game", WindowPolicy.choose(listOf(WindowFact(WindowKind.OWN_OVERLAY,"ours",true,4),WindowFact(WindowKind.APP,"game",false,1)), true))
    }
    @Test fun keyboardDoesNotDismissInputGate() {
        assertEquals("game", WindowPolicy.choose(listOf(WindowFact(WindowKind.KEYBOARD,"ime",true,5),WindowFact(WindowKind.OWN_OVERLAY,"ours",false,4),WindowFact(WindowKind.APP,"game",false,1)), true))
    }
    @Test fun activeSystemUiNeverSelectsAnAppBehindIt() {
        assertNull(WindowPolicy.choose(listOf(WindowFact(WindowKind.SYSTEM,"system",true,5),WindowFact(WindowKind.APP,"game",false,1)), true))
    }
    @Test fun switchedUnderlyingAppIsNotMistakenForPreviousTarget() {
        assertEquals("other", WindowPolicy.choose(listOf(WindowFact(WindowKind.OWN_OVERLAY,"ours",true,9),WindowFact(WindowKind.APP,"game",false,1),WindowFact(WindowKind.APP,"other",false,3)), true))
    }
    @Test fun launchUsesEventIdentityBeforeWindowRootIsAvailable() {
        val rootNotReady = listOf(WindowFact(WindowKind.APP, null, true, 1, id = 42))
        assertEquals("game", WindowPolicy.choose(rootNotReady, false, LaunchSignal("game", 42, 1000), 1001))
    }
    @Test fun focusedLaunchDoesNotWaitForOldLauncherActiveFlagToClear() {
        val transitioning = listOf(WindowFact(WindowKind.APP, "home", true, 0, id = 1), WindowFact(WindowKind.APP, null, false, 1, id = 42, focused = true))
        assertEquals("game", WindowPolicy.choose(transitioning, false, LaunchSignal("game", 42, 1000), 1001))
    }
    @Test fun staleOrDifferentWindowEventCannotCoverCurrentApp() {
        val current = listOf(WindowFact(WindowKind.APP, "other", true, 3, id = 43))
        assertEquals("other", WindowPolicy.choose(current, false, LaunchSignal("game", 42, 1000), 1001))
        assertNull(WindowPolicy.choose(listOf(WindowFact(WindowKind.APP, null, true, 1, id = 42)), false, LaunchSignal("game", 42, 1000), 2000))
    }
    @Test fun newLaunchCannotCoverActiveNotificationShade() {
        val shade = listOf(WindowFact(WindowKind.SYSTEM, "system", true, 4, id = 9), WindowFact(WindowKind.APP, null, false, 1, id = 42, focused = true))
        assertNull(WindowPolicy.choose(shade, false, LaunchSignal("game", 42, 1000), 1001))
    }
}
