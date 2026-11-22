package com.memo494.posetracker.modelimport

import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.model.ModelInputText

/** The one log line of an accepted import (M1b-fix-import), so a report shows what an import did to the library. */
internal object AcceptedImportLog {
    /**
     * `model import accepted: <name> sha256=<first 12> input=<WxH> new|duplicate slot=portrait|landscape|none library=<count>`.
     * Pure; the caller writes it to the log once per accepted import.
     */
    fun line(accepted: ImportOutcome.Accepted): String {
        val meta = accepted.meta
        val shortSha = meta.sha256.take(TrackerConfig.SHA256_SHORT_CHARS)
        val input = ModelInputText.text(meta.inputWidth, meta.inputHeight)
        val kind = if (accepted.duplicate) "duplicate" else "new"
        val slot = accepted.assignedSlot?.logName ?: "none"
        return "model import accepted: ${meta.displayName} sha256=$shortSha input=$input $kind slot=$slot library=${accepted.libraryCount}"
    }

    /**
     * Writes [line] through [log] once when [outcome] is accepted (new or duplicate) and writes nothing for a
     * rejection or a failure. Returns [outcome] unchanged, so the caller can call it in the middle of an expression.
     */
    fun logIfAccepted(
        outcome: ImportOutcome,
        log: (String) -> Unit,
    ): ImportOutcome {
        if (outcome is ImportOutcome.Accepted) log(line(outcome))
        return outcome
    }
}
