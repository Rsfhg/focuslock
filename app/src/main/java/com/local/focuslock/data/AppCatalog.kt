package com.local.focuslock.data

import android.content.Context
import android.content.Intent
import android.telecom.TelecomManager

data class AppEntry(val packageName: String, val label: String)
class AppCatalog(private val context: Context) {
    private val pm = context.packageManager
    private fun homes() = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0).map { it.activityInfo.packageName }.toSet()
    fun criticalPackages(): Set<String> = homes() + listOfNotNull(context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage, context.packageName)
    fun allowed(pkg: String): Boolean {
        val dialer = context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        return CriticalApps.allowed(pkg, context.packageName, homes(), dialer)
    }
    fun load(): List<AppEntry> = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }.distinctBy { it.packageName }
        .filter { allowed(it.packageName) }.sortedBy { it.label }
    fun label(pkg: String): String = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { "$pkg（未安装）" }
}
