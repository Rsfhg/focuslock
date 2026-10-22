package com.local.focuslock.enforcement

import com.local.focuslock.domain.*

enum class HomeResult { EXITED, OTHER_APP, UNCONFIRMED }
interface GateHost {
    fun show(pkg: String, rule: Rule, status: Status)
    fun dismiss()
    fun returnHomeIfForeground(pkg: String): HomeResult
    fun warn(session: Session)
}

/** Application signals drive entry. Probes may reconcile, but unknown focus is not an exit. */
class GateCoordinator(private val ownPackage: String, private val clock: ClockSource, private val host: GateHost) {
    var currentPackage: String? = null; private set
    private var snapshot: EntrySnapshot? = null
    private var usingId: String? = null
    private var departingPackage: String? = null
    private var departureUntil = 0L
    private var ownsGate = false
    private fun dismissGate() { ownsGate = false; host.dismiss() }
    private fun showGate(pkg: String, rule: Rule, status: Status) { ownsGate = true; host.show(pkg, rule, status) }

    fun updateSnapshot(state: EntrySnapshot) { snapshot = state; tick() }
    fun applicationOpened(pkg: String) {
        if (pkg == departingPackage && clock.sample().elapsedMs < departureUntil) return
        if (pkg != currentPackage) usingId = null
        if (snapshot?.status(pkg, clock.sample())?.phase != Phase.USING) usingId = null
        if (pkg != departingPackage) departingPackage = null
        currentPackage = pkg; tick()
    }
    fun observed(pkg: String?) {
        // Our focusable gate, IME and transient missing window roots are not a leave.
        if (pkg == null || pkg == ownPackage || ownsGate || (snapshot == null && currentPackage != null)) return
        if (pkg == currentPackage) { tick(); return }
        applicationOpened(pkg)
    }
    fun deviceLocked() { currentPackage = null; usingId = null; dismissGate() }
    fun exited() {
        departingPackage = currentPackage; departureUntil = clock.sample().elapsedMs + 500
        currentPackage = null; usingId = null; dismissGate()
    }
    fun tick() {
        val pkg = currentPackage ?: run { dismissGate(); return }
        val state = snapshot ?: return
        val rule = state.rules[pkg] ?: run { usingId = null; dismissGate(); return }
        val s = state.sessions[pkg]
        val status = state.status(pkg, clock.sample())
        when {
            status.phase == Phase.USING -> {
                usingId = s?.id; dismissGate()
                if (s != null && !s.warned && status.remainingMs <= 60_000) host.warn(s)
            }
            usingId != null && usingId == s?.id -> {
                when (host.returnHomeIfForeground(pkg)) {
                    HomeResult.EXITED, HomeResult.OTHER_APP -> exited()
                    HomeResult.UNCONFIRMED -> Unit // Retry on app proof; don't cover an open system shade.
                }
            }
            else -> showGate(pkg, rule, status)
        }
    }
}
