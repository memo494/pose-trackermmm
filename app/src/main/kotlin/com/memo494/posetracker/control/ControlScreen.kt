package com.memo494.posetracker.control

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.memo494.posetracker.R

private val SCREEN_PADDING = 24.dp
private val ITEM_SPACING = 16.dp

/**
 * The control screen: status, the build label, Start or Stop, and the way to the overlay permission.
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
                enabled = !state.awaitingConsent,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(primaryRes))
            }

            if (!state.overlayPermission) {
                OutlinedButton(onClick = onOpenOverlaySettingsClick, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.control_allow_overlay))
                }
            }

            Text(stringResource(R.string.control_description), style = MaterialTheme.typography.bodyMedium)
            diagnosticsContent()
        }
    }
}

@Preview
@Composable
private fun ControlScreenPreview() {
    MaterialTheme {
        ControlScreen(
            state = ControlState(notice = ControlNotice.OVERLAY_PERMISSION_NEEDED),
            buildLabel = "v0.1.1 · local",
            onStartClick = {},
            onStopClick = {},
            onOpenOverlaySettingsClick = {},
        )
    }
}
