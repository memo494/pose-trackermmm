package com.memo494.posetracker.modelimport

import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.modelimport.ImportCheck
import com.memo494.posetracker.core.modelimport.ImportGuard
import com.memo494.posetracker.core.modelimport.ImportGuardState
import com.memo494.posetracker.core.modelimport.ImportRejection
import com.memo494.posetracker.core.modelimport.ImportStage
import com.memo494.posetracker.core.modelimport.ImportedModelMeta
import com.memo494.posetracker.core.modelimport.ImportedModelRules
import com.memo494.posetracker.core.modelimport.ModelFileSniffer
import com.memo494.posetracker.core.modelimport.SniffRejection
import com.memo494.posetracker.core.modelimport.SniffResult
import com.memo494.posetracker.detector.ModelProbe
import com.memo494.posetracker.detector.ProbeResult
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Why a picked file was refused; the control screen turns each case into text without parsing strings. */
sealed interface RejectionKind {
    /** The cheap file checks failed: [reason] says too small, too large, header too short or not TFLite. */
    data class Sniffed(
        val reason: SniffRejection,
    ) : RejectionKind

    /** LiteRT could not open or read the file; the probe's message is the `details` of the outcome. */
    object Unreadable : RejectionKind {
        override fun toString(): String = "Unreadable"
    }

    /** LiteRT read the model but it does not fit the accepted layout; `details` names the shapes found. */
    data class Layout(
        val reason: ImportRejection,
    ) : RejectionKind
}

/** Result of [ModelImporter.import]. */
sealed interface ImportOutcome {
    /** The model passed every check and is now the stored imported model; [meta] describes it. */
    data class Accepted(
        val meta: ImportedModelMeta,
    ) : ImportOutcome

    /**
     * The file was refused; the previously imported model (if any) is untouched.
     *
     * @property kind which check refused it.
     * @property details facts for the message: the size, the probe's message or the shapes found; never empty.
     */
    data class Rejected(
        val kind: RejectionKind,
        val details: String,
    ) : ImportOutcome

    /**
     * The import did not run to a verdict (not a verdict on the file); the previous model is untouched.
     *
     * @property kind what happened, so the screen can pick a text without parsing [message].
     * @property message a short technical line for [FailureKind.IO] (exception name and message), `null` for the
     *   kinds whose [kind] says everything.
     */
    data class Failed(
        val kind: FailureKind,
        val message: String? = null,
    ) : ImportOutcome
}

/** Why an import failed without a verdict on the file. */
enum class FailureKind {
    /** Another import or model operation is running (single flight, or a crash marker still pending). */
    BUSY,

    /** The crash marker could not be saved, so the risky step was not taken (or its result is not safe to keep). */
    GUARD_NOT_STORED,

    /** An I/O or other unexpected error; the message names it. */
    IO,
}

/**
 * Imports a picked model file (M0c D1 to D4): copy with hash and size cap, cheap sniff, guarded probe through
 * LiteRT, layout rules, then store. Each step fails closed with its own [ImportOutcome]; only an accept changes
 * what is stored (a refused import never touches the previous model or a `Tripped` guard).
 *
 * Crash marker: from `Idle` the probe stores `Pending(PROBE)` before LiteRT opens the copy and `Idle` after it.
 * From a `Tripped` guard (any stage) it writes nothing before or after the probe: the stored trip stays as it is
 * for the whole probe, so a process death there still reads as the old trip (a model switched off by a load
 * crash stays off). A `Pending` guard of another operation answers `Failed(BUSY)`.
 *
 * Threading: [import] reads, hashes and probes a file, so it must run on a worker thread and throws
 * [IllegalStateException] on the main thread. It never throws otherwise: every `Throwable` (`OutOfMemoryError`
 * included) becomes [ImportOutcome.Failed].
 *
 * Single flight: one import runs per process (a flag shared by every instance, [isImporting]). A second [import]
 * while one runs returns `Failed(BUSY)` at once and touches nothing on disk (in particular not the temporary file
 * the first one is filling). The flag is cleared on every way out, including exceptions.
 *
 * @param threadCheck throws [IllegalStateException] on the main thread; the production default needs a real
 *   `Looper`, tests pass a fake.
 * @param probe reads the tensors of a file; the real one is `ModelProbe.probe`, tests pass a fake.
 */
