package com.metrolauncher.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Ascolta install/uninstall di pacchetti per tenere aggiornato il drawer "tutte le app".
 * Il MainActivity, se in foreground, ascolta lo stesso intent (runtime receiver).
 */
class PackageChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val rebroadcast = Intent(ACTION_APPS_CHANGED).setPackage(context.packageName)
        context.sendBroadcast(rebroadcast)
    }
    companion object {
        const val ACTION_APPS_CHANGED = "com.metrolauncher.APPS_CHANGED"
    }
}
