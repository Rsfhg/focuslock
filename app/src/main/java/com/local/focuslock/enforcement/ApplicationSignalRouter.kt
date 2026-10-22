package com.local.focuslock.enforcement

/** Window IDs may be unavailable on OEM launch events. They must not gate delivery. */
class ApplicationSignalRouter(
    private val ownPackage: String,
    private val nowUptime: () -> Long,
    private val inputMethods: () -> Set<String>,
    private val windowChanged: () -> Unit = {},
    private val opened: (String, Int, Long) -> Unit
) {
    private var lastApplicationEvent = -1L
    fun ownUiOpened() { windowChanged(); val time = nowUptime(); lastApplicationEvent = maxOf(lastApplicationEvent, time); opened(ownPackage, -1, time) }
    fun windowState(pkg: String?, className: String, windowId: Int, eventTime: Long, contentChangeTypes: Int = 0): Boolean {
        // A system/IME focus change revokes old foreground proof even when it isn't an app launch.
        windowChanged()
        // Pane lifecycle uses WINDOW_STATE_CHANGED too, including when a background pane disappears.
        // These events cannot prove entry. Active-window sampling still reconciles real foreground apps.
        if (contentChangeTypes and (8 or 16 or 32) != 0) return false
        if (pkg.isNullOrBlank()) return false
        val ownActivity = pkg == ownPackage && className == "com.local.focuslock.ui.MainActivity"
        if ((pkg == ownPackage && !ownActivity) || pkg in inputMethods() || className.startsWith("android.inputmethodservice.") ||
            className == "android.widget.Toast" || pkg in setOf("android", "com.android.systemui")) return false
        val time = eventTime.takeIf { it > 0 } ?: nowUptime()
        if (time < lastApplicationEvent) return false
        lastApplicationEvent = time
        opened(pkg, windowId, time)
        return true
    }
}
