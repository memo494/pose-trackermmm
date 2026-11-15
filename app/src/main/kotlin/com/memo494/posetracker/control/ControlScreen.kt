package com.memo494.posetracker.control

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
    onUseBuiltInClick: () -> Unit,
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

            ModelBlock(state, onImportClick, onUseBuiltInClick)

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
 * The model block: the active model, Import and Use built-in, the progress line, the last result, the notice and
 * the trust hint. Import and reset are disabled while the capture runs or starts and while an import runs.
 */
@Composable
private fun ModelBlock(
    state: ControlState,
    onImportClick: () -> Unit,
    onUseBuiltInClick: () -> Unit,
) {
    val idle = !state.running && !state.awaitingConsent && !state.importing
    Column(verticalArrangement = Arrangement.spacedBy(ITEM_SPACING)) {
        Text(stringResource(R.string.model_title), style = MaterialTheme.typography.titleMedium)
        Text(activeModelLine(state), style = MaterialTheme.typography.bodyLarge)

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

        OutlinedButton(onClick = onImportClick, enabled = idle, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.model_import))
        }
        if (state.activeModel is ActiveModel.Imported || modelNotice != null || state.modelFileStored) {
            OutlinedButton(onClick = onUseBuiltInClick, enabled = idle, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.model_use_built_in))
            }
        }
        Text(stringResource(R.string.model_hint_trust), style = MaterialTheme.typography.bodySmall)
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
            Text(stringResource(R.string.model_result_accepted, result.displayName), style = MaterialTheme.typography.bodyMedium)
        }

        is ImportSummary.Rejected -> {
            val sentence = stringResource(ControlModelMapper.messageRes(result.reason))
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
            onUseBuiltInClick = {},
            onDebugMarksChange = {},
        )
    }
}
