package com.local.focuslock.permissions
import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.local.focuslock.enforcement.FocusAccessibilityService

data class HealthSnapshot(val accessibility: Boolean, val notifications: Boolean, val exactAlarms: Boolean)
object ProtectionHealth {
    fun inspect(context: Context): HealthSnapshot {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val component = ComponentName(context, FocusAccessibilityService::class.java)
        val a11y = enabled.split(':').any { ComponentName.unflattenFromString(it) == component }
        val notifications = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        val exact = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        return HealthSnapshot(a11y && FocusAccessibilityService.connected, notifications, exact)
    }
}
