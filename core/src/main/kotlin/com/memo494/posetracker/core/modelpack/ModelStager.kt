package com.memo494.posetracker.core.modelpack

import com.memo494.posetracker.core.TrackerConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The bytes of a model file do not hash to the expected SHA-256 (a corrupt or tampered file). */
class ModelIntegrityException(
    message: String,
) : IOException(message)

/**
 * Copies a model file out of the app's assets into a real file, once, at start.
 *
 * Why: LiteRT maps a model file into memory. `CompiledModel.create(assets, ...)` would instead keep a
 * copy of the whole model in the native heap, so the model is staged to [directory] (the app passes
 * `noBackupFilesDir`) and loaded by path. The staged name carries the first
 * [TrackerConfig.STAGED_MODEL_SHA_PREFIX_LENGTH] characters of the SHA-256, so a new model version never
 * collides with an old file, and the file is only trusted after its hash was checked.
 *
 * Not thread-safe: call it on the capture thread when the service starts, never per frame (it reads
 * and hashes a model of several megabytes).
 *
 * @property directory where staged files live; created on the first [stage] if it is missing.
 */
class ModelStager(
    private val directory: File,
) {
    /**
     * Returns the staged file `<directory>/<base>-<sha prefix>.<ext>` for [fileName], whose content is
     * guaranteed to hash to [expectedSha256].
     *
     * An existing staged file is hashed: when it matches it is returned and [open] is not called; when it
     * does not, it is deleted and staged again. Staging copies the stream from [open] (always closed)
     * to a `.tmp` file next to the target while hashing, then atomically moves it over the target. On any
     * failure neither the `.tmp` file nor the target is left behind. After a successful stage or reuse,
     * older versions of the same model (`<base>-<prefix>.<ext>`) and stale `.tmp` files are deleted on a
     * best-effort basis; the current file and the files of other models are never touched.
     *
     * @param fileName bare name of the model file, e.g. `yolo26n-pose_416.tflite`.
     * @param expectedSha256 SHA-256 of the file, 64 lowercase hex characters.
     * @param open opens a fresh stream over the source bytes (for example `assets.open(fileName)`).
     * @throws IllegalArgumentException when [fileName] or [expectedSha256] is invalid (nothing is touched).
     * @throws ModelIntegrityException when the source does not hash to [expectedSha256].
     * @throws IOException when the directory cannot be created or the copy fails.
     */
    fun stage(
        fileName: String,
        expectedSha256: String,
        open: () -> InputStream,
    ): File {
        ModelFileRules.requireFileName(fileName)
        ModelFileRules.requireSha256(expectedSha256)
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("cannot create the model directory $directory")
        }
        val base = ModelFileRules.baseOf(fileName)
        val extension = ModelFileRules.extensionOf(fileName)
        val target = File(directory, stagedName(base, extension, expectedSha256))
        if (!(target.exists() && hashOrNull(target) == expectedSha256)) {
            Files.deleteIfExists(target.toPath())
            copyVerified(fileName, expectedSha256, open, target)
        }
        deleteOtherVersions(base, extension, target)
        return target
    }

    private fun stagedName(
        base: String,
        extension: String,
        sha256: String,
    ): String = "$base-${sha256.take(TrackerConfig.STAGED_MODEL_SHA_PREFIX_LENGTH)}.$extension"

    /** The SHA-256 of [file], or null when it cannot be read (then it counts as not matching). */
    private fun hashOrNull(file: File): String? =
        try {
            file.inputStream().use { Sha256.hex(it) }
        } catch (e: IOException) {
            null
        }

    private fun copyVerified(
        fileName: String,
        expectedSha256: String,
        open: () -> InputStream,
        target: File,
    ) {
        val tmp = File(directory, target.name + TMP_SUFFIX)
        var moved = false
        try {
            val actualSha256 = copyAndHash(open, tmp)
            if (actualSha256 != expectedSha256) {
                throw ModelIntegrityException(
                    "model file $fileName is corrupt: expected sha256 $expectedSha256 but got $actualSha256",
                )
            }
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            moved = true
        } finally {
            if (!moved) tmp.delete()
        }
    }

    /** Copies the stream from [open] into [tmp], flushed to disk, and returns the SHA-256 of what was copied. */
    private fun copyAndHash(
        open: () -> InputStream,
        tmp: File,
    ): String {
        val digest = Sha256.newDigest()
        val buffer = ByteArray(TrackerConfig.MODEL_IO_BUFFER_BYTES)
        open().use { input ->
            FileOutputStream(tmp).use { output ->
                var count = input.read(buffer)
                while (count >= 0) {
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                    count = input.read(buffer)
                }
                output.fd.sync()
            }
        }
        return Sha256.toHex(digest.digest())
    }

    /** Best effort: deletes older versions and stale `.tmp` files of the same model, never [current]. */
    private fun deleteOtherVersions(
        base: String,
        extension: String,
        current: File,
    ) {
        val prefix = Regex.escape(base) + "-[0-9a-f]{${TrackerConfig.STAGED_MODEL_SHA_PREFIX_LENGTH}}"
        val suffix = Regex.escape(".$extension")
        val sameModel = Regex("$prefix$suffix(${Regex.escape(TMP_SUFFIX)})?")
        val files = directory.listFiles() ?: return
        for (file in files) {
            if (file.name != current.name && file.isFile && sameModel.matches(file.name)) file.delete()
        }
    }

    private companion object {
        const val TMP_SUFFIX = ".tmp"
    }
}
