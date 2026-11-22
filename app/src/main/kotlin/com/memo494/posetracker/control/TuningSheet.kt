package com.memo494.posetracker.control

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.memo494.posetracker.R
import com.memo494.posetracker.core.tuning.TuningGroup
import com.memo494.posetracker.core.tuning.TuningKey
import com.memo494.posetracker.core.tuning.TuningOverrides
import com.memo494.posetracker.tuning.TuningRow
import com.memo494.posetracker.tuning.TuningSheetMapper

private val ROW_SPACING = 4.dp
private val SHEET_SPACING = 12.dp

/**
 * The tuning sheet (ARCHITECTURE §7.1): one slider per [TuningKey], grouped, with the value, the default, a note that
 * changes apply on the next Start, and "Reset to defaults". Stateless except for open or closed. The rows come from
 * [TuningSheetMapper], so a new [TuningKey] shows up here without a change.
 */
@Composable
internal fun TuningSheet(
    overrides: TuningOverrides,
    onChange: (TuningKey, Float) -> Unit,
    onCommit: () -> Unit,
    onReset: () -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(SHEET_SPACING)) {
        OutlinedButton(onClick = { open = !open }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.tuning_title) + if (overrides.count > 0) " (${overrides.count})" else "")
        }
        if (open) {
            Text(stringResource(R.string.tuning_note), style = MaterialTheme.typography.bodySmall)
            val rows = TuningSheetMapper.rows(overrides)
            for (group in TuningGroup.entries) {
                Text(stringResource(groupTitle(group)), style = MaterialTheme.typography.titleSmall)
                if (group == TuningGroup.LEAD) {
                    Text(stringResource(R.string.tuning_lead_caption), style = MaterialTheme.typography.bodySmall)
                }
                if (TuningSheetMapper.hasSafetyWarning(group)) {
                    Text(
                        text = stringResource(R.string.tuning_lock_warning),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                for (row in rows.filter { it.key.group == group }) TuningRowView(row, onChange, onCommit)
            }
            OutlinedButton(onClick = onReset, enabled = overrides.count > 0, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.tuning_reset))
            }
        }
    }
}

@StringRes
private fun groupTitle(group: TuningGroup): Int =
    when (group) {
        TuningGroup.DETECTION -> R.string.tuning_group_detection
        TuningGroup.SMOOTHING -> R.string.tuning_group_smoothing
        TuningGroup.TRACKING -> R.string.tuning_group_tracking
        TuningGroup.LOCK -> R.string.tuning_group_lock
        TuningGroup.LEAD -> R.string.tuning_group_lead
    }

@Composable
private fun TuningRowView(
    row: TuningRow,
    onChange: (TuningKey, Float) -> Unit,
    onCommit: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ROW_SPACING)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(row.key.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            val color = if (row.overridden) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            Text(row.valueText, style = MaterialTheme.typography.bodyMedium, color = color)
        }
        Slider(
            value = row.sliderValue,
            onValueChange = { onChange(row.key, TuningSheetMapper.snap(row.key, it)) },
            onValueChangeFinished = onCommit,
            valueRange = row.key.min..row.key.max,
        )
        Text(row.defaultText, style = MaterialTheme.typography.bodySmall)
    }
}
