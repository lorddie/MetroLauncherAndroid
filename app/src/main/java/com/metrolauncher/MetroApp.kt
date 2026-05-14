package com.metrolauncher

import android.app.Application
import com.metrolauncher.model.AppInfo

/**
 * Application singleton. Tiene una cache in RAM della lista di AppInfo così
 * da non dover interrogare il PackageManager ad ogni apertura del drawer.
 */
class MetroApp : Application() {

    /** Cache condivisa — invalidata quando PackageChangeReceiver riceve un evento. */
    @Volatile var appsCache: List<AppInfo>? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        @Volatile lateinit var instance: MetroApp
            private set
    }
}
