package com.local.focuslock.data
import org.junit.Assert.*
import org.junit.Test
class CriticalAppsTest {
    @Test fun essentialAppsCannotBeRestricted() {
        for (p in listOf("mine", "home", "dialer", "com.android.settings", "com.android.systemui", "com.miui.securitycenter", "com.google.android.permissioncontroller"))
            assertFalse(p, CriticalApps.allowed(p, "mine", setOf("home"), "dialer"))
        assertTrue(CriticalApps.allowed("com.example.game", "mine", setOf("home"), "dialer"))
    }
}
