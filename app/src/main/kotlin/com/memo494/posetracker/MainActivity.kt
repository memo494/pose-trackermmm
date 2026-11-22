package com.memo494.posetracker

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.memo494.posetracker.capture.CaptureLog
import com.memo494.posetracker.capture.CaptureService
import com.memo494.posetracker.control.ControlEffect
import com.memo494.posetracker.control.ControlEvent
import com.memo494.posetracker.control.ControlModelMapper
import com.memo494.posetracker.control.ControlReducer
import com.memo494.posetracker.control.ControlScreen
import com.memo494.posetracker.control.ControlState
import com.memo494.posetracker.control.ImportFailure
import com.memo494.posetracker.control.ImportResults
import com.memo494.posetracker.control.ImportSummary
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.modelimport.ModelOrientation
import com.memo494.posetracker.core.tuning.TuningOverrides
import com.memo494.posetracker.diagnostics.AppLog
import com.memo494.posetracker.diagnostics.CrashNotice
import com.memo494.posetracker.diagnostics.CrashStore
import com.memo494.posetracker.diagnostics.DiagnosticsNotice
import com.memo494.posetracker.diagnostics.DiagnosticsSave
import com.memo494.posetracker.diagnostics.DiagnosticsSection
import com.memo494.posetracker.diagnostics.DiagnosticsShare
import com.memo494.posetracker.diagnostics.DiagnosticsUiState
import com.memo494.posetracker.diagnostics.DiagnosticsZip
import com.memo494.posetracker.modelimport.AcceptedImportLog
import com.memo494.posetracker.modelimport.ModelImporter
import com.memo494.posetracker.modelimport.ModelLibraryStore
import com.memo494.posetracker.modelimport.ModelStatus
import com.memo494.posetracker.modelimport.ModelStatusHolder
import com.memo494.posetracker.tuning.TuningStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Control screen: permissions, screen-capture consent, Start and Stop (ARCHITECTURE §1, §14).
 * Every decision comes from [ControlReducer]; this class only reads the system and performs the effect it returns.
 *
 * Model import: the picked file is copied at once by [ModelImporter] on `Dispatchers.IO`, so no persistable URI
 * permission is needed. There is no service for it. The import is single flight per process
 * ([ModelImporter.isImporting]): a recreated activity (rotation) seeds `importing` from it, so Import, Use
 * built-in model and Start stay disabled until the running import ends, and the result goes through the
 * process-wide [ImportResults] that every activity collects, so the new one learns it and reloads the model state.
 * If the whole process dies mid-import, the copy is lost and the next model-state read shows what was stored.
 */
class MainActivity : ComponentActivity() {
    /** The last [ImportResults] sequence this activity handled; read before `importing` is seeded (see [onImportResult]). */
    private var seenImportSequence = ImportResults.latest.value.sequence

    private val controlState = mutableStateOf(ControlState(importing = ModelImporter.isImporting()))
    private val diagnosticsState = mutableStateOf(DiagnosticsUiState())
    private var seenStartFailures = 0

    /** Counts the model-state reads; only the newest read may answer (main thread only). */
    private var modelLoadSeq = 0

    /** Keeps the tuning saves in the order they were asked for (the lock is fair; main thread only). */
    private val tuningSaveLock = Mutex()

