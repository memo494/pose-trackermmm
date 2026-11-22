package com.memo494.posetracker.capture

import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.tuning.TuningOverrides
import com.memo494.posetracker.diagnostics.AppLog
import com.memo494.posetracker.diagnostics.CaptureStatsHolder
import com.memo494.posetracker.tuning.TuningSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Foreground service that owns one [CaptureSession] (ARCHITECTURE §5). Everything here runs on the main
 * thread and only talks to the system and to the session; the capture work lives in the session.
 *
 * The caller must obtain screen-capture consent first and start the service with [startIntent] through
 * `startForegroundService`. Every start intent calls `startForeground` first (the platform requires it
 * within seconds), then creates the session. A restart always needs new consent (never sticky).
 */
class CaptureService : Service() {
    private var session: CaptureSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent == null) {
            if (session == null) stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> handleStop()
            else -> if (session == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        session?.onConfigurationChanged()
    }

    override fun onDestroy() {
        session?.stop()
        _running.value = false
        super.onDestroy()
    }

    private fun handleStop() {
        val current = session
        if (current == null) stopSelf() else current.stop()
    }

    private fun handleStart(intent: Intent) {
        try {
            CaptureNotification.ensureChannel(this)
            ServiceCompat.startForeground(
                this,
                CaptureNotification.NOTIFICATION_ID,
                CaptureNotification.build(this),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } catch (e: Exception) {
            // SecurityException or ForegroundServiceStartNotAllowedException: no foreground, no capture.
            CaptureLog.log("startForeground refused", e)
            _startFailures.update { it + 1 }
            stopSelf()
            return
        }
        if (session != null) {
            CaptureLog.log("start ignored: a capture session is already running", null)
            return
        }
        val projection = createProjection(intent)
        if (projection == null) {
            CaptureLog.log("capture not started: no media projection", null)
            _startFailures.update { it + 1 }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        // The switch value travels in the Intent: the service must not read preferences on the main thread.
        val config = sessionConfig(intent)
        val newSession = CaptureSession(this, projection, config, ::onSessionFinished)
        session = newSession
        _running.value = true
        newSession.start()
    }

    /**
     * The config of this session: the defaults and the debug-marks switch, then the tuning sheet's overrides that travel
     * in the Intent (a missing or unreadable extra is no override). Remembered for `config.txt` and `summary.txt`.
     */
    private fun sessionConfig(intent: Intent): TrackerConfig {
        val debugMarks = intent.getBooleanExtra(EXTRA_DEBUG_MARKS, TrackerConfig.DEFAULT_DEBUG_MARKS)
        val overrides = TuningSession.overridesOf(intent.getStringExtra(EXTRA_TUNING))
        val result = overrides.applyTo(TrackerConfig(debugMarks = debugMarks))
        CaptureStatsHolder.setSession(result.config, result.applied)
        if (overrides.count > 0) AppLog.log("tuning: ${result.applied} override(s) applied: ${overrides.summaryText()}", null)
        if (result.skipped.isNotEmpty()) {
            AppLog.log(
                "tuning: skipped (the config refused them): ${result.skipped.joinToString { it.id }}",
                null,
            )
        }
        return result.config
    }

    /** The projection for the consent result in [intent], or `null` (logged) when it cannot be created. */
    private fun createProjection(intent: Intent): MediaProjection? {
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
        if (data == null || !intent.hasExtra(EXTRA_RESULT_CODE)) {
            CaptureLog.log("start refused: the consent result is missing", null)
            return null
        }
        return try {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            getSystemService(MediaProjectionManager::class.java)?.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            CaptureLog.log("getMediaProjection failed", e)
            null
        }
    }

    /** Called by the session on the main thread when its teardown is complete ([failed]: its own failure ended it). */
    private fun onSessionFinished(failed: Boolean) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        session = null
        _running.value = false
        if (failed) _startFailures.update { it + 1 }
        stopSelf()
    }

    companion object {
        private const val ACTION_START = "com.memo494.posetracker.action.START_CAPTURE"
        private const val ACTION_STOP = "com.memo494.posetracker.action.STOP_CAPTURE"
        private const val EXTRA_RESULT_CODE = "com.memo494.posetracker.extra.RESULT_CODE"
        private const val EXTRA_DATA = "com.memo494.posetracker.extra.DATA"
        private const val EXTRA_DEBUG_MARKS = "com.memo494.posetracker.extra.DEBUG_MARKS"
        private const val EXTRA_TUNING = "com.memo494.posetracker.extra.TUNING"

        private val _running = MutableStateFlow(false)

        /** `true` from the moment a capture session is created until its teardown has finished. */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        private val _startFailures = MutableStateFlow(0)

        /**
         * Counter that grows by one for every start that failed: `startForeground` refused, the consent
         * result or the projection missing, or the session ended by a failure of its own. The control screen
         * shows a notice when it sees the counter grow.
         */
        val startFailures: StateFlow<Int> = _startFailures.asStateFlow()

        /**
         * Intent that starts a capture session with the consent result of `createScreenCaptureIntent`
         * ([resultCode] and [data] of the activity result). Send it with `startForegroundService`.
         * [debugMarks] is the control screen's switch for this session (a missing extra means `false`);
         * [tuning] is the tuning sheet's overrides for it (a missing extra means none).
         */
        fun startIntent(
            context: Context,
            resultCode: Int,
            data: Intent,
            debugMarks: Boolean = TrackerConfig.DEFAULT_DEBUG_MARKS,
            tuning: TuningOverrides = TuningOverrides.NONE,
        ): Intent =
            Intent(context, CaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
                putExtra(EXTRA_DEBUG_MARKS, debugMarks)
                putExtra(EXTRA_TUNING, tuning.encode())
            }

        /** Intent that stops the running capture session (the notification's Stop action). */
        fun stopIntent(context: Context): Intent = Intent(context, CaptureService::class.java).setAction(ACTION_STOP)
    }
}
