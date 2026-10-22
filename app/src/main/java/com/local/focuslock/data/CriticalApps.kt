package com.local.focuslock.data
object CriticalApps {
    fun allowed(pkg: String, own: String, home: Set<String>, dialer: String?): Boolean = pkg.isNotBlank() && pkg != own && pkg !in home && pkg != dialer && pkg !in setOf(
        "android", "com.android.settings", "com.android.systemui", "com.miui.securitycenter", "com.miui.home",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller", "com.lbe.security.miui", "com.android.packageinstaller", "com.google.android.packageinstaller"
    )
}
