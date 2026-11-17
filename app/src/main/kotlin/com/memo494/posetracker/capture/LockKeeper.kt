package com.memo494.posetracker.capture

import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.capture.CaptureFrame
import com.memo494.posetracker.core.model.PoseDetection
import com.memo494.posetracker.core.relock.AppearanceSignature
import com.memo494.posetracker.core.relock.RelockMatcher
import com.memo494.posetracker.core.relock.TorsoRect
import com.memo494.posetracker.core.relock.TorsoRegion
import com.memo494.posetracker.core.tracking.TrackList
import com.memo494.posetracker.core.tracking.TrackSnapshot
import com.memo494.posetracker.overlay.OverlayState
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * The lock of one capture session and its memory (docs/plans/M4.md, M4b T2 and M4c T2; ARCHITECTURE §6.6): which
 * track is locked, what the locked person's torso looked like, and, once the track is gone, the wait for the person
 * to come back.
 *
 * Threads: [request], [clear] and [hasLock] may be called from any thread (the lock handle, on the UI thread); every
 * other method is for the capture thread only, like [FramePipeline] which owns the one instance.
 *
 * The wish of the user is one [AtomicInteger] (`requestedId`, [OverlayState.NO_LOCK] =
 * none); the capture thread keeps what it has acted on (`heldId`) beside it and learns about a tap on the handle by
 * the two differing. A lock goes through these steps:
 * - **set**: a requested id that is among the shown tracks of a state becomes held (an id that is not shown is
 *   dropped, so an id of an old geometry never reaches a new track); `lock: set id=N`;
 * - **signature**: on every frame in which the held track was matched to a detection (not lost), the colours of the
 *   person's torso ([TorsoRegion]) are averaged into an [AppearanceSignature] (a read of the frame's pixels, so only
 *   inside [onFrame], before the frame's array is reused); a torso that the image edge cut ([TorsoRect.clipped]) or
 *   that is too small is not used. On the same frames the highest track id in the tracker's list is noted
 *   (`maxIdAtLastMatch`): every track with a higher id was born after the person was last seen;
 * - **pending**: when the held track is no longer among the shown tracks (it left, or was lost for too long) and a
 *   signature exists, the lock waits ([RelockMatcher]) for `relockMemoryMs` of frame time; without a signature the
 *   lock simply ends. A pending lock draws nothing ([resolve] says [OverlayState.NO_LOCK]) and [hasLock] stays true,
 *   so a tap on the handle ends it;
 * - **re-lock**: while pending, a candidate is any shown track matched in the frame whose id is above
 *   `maxIdAtLastMatch` (so a person who came back under a new id while the old track was still lost counts too).
 *   Each candidate is judged on each of its first `relockJudgeFrames` matched frames: every shown, matched track is
 *   sampled once per frame (a clipped or too small torso is "unsampled"), the candidate's distance to the signature
 *   and the nearest of the others go to [RelockMatcher.judge], and the lock moves at the first acceptance. A
 *   candidate that is not accepted in its window is counted and logged once, when the window ends. Another track
 *   that cannot be sampled makes every candidate ambiguous (`next=unsampled`: fail closed);
 * - **end**: a tap on the handle (also one that replaces a pending lock with a new one), a reset ([onReset]), the end
 *   of the session ([onStop]) or the end of the memory.
 *
 * The signature is a colour histogram in memory on the capture thread only; it is dropped whenever the lock ends.
 * Allocation-free per frame (two preallocated signatures, one rectangle, small arrays); log lines are built only at
 * the rare lock events.
 *
 * @param config the re-lock numbers.
 * @param log receives one line per lock event (`lock: ...`); may be called from the capture thread only.
 */
