package com.local.focuslock.enforcement
import org.junit.Assert.*
import org.junit.Test
class ApplicationSignalRouterTest {
    @Test fun backgroundPaneDisappearingAfterSettingsDoesNotReopenRestrictedApp() {
        val received = mutableListOf<String>()
        val router = ApplicationSignalRouter("ours", { 1000L }, { emptySet() }) { pkg, _, _ -> received.add(pkg) }
        router.windowState("game", "GameActivity", 10, 100)
        router.windowState("settings", "SettingsActivity", 11, 200)
        assertFalse(router.windowState("game", "CustomRelativeLayout", 10, 300, 32))
        assertEquals(listOf("game", "settings"), received)
        assertTrue(router.windowState("game", "GameActivity", 10, 400))
        assertEquals(listOf("game", "settings", "game"), received)
    }
    @Test fun paneTitleAndAppearanceAreNotProofOfApplicationEntry() {
        val received = mutableListOf<String>()
        val router = ApplicationSignalRouter("ours", { 1000L }, { emptySet() }) { pkg, _, _ -> received.add(pkg) }
        router.windowState("settings", "SettingsActivity", 11, 100)
        for (changes in listOf(8, 16, 32, 33)) {
            assertFalse(router.windowState("game", "CustomRelativeLayout", 10, 200, changes))
        }
        assertEquals(listOf("settings"), received)
    }
    @Test fun missingWindowIdDoesNotDelayOrDiscardLaunch() {
        val received = mutableListOf<String>()
        val router = ApplicationSignalRouter("ours", { 1000L }, { emptySet() }) { pkg, _, _ -> received.add(pkg) }
        assertTrue(router.windowState("game", "GameActivity", -1, 0))
        assertEquals(listOf("game"), received)
    }
    @Test fun overlayAndKeyboardEventsCannotPretendUserLeftTheTarget() {
        val received = mutableListOf<String>()
        val router = ApplicationSignalRouter("ours", { 1000L }, { setOf("keyboard") }) { pkg, _, _ -> received.add(pkg) }
        router.windowState("game", "GameActivity", -1, 100)
        assertFalse(router.windowState("ours", "android.widget.FrameLayout", 5, 101))
        assertFalse(router.windowState("keyboard", "KeyboardWindow", 6, 102))
        assertFalse(router.windowState("unknown-keyboard", "android.inputmethodservice.SoftInputWindow", 7, 103))
        assertEquals(listOf("game"), received)
    }
    @Test fun realMainActivityIsAnExplicitExitAndLateOldEventCannotReopenGate() {
        val received = mutableListOf<String>()
        val router = ApplicationSignalRouter("ours", { 1000L }, { emptySet() }) { pkg, _, _ -> received.add(pkg) }
        router.windowState("game", "GameActivity", -1, 100)
        router.windowState("ours", "com.local.focuslock.ui.MainActivity", 8, 102)
        assertFalse(router.windowState("game", "GameActivity", -1, 101))
        assertEquals(listOf("game", "ours"), received)
    }
    @Test fun explicitMainResumeRejectsOlderQueuedGameEventWithoutCanonicalClassName() {
        var time = 100L
        val received = mutableListOf<String>()
        val router = ApplicationSignalRouter("ours", { time }, { emptySet() }) { pkg, _, _ -> received.add(pkg) }
        router.windowState("game", "GameActivity", -1, 100)
        time = 105; router.ownUiOpened()
        assertFalse(router.windowState("game", "GameActivity", -1, 102))
        assertEquals(listOf("game", "ours"), received)
    }
}