    // Registered at activity level so a result that arrives after a recreation still reaches the new instance.
    private val consentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            onConsentResult(result)
        }
    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            dispatch(ControlEvent.NotificationPermissionResult(granted))
        }

    // A .tflite has no registered MIME type, so the picker is opened for every type.
    private val modelPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            dispatch(ControlEvent.ImportPicked(uri?.toString()))
        }

    // "Save as" dialog for the diagnostics zip; no storage permission is needed. Cancel gives a null Uri.
    private val saveDiagnosticsLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument(DIAGNOSTICS_MIME_TYPE)) { uri ->
            if (uri != null) saveDiagnosticsTo(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                ControlScreen(
                    state = controlState.value,
                    buildLabel = BuildLabel.line(BuildConfig.VERSION_NAME, BuildConfig.GIT_SHA),
                    onStartClick = { dispatch(ControlEvent.StartClicked) },
                    onStopClick = { dispatch(ControlEvent.StopClicked) },
                    onOpenOverlaySettingsClick = { dispatch(ControlEvent.OpenOverlaySettingsClicked) },
                    onImportClick = { dispatch(ControlEvent.ImportClicked) },
                    onRemoveAllClick = { dispatch(ControlEvent.RemoveAllClicked) },
                    onDeleteModelClick = { dispatch(ControlEvent.DeleteModelClicked(it)) },
                    onSlotPick = { orientation, sha256 -> dispatch(ControlEvent.SlotPicked(orientation, sha256)) },
                    onDebugMarksChange = { dispatch(ControlEvent.DebugMarksToggled(it)) },
                    onTuningChange = { key, value -> dispatch(ControlEvent.TuningChanged(key, value)) },
                    onTuningCommit = { dispatch(ControlEvent.TuningCommitted) },
                    onTuningReset = { dispatch(ControlEvent.TuningResetClicked) },
                    crashContent = {
                        CrashNotice(
                            state = diagnosticsState.value,
                            onShareClick = { shareDiagnostics() },
                            onDismissClick = { dismissCrashNotice() },
                        )
                    },
                    diagnosticsContent = {
                        DiagnosticsSection(
                            state = diagnosticsState.value,
                            onShareClick = { shareDiagnostics() },
                            onSaveClick = { startSaveDiagnostics() },
                            onToggleLogClick = { toggleLog() },
                            onRefreshLogClick = { refreshLog() },
                        )
                    },
                )
            }
        }
        // Failures before this activity existed (for example before a rotation) are not shown again.
        seenStartFailures = CaptureService.startFailures.value
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    // Also the first value of every start of the screen, so the model block is read when the screen
                    // comes up and again when a session stops (the session publishes its model status).
                    CaptureService.running.collect {
                        dispatch(ControlEvent.RunningChanged(it))
                        loadModelState()
                    }
                }
                launch { ImportResults.latest.collect { onImportResult(it) } }
                CaptureService.startFailures.collect { failures ->
                    if (failures > seenStartFailures) dispatch(ControlEvent.StartFailed)
                    seenStartFailures = failures
                }
            }
        }
        showCrashNoticeIfAny()
        loadDebugMarks()
        loadTuning()
    }

    override fun onResume() {
        super.onResume()
        dispatch(ControlEvent.OverlayPermissionChecked(Settings.canDrawOverlays(this)))
    }

    /**
     * Runs [event] through the reducer, stores the new state and performs the effect. [consentResult] is only
     * passed on by the consent callback, so the consent Intent reaches `START_SERVICE` without being stored.
     */
    private fun dispatch(
        event: ControlEvent,
        consentResult: ActivityResult? = null,
    ) {
        val step = ControlReducer.reduce(controlState.value, event, notificationPermissionMissing())
        controlState.value = step.state
        when (step.effect) {
            ControlEffect.OPEN_OVERLAY_SETTINGS -> openOverlaySettings()
            ControlEffect.REQUEST_NOTIFICATION_PERMISSION -> requestNotificationPermission()
            ControlEffect.LAUNCH_CONSENT -> launchConsent()
            ControlEffect.START_SERVICE -> startCapture(consentResult, step.debugMarks == true, step.tuning ?: TuningOverrides.NONE)
            ControlEffect.STOP_SERVICE -> stopCapture()
            ControlEffect.OPEN_MODEL_PICKER -> openModelPicker()
            ControlEffect.RUN_IMPORT -> runImport(step.importUri)
            ControlEffect.CLEAR_IMPORTED -> clearImported()
            ControlEffect.SCHEDULE_REMOVE_ALL_DISARM -> scheduleRemoveAllDisarm()
            ControlEffect.DELETE_MODEL -> deleteModel(step.modelSha)
            ControlEffect.SET_SLOT -> setSlot(step.slotOrientation, step.modelSha)
            ControlEffect.LOAD_MODEL_STATE -> loadModelState()
            ControlEffect.PERSIST_DEBUG_MARKS -> saveDebugMarks(step.debugMarks == true)
            ControlEffect.PERSIST_TUNING -> saveTuning(step.tuning ?: TuningOverrides.NONE)
            null -> Unit
        }
    }

    private fun notificationPermissionMissing(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val answer = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
        return answer != PackageManager.PERMISSION_GRANTED
    }

    private fun openOverlaySettings() {
        val action = Settings.ACTION_MANAGE_OVERLAY_PERMISSION
        try {
            startActivity(Intent(action, Uri.fromParts("package", packageName, null)))
        } catch (e: ActivityNotFoundException) {
            CaptureLog.log("overlay settings for this app not found, opening the general page", e)
            try {
                startActivity(Intent(action))
            } catch (e2: ActivityNotFoundException) {
                CaptureLog.log("overlay settings not found", e2)
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            dispatch(ControlEvent.NotificationPermissionResult(true))
            return
        }
        try {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } catch (e: Exception) {
            CaptureLog.log("notification permission request failed", e)
            dispatch(ControlEvent.NotificationPermissionResult(false))
        }
    }

    private fun launchConsent() {
        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            if (manager == null) {
                CaptureLog.log("no MediaProjectionManager on this device", null)
                dispatch(ControlEvent.ConsentResult(false))
                return
            }
            val intent =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                } else {
                    manager.createScreenCaptureIntent()
                }
            consentLauncher.launch(intent)
        } catch (e: Exception) {
            CaptureLog.log("launching the screen-capture consent failed", e)
            dispatch(ControlEvent.ConsentResult(false))
        }
    }

    private fun onConsentResult(result: ActivityResult) {
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) {
            dispatch(ControlEvent.ConsentResult(false))
            return
        }
        dispatch(ControlEvent.ConsentResult(true), result)
    }

    /** Starts the service at once with the callback's own consent Intent; any failure is shown as a denied consent. */
    private fun startCapture(
        consentResult: ActivityResult?,
        debugMarks: Boolean,
        tuning: TuningOverrides,
    ) {
        val data = consentResult?.data
        if (consentResult == null || data == null) {
            CaptureLog.log("start refused: no consent result to pass on", null)
            dispatch(ControlEvent.ConsentResult(false))
            return
        }
        try {
            ContextCompat.startForegroundService(this, CaptureService.startIntent(this, consentResult.resultCode, data, debugMarks, tuning))
        } catch (e: Exception) {
            CaptureLog.log("starting the capture service failed", e)
            dispatch(ControlEvent.ConsentResult(false))
        }
    }

    private fun stopCapture() {
        try {
            startService(CaptureService.stopIntent(this))
        } catch (e: Exception) {
            CaptureLog.log("stopping the capture service failed", e)
        }
    }

    private fun openModelPicker() {
        try {
            modelPickerLauncher.launch(arrayOf(MODEL_PICKER_MIME_TYPE))
        } catch (e: Exception) {
            CaptureLog.log("opening the model picker failed", e)
        }
    }

    /**
     * Imports the picked document off the main thread and publishes the outcome to [ImportResults]; the collector
     * ([onImportResult]) reports it, also in an activity that was recreated meanwhile. The publish runs inside the
     * worker block, so a cancelled `lifecycleScope` cannot lose it.
     */
    private fun runImport(uriString: String?) {
        if (uriString == null) return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { ImportResults.publish(importOrFail(Uri.parse(uriString))) }
        }
    }

    /**
     * A finished import. Each result is handled once per activity (`seenImportSequence`, read in the field
     * initialiser before `importing` is seeded, so an import that ends in between is still delivered). When this
     * activity is waiting for it the reducer finishes the import; otherwise (it started after the import began and
     * missed the flag) only the model state is read again.
     */
    private fun onImportResult(result: ImportResults.Result) {
        val summary = result.summary
        if (result.sequence <= seenImportSequence || summary == null) return
        seenImportSequence = result.sequence
        // A new model is a new situation: what the last session said about the old one no longer applies.
        if (summary is ImportSummary.Accepted) acknowledgedStatus = ModelStatusHolder.latest()
        if (controlState.value.importing) dispatch(ControlEvent.ImportFinished(summary)) else loadModelState()
    }

    /** Worker thread. Never throws: any failure, `Error`s included, becomes [ImportSummary.Failed]. */
    private fun importOrFail(uri: Uri): ImportSummary =
        try {
            val (name, size) = readNameAndSize(uri)
            val importer = ModelImporter(ModelLibraryStore(applicationContext))
            val source = { contentResolver.openInputStream(uri) ?: throw IOException("The picked file cannot be opened") }
            val outcome = AcceptedImportLog.logIfAccepted(importer.import(source, name, size)) { AppLog.log(it, null) }
            ControlModelMapper.summary(outcome)
        } catch (t: Throwable) {
            AppLog.log("model import failed", t)
            ImportSummary.Failed(ImportFailure.IO, t.javaClass.simpleName + (t.message?.let { ": $it" } ?: ""))
        }

    /**
     * The provider's file name and size, each `null` when it does not say (or the query fails): the importer
     * counts the bytes itself and falls back to a neutral name.
     */
    private fun readNameAndSize(uri: Uri): Pair<String?, Long?> {
        val unknown: Pair<String?, Long?> = Pair(null, null)
        return try {
            val columns = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use unknown
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) cursor.getString(nameIndex) else null
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
                Pair(name, size)
            } ?: unknown
        } catch (e: Exception) {
            AppLog.log("reading the picked file's name and size failed", e)
            unknown
        }
    }

    /** Deletes every imported model off the main thread, then reads the model state again. */
    private fun clearImported() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { clearOrLog() }
            acknowledgedStatus = ModelStatusHolder.latest()
            loadModelState()
        }
    }

    /** Gives the second tap on "Remove all imported models" [TrackerConfig.removeAllConfirmMs]; then the first tap is forgotten. */
    private fun scheduleRemoveAllDisarm() {
        lifecycleScope.launch {
            delay(TrackerConfig().removeAllConfirmMs)
            dispatch(ControlEvent.RemoveAllDisarm)
        }
    }

    private fun clearOrLog() {
        try {
            if (!ModelLibraryStore(applicationContext).clearAll()) AppLog.log("removing the imported models was incomplete", null)
        } catch (t: Throwable) {
            AppLog.log("removing the imported models failed", t)
        }
    }

    /** Deletes the library model [sha256] off the main thread, then reads the model state again. */
    private fun deleteModel(sha256: String?) {
        if (sha256 == null) return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    if (!ModelLibraryStore(applicationContext).delete(sha256)) AppLog.log("deleting the model was incomplete", null)
                } catch (t: Throwable) {
                    AppLog.log("deleting the model failed", t)
                }
            }
            loadModelState()
        }
    }

    /** Stores the choice of [orientation] (`null` sha256 = built-in) off the main thread, then reads the model state again. */
    private fun setSlot(
        orientation: ModelOrientation?,
        sha256: String?,
    ) {
        if (orientation == null) return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val stored = ModelLibraryStore(applicationContext).setSlot(orientation, sha256)
                    if (!stored) AppLog.log("storing the model choice failed", null)
                } catch (t: Throwable) {
                    AppLog.log("storing the model choice failed", t)
                }
            }
            loadModelState()
        }
    }

    /**
     * Reads the stored model state and the newest session's model status off the main thread. Only the newest
     * read answers, so an older read cannot overwrite a newer one; a failed read leaves the screen as it was.
     */
    private fun loadModelState() {
        val sequence = ++modelLoadSeq
        lifecycleScope.launch {
            val event = withContext(Dispatchers.IO) { readModelState() }
            if (event != null && sequence == modelLoadSeq) dispatch(event)
        }
    }

    private fun readModelState(): ControlEvent.ModelStateLoaded? =
        try {
            val status: ModelStatus? = ModelStatusHolder.latest().takeUnless { it === acknowledgedStatus }
            val store = ModelLibraryStore(applicationContext)
            val metrics = resources.displayMetrics
            val orientation = ModelOrientation.of(metrics.widthPixels, metrics.heightPixels)
            val max = TrackerConfig().modelLibraryMax
            ControlModelMapper.libraryLoaded(store.state(), status, orientation, max, store::storedFileMatches)
        } catch (t: Throwable) {
            AppLog.log("reading the model state failed", t)
            null
        }

    /** Reads the saved "Debug marks" switch off the main thread; a failed read leaves the default (off). */
    private fun loadDebugMarks() {
        lifecycleScope.launch {
            val saved =
                withContext(Dispatchers.IO) {
                    try {
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_DEBUG_MARKS, TrackerConfig.DEFAULT_DEBUG_MARKS)
                    } catch (t: Throwable) {
                        AppLog.log("reading the debug marks switch failed", t)
                        null
                    }
                }
            if (saved != null) dispatch(ControlEvent.DebugMarksLoaded(saved))
        }
    }

    /** Reads the saved tuning sheet off the main thread; the store never throws and logs an unreadable file itself. */
    private fun loadTuning() {
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) { TuningStore(applicationContext).read() }
            dispatch(ControlEvent.TuningLoaded(saved))
        }
    }

    /** Saves the tuning sheet off the main thread, in the order asked; a failed write is logged by the store. */
    private fun saveTuning(overrides: TuningOverrides) {
        lifecycleScope.launch {
            tuningSaveLock.withLock {
                withContext(Dispatchers.IO) { TuningStore(applicationContext).write(overrides) }
            }
        }
    }

    /** Saves the "Debug marks" switch off the main thread; a failed write is logged and the switch still works this run. */
    private fun saveDebugMarks(enabled: Boolean) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(KEY_DEBUG_MARKS, enabled).apply()
                } catch (t: Throwable) {
                    AppLog.log("saving the debug marks switch failed", t)
                }
            }
        }
    }

    private fun updateDiagnostics(change: (DiagnosticsUiState) -> DiagnosticsUiState) {
        diagnosticsState.value = change(diagnosticsState.value)
    }

    /** Reads the saved crash off the main thread and shows the notice when there is one. */
    private fun showCrashNoticeIfAny() {
        lifecycleScope.launch {
            val crash = withContext(Dispatchers.IO) { CrashStore.read(applicationContext) }
            if (crash != null) updateDiagnostics { it.copy(crashNotice = true) }
        }
    }

    private fun dismissCrashNotice() {
        updateDiagnostics { it.copy(crashNotice = false) }
        clearSavedCrash()
    }

    private fun clearSavedCrash() {
        lifecycleScope.launch { withContext(Dispatchers.IO) { CrashStore.clear(applicationContext) } }
    }

    /** Writes the zip off the main thread, then opens the share sheet; any failure becomes a short notice. */
    private fun shareDiagnostics() {
        if (diagnosticsState.value.sharing) return
        updateDiagnostics { it.copy(sharing = true, notice = null) }
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { writeZipOrNull() }
            val notice = if (file == null) DiagnosticsNotice.PREPARE_FAILED else openShareSheet(file)
            updateDiagnostics { it.copy(sharing = false, notice = notice) }
            if (file != null && notice == null && diagnosticsState.value.crashNotice) dismissCrashNotice()
        }
    }

    /** Opens the system "save as" dialog; the zip is built only after a place is picked ([saveDiagnosticsTo]). */
    private fun startSaveDiagnostics() {
        if (diagnosticsState.value.sharing) return
        updateDiagnostics { it.copy(notice = null) }
        try {
            saveDiagnosticsLauncher.launch(DiagnosticsSave.fileName())
        } catch (e: Exception) {
            AppLog.log("opening the save dialog failed", e)
            updateDiagnostics { it.copy(notice = DiagnosticsNotice.SAVE_FAILED) }
        }
    }

    /** Writes the zip and copies it to [uri] off the main thread, then shows "Saved" or the failure notice. */
    private fun saveDiagnosticsTo(uri: Uri) {
        if (diagnosticsState.value.sharing) return
        updateDiagnostics { it.copy(sharing = true, notice = null) }
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) { writeZipAndCopyTo(uri) }
            val notice = if (saved) DiagnosticsNotice.SAVED else DiagnosticsNotice.SAVE_FAILED
            updateDiagnostics { it.copy(sharing = false, notice = notice) }
            if (saved && diagnosticsState.value.crashNotice) dismissCrashNotice()
        }
    }

    /**
     * Builds a private temporary zip, copies it to [uri] (truncating), deletes the temporary file, and on a
     * failure removes the empty document the dialog created. Never throws.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun writeZipAndCopyTo(uri: Uri): Boolean {
        var temp: File? = null
        val saved =
            try {
                val file = DiagnosticsZip.write(applicationContext, DiagnosticsZip.SAVE_FILE_NAME)
                temp = file
                // Output first, then input: copy() closes whichever of the two it gets, so nothing leaks.
                val output = contentResolver.openOutputStream(uri, "wt")
                val input =
                    try {
                        file.inputStream()
                    } catch (t: Throwable) {
                        AppLog.log("reading the temporary zip failed", t)
                        null
                    }
                DiagnosticsSave.copy(input, output)
            } catch (t: Throwable) {
                AppLog.log("saving the diagnostics zip failed", t)
                false
            }
        try {
            temp?.delete()
        } catch (t: Throwable) {
            AppLog.log("deleting the temporary zip failed", t)
        }
        if (!saved) deleteDocumentQuietly(uri)
        return saved
    }

    /** Best effort: removes the empty file the "save as" dialog created for a save that failed. */
    @Suppress("TooGenericExceptionCaught")
    private fun deleteDocumentQuietly(uri: Uri) {
        try {
            DocumentsContract.deleteDocument(contentResolver, uri)
        } catch (t: Throwable) {
            AppLog.log("removing the unsaved document failed", t)
        }
    }

    private fun writeZipOrNull(): File? =
        try {
            DiagnosticsZip.write(applicationContext)
        } catch (e: Throwable) {
            AppLog.log("writing the diagnostics zip failed", e)
            null
        }

    /** Starts the chooser for [file]; returns `null` on success or the notice to show. */
    private fun openShareSheet(file: File): DiagnosticsNotice? =
        try {
            startActivity(DiagnosticsShare.chooser(this, file, getString(R.string.diagnostics_share_title)))
            null
        } catch (e: Exception) {
            AppLog.log("opening the share sheet failed", e)
            DiagnosticsNotice.SHARE_FAILED
        }

    private fun toggleLog() {
        val show = !diagnosticsState.value.logVisible
        updateDiagnostics { it.copy(logVisible = show, logText = if (show) logText() else "") }
    }

    private fun refreshLog() {
        updateDiagnostics { it.copy(logText = logText()) }
    }

    /** The newest log lines, newest last. */
    private fun logText(): String = AppLog.lines().takeLast(TrackerConfig.LOG_SCREEN_LINES).joinToString("\n")

    private companion object {
        const val MODEL_PICKER_MIME_TYPE = "*/*"
        const val DIAGNOSTICS_MIME_TYPE = "application/zip"

        /** App preferences file and the key of the "Debug marks" switch; only read and written on `Dispatchers.IO`. */
        const val PREFS_NAME = "pose_tracker_prefs"
        const val KEY_DEBUG_MARKS = "debug_marks"

        /**
         * The session status the owner has already acted on (imported a new model or reset to the built-in one);
         * its fallback notice is not shown again. Compared by identity: a new session publishes a new object.
         */
        @Volatile
        var acknowledgedStatus: ModelStatus? = null
    }
}
