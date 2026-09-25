package com.sideload.splitinstaller.core

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Whether any screen of the app is showing.
 *
 * A self-update closes the app the moment it lands, so it waits until nobody is looking at
 * it — an install half-way through a 500 MB bundle must not be cut off by the app replacing
 * itself.
 */
object AppVisibility : Application.ActivityLifecycleCallbacks {

    @Volatile private var started = 0

    val foreground: Boolean get() = started > 0

    fun register(app: Application) = app.registerActivityLifecycleCallbacks(this)

    override fun onActivityStarted(activity: Activity) {
        started++
    }

    override fun onActivityStopped(activity: Activity) {
        if (started > 0) started--
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
