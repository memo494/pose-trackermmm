package com.memo494.posetracker.core.modelimport

import com.memo494.posetracker.core.TrackerConfig

/** The text form of a model's SHA-256 (the key of the model library): 64 lowercase hex characters. */
internal object ModelSha {
    /** Whether [text] is a SHA-256 in the stored form. */
    fun isValid(text: String): Boolean = text.length == TrackerConfig.SHA256_HEX_LENGTH && text.all { it in '0'..'9' || it in 'a'..'f' }
}
