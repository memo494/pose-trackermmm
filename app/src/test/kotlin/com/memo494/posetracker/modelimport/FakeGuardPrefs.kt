package com.memo494.posetracker.modelimport

/** In-memory [GuardPrefs]; [failWrites] makes every write report failure without storing anything. */
internal class FakeGuardPrefs(
    var text: String? = null,
    var failWrites: Boolean = false,
) : GuardPrefs {
    /** Every text written successfully, in order. */
    val writes = mutableListOf<String>()

    override fun read(): String? = text

    override fun write(text: String): Boolean {
        if (failWrites) return false
        this.text = text
        writes.add(text)
        return true
    }
}
