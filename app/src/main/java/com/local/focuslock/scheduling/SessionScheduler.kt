package com.local.focuslock.scheduling

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.local.focuslock.appContainer
import com.local.focuslock.data.SessionRepository
import com.local.focuslock.domain.*
import com.local.focuslock.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.local.focuslock.enforcement.FocusAccessibilityService

class SessionScheduler(private val context: Context, private val repo: SessionRepository, private val clock: ClockSource) {
    private val manager = context.getSystemService(AlarmManager::class.java)
    private val warningMutex = Mutex()
    suspend fun reschedule(sessions: List<Session>, now: ClockSample) {
        for (s in sessions) {
            val status = SessionPolicy.status(s, now)
            if (status.phase != Phase.USING) continue
            if (SchedulePolicy.shouldWarn(s, now)) warn(s)
            else if (!s.warned) schedule(s, "WARNING", (status.remainingMs - 60_000).coerceAtLeast(1), now)
            schedule(s, "DEADLINE", status.remainingMs, now)
        }
    }
    private fun schedule(s: Session, kind: String, delay: Long, now: ClockSample) {
        val intent = Intent(context, AlarmReceiver::class.java).setData(Uri.parse("focuslock://alarm/${s.packageName}/${s.id}/$kind"))
            .putExtra("package", s.packageName).putExtra("session", s.id).putExtra("kind", kind)
        val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            if (Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()) manager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, now.elapsedMs + delay, pending)
            else manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, now.elapsedMs + delay, pending)
        } catch (_: SecurityException) { manager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, now.elapsedMs + delay, pending) }
    }
    suspend fun onAlarm(pkg: String, id: String, kind: String) {
        val s = repo.session(pkg) ?: return
        if (!SchedulePolicy.matches(s, id)) return
        if (kind == "WARNING" && SchedulePolicy.shouldWarn(s, clock.sample())) warn(s)
        // Active accessibility service polls the deadline; a broadcast never launches UI.
        repo.reconcile()
    }
    suspend fun maybeWarn(s: Session) { if (SchedulePolicy.shouldWarn(s, clock.sample())) warn(s) }
    private suspend fun warn(s: Session) = warningMutex.withLock {
        val latest = repo.session(s.packageName) ?: return@withLock
        if (latest.id != s.id || !SchedulePolicy.shouldWarn(latest, clock.sample())) return@withLock
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("time_reminder", "使用时间提醒", NotificationManager.IMPORTANCE_HIGH))
        val canNotify = nm.areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val label = context.appContainer.catalog.label(s.packageName)
        val delivered = WarningDelivery.deliver(canNotify, { FocusAccessibilityService.warnForeground(s.packageName) }, {
            try {
                nm.notify(s.packageName.hashCode(), NotificationCompat.Builder(context, "time_reminder").setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("$label 还剩 1 分钟").setContentText("到时将返回桌面，本次不能加时。").setContentIntent(open).setAutoCancel(true).build())
                true
            } catch (_: SecurityException) { false }
        })
        if (delivered) repo.markWarned(s.packageName, s.id)
    }
}
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        context.appContainer.scope.launch {
            try { withTimeout(8_000) { context.appContainer.scheduler.onAlarm(intent.getStringExtra("package") ?: "", intent.getStringExtra("session") ?: "", intent.getStringExtra("kind") ?: "") } }
            finally { pending.finish() }
        }
    }
}
class RecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) return
        val pending = goAsync()
        context.appContainer.scope.launch {
            try { withTimeout(8_000) {
                context.appContainer.repository.reconcile()
                context.appContainer.scheduler.reschedule(context.appContainer.repository.allSessions(), context.appContainer.clock.sample())
            } } finally { pending.finish() }
        }
    }
}