class ModelImporter(
    private val store: ImportedModelStore,
    private val config: TrackerConfig = TrackerConfig(),
    private val threadCheck: () -> Unit = { WorkerThreadCheck.require() },
    private val probe: (File) -> ProbeResult = ModelProbe::probe,
) {
    /**
     * @param source opens the picked file's stream (called once; closed here). A provider that throws on open
     *   gives [ImportOutcome.Failed].
     * @param rawDisplayName the provider's file name, if any; becomes `ImportedModelMeta.displayName` via
     *   `sanitiseName`.
     * @param declaredBytes the size the provider claims, if any. Untrusted: it is used only to refuse early a file
     *   declared above the cap; the real size is counted during the copy, which stops at the cap.
     */
    fun import(
        source: () -> InputStream,
        rawDisplayName: String?,
        declaredBytes: Long?,
    ): ImportOutcome {
        threadCheck()
        if (!RUNNING.compareAndSet(false, true)) return ImportOutcome.Failed(FailureKind.BUSY)
        try {
            return importExclusively(source, rawDisplayName, declaredBytes)
        } finally {
            RUNNING.set(false)
        }
    }

    private fun importExclusively(
        source: () -> InputStream,
        rawDisplayName: String?,
        declaredBytes: Long?,
    ): ImportOutcome {
        if (declaredBytes != null && declaredBytes > config.importMaxBytes) return tooLarge("declared $declaredBytes bytes")
        val temp =
            try {
                store.newTempFile()
            } catch (t: Throwable) {
                return failed(t)
            }
        val outcome =
            try {
                importInto(temp, source, rawDisplayName)
            } catch (t: Throwable) {
                failed(t)
            }
        if (outcome !is ImportOutcome.Accepted) temp.delete()
        return outcome
    }

    private fun importInto(
        temp: File,
        source: () -> InputStream,
        rawDisplayName: String?,
    ): ImportOutcome {
        val copy =
            when (val copied = copyToTemp(source, temp)) {
                is CopyResult.TooLarge -> {
                    return tooLarge("more than ${config.importMaxBytes} bytes")
                }

                is CopyResult.NotTflite -> {
                    return ImportOutcome.Rejected(
                        RejectionKind.Sniffed(SniffRejection.NOT_TFLITE),
                        "stopped after ${copied.bytesRead} bytes",
                    )
                }

                is CopyResult.Copied -> {
                    copied
                }
            }
        val sniff = ModelFileSniffer.looksLikeTflite(copy.header, copy.bytes, config)
        if (sniff is SniffResult.Rejected) {
            return ImportOutcome.Rejected(RejectionKind.Sniffed(sniff.reason), "${copy.bytes} bytes")
        }

        val initialGuard = store.state().guard
        if (initialGuard is ImportGuardState.Pending) return ImportOutcome.Failed(FailureKind.BUSY)
        // A stored trip stays untouched for the whole probe: replacing it by the probe marker would let a process
        // death in the probe read as a trip at the probe, which keeps a stored model that a load crash switched off.
        // A refusal or a catchable failure is not a crash, and a trip is only lifted by an accept (in the store).
        val markerOwned = initialGuard !is ImportGuardState.Tripped
        if (markerOwned && !store.setGuard(ImportGuard.begin(ImportStage.PROBE))) {
            return ImportOutcome.Failed(FailureKind.GUARD_NOT_STORED)
        }
        val probed = runProbe(temp)
        if (markerOwned && !store.setGuard(ImportGuard.succeed())) return ImportOutcome.Failed(FailureKind.GUARD_NOT_STORED)

        val tensors =
            when (probed) {
                is ProbeResult.Failed -> return ImportOutcome.Rejected(RejectionKind.Unreadable, probed.message)
                is ProbeResult.Tensors -> probed
            }
        return when (val check = ImportedModelRules.check(tensors.inputs, tensors.outputs, config)) {
            is ImportCheck.Rejected -> {
                ImportOutcome.Rejected(RejectionKind.Layout(check.reason), check.details)
            }

            is ImportCheck.Accepted -> {
                val meta =
                    ImportedModelMeta(
                        displayName = ImportedModelMeta.sanitiseName(rawDisplayName),
                        sha256 = copy.sha256,
                        bytes = copy.bytes,
                        inputWidth = check.inputWidth,
                        inputHeight = check.inputHeight,
                        outputRows = check.outputRows,
                    )
                accept(temp, meta)
            }
        }
    }

    private fun accept(
        temp: File,
        meta: ImportedModelMeta,
    ): ImportOutcome {
        // A model the rules accept but the stored form cannot describe is not stored (fail closed; the rules and
        // the stored form share their limits, so this is a guard against them drifting apart).
        if (ImportedModelMeta.decode(meta.encode()) != meta) {
            return ImportOutcome.Failed(FailureKind.IO, "The model description cannot be stored")
        }
        // The store also lifts a trip (the new file replaces the one that tripped the guard), under the same lock.
        if (!store.accept(temp, meta)) return ImportOutcome.Failed(FailureKind.IO, "The model could not be stored")
        return ImportOutcome.Accepted(meta)
    }

    /** A probe that throws is a catchable failure (no crash happened), reported like a failed probe. */
    private fun runProbe(temp: File): ProbeResult =
        try {
            probe(temp)
        } catch (t: Throwable) {
            ProbeResult.Failed(describe(t))
        }

    /**
     * Copies the stream into [temp] while hashing and counting. It stops early, without reading the rest of the
     * stream, with [CopyResult.TooLarge] as soon as the count passes the cap, and with [CopyResult.NotTflite] as
     * soon as the size is at least the minimum and the first 8 bytes are not a TFLite header (a photo or a text
     * file is refused after one buffer, not after a full copy). Below the minimum size the final sniff answers
     * "too small" first, so the early look waits for it.
     */
    private fun copyToTemp(
        source: () -> InputStream,
        temp: File,
    ): CopyResult {
        val digest = MessageDigest.getInstance(HASH_ALGORITHM)
        val buffer = ByteArray(TrackerConfig.MODEL_IO_BUFFER_BYTES)
        val header = ByteArray(ModelFileSniffer.HEADER_BYTES)
        var headerFilled = 0
        var total = 0L
        source().use { input ->
            FileOutputStream(temp).use { output ->
                var count = input.read(buffer)
                while (count >= 0) {
                    total += count
                    if (total > config.importMaxBytes) return CopyResult.TooLarge
                    val take = minOf(count, header.size - headerFilled)
                    if (take > 0) {
                        System.arraycopy(buffer, 0, header, headerFilled, take)
                        headerFilled += take
                    }
                    if (headerFilled == header.size && isNotTflite(header, total)) return CopyResult.NotTflite(total)
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                    count = input.read(buffer)
                }
                output.fd.sync()
            }
        }
        return CopyResult.Copied(total, toHex(digest.digest()), header.copyOf(headerFilled))
    }

    private fun isNotTflite(
        header: ByteArray,
        bytesSoFar: Long,
    ): Boolean = ModelFileSniffer.looksLikeTflite(header, bytesSoFar, config) == SniffResult.Rejected(SniffRejection.NOT_TFLITE)

    private sealed interface CopyResult {
        /** The stream is longer than the cap. */
        object TooLarge : CopyResult

        /** The header is not TFLite; [bytesRead] bytes were read before the copy stopped. */
        class NotTflite(
            val bytesRead: Long,
        ) : CopyResult

        /** The whole stream was copied. */
        class Copied(
            val bytes: Long,
            val sha256: String,
            val header: ByteArray,
        ) : CopyResult
    }

    private fun tooLarge(details: String): ImportOutcome =
        ImportOutcome.Rejected(RejectionKind.Sniffed(SniffRejection.TOO_LARGE), "$details, limit ${config.importMaxBytes} bytes")

    private fun failed(t: Throwable): ImportOutcome = ImportOutcome.Failed(FailureKind.IO, describe(t))

    private fun describe(t: Throwable): String = t.javaClass.simpleName + (t.message?.let { ": $it" } ?: "")

    private fun toHex(digest: ByteArray): String = BigInteger(1, digest).toString(HEX_RADIX).padStart(TrackerConfig.SHA256_HEX_LENGTH, '0')

    companion object {
        private const val HASH_ALGORITHM = "SHA-256"
        private const val HEX_RADIX = 16

        /** Set while an [import] runs; shared by every instance, so it also covers a recreated activity. */
        private val RUNNING = AtomicBoolean(false)

        /** Whether an [import] is running in this process now; safe to call from any thread. */
        fun isImporting(): Boolean = RUNNING.get()
    }
}
