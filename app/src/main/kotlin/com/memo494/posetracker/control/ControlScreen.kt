package com.memo494.posetracker.control

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.memo494.posetracker.R
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.modelimport.ModelOrientation

private val SCREEN_PADDING = 24.dp
private val ITEM_SPACING = 16.dp
private val SWITCH_NOTE_SPACING = 4.dp

/**
 * The control screen: status, the build label, Start or Stop, the way to the overlay permission and the model block.
 * Stateless: [state] comes in, user actions go out through the callbacks. The [Scaffold] insets keep
 * everything clear of the system bars and the cutout (edge-to-edge).
 *
 * @param crashContent shown below the status when the last run crashed (the diagnostics notice); empty by default.
 * @param diagnosticsContent shown at the bottom of the scrolling column (Share diagnostics and the log).
 */
@Composable
internal fun ControlScreen(
    state: ControlState,
    buildLabel: String,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onOpenOverlaySettingsClick: () -> Unit,
    onImportClick: () -> Unit,
    onRemoveAllClick: () -> Unit,
    onDeleteModelClick: (String) -> Unit,
    onSlotPick: (ModelOrientation, String?) -> Unit,
    onDebugMarksChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    crashContent: @Composable () -> Unit = {},
    diagnosticsContent: @Composable () -> Unit = {},
) {
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        val insets = Modifier.fillMaxSize().padding(innerPadding)
        Column(
            modifier = insets.verticalScroll(rememberScrollState()).padding(SCREEN_PADDING),
            verticalArrangement = Arrangement.spacedBy(ITEM_SPACING),
        ) {
            Text(stringResource(R.string.control_title), style = MaterialTheme.typography.headlineMedium)
            Text(buildLabel, style = MaterialTheme.typography.bodySmall)
            val statusRes = if (state.running) R.string.control_status_running else R.string.control_status_stopped
            Text(stringResource(statusRes), style = MaterialTheme.typography.titleMedium)
            crashContent()

            val notice = state.notice
            if (notice != null) {
                val noticeRes =
                    when (notice) {
                        ControlNotice.OVERLAY_PERMISSION_NEEDED -> R.string.control_notice_overlay_needed
                        ControlNotice.CONSENT_DENIED -> R.string.control_notice_consent_denied
                        ControlNotice.START_FAILED -> R.string.control_notice_start_failed
                    }
                Text(
                    text = stringResource(noticeRes),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            val primaryRes = if (state.running) R.string.control_stop else R.string.control_start
            Button(
                onClick = if (state.running) onStopClick else onStartClick,
                enabled = !state.awaitingConsent && !state.importing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(primaryRes))
            }

            if (!state.overlayPermission) {
                OutlinedButton(onClick = onOpenOverlaySettingsClick, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.control_allow_overlay))
                }
            }

            DebugMarksSwitch(state.debugMarks, onDebugMarksChange)

            ModelBlock(state, onImportClick, onRemoveAllClick, onDeleteModelClick, onSlotPick)

            Text(stringResource(R.string.control_description), style = MaterialTheme.typography.bodyMedium)
            diagnosticsContent()
        }
    }
}

/** The "Debug marks" switch with its note. Always enabled: a change is used from the next Start only. */
@Composable
private fun DebugMarksSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(SWITCH_NOTE_SPACING)) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ITEM_SPACING),
        ) {
            Text(
                text = stringResource(R.string.marks_debug_label),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = checked, onCheckedChange = null)
        }
        Text(stringResource(R.string.marks_debug_note), style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * The model block: the library list with Delete, the two choices (portrait and landscape), the model of this
 * orientation, Import and Remove all, the progress line, the last result, the notice and the trust hint. Everything
 * that changes the models is disabled while the capture runs or starts and while an import runs.
 */
@Composable
private fun ModelBlock(
    state: ControlState,
    onImportClick: () -> Unit,
    onRemoveAllClick: () -> Unit,
    onDeleteModelClick: (String) -> Unit,
    onSlotPick: (ModelOrientation, String?) -> Unit,
) {
    val idle = !state.running && !state.awaitingConsent && !state.importing
    Column(verticalArrangement = Arrangement.spacedBy(ITEM_SPACING)) {
        Text(stringResource(R.string.model_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.model_now, activeModelLine(state)), style = MaterialTheme.typography.bodyLarge)

        SlotPicker(R.string.model_slot_portrait, state.portraitSha, state, idle) { onSlotPick(ModelOrientation.PORTRAIT, it) }
        SlotPicker(R.string.model_slot_landscape, state.landscapeSha, state, idle) { onSlotPick(ModelOrientation.LANDSCAPE, it) }
        LibraryList(state, idle, onDeleteModelClick)

        val modelNotice = state.modelNotice
        if (modelNotice != null) {
            Text(
                text = stringResource(ControlModelMapper.noticeRes(modelNotice.kind)),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            DetailsLine(modelNotice.message)
        }

        if (state.importing) {
            Text(stringResource(R.string.model_importing), style = MaterialTheme.typography.bodyMedium)
        }
        val result = state.importResult
        if (result != null) ImportResultText(result)

        if (state.libraryFull) {
            Text(stringResource(R.string.model_library_full), style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedButton(onClick = onImportClick, enabled = idle && !state.libraryFull, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.model_import))
        }
        if (state.library.isNotEmpty() || modelNotice != null || state.modelFileStored) {
            OutlinedButton(onClick = onRemoveAllClick, enabled = idle, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (state.removeAllArmed) R.string.model_remove_all_confirm else R.string.model_use_built_in))
            }
        }
        Text(stringResource(R.string.model_hint_trust), style = MaterialTheme.typography.bodySmall)
    }
}