internal class LockKeeper(
    private val config: TrackerConfig,
    private val log: (String) -> Unit,
) {
    private val requestedId = AtomicInteger(OverlayState.NO_LOCK)
    private var heldId = OverlayState.NO_LOCK
    private val matcher = RelockMatcher(config)
    private val torsoRegion = TorsoRegion(config)
    private val torso = TorsoRect()
    private val signature = AppearanceSignature(config)
    private val sampleSignature = AppearanceSignature(config)
    private var lastFrameNs = 0L

    /** Highest track id in the tracker's list the last time the held track was matched; candidates have a higher id. */
    private var maxIdAtLastMatch = 0

    /** Per list index of the newest frame: how [sampleAll] found the track (see the `STATUS_` constants) and its distance. */
    private val sampleStatus = IntArray(config.maxTracks)
    private val sampleDistance = FloatArray(config.maxTracks)

    /** The candidates seen while pending, in slots `0 until candidateCount`: id, matched frames judged, and the newest verdict. */
    private val candidateId = IntArray(config.maxTracks)
    private val candidateFrames = IntArray(config.maxTracks)
    private val candidateDistance = FloatArray(config.maxTracks)
    private val candidateNext = FloatArray(config.maxTracks)
    private val candidateReason = IntArray(config.maxTracks)
    private var candidateCount = 0

    /** Locks set (a tap on a person that found its track) since this keeper began. Capture thread. */
    var lockCount: Int = 0
        private set

    /** Pending locks that moved to a returning person. Capture thread. */
    var relockCount: Int = 0
        private set

    /** New tracks compared with a pending lock and not accepted. Capture thread. */
    var relockRejectCount: Int = 0
        private set

    /** Pending locks whose memory ran out. Capture thread. */
    var expiredCount: Int = 0
        private set

    /** True while a lock is wanted: held, pending, or requested and not yet looked at. Any thread. */
    val hasLock: Boolean
        get() = requestedId.get() != OverlayState.NO_LOCK

    /** Asks to lock the track with [id]; a negative [id] clears the lock. Any thread. */
    fun request(id: Int) {
        requestedId.set(if (id < 0) OverlayState.NO_LOCK else id)
    }

    /** Asks to end the lock, pending or not. Any thread. */
    fun clear() {
        requestedId.set(OverlayState.NO_LOCK)
    }

    /**
     * The locked id for a state whose shown tracks are [tracks]: the held id while its track is among them, else
     * [OverlayState.NO_LOCK] (nothing is drawn). Also moves the lock through its steps (set, pending, end by the
     * handle), so every state that is published must pass here. Idempotent for one [tracks]. Capture thread.
     */
    fun resolve(tracks: TrackSnapshot): Int {
        val id = requestedId.get()
        if (id == OverlayState.NO_LOCK) {
            if (heldId != OverlayState.NO_LOCK) end(REASON_HANDLE)
            return OverlayState.NO_LOCK
        }
        if (id != heldId) {
            if (heldId != OverlayState.NO_LOCK) end(if (matcher.isPending) REASON_REPLACED else REASON_HANDLE)
            if (tracks.indexOfId(id) < 0) {
                requestedId.compareAndSet(id, OverlayState.NO_LOCK)
                return OverlayState.NO_LOCK
            }
            begin(id)
            return id
        }
        if (tracks.indexOfId(id) >= 0) return id
        if (matcher.isPending) return OverlayState.NO_LOCK
        if (signature.hasData) {
            matcher.startPending(lastFrameNs)
            log("lock: pending id=$id")
        } else {
            end(REASON_REMOVED)
        }
        return OverlayState.NO_LOCK
    }

    /**
     * One tracked frame, right after `Tracker.update` and the snapshot taken from it: [list] is the tracker's view
     * (valid until its next call), [tracks] the snapshot of the same call, [detections] the list the tracker was
     * given and [frame] the pixels the detector read (still intact). Updates the signature of the held track, or
     * judges the candidates against it while the lock is pending; ends a pending lock whose memory ran out. Capture
     * thread, once per detected frame.
     */
    fun onFrame(
        list: TrackList,
        tracks: TrackSnapshot,
        detections: List<PoseDetection>,
        frame: CaptureFrame,
    ) {
        lastFrameNs = frame.timestampNs
        resolve(tracks)
        if (matcher.isPending) {
            if (matcher.isExpired(lastFrameNs)) expire() else tryRelock(list, detections, frame)
        } else if (heldId != OverlayState.NO_LOCK) {
            followHeld(list, detections, frame)
        }
    }

    /** The tracker was reset (a new geometry): ends the lock, whatever its step. */
    fun onReset() {
        if (heldId != OverlayState.NO_LOCK) end(REASON_RESET)
    }

    /** The session ends: ends the lock and drops the signature. */
    fun onStop() {
        if (heldId != OverlayState.NO_LOCK) end(REASON_STOP)
    }

    private fun begin(id: Int) {
        heldId = id
        matcher.cancel()
        signature.clear()
        sampleSignature.clear()
        candidateCount = 0
        maxIdAtLastMatch = id
        lockCount++
        log("lock: set id=$id")
    }

    /** Drops the held lock and every signature without a log line; the request is cleared only if it still names the held id. */
    private fun drop() {
        val id = heldId
        heldId = OverlayState.NO_LOCK
        matcher.cancel()
        signature.clear()
        sampleSignature.clear()
        candidateCount = 0
        maxIdAtLastMatch = 0
        requestedId.compareAndSet(id, OverlayState.NO_LOCK)
    }

    private fun end(reason: String) {
        drop()
        log("lock: cleared ($reason)")
    }

    private fun expire() {
        drop()
        expiredCount++
        log("lock: expired")
    }

    /** The held track in a frame: notes the newest id and adds its torso colours, unless it is not matched or clipped. */
    private fun followHeld(
        list: TrackList,
        detections: List<PoseDetection>,
        frame: CaptureFrame,
    ) {
        val index = indexOfId(list, heldId)
        if (index < 0 || list.matchedDetectionIndex(index) < 0) return
        maxIdAtLastMatch = highestId(list)
        if (!torsoOf(list, index, detections, frame) || torso.clipped) return
        signature.blend(frame.rgba, frame.widthImg, frame.heightImg, torso)
    }

    private fun tryRelock(
        list: TrackList,
        detections: List<PoseDetection>,
        frame: CaptureFrame,
    ) {
        forgetCandidatesNotInList(list)
        var sampled = false
        for (i in 0 until list.size) {
            if (!list.isShown(i) || list.matchedDetectionIndex(i) < 0 || list.id(i) <= maxIdAtLastMatch) continue
            val slot = slotOf(list.id(i))
            if (slot < 0 || candidateFrames[slot] >= config.relockJudgeFrames) continue
            if (!sampled) {
                sampleAll(list, detections, frame)
                sampled = true
            }
            if (!judge(list, i, slot)) return
        }
    }

    /** Judges the candidate at list index [index] (slot [slot]) in this frame; false when the lock moved or was ended. */
    private fun judge(
        list: TrackList,
        index: Int,
        slot: Int,
    ): Boolean {
        candidateFrames[slot]++
        if (sampleStatus[index] != STATUS_OK) {
            noteVerdict(slot, Float.NaN, Float.NaN, REASON_NO_TORSO)
        } else {
            val distance = sampleDistance[index]
            val next = nearestOther(list, index)
            when (matcher.judge(distance, next)) {
                RelockMatcher.Verdict.ACCEPT -> {
                    moveTo(list.id(index), distance, next)
                    return false
                }

                RelockMatcher.Verdict.TOO_FAR -> {
                    noteVerdict(slot, distance, next, REASON_TOO_FAR)
                }

                RelockMatcher.Verdict.AMBIGUOUS -> {
                    noteVerdict(slot, distance, next, REASON_AMBIGUOUS)
                }
            }
        }
        if (candidateFrames[slot] >= config.relockJudgeFrames) finishWindow(slot, list.id(index))
        return true
    }

    private fun noteVerdict(
        slot: Int,
        distance: Float,
        next: Float,
        reason: Int,
    ) {
        candidateDistance[slot] = distance
        candidateNext[slot] = next
        candidateReason[slot] = reason
    }

    /** The window of the candidate in [slot] ended without an acceptance: one reject, counted and logged. */
    private fun finishWindow(
        slot: Int,
        id: Int,
    ) {
        relockRejectCount++
        val distance = format(candidateDistance[slot])
        when (candidateReason[slot]) {
            REASON_TOO_FAR -> log("lock: candidate id=$id d=$distance rejected (too far)")
            REASON_AMBIGUOUS -> log("lock: candidate id=$id d=$distance rejected (ambiguous, next=${format(candidateNext[slot])})")
            else -> log("lock: candidate id=$id rejected (no torso)")
        }
    }

    /** The slot of candidate [id], made when it is new; -1 when all slots are taken. */
    private fun slotOf(id: Int): Int {
        for (slot in 0 until candidateCount) {
            if (candidateId[slot] == id) return slot
        }
        if (candidateCount >= candidateId.size) return -1
        val slot = candidateCount++
        candidateId[slot] = id
        candidateFrames[slot] = 0
        candidateReason[slot] = REASON_NO_TORSO
        return slot
    }

    /** Frees the slots of candidates that are no longer in the tracker's list (their id is never used again). */
    private fun forgetCandidatesNotInList(list: TrackList) {
        var kept = 0
        for (slot in 0 until candidateCount) {
            if (indexOfId(list, candidateId[slot]) < 0) continue
            if (kept != slot) {
                candidateId[kept] = candidateId[slot]
                candidateFrames[kept] = candidateFrames[slot]
                candidateDistance[kept] = candidateDistance[slot]
                candidateNext[kept] = candidateNext[slot]
                candidateReason[kept] = candidateReason[slot]
            }
            kept++
        }
        candidateCount = kept
    }

    /**
     * Samples every shown track matched in this frame once: its status ([STATUS_OK] with a distance to the signature,
     * [STATUS_UNSAMPLED] when it has no torso, a clipped or too small one) or [STATUS_NONE] when it is not shown or not
     * matched.
     */
    private fun sampleAll(
        list: TrackList,
        detections: List<PoseDetection>,
        frame: CaptureFrame,
    ) {
        for (i in 0 until list.size) {
            if (!list.isShown(i) || list.matchedDetectionIndex(i) < 0) {
                sampleStatus[i] = STATUS_NONE
            } else if (sampleOne(list, i, detections, frame)) {
                sampleStatus[i] = STATUS_OK
                sampleDistance[i] = sampleSignature.distanceTo(signature)
            } else {
                sampleStatus[i] = STATUS_UNSAMPLED
            }
        }
    }

    /** Reads the whole (not clipped) torso of track [index] into [sampleSignature]; false when that is not possible. */
    private fun sampleOne(
        list: TrackList,
        index: Int,
        detections: List<PoseDetection>,
        frame: CaptureFrame,
    ): Boolean =
        torsoOf(list, index, detections, frame) && !torso.clipped &&
            sampleSignature.set(frame.rgba, frame.widthImg, frame.heightImg, torso)

    /**
     * The smallest distance among the sampled tracks other than [skip]; [Float.POSITIVE_INFINITY] when there is none,
     * [Float.NaN] (judged ambiguous) when another shown, matched track could not be sampled.
     */
    private fun nearestOther(
        list: TrackList,
        skip: Int,
    ): Float {
        var nearest = Float.POSITIVE_INFINITY
        for (j in 0 until list.size) {
            if (j == skip || sampleStatus[j] == STATUS_NONE) continue
            if (sampleStatus[j] == STATUS_UNSAMPLED) return Float.NaN
            nearest = min(nearest, sampleDistance[j])
        }
        return nearest
    }

    /** Moves the lock to [newId]; false when the user ended it meanwhile (the next [resolve] then ends the hold). */
    private fun moveTo(
        newId: Int,
        distance: Float,
        nextDistance: Float,
    ): Boolean {
        val oldId = heldId
        if (!requestedId.compareAndSet(oldId, newId)) return false
        heldId = newId
        matcher.cancel()
        candidateCount = 0
        maxIdAtLastMatch = maxOf(maxIdAtLastMatch, newId)
        relockCount++
        log("lock: relocked id=$oldId -> $newId d=${format(distance)} next=${format(nextDistance)}")
        return true
    }

    /** Writes the torso of track [index]'s matched detection into [torso]; false when it has none. */
    private fun torsoOf(
        list: TrackList,
        index: Int,
        detections: List<PoseDetection>,
        frame: CaptureFrame,
    ): Boolean {
        val detectionIndex = list.matchedDetectionIndex(index)
        if (detectionIndex < 0 || detectionIndex >= detections.size) return false
        return torsoRegion.of(detections[detectionIndex], frame.widthImg, frame.heightImg, torso)
    }

    private fun indexOfId(
        list: TrackList,
        id: Int,
    ): Int {
        for (i in 0 until list.size) {
            if (list.id(i) == id) return i
        }
        return -1
    }

    private fun highestId(list: TrackList): Int {
        var highest = 0
        for (i in 0 until list.size) highest = maxOf(highest, list.id(i))
        return highest
    }

    private fun format(distance: Float): String =
        when {
            distance.isNaN() -> "unsampled"
            distance.isInfinite() -> "none"
            else -> String.format(Locale.ROOT, "%.2f", distance)
        }

    private companion object {
        const val REASON_HANDLE = "handle"
        const val REASON_RESET = "reset"
        const val REASON_STOP = "stop"
        const val REASON_REMOVED = "track removed"
        const val REASON_REPLACED = "replaced"

        /** Verdicts of a candidate's newest judged frame. */
        const val REASON_NO_TORSO = 0
        const val REASON_TOO_FAR = 1
        const val REASON_AMBIGUOUS = 2

        /** How [sampleAll] found a track. */
        const val STATUS_NONE = 0
        const val STATUS_OK = 1
        const val STATUS_UNSAMPLED = 2
    }
}
