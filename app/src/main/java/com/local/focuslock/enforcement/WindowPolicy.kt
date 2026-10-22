package com.local.focuslock.enforcement
enum class WindowKind { APP, OWN_OVERLAY, KEYBOARD, SYSTEM, OTHER }
data class WindowFact(val kind: WindowKind, val pkg: String?, val active: Boolean, val layer: Int, val id: Int = -1, val focused: Boolean = false)
data class LaunchSignal(val pkg: String, val windowId: Int, val eventUptimeMs: Long)
object WindowPolicy {
    fun choose(windows: List<WindowFact>, overlayShowing: Boolean, signal: LaunchSignal? = null, nowUptimeMs: Long = 0): String? {
        val active = windows.filter { it.active }.maxByOrNull { it.layer } ?: return null
        // A launch event can precede both the node tree and the old active-window flag.
        // Require the same visible window and input focus; never cover active SystemUI.
        val fresh = signal?.takeIf { nowUptimeMs - it.eventUptimeMs in 0..750 }
        if (active.kind == WindowKind.APP && fresh != null && windows.any {
            it.kind == WindowKind.APP && it.id == fresh.windowId && (it.active || it.focused)
        }) return fresh.pkg
        if (active.kind == WindowKind.APP) return active.pkg
        val ownOverlayFocused = active.kind == WindowKind.OWN_OVERLAY
        val keyboardOverOwnOverlay = active.kind == WindowKind.KEYBOARD && windows.any { it.kind == WindowKind.OWN_OVERLAY }
        if (overlayShowing && (ownOverlayFocused || keyboardOverOwnOverlay)) return windows.filter { it.kind == WindowKind.APP }.maxByOrNull { it.layer }?.pkg
        return null
    }
}
