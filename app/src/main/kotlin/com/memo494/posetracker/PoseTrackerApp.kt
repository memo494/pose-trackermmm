package com.memo494.posetracker

import android.app.Application
import com.memo494.posetracker.diagnostics.AppLog
import com.memo494.posetracker.diagnostics.CrashStore

/** Installs the crash recorder before any component starts, and logs which build is running. */
class PoseTrackerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashStore.install(this)
        AppLog.log("app started: ${BuildLabel.line(BuildConfig.VERSION_NAME, BuildConfig.GIT_SHA)}", null)
    }
}
