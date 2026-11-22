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
import com.memo494.posetracker.core.tracking.TrackState
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
 *   signature exists, the lock waits ([RelockMatcher]) for `relockMemoryMs` of frame time, and logs once
 *   `lock: pending id=N frames=K` (K = frames blended into the signature); without a signature (no unclipped torso was
 *   ever read, though every matched frame of the held track was tried) the lock ends at once, `cleared (no signature)`.
 *   A pending lock draws nothing ([resolve] says [OverlayState.NO_LOCK]) and [hasLock] stays true,
 *   so a tap on the handle ends it;
 * - **re-lock**: while pending, a candidate is any shown track matched in the frame whose id is above
 *   `maxIdAtLastMatch` (so a person who came back under a new id while the old track was still lost counts too).
 *   Each candidate is judged on each of its first `relockJudgeFrames` matched frames: every shown, matched track is
 *   sampled once per frame (a clipped or too small torso is "unsampled"; a torso of a bucket the signature holds fewer
 *   than `relockMinSourceSamples` frames of, whole torso against upper body, is "not comparable"), the candidate's
 *   distance to the signature and the nearest of the others go to [RelockMatcher.judge], and the lock moves at the
 *   first acceptance. Only readable frames count toward `relockJudgeFrames`; every matched frame counts toward the cap
 *   `relockJudgeMaxFrames`. A candidate that is not accepted in its window is counted and logged once, when the window
 *   ends (`d=` newest, `min=` smallest of the window, `src=` torso rule, `sig=` frames in the signature). Another track
 *   that cannot be sampled or compared makes every candidate ambiguous (`next=unsampled`: fail closed). While the held
 *   track is matched, a frame whose torso another detected person's box covers by more than `relockOccluderMaxOverlap`
 *   is not added to the signature;
 * - **early re-lock** (M4f): the same judging, with the same rules, already while the held track is LOST (coasting, not
 *   yet removed) and a signature exists, so the ring does not wait for the old track to be removed. At the first
 *   acceptance the lock moves at once, the old track is no longer held, and the line ends with `early`. An early accept
 *   also needs spatial evidence: the candidate's head within `relockEarlyMaxHeadRatio` of the held track's height from
 *   the held track's predicted head and no other track (tentative ones count) within that radius; a candidate without it is not judged
 *   (no window used, no reject) and waits for pending. When the held
 *   track is matched again first, the candidates are forgotten (no move). Whatever was judged before the removal
 *   carries over into the pending step;
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

    /**
     * Whether the model is box-only (no keypoints; decided by the loaded model). The torso is then the box part of a
     * whole-body box only (`TorsoRegion.boxOnly`): the same `relock*` numbers and the same judge as a pose model whose
     * shoulders are not confident, so a re-lock is never accepted more easily than today; a person whose box is not a
     * whole body is "unsampled" and the judge stays fail-closed (no signature: the lock ends, logged as `cleared (no
     * signature)`). Capture thread.
     */
    var boxOnly: Boolean
        get() = torsoRegion.boxOnly
        set(value) {
            torsoRegion.boxOnly = value
        }
    private val torso = TorsoRect()
    private val signature = AppearanceSignature(config)
    private val sampleSignature = AppearanceSignature(config)
    private var lastFrameNs = 0L

    /** Frames whose torso was blended into [signature] since the lock began; logged when the lock turns pending. */
    private var signatureFrames = 0

    /** Highest track id in the tracker's list the last time the held track was matched; candidates have a higher id. */
    private var maxIdAtLastMatch = 0

    /** Per list index of the newest frame: how [sampleAll] found the track (see the `STATUS_` constants) and its distance. */
    private val sampleStatus = IntArray(config.maxTracks)
    private val sampleDistance = FloatArray(config.maxTracks)
    private val sampleSource = IntArray(config.maxTracks)
    private val sampleBucket = IntArray(config.maxTracks)

    /**
     * The candidates seen while pending, in slots `0 until candidateCount`: id, readable frames judged
     * ([candidateFrames], at most `relockJudgeFrames`), all matched frames seen ([candidateAttempts], at most
     * `relockJudgeMaxFrames`), the smallest distance and the `next` of that frame, the newest verdict and the torso source.
     */
    private val candidateId = IntArray(config.maxTracks)
    private val candidateFrames = IntArray(config.maxTracks)
    private val candidateAttempts = IntArray(config.maxTracks)
    private val candidateDistance = FloatArray(config.maxTracks)
    private val candidateMinDistance = FloatArray(config.maxTracks)
    private val candidateNext = FloatArray(config.maxTracks)
    private val candidateReason = IntArray(config.maxTracks)
    private val candidateSource = IntArray(config.maxTracks)
    private var candidateCount = 0

    /** Locks set (a tap on a person that found its track) since this keeper began. Capture thread. */
    var lockCount: Int = 0
        private set

    /** Locks that moved to a returning person, pending or early (M4f). Capture thread. */
    var relockCount: Int = 0
        private set

    /** New tracks compared with a pending or an early lock and not accepted when their window ended. Capture thread. */
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
            log("lock: pending id=$id frames=$signatureFrames")
        } else {
            end(REASON_NO_SIGNATURE)
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
            if (matcher.isExpired(lastFrameNs)) expire() else tryRelock(list, detections, frame, NOT_EARLY)
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
        signatureFrames = 0
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
        signatureFrames = 0
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
        if (index < 0) return
        if (list.matchedDetectionIndex(index) < 0) {
            if (list.state(index) == TrackState.LOST && signature.hasData) tryRelock(list, detections, frame, index)
            return
        }
        candidateCount = 0
        maxIdAtLastMatch = highestId(list)
        if (!torsoOf(list, index, detections, frame) || torso.clipped) return
        if (isOccluded(list, index, detections)) return
        if (signature.blend(frame.rgba, frame.widthImg, frame.heightImg, torso)) signatureFrames++
    }

    /**
     * True when the box of any other detected person (tracked or not) covers more than `relockOccluderMaxOverlap` of
     * the held torso in [torso] (their clothes would be read as the held person's), or when that cannot be told (fail closed).
     */
    private fun isOccluded(
        list: TrackList,
        heldIndex: Int,
        detections: List<PoseDetection>,
    ): Boolean {
        val heldDetection = list.matchedDetectionIndex(heldIndex)
        for (d in detections.indices) {
            if (d == heldDetection) continue
            val other = detections[d]
            val share = torso.coveredShareBy(other.boxLeftImg, other.boxTopImg, other.boxRightImg, other.boxBottomImg)
            if (!(share <= config.relockOccluderMaxOverlap)) return true
        }
        return false
    }

    /**
     * Judges the new tracks against the signature. [heldIndex] is the list index of the held track when it is still
     * LOST (early re-lock: a candidate is only judged when it passes [hasSpatialEvidence]), [NOT_EARLY] when pending.
     */
    private fun tryRelock(
        list: TrackList,
        detections: List<PoseDetection>,
        frame: CaptureFrame,
        heldIndex: Int,
    ) {
        val early = heldIndex != NOT_EARLY
        forgetCandidatesNotInList(list)
        var sampled = false
        for (i in 0 until list.size) {
            if (!list.isShown(i) || list.matchedDetectionIndex(i) < 0 || list.id(i) <= maxIdAtLastMatch) continue
            if (early && !hasSpatialEvidence(list, heldIndex, i)) continue
            val slot = slotOf(list.id(i))
            if (slot < 0 || candidateFrames[slot] >= config.relockJudgeFrames || candidateAttempts[slot] >= config.relockJudgeMaxFrames) {
                continue
            }
            if (!sampled) {
                sampleAll(list, detections, frame)
                sampled = true
            }
            if (!judge(list, i, slot, early)) return
        }
    }

    /**
     * Early re-lock only: true when the candidate at list index [candidate] is within `relockEarlyMaxHeadRatio` times
     * the held track's height of the held track's predicted head and no other track in the list, tentative or not (not the
     * held one, not the candidate), is. A candidate that fails is not judged at all (no slot, no window, no reject): pending decides later.
     * Fails closed on any value that is not a number.
     */
    private fun hasSpatialEvidence(
        list: TrackList,
        heldIndex: Int,
        candidate: Int,
    ): Boolean {
        val radiusImg = config.relockEarlyMaxHeadRatio * list.heightImg(heldIndex)
        val radius2Img2 = radiusImg * radiusImg
        if (!(distance2Img2(list, heldIndex, candidate) <= radius2Img2)) return false
        for (j in 0 until list.size) {
            if (j == heldIndex || j == candidate) continue
            if (!(distance2Img2(list, heldIndex, j) > radius2Img2)) return false
        }
        return true
    }

    /** Squared distance between the heads of list tracks [a] and [b], Img px^2. */
    private fun distance2Img2(
        list: TrackList,
        a: Int,
        b: Int,
    ): Float {
        val dx = list.headXImg(a) - list.headXImg(b)
        val dy = list.headYImg(a) - list.headYImg(b)
        return dx * dx + dy * dy
    }

    /**
     * Judges the candidate at list index [index] (slot [slot]) in this frame; false when the lock moved or was ended.
     * Only frames with a readable torso count toward `relockJudgeFrames`; every matched frame counts toward the cap
     * `relockJudgeMaxFrames`, so a person who enters clipped is not used up before their shirt is seen.
     */
    private fun judge(
        list: TrackList,
        index: Int,
        slot: Int,
        early: Boolean,
    ): Boolean {
        candidateAttempts[slot]++
        if (sampleSource[index] != TorsoRect.SOURCE_NONE) candidateSource[slot] = sampleSource[index]
        if (sampleStatus[index] != STATUS_OK && candidateFrames[slot] == 0) {
            candidateReason[slot] = if (sampleStatus[index] == STATUS_UNCOMPARABLE) REASON_NO_COMPARABLE else REASON_NO_TORSO
        }
        if (sampleStatus[index] == STATUS_OK) {
            candidateFrames[slot]++
            val distance = sampleDistance[index]
            val next = nearestOther(list, index)
            when (matcher.judge(distance, next)) {
                RelockMatcher.Verdict.ACCEPT -> {
                    moveTo(list.id(index), distance, next, early, min(distance, candidateMinDistance[slot]), candidateSource[slot])
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
        if (candidateFrames[slot] >= config.relockJudgeFrames || candidateAttempts[slot] >= config.relockJudgeMaxFrames) {
            finishWindow(slot, list.id(index))
        }
        return true
    }

    /** Notes the verdict of the newest readable frame, and the smallest distance of the window. */
    private fun noteVerdict(
        slot: Int,
        distance: Float,
        next: Float,
        reason: Int,
    ) {
        candidateDistance[slot] = distance
        candidateNext[slot] = next
        candidateMinDistance[slot] = min(candidateMinDistance[slot], distance)
        candidateReason[slot] = reason
    }

    /** The window of the candidate in [slot] ended without an acceptance: one reject, counted and logged. */
    private fun finishWindow(
        slot: Int,
        id: Int,
    ) {
        relockRejectCount++
        val distances = "d=${format(candidateDistance[slot])} min=${format(candidateMinDistance[slot])}"
        val tail = " src=${sourceName(candidateSource[slot])} sig=$signatureFrames"
        when (candidateReason[slot]) {
            REASON_TOO_FAR -> log("lock: candidate id=$id $distances rejected (too far)$tail")
            REASON_AMBIGUOUS -> log("lock: candidate id=$id $distances rejected (ambiguous, next=${format(candidateNext[slot])})$tail")
            REASON_NO_COMPARABLE -> log("lock: candidate id=$id rejected (no comparable torso)$tail")
            else -> log("lock: candidate id=$id rejected (no torso)$tail")
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
        candidateAttempts[slot] = 0
        candidateDistance[slot] = Float.POSITIVE_INFINITY
        candidateMinDistance[slot] = Float.POSITIVE_INFINITY
        candidateNext[slot] = Float.POSITIVE_INFINITY
        candidateSource[slot] = TorsoRect.SOURCE_NONE
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
                candidateAttempts[kept] = candidateAttempts[slot]
                candidateSource[kept] = candidateSource[slot]
                candidateDistance[kept] = candidateDistance[slot]
                candidateMinDistance[kept] = candidateMinDistance[slot]
                candidateNext[kept] = candidateNext[slot]
                candidateReason[kept] = candidateReason[slot]
            }
            kept++
        }
        candidateCount = kept
    }

    /**
     * Samples every shown track matched in this frame once: its status ([STATUS_OK] with a distance to the signature,
     * [STATUS_UNSAMPLED] when it has no torso, a clipped or too small one, [STATUS_UNCOMPARABLE] when the torso was read but
     * the signature holds too little of its bucket) or [STATUS_NONE] when it is not shown or not
     * matched.
     */
    private fun sampleAll(
        list: TrackList,
        detections: List<PoseDetection>,
        frame: CaptureFrame,
    ) {
        for (i in 0 until list.size) {
            sampleSource[i] = TorsoRect.SOURCE_NONE
            if (!list.isShown(i) || list.matchedDetectionIndex(i) < 0) {
                sampleStatus[i] = STATUS_NONE
                continue
            }
            val read = sampleOne(list, i, detections, frame)
            sampleSource[i] = torso.source
            // A torso whose bucket the signature does not hold enough of (a desk person against a standing one, or
            // too few frames) cannot be compared: never a match, never "far".
            if (!read) {
                sampleStatus[i] = STATUS_UNSAMPLED
            } else if (signature.hasDataFor(sampleSignature.lastBucket)) {
                val distance = sampleSignature.distanceTo(signature, sampleSignature.lastBucket)
                sampleBucket[i] = sampleSignature.lastBucket
                sampleDistance[i] = distance
                sampleStatus[i] = if (distance.isNaN()) STATUS_UNCOMPARABLE else STATUS_OK
            } else {
                sampleStatus[i] = STATUS_UNCOMPARABLE
            }
        }
    }

    /**
     * Reads the whole (not clipped) torso of track [index] into [sampleSignature]; false when that is not possible.
     * Leaves the source of the torso in [sampleSignature] (`lastSource`).
     */
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
     * [Float.NaN] (judged ambiguous) when another shown, matched track could not be sampled, compared, or is of another bucket than [skip].
     */
    private fun nearestOther(
        list: TrackList,
        skip: Int,
    ): Float {
        var nearest = Float.POSITIVE_INFINITY
        for (j in 0 until list.size) {
            if (j == skip || sampleStatus[j] == STATUS_NONE) continue
            if (sampleStatus[j] == STATUS_UNSAMPLED || sampleStatus[j] == STATUS_UNCOMPARABLE) return Float.NaN
            // Distances to different histograms are on different scales: a bystander of another bucket gives no margin.
            if (sampleBucket[j] != sampleBucket[skip]) return Float.NaN
            nearest = min(nearest, sampleDistance[j])
        }
        return nearest
    }

    /** Moves the lock to [newId]; false when the user ended it meanwhile (the next [resolve] then ends the hold). */
    private fun moveTo(
        newId: Int,
        distance: Float,
        nextDistance: Float,
        early: Boolean,
        minDistance: Float,
        source: Int,
    ): Boolean {
        val oldId = heldId
        if (!requestedId.compareAndSet(oldId, newId)) return false
        heldId = newId
        matcher.cancel()
        candidateCount = 0
        maxIdAtLastMatch = maxOf(maxIdAtLastMatch, newId)
        relockCount++
        val suffix = if (early) " early" else ""
        log(
            "lock: relocked id=$oldId -> $newId d=${format(distance)} next=${format(nextDistance)}$suffix " +
                "min=${format(minDistance)} src=${sourceName(source)} sig=$signatureFrames",
        )
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
        if (detectionIndex < 0 || detectionIndex >= detections.size) {
            torso.clear()
            return false
        }
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

    private fun sourceName(source: Int): String =
        when (source) {
            TorsoRect.SOURCE_KEYPOINTS -> "keypoints"
            TorsoRect.SOURCE_BOX -> "box"
            TorsoRect.SOURCE_UPPER_BODY -> "upper"
            else -> "none"
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
        const val REASON_NO_SIGNATURE = "no signature"
        const val REASON_REPLACED = "replaced"

        /** Verdicts of a candidate's newest judged frame. */
        const val REASON_NO_TORSO = 0
        const val REASON_TOO_FAR = 1
        const val REASON_AMBIGUOUS = 2
        const val REASON_NO_COMPARABLE = 3

        /** How [sampleAll] found a track. */
        const val STATUS_NONE = 0
        const val STATUS_OK = 1
        const val STATUS_UNSAMPLED = 2
        const val STATUS_UNCOMPARABLE = 3

        /** `tryRelock` while pending: no held track in the list. */
        const val NOT_EARLY = -1
    }
}
