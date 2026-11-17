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
 *   inside [onFrame], before the frame's array is reused);
 * - **pending**: when the held track is no longer among the shown tracks (it left, or was lost for too long) and a
 *   signature exists, the lock waits ([RelockMatcher]) for `relockMemoryMs` of frame time; without a signature the
 *   lock simply ends. A pending lock draws nothing ([resolve] says [OverlayState.NO_LOCK]) and [hasLock] stays true,
 *   so a tap on the handle ends it;
 * - **re-lock**: while pending, every track that becomes shown in a frame (and is matched in it) is compared with the
 *   signature, and so is every other shown track matched in that frame; the lock moves to the new track only when
 *   [RelockMatcher.judge] accepts (close enough and clearly closer than all the others, else it stays pending and the
 *   distances go to the log);
 * - **end**: a tap on the handle, a reset ([onReset]), the end of the session ([onStop]) or the end of the memory.
 *
 * The signature is a colour histogram in memory on the capture thread only; it is dropped whenever the lock ends.
 * Allocation-free per frame (three preallocated signatures, one rectangle, one id array); log lines are built only at
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
    private val candidateSignature = AppearanceSignature(config)
    private val otherSignature = AppearanceSignature(config)
    private val shownIds = IntArray(config.maxTracks)
    private var shownCount = 0
    private var lastFrameNs = 0L

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
            if (heldId != OverlayState.NO_LOCK) end(REASON_HANDLE)
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
     * compares the tracks that just became shown with it while the lock is pending; ends a pending lock whose memory
     * ran out. Capture thread, once per detected frame.
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
            addToSignature(list, detections, frame)
        }
        rememberShown(list)
    }

    /** The tracker was reset (a new geometry): ends the lock, whatever its step, and forgets the shown ids. */
    fun onReset() {
        if (heldId != OverlayState.NO_LOCK) end(REASON_RESET)
        shownCount = 0
    }

    /** The session ends: ends the lock and drops the signature. */
    fun onStop() {
        if (heldId != OverlayState.NO_LOCK) end(REASON_STOP)
        shownCount = 0
    }

    private fun begin(id: Int) {
        heldId = id
        matcher.cancel()
        signature.clear()
        lockCount++
        log("lock: set id=$id")
    }

    /** Drops the held lock without a log line; the request is cleared only if it still names the held id. */
    private fun drop() {
        val id = heldId
        heldId = OverlayState.NO_LOCK
        matcher.cancel()
        signature.clear()
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

    private fun addToSignature(
        list: TrackList,
        detections: List<PoseDetection>,
        frame: CaptureFrame,
    ) {
        val index = indexOfId(list, heldId)
        if (index < 0 || !torsoOf(list, index, detections, frame)) return
        signature.blend(frame.rgba, frame.widthImg, frame.heightImg, torso)
    }

    private fun tryRelock(
        list: TrackList,
        detections: List<PoseDetection>,
        frame: CaptureFrame,
    ) {
        for (i in 0 until list.size) {
            if (!list.isShown(i) || list.matchedDetectionIndex(i) < 0 || wasShown(list.id(i))) continue
            val candidateId = list.id(i)
            if (!sample(candidateSignature, list, i, detections, frame)) {
                relockRejectCount++
                log("lock: candidate id=$candidateId rejected (no torso)")
                continue
            }
            val distance = candidateSignature.distanceTo(signature)
            val nextDistance = nearestOtherDistance(list, i, detections, frame)
            when (matcher.judge(distance, nextDistance)) {
                RelockMatcher.Verdict.ACCEPT -> {
                    if (moveTo(candidateId, distance, nextDistance)) return
                }

                RelockMatcher.Verdict.TOO_FAR -> {
                    relockRejectCount++
                    log("lock: candidate id=$candidateId d=${format(distance)} rejected (too far)")
                }

                RelockMatcher.Verdict.AMBIGUOUS -> {
                    relockRejectCount++
                    log("lock: candidate id=$candidateId d=${format(distance)} rejected (ambiguous, next=${format(nextDistance)})")
                }
            }
        }
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
        relockCount++
        log("lock: relocked id=$oldId -> $newId d=${format(distance)} next=${format(nextDistance)}")
        return true
    }

    /** The smallest distance to the signature among the shown tracks other than [skip] that were matched in this frame. */
    private fun nearestOtherDistance(
        list: TrackList,
        skip: Int,
        detections: List<PoseDetection>,
        frame: CaptureFrame,
    ): Float {
        var nearest = Float.POSITIVE_INFINITY
        for (j in 0 until list.size) {
            if (j == skip || !list.isShown(j) || list.matchedDetectionIndex(j) < 0) continue
            if (sample(otherSignature, list, j, detections, frame)) nearest = min(nearest, otherSignature.distanceTo(signature))
        }
        return nearest
    }

    /** Sets [target] to the torso colours of track [index] in this frame; false when there is no detection or torso. */
    private fun sample(
        target: AppearanceSignature,
        list: TrackList,
        index: Int,
        detections: List<PoseDetection>,
        frame: CaptureFrame,
    ): Boolean = torsoOf(list, index, detections, frame) && target.set(frame.rgba, frame.widthImg, frame.heightImg, torso)

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

    private fun rememberShown(list: TrackList) {
        shownCount = 0
        for (i in 0 until list.size) {
            if (list.isShown(i) && shownCount < shownIds.size) shownIds[shownCount++] = list.id(i)
        }
    }

    private fun wasShown(id: Int): Boolean {
        for (i in 0 until shownCount) {
            if (shownIds[i] == id) return true
        }
        return false
    }

    private fun format(distance: Float): String = if (distance.isFinite()) String.format(Locale.ROOT, "%.2f", distance) else "none"

    private companion object {
        const val REASON_HANDLE = "handle"
        const val REASON_RESET = "reset"
        const val REASON_STOP = "stop"
        const val REASON_REMOVED = "track removed"
    }
}
