package com.memo494.posetracker.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.memo494.posetracker.R

private val ITEM_SPACING = 8.dp

/**
 * The notice shown when the previous run ended with an uncaught exception: what happened, Share
 * diagnostics and Dismiss. Shows nothing when [DiagnosticsUiState.crashNotice] is `false`. Stateless.
 */
@Composable
internal fun CrashNotice(
    state: DiagnosticsUiState,
    onShareClick: () -> Unit,
    onDismissClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.crashNotice) {
        Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ITEM_SPACING)) {
            Text(
                text = stringResource(R.string.diagnostics_crash_notice),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(ITEM_SPACING)) {
                Button(onClick = onShareClick, enabled = !state.sharing) {
                    Text(stringResource(R.string.diagnostics_share))
                }
                OutlinedButton(onClick = onDismissClick) {
                    Text(stringResource(R.string.diagnostics_dismiss))
                }
            }
        }
    }
}

/**
 * Share diagnostics, the short failure notice, and the log with its Show or Hide and Refresh buttons.
 * Stateless: the log text is refreshed only by the callbacks, there is no polling.
 */
@Composable
internal fun DiagnosticsSection(
    state: DiagnosticsUiState,
    onShareClick: () -> Unit,
    onToggleLogClick: () -> Unit,
    onRefreshLogClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ITEM_SPACING)) {
        Button(onClick = onShareClick, enabled = !state.sharing, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.diagnostics_share))
        }
        val notice = state.notice
        if (notice != null) {
            val noticeRes =
                when (notice) {
                    DiagnosticsNotice.PREPARE_FAILED -> R.string.diagnostics_notice_prepare_failed
                    DiagnosticsNotice.SHARE_FAILED -> R.string.diagnostics_notice_share_failed
                }
            Text(
                text = stringResource(noticeRes),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ITEM_SPACING)) {
            val toggleRes = if (state.logVisible) R.string.diagnostics_log_hide else R.string.diagnostics_log_show
            OutlinedButton(onClick = onToggleLogClick) {
                Text(stringResource(toggleRes))
            }
            if (state.logVisible) {
                OutlinedButton(onClick = onRefreshLogClick) {
                    Text(stringResource(R.string.diagnostics_log_refresh))
                }
            }
        }
        if (state.logVisible) {
            val logText = state.logText.ifEmpty { stringResource(R.string.diagnostics_log_empty) }
            SelectionContainer {
                Text(
                    text = logText,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
