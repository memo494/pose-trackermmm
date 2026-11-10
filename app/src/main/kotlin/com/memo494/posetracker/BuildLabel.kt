package com.memo494.posetracker

/** Formats the version line shown in the debug HUD (ARCHITECTURE §1 — "version + git SHA"). */
object BuildLabel {
    fun line(
        versionName: String,
        gitSha: String,
    ): String = "v$versionName · $gitSha"
}
