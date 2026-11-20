package com.memo494.posetracker.core.modelimport

/**
 * The models that crashed LiteRT while loading (M1b-1): their SHA-256 keys, so only those models are switched off
 * and the others keep working. Pure codec of the stored text: the sha256 values joined by `,`, sorted, with no
 * header; empty text is the empty set. [decode] keeps every valid value and drops anything else (a dropped value
 * could never equal a model's sha256, so it never unblocks a model).
 */
object ModelBlocklist {
    private const val SEPARATOR = ","

    /** The stored text of [shas] (only valid sha256 values are written). */
    fun encode(shas: Set<String>): String = shas.filter(ModelSha::isValid).sorted().joinToString(SEPARATOR)

    /** The set stored as [text]; `null` or empty text is the empty set. Never throws. */
    fun decode(text: String?): Set<String> {
        if (text.isNullOrEmpty()) return emptySet()
        return text.split(SEPARATOR).filter(ModelSha::isValid).toSet()
    }
}
