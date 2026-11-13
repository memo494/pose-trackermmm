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

    /** An I/O or other unexpected error (not a verdict on the file); the previous model is untouched. */
    data class Failed(
        val message: String,
    ) : ImportOutcome
}

/**
 * Imports a picked model file (M0c D1 to D4): copy with hash and size cap, cheap sniff, guarded probe through
 * LiteRT, layout rules, then store. Each step fails closed with its own [ImportOutcome]; only an accept changes
 * what is stored (a refused import never touches the previous model or a `Tripped` guard).
 *
 * Threading: [import] reads, hashes and probes a file, so it must run on a worker thread and throws
 * [IllegalStateException] on the main thread. It never throws otherwise: every `Throwable` (`OutOfMemoryError`
 * included) becomes [ImportOutcome.Failed].
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
        val copy = copyToTemp(source, temp) ?: return tooLarge("more than ${config.importMaxBytes} bytes")
        val sniff = ModelFileSniffer.looksLikeTflite(copy.header, copy.bytes, config)
        if (sniff is SniffResult.Rejected) {
            return ImportOutcome.Rejected(RejectionKind.Sniffed(sniff.reason), "${copy.bytes} bytes")
        }

        val initialGuard = store.state().guard
        if (initialGuard is ImportGuardState.Pending) return ImportOutcome.Failed(BUSY)
        if (!store.setGuard(ImportGuard.begin(ImportStage.PROBE))) return ImportOutcome.Failed(GUARD_NOT_STORED)
        val probed = runProbe(temp)
        // A refusal or a catchable failure is not a crash: the marker goes back, but a trip is only lifted by an accept.
        val afterProbe = if (initialGuard is ImportGuardState.Tripped) initialGuard else ImportGuard.succeed()
        if (!store.setGuard(afterProbe)) return ImportOutcome.Failed(GUARD_NOT_STORED)

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
                        inputSize = check.inputSize,
                        outputRows = check.outputRows,
                    )
                accept(temp, meta, afterProbe)
            }
        }
    }

    private fun accept(
        temp: File,
        meta: ImportedModelMeta,
        guard: ImportGuardState,
    ): ImportOutcome {
        // A model the rules accept but the stored form cannot describe (for example absurdly many rows) is not stored.
        if (ImportedModelMeta.decode(meta.encode()) != meta) return ImportOutcome.Failed("The model description cannot be stored")
        if (!store.accept(temp, meta)) return ImportOutcome.Failed("The model could not be stored")
        // The new file replaces the one that tripped the guard, so the trip ends here.
        if (guard != ImportGuardState.Idle && !store.setGuard(ImportGuard.clear())) {
            return ImportOutcome.Failed(GUARD_NOT_STORED)
        }
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
     * Copies the stream into [temp] while hashing and counting; `null` as soon as the count passes the cap
     * (the rest of the stream is not read).
     */
    private fun copyToTemp(
        source: () -> InputStream,
        temp: File,
    ): Copied? {
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
                    if (total > config.importMaxBytes) return null
                    val take = minOf(count, header.size - headerFilled)
                    if (take > 0) {
                        System.arraycopy(buffer, 0, header, headerFilled, take)
                        headerFilled += take
                    }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                    count = input.read(buffer)
                }
                output.fd.sync()
            }
        }
        return Copied(total, toHex(digest.digest()), header.copyOf(headerFilled))
    }

    private class Copied(
        val bytes: Long,
        val sha256: String,
        val header: ByteArray,
    )

    private fun tooLarge(details: String): ImportOutcome =
        ImportOutcome.Rejected(RejectionKind.Sniffed(SniffRejection.TOO_LARGE), "$details, limit ${config.importMaxBytes} bytes")

    private fun failed(t: Throwable): ImportOutcome = ImportOutcome.Failed(describe(t))

    private fun describe(t: Throwable): String = t.javaClass.simpleName + (t.message?.let { ": $it" } ?: "")

    private fun toHex(digest: ByteArray): String = BigInteger(1, digest).toString(HEX_RADIX).padStart(TrackerConfig.SHA256_HEX_LENGTH, '0')

    private companion object {
        const val HASH_ALGORITHM = "SHA-256"
        const val HEX_RADIX = 16
        const val BUSY = "Another model operation is running"
        const val GUARD_NOT_STORED = "The crash marker could not be stored"
    }
}
