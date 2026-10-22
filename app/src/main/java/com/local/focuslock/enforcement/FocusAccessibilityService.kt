package com.local.focuslock.enforcement

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.*
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.local.focuslock.appContainer
import com.local.focuslock.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import java.util.concurrent.Executors

class FocusAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val checks = Channel<Unit>(Channel.CONFLATED)
    private lateinit var overlay: AccessOverlay
    private lateinit var coordinator: GateCoordinator
    private lateinit var launchRouter: ApplicationSignalRouter
    private lateinit var sampler: ForegroundSampler
    private val windowWorker = Executors.newSingleThreadExecutor { work -> Thread(work, "focuslock-window-probe").apply { isDaemon = true } }.asCoroutineDispatcher()
    private var nodeLookupsSafe = false
    private var snapshot: EntrySnapshot? = null
    private var labels: Map<String, String> = emptyMap()
    private var imePackages: Set<String> = emptySet()
    private var launchSignal: LaunchSignal? = null
    private val windowPackages = object : LinkedHashMap<Int, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>?): Boolean = size > 128
    }
    private val catalogRevision = MutableStateFlow(0L)
    private var receiverRegistered = false
    private val catalogChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action in setOf(TelecomManager.ACTION_DEFAULT_DIALER_CHANGED,
                    Intent.ACTION_PACKAGE_ADDED, Intent.ACTION_PACKAGE_REMOVED, Intent.ACTION_PACKAGE_REPLACED)) catalogRevision.value++
        }
    }
    companion object {
        @Volatile var connected = false; private set
        @Volatile var ready = false; private set
        private var instance: FocusAccessibilityService? = null
        private var mainUiVisible = false
        fun mainUiOpened() {
            val service = instance ?: return
            mainUiVisible = true
            if (service::launchRouter.isInitialized) service.launchRouter.ownUiOpened()
        }
        fun mainUiClosed() { mainUiVisible = false }
        fun warnForeground(pkg: String): Boolean {
            val service = instance ?: return false
            if (service.readForeground() != pkg) return false
            Toast.makeText(service, "本次使用还剩 1 分钟，到期将返回桌面。", Toast.LENGTH_LONG).show()
            return true
        }
    }
    private fun locked() = GuardDiagnostics.measure("keyguard query") { getSystemService(KeyguardManager::class.java).isKeyguardLocked }
    override fun onServiceConnected() {
        connected = true; ready = false; instance = this
        serviceInfo = serviceInfo.apply { notificationTimeout = 0 }
        // Framework cache refresh happens BEFORE our event callback and can wait for remote nodes.
        // Without this API, don't populate the node cache at all: use event package identities.
        nodeLookupsSafe = Build.VERSION.SDK_INT >= 33 && try { setCacheEnabled(false) } catch (_: Exception) { false }
        GuardDiagnostics.record("node cache disabled=$nodeLookupsSafe")
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')?.let { imePackages = setOf(it) }
        overlay = AccessOverlay(this)
        sampler = ForegroundSampler(scope, windowWorker, ::queryWindows, { reading ->
            reading.windows.forEach { w -> w.pkg?.let { if (w.id !in windowPackages) windowPackages[w.id] = it } }
            if (locked()) coordinator.deviceLocked()
            else { coordinator.observed(readForeground()); coordinator.tick() }
        }, { GuardDiagnostics.record("window query failed ${it.javaClass.simpleName}") })
        coordinator = GateCoordinator(packageName, appContainer.clock, object : GateHost {
            override fun show(pkg: String, rule: Rule, status: Status) {
                if (locked()) return
                if (!overlay.isShowing) GuardDiagnostics.record("show $pkg ${status.phase}")
                showGate(pkg, status, rule)
            }
            override fun dismiss() {
                if (overlay.isShowing) GuardDiagnostics.record("dismiss gate")
                overlay.dismiss()
            }
            override fun returnHomeIfForeground(pkg: String): HomeResult {
                if (locked()) return HomeResult.UNCONFIRMED
                val session = snapshot?.sessions?.get(pkg) ?: return HomeResult.UNCONFIRMED
                val actual = foregroundFrom(sampler.expiryProof(session, appContainer.clock.sample()))
                if (actual != null && actual != pkg && actual != packageName) return HomeResult.OTHER_APP
                if (actual != pkg) return HomeResult.UNCONFIRMED
                return if (performGlobalAction(GLOBAL_ACTION_HOME)) {
                    Toast.makeText(this@FocusAccessibilityService, "本次使用时间已到", Toast.LENGTH_SHORT).show()
                    HomeResult.EXITED
                } else HomeResult.UNCONFIRMED
            }
            override fun warn(session: Session) { scope.launch { appContainer.scheduler.maybeWarn(session) } }
        })
        launchRouter = ApplicationSignalRouter(packageName, SystemClock::uptimeMillis, { imePackages }, { sampler.invalidate() }) { pkg, id, time ->
            launchSignal = if (id >= 0) LaunchSignal(pkg, id, time) else null
            val state = snapshot?.status(pkg, appContainer.clock.sample())
            GuardDiagnostics.record("app $pkg id=$id eventLag=${SystemClock.uptimeMillis() - time} phase=${state?.phase} remaining=${state?.remainingMs}")
            if (locked()) coordinator.deviceLocked() else coordinator.applicationOpened(pkg)
        }
        GuardDiagnostics.record("service connected")
        ContextCompat.registerReceiver(this, catalogChanges, IntentFilter(TelecomManager.ACTION_DEFAULT_DIALER_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
        ContextCompat.registerReceiver(this, catalogChanges,
            IntentFilter().apply { addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_REPLACED); addDataScheme("package") }, ContextCompat.RECEIVER_EXPORTED)
        scope.launch {
            imePackages = withContext(Dispatchers.IO) { getSystemService(InputMethodManager::class.java).inputMethodList.map { it.packageName }.toSet() }
        }
        scope.launch {
            var lastRules: List<Rule>? = null
            var lastRevision = -1L
            var permitted: Map<String, Rule> = emptyMap()
            var generation = 0L
            var metadataJob: Job? = null
            val criticalFilter = CriticalRuleFilter(packageName)
            combine(appContainer.repository.rules, appContainer.repository.sessions, catalogRevision) { r, s, v -> Triple(r, s, v) }.collect { (rules, sessions, revision) ->
                val changed = lastRules != rules || lastRevision != revision
                if (lastRevision != revision) {
                    // One small role/home lookup per revision; no per-rule queries or labels.
                    criticalFilter.update(withContext(Dispatchers.IO) { appContainer.catalog.criticalPackages() })
                    GuardDiagnostics.record("critical application catalog ready")
                }
                permitted = criticalFilter.filter(rules)
                publish(EntrySnapshot(permitted, sessions.associateBy { it.packageName }))
                if (changed) {
                    lastRules = rules; lastRevision = revision
                    val ticket = ++generation
                    metadataJob?.cancel()
                    metadataJob = scope.launch {
                        val loaded = withContext(Dispatchers.IO) {
                            rules.associate { it.packageName to appContainer.catalog.label(it.packageName) }
                        }
                        if (ticket == generation) {
                            labels = loaded
                        }
                    }
                }
            }
        }
        scope.launch { try { appContainer.repository.reconcile() } catch (_: Exception) { GuardDiagnostics.record("session recovery failed") } }
        scope.launch { while (isActive) { GuardDiagnostics.mainPulse(); checks.trySend(Unit); delay(250) } }
        scope.launch(Dispatchers.Default) { while (isActive) { delay(1000); GuardDiagnostics.checkMainPulse() } }
        scope.launch {
            for (ignored in checks) {
                GuardDiagnostics.measure("guard check") {
                    if (locked()) coordinator.deviceLocked()
                    else {
                        sampler.refresh(windowPackages)
                        coordinator.observed(readForeground()); coordinator.tick()
                    }
                }
            }
        }
    }
    private fun publish(state: EntrySnapshot) {
        snapshot = state
        ready = true
        GuardDiagnostics.record("snapshot rules=${state.rules.size} sessions=${state.sessions.size}")
        if (::coordinator.isInitialized) coordinator.updateSnapshot(state)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::coordinator.isInitialized) return
        GuardDiagnostics.measure("event callback") { handleEvent(event) }
    }
    private fun handleEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString()
        val id = event.windowId
        val kind = event.className?.toString().orEmpty()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            sampler.invalidate()
            if (Build.VERSION.SDK_INT >= 28 && event.windowChanges and AccessibilityEvent.WINDOWS_CHANGE_REMOVED != 0) windowPackages.remove(id)
        }
        if (pkg != null && id >= 0 && event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) windowPackages[id] = pkg
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (!launchRouter.windowState(pkg, kind, id, event.eventTime, event.contentChangeTypes) && pkg != null && snapshot?.rules?.containsKey(pkg) == true) {
                GuardDiagnostics.record("ignored app event $pkg id=$id changes=${event.contentChangeTypes} eventLag=${SystemClock.uptimeMillis() - event.eventTime}")
            }
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && pkg != null &&
            snapshot?.rules?.containsKey(pkg) == true && !overlay.isShowing && coordinator.currentPackage !in snapshot!!.rules &&
            id >= 0 && contentWindowIsForeground(id)) {
            // Some OEMs emit visible application content before a top-level window event.
            // Do not retarget an existing gate or another restricted session using content noise.
            GuardDiagnostics.record("content launch fallback $pkg id=$id")
            launchRouter.windowState(pkg, kind, id, event.eventTime)
        }
        // State events are handled synchronously above, even when windowId == -1.
        // Window polling is reconciliation, never a prerequisite for the initial gate.
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || pkg != coordinator.currentPackage) checks.trySend(Unit)
    }
    private fun contentWindowIsForeground(id: Int): Boolean = sampler.recent(SystemClock.elapsedRealtime())?.windows?.any {
        it.id == id && it.kind == WindowKind.APP && (it.active || it.focused)
    } == true
    private fun readForeground(): String? = foregroundFrom(if (::sampler.isInitialized) sampler.recent(SystemClock.elapsedRealtime()) else null)
    private fun foregroundFrom(reading: WindowReading?): String? {
        if (mainUiVisible) return packageName
        if (reading == null) return null
        return WindowPolicy.choose(reading.windows, overlay.isShowing, launchSignal, SystemClock.uptimeMillis())
    }
    /** Runs only on the serial probe worker; Android window/node objects never cross to main. */
    private fun queryWindows(identities: Map<Int, String>): WindowReading {
        val capturedAt = SystemClock.elapsedRealtime()
        val capturedWall = System.currentTimeMillis()
        val activeWindows = GuardDiagnostics.measure("window query") { windows }
        val facts = activeWindows.map { w ->
            val p = identities[w.id] ?: if (Build.VERSION.SDK_INT >= 33 && nodeLookupsSafe && w.type == AccessibilityWindowInfo.TYPE_APPLICATION && (w.isActive || w.isFocused)) {
                GuardDiagnostics.measure("node query id=${w.id}") { w.getRoot(0)?.packageName?.toString() }
            } else null
            WindowFact(when {
                w.type == AccessibilityWindowInfo.TYPE_APPLICATION -> WindowKind.APP
                w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && p == packageName -> WindowKind.OWN_OVERLAY
                w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD -> WindowKind.KEYBOARD
                w.type == AccessibilityWindowInfo.TYPE_SYSTEM -> WindowKind.SYSTEM
                else -> WindowKind.OTHER
            }, p, w.isActive, w.layer, w.id, w.isFocused)
        }
        return WindowReading(facts, capturedAt, capturedWall)
    }
    private fun showGate(pkg: String, status: Status, rule: Rule) {
        overlay.show(pkg, labels[pkg] ?: pkg, status, rule.maxMinutes, request = { minutes, done ->
            val ticket = overlay.ownership.ticket()
            scope.launch {
                if (coordinator.currentPackage != pkg) return@launch
                val result = appContainer.repository.request(pkg, minutes)
                if (result == MutationResult.APPLIED) {
                    val saved = appContainer.repository.session(pkg)
                    snapshot?.let { current -> if (saved != null) publish(current.copy(sessions = current.sessions + (pkg to saved))) }
                    GuardDiagnostics.record("grant saved $pkg")
                }
                if (!overlay.ownership.owns(ticket)) return@launch
                when (result) {
                    MutationResult.APPLIED -> overlay.dismiss()
                    MutationResult.ACTIVE_SESSION -> { done("状态已改变，请稍候。"); checks.trySend(Unit) }
                    else -> done("未能开始，请检查时长后重试。原限制仍有效。")
                }
            }
        }, exit = {
            if (performGlobalAction(GLOBAL_ACTION_HOME)) coordinator.exited()
        })
    }
    override fun onInterrupt() { GuardDiagnostics.record("service interrupted; gate retained") }
    override fun onDestroy() {
        connected = false; ready = false; instance = null
        GuardDiagnostics.record("service destroyed")
        if (receiverRegistered) { unregisterReceiver(catalogChanges); receiverRegistered = false }
        scope.cancel(); checks.close(); windowWorker.close(); if (::overlay.isInitialized) overlay.dismiss(); super.onDestroy()
    }
}
