package com.metrolauncher.model

import android.graphics.drawable.Drawable

/**
 * Rappresenta un'app installata. `icon` è volatile (non viene serializzato),
 * la ricarichiamo dal PackageManager al bisogno.
 */
data class AppInfo(
    val label: String,
    val packageName: String,
    val activityName: String,
    @Transient var icon: Drawable? = null
) {
    val componentKey: String get() = "$packageName/$activityName"
}