/** The choice of one orientation: the built-in model and every library model that is not switched off. */
@Composable
private fun SlotPicker(
    @StringRes labelRes: Int,
    selectedSha: String?,
    state: ControlState,
    enabled: Boolean,
    onPick: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(SWITCH_NOTE_SPACING)) {
        Text(stringResource(labelRes), style = MaterialTheme.typography.titleSmall)
        PickRow(stringResource(R.string.model_active_built_in, state.builtInInputSize), selectedSha == null, enabled) { onPick(null) }
        for (item in state.library) {
            val text = stringResource(R.string.model_library_item, item.displayName, item.inputText)
            PickRow(text, selectedSha == item.sha256, enabled && !item.blocked) { onPick(item.sha256) }
        }
    }
}

@Composable
private fun PickRow(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ITEM_SPACING),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** The library: one row per model with its size and a Delete button, or a sentence when it is empty. */
@Composable
private fun LibraryList(
    state: ControlState,
    enabled: Boolean,
    onDeleteModelClick: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(SWITCH_NOTE_SPACING)) {
        Text(stringResource(R.string.model_library_title), style = MaterialTheme.typography.titleSmall)
        if (state.library.isEmpty()) {
            Text(stringResource(R.string.model_library_empty), style = MaterialTheme.typography.bodySmall)
        }
        for (item in state.library) {
            val itemRes = if (item.blocked) R.string.model_library_item_blocked else R.string.model_library_item
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ITEM_SPACING)) {
                Text(
                    text = stringResource(itemRes, item.displayName, item.inputText),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { onDeleteModelClick(item.sha256) }, enabled = enabled) {
                    Text(stringResource(R.string.model_delete))
                }
            }
        }
    }
}

@Composable
private fun activeModelLine(state: ControlState): String =
    when (val model = state.activeModel) {
        ActiveModel.BuiltIn -> stringResource(R.string.model_active_built_in, state.builtInInputSize)
        is ActiveModel.Imported -> stringResource(R.string.model_active_imported, model.displayName, model.inputText)
    }

@Composable
private fun ImportResultText(result: ImportSummary) {
    when (result) {
        is ImportSummary.Accepted -> {
            val sentence =
                if (result.duplicate) {
                    R.string.model_result_already_in_library
                } else {
                    when (result.slot) {
                        ModelOrientation.PORTRAIT -> R.string.model_result_added_portrait
                        ModelOrientation.LANDSCAPE -> R.string.model_result_added_landscape
                        null -> R.string.model_result_added_library
                    }
                }
            Text(stringResource(sentence), style = MaterialTheme.typography.bodyMedium)
        }

        is ImportSummary.Rejected -> {
            val sentence =
                if (result.reason == ModelRejectionReason.INPUT_ASPECT) {
                    stringResource(
                        ControlModelMapper.messageRes(result.reason),
                        ControlModelMapper.aspectLimitText(TrackerConfig().importMaxInputAspect),
                    )
                } else if (result.reason == ModelRejectionReason.OUTPUT_ROWS) {
                    stringResource(
                        ControlModelMapper.messageRes(result.reason),
                        TrackerConfig.IMPORT_MAX_OUTPUT_ROWS,
                        TrackerConfig.IMPORT_MAX_RAW_OUTPUT_ROWS,
                    )
                } else {
                    stringResource(ControlModelMapper.messageRes(result.reason))
                }
            Text(sentence, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            DetailsLine(result.details)
        }

        is ImportSummary.Failed -> {
            val text =
                if (result.kind == ImportFailure.IO) {
                    stringResource(R.string.model_result_failed, result.message.orEmpty())
                } else {
                    stringResource(ControlModelMapper.failureRes(result.kind))
                }
            Text(
                text = text,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** The technical detail line (shapes found, the runtime's message); nothing when there is none. */
@Composable
private fun DetailsLine(details: String?) {
    if (details.isNullOrEmpty()) return
    Text(stringResource(R.string.model_details, details), style = MaterialTheme.typography.bodySmall)
}

@Preview
@Composable
private fun ControlScreenPreview() {
    MaterialTheme {
        ControlScreen(
            state =
                ControlState(
                    notice = ControlNotice.OVERLAY_PERMISSION_NEEDED,
                    activeModel = ActiveModel.Imported("yolo26n-pose_640.tflite", "640"),
                    importResult = ImportSummary.Rejected(ModelRejectionReason.INPUT_NHWC, "input [1,640,640,3] float32"),
                ),
            buildLabel = "v0.1.1 · local",
            onStartClick = {},
            onStopClick = {},
            onOpenOverlaySettingsClick = {},
            onImportClick = {},
            onRemoveAllClick = {},
            onDeleteModelClick = {},
            onSlotPick = { _, _ -> },
            onDebugMarksChange = {},
        )
    }
}
