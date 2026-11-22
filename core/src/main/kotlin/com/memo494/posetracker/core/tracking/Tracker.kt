package com.memo494.posetracker.core.tracking

import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.model.DetectionFrame
import com.memo494.posetracker.core.model.PoseDetection
import kotlin.math.sqrt

/**
 * Multi-person tracker: detections in, stable tracks with ids and a smoothed head point out. The algorithm is
 * the reference model `docs/plans/M3_sim/tracker_ref.py`; the decisions behind it are Consults C1 to C3 of
 * `docs/plans/M3.md`.
 *
 * One [update] does, in order:
 * 1. time: the first frame does not predict; `dt <= 0` ignores the frame; a step is `min(dt, trackMaxDtMs)`
 *    long and a longer one (a gap step) inflates the process noise by `trackGapNoiseInflate`;
 * 2. every track is predicted (head x and y, and the box height, as constant-velocity Kalman filters);
 * 3. detections: at most [TrackerConfig.MAX_DETECTIONS_PER_FRAME], duplicates dropped, scores below
 *    `scoreThreshold` and unusable boxes ignored; "high" means a score of at least `trackHighScore`;
 * 4. stage 1 assigns the confirmed and lost tracks to the high detections, stage 2 the unmatched ones to the low
 *    ones; then the tentative tracks get the same two stages on the detections that are left over, each stage
 *    with a cost limit (`AssociationCost`, `HungarianAssigner`);
 * 5. a matched track is updated and counted a hit (a tentative one is confirmed after `trackConfirmHits`);
 * 6. an unmatched tentative track coasts (its velocity decays, it stays tentative and hidden, `lostMs` stays 0)
 *    until its consecutive misses exceed `trackTentativeMaxMisses`, then it is removed (a hit resets the count);
 *    an unmatched confirmed one is LOST (its velocity decays) and is removed after `trackMaxLostMs` or when
 *    its head point is more than `trackExitMarginRatio` heights outside the image;
 * 7. an unmatched detection with a score of at least `trackBirthScore` starts a tentative track while fewer
 *    than `maxTracks` tracks exist (confirmed at once when `trackConfirmHits` is 1); not when its box overlaps with
 *    `trackBirthSuppressIou` or more a detection that a confirmed or lost track took in this frame (a near duplicate
 *    of that person, counted in [assocBirthSuppressed]);
 * 8. [onIdle] promotes the tentative tracks after `trackIdlePromoteMs` without a frame and removes a LOST track
 *    whose lost time plus the idle time exceeds `trackMaxLostMs` (so a still screen does not keep a ring for ever).
 *
 * Association-miss diagnostics (M4e, no effect on any track): when step 7 starts a track, the nearest LOST track
 * whose predicted head is within `assocMissNearRatio` heights of the new head is recorded ([missCount], [miss]) with
 * the values the matcher uses for a pair, and [assocMisses] (by reason [assocMissGate], [assocMissCost],
 * [assocMissTaken]) goes up. With no LOST track near, the nearest track that is not LOST (and was not born in this
 * frame) is recorded as [AssociationMissReason.LIVE] ([assocBirthNearLive]); with none, the nearest track removed in
 * this frame (a confirmed or lost one) as [AssociationMissReason.REMOVED] ([assocBirthAfterRemoval]). An unmatched
 * detection below `trackBirthScore` next to a LOST track adds to [assocMissLowBirth]. The work is a loop over the
 * tracks and runs only for such a detection; the removed tracks are copied to scratch arrays made in the constructor.
 *
 * Thread rule: not thread-safe, one capture thread calls everything. **Allocation-free after construction**
 * (AGENTS rule 8): the pool of `maxTracks` tracks, the order array and every scratch array are made in the
 * constructor, [update] and [onIdle] use index loops only. The returned [TrackList] is one object owned by the
 * tracker, valid until the next call.
 *
 * Fail closed: a detection with a non-finite score or an unusable box is ignored; a track whose position,
 * height or covariance is not finite, or whose height is not positive, is removed.
 */
class Tracker(
    private val config: TrackerConfig,
) {
    /** One pooled track; every field is overwritten at birth. */
    private class Track(
        config: TrackerConfig,
    ) {
        val x = KalmanAxis()
        val y = KalmanAxis()
        val height = KalmanAxis()
        val estimator = HeadPointEstimator(config)
        var id = 0
        var state = TrackState.TENTATIVE
        var hits = 0

        /** Consecutive frames without a match while tentative; a hit resets it. */
        var misses = 0
        var lostMs = 0f
        var score = 0f
        var headEstimated = false

        /** Index of the detection matched or started on in the newest [update], or -1. */
        var matchedDet = -1

        /** Box edges relative to the head point, in units of the box height. */
        var offLeft = 0f
        var offTop = 0f
        var offRight = 0f
        var offBottom = 0f

        fun boxLeftImg(): Float = x.position + offLeft * height.position

        fun boxTopImg(): Float = y.position + offTop * height.position

        fun boxRightImg(): Float = x.position + offRight * height.position

        fun boxBottomImg(): Float = y.position + offBottom * height.position

        /** True when position, height and covariances are finite and the height is positive. */
        fun isHealthy(): Boolean =
            x.position.isFinite() && y.position.isFinite() && height.position.isFinite() && height.position > 0f &&
                x.pPos.isFinite() && y.pPos.isFinite() && height.pPos.isFinite()
    }

    private val maxTracks = config.maxTracks
    private val tracks = Array(maxTracks) { Track(config) }
    private val inUse = BooleanArray(maxTracks)

    /** Pool indices of the active tracks, in creation order; the first [activeCount] entries count. */
    private val order = IntArray(maxTracks)
    private var activeCount = 0

    private val dedupeKept = IntArray(TrackerConfig.MAX_DETECTIONS_PER_FRAME)
    private val keep = IntArray(TrackerConfig.MAX_DETECTIONS_PER_FRAME)
    private val high = IntArray(TrackerConfig.MAX_DETECTIONS_PER_FRAME)
    private val low = IntArray(TrackerConfig.MAX_DETECTIONS_PER_FRAME)
    private val freeHigh = IntArray(TrackerConfig.MAX_DETECTIONS_PER_FRAME)
    private val freeLow = IntArray(TrackerConfig.MAX_DETECTIONS_PER_FRAME)
    private val detUsed = BooleanArray(TrackerConfig.MAX_DETECTIONS_PER_FRAME)
    private val heads = Array(TrackerConfig.MAX_DETECTIONS_PER_FRAME) { HeadPoint() }
    private val point = HeadPoint()
    private var keepCount = 0
    private var highCount = 0
    private var lowCount = 0

    /** Per position in [order]: the detection index matched this frame, or -1. */
    private val trackDet = IntArray(maxTracks)
    private val stageTracks = IntArray(maxTracks)
    private val rowToCol = IntArray(maxTracks)
    private val colToRow = IntArray(TrackerConfig.MAX_DETECTIONS_PER_FRAME)
    private val assigner = HungarianAssigner(maxTracks, TrackerConfig.MAX_DETECTIONS_PER_FRAME)
    private val dedupe = DetectionDedupe(TrackerConfig.MAX_DETECTIONS_PER_FRAME, config)

    private val maxDtNs: Double = config.trackMaxDtMs.toDouble() * TrackerConfig.NANOS_PER_MILLI
    private val nanosPerSecond: Double = TrackerConfig.NANOS_PER_SECOND.toDouble()
    private val nanosPerMilli: Double = TrackerConfig.NANOS_PER_MILLI.toDouble()
    private val missTauS: Float = (config.trackMissVelocityTauMs.toDouble() * nanosPerMilli / nanosPerSecond).toFloat()

    private var hasLastFrame = false
    private var lastTimestampNs = 0L
    private var nextId = 1
    private val view = View()

    /** Tracks started since construction (`reset` keeps it). */
    var birthCount: Int = 0
        private set

    /** Tentative tracks confirmed by enough hits since construction. */
    var confirmCount: Int = 0
        private set

    /** Tentative tracks promoted by [onIdle] since construction. */
    var idlePromoteCount: Int = 0
        private set

    /** LOST tracks that were matched again (back to CONFIRMED) since construction. */
    var recoverCount: Int = 0
        private set

    /** Confirmed or lost tracks removed since construction (tentative ones do not count). */
    var removeCount: Int = 0
        private set

    /** Most tracks shown (CONFIRMED or LOST) at once after any call since construction. */
    var mostShownAtOnce: Int = 0
        private set

    /** Tracks born next to a LOST track (an association miss, see the class doc) since construction. */
    var assocMisses: Int = 0
        private set

    /** Misses whose pair was outside the gate; part of [assocMisses]. */
    var assocMissGate: Int = 0
        private set

    /** Misses whose pair passed the gate but cost too much; part of [assocMisses]. */
    var assocMissCost: Int = 0
        private set

    /** Misses whose pair the matcher would have accepted; part of [assocMisses]. */
    var assocMissTaken: Int = 0
        private set

    /** Unmatched detections below the birth score next to a LOST track, counted once per frame they appear (no record). */
    var assocMissLowBirth: Int = 0
        private set

    /** Births next to a track that is not LOST (reason LIVE), count; kept by `reset()`. */
    var assocBirthNearLive: Int = 0
        private set

    /** Births next to a track removed in the same frame (reason REMOVED), count; kept by `reset()`. */
    var assocBirthAfterRemoval: Int = 0
        private set

    /** Unmatched detections not given a track because they overlap what a confirmed or lost track took; kept by `reset()`. */
    var assocBirthSuppressed: Int = 0
        private set

    private val misses = Array(TrackerConfig.MAX_DETECTIONS_PER_FRAME) { AssociationMiss() }

    /** Tracks (confirmed or lost) removed in the newest [update], copied before their slot is reused. */
    private var removedCount = 0
    private val removedId = IntArray(maxTracks)
    private val removedXImg = FloatArray(maxTracks)
    private val removedYImg = FloatArray(maxTracks)
    private val removedHeightImg = FloatArray(maxTracks)
    private val removedVarXImg2 = FloatArray(maxTracks)
    private val removedVarYImg2 = FloatArray(maxTracks)
    private val removedLeftImg = FloatArray(maxTracks)
    private val removedTopImg = FloatArray(maxTracks)
    private val removedRightImg = FloatArray(maxTracks)
    private val removedBottomImg = FloatArray(maxTracks)
    private val removedLostMs = FloatArray(maxTracks)
    private val removedCause = Array(maxTracks) { RemovalCause.NONE }

    /** Number of records the newest [update] made (valid until the next call to [update] or [reset]). */
    var missCount: Int = 0
        private set

    /** The record [index] of the newest [update], `0 until missCount`; throws `IndexOutOfBoundsException` otherwise. */
    fun miss(index: Int): AssociationMiss {
        if (index < 0 || index >= missCount) throw IndexOutOfBoundsException("index $index, size $missCount")
        return misses[index]
    }

    /** Feeds one frame of detections and returns the tracks afterwards (the same list object every call). */
    fun update(frame: DetectionFrame): TrackList {
        missCount = 0
        removedCount = 0
        var dteNs = 0.0
        if (hasLastFrame) {
            val dtNs = frame.timestampNs - lastTimestampNs
            if (dtNs <= 0L) {
                for (pos in 0 until activeCount) tracks[order[pos]].matchedDet = -1
                return view
            }
            val gap = dtNs.toDouble() > maxDtNs
            dteNs = minOf(dtNs.toDouble(), maxDtNs)
            predictAll((dteNs / nanosPerSecond).toFloat(), if (gap) config.trackGapNoiseInflate else 1f)
        }
        hasLastFrame = true
        lastTimestampNs = frame.timestampNs

        val detections = frame.detections
        prepareDetections(detections)
        matchTracks(detections)
        updateTracks(detections, dteNs, frame)
        giveBirths(detections)
        noteShown()
        return view
    }

    /**
     * Tells the tracker that [idleNs] nanoseconds have passed since the newest frame arrived (measured by the
     * caller) and returns the tracks afterwards. At or above `trackIdlePromoteMs` every tentative track
     * that was hit on the newest frame (no miss since) becomes confirmed; one that has missed stays tentative. A LOST track whose `lostMs` plus the idle time exceeds `trackMaxLostMs` is removed
     * (counted in [removeCount]); `lostMs` itself is not changed, so repeated calls with a growing [idleNs] stay
     * consistent and the next [update] goes on as before. A CONFIRMED track is never touched (a paused video
     * keeps its rings); nothing else changes.
     */
    fun onIdle(idleNs: Long): TrackList {
        val promote = idleNs >= config.trackIdlePromoteNs
        val idleMs = maxOf(idleNs, 0L).toDouble() / nanosPerMilli
        var survivors = 0
        for (pos in 0 until activeCount) {
            val slot = order[pos]
            val track = tracks[slot]
            if (track.state == TrackState.LOST && track.lostMs + idleMs > config.trackMaxLostMs) {
                removeCount++
                inUse[slot] = false
                continue
            }
            if (promote && track.state == TrackState.TENTATIVE && track.misses == 0) {
                track.state = TrackState.CONFIRMED
                idlePromoteCount++
            }
            order[survivors++] = slot
        }
        activeCount = survivors
        noteShown()
        return view
    }

    /** Forgets every track and the last timestamp; the id counter and the counters go on. */
    fun reset() {
        inUse.fill(false)
        activeCount = 0
        missCount = 0
        removedCount = 0
        hasLastFrame = false
    }

    private fun predictAll(
        dteS: Float,
        inflate: Float,
    ) {
        for (pos in 0 until activeCount) {
            val track = tracks[order[pos]]
            val heightImg = track.height.position
            track.x.predict(dteS, config.trackAccelSigmaRatio * heightImg, inflate)
            track.y.predict(dteS, config.trackAccelSigmaRatio * heightImg, inflate)
            track.height.predict(dteS, config.trackHeightAccelSigmaRatio * heightImg, inflate)
        }
    }

    /** Fills `keep`, `high` and `low` (detection indices in input order) and the head points of the kept ones. */
    private fun prepareDetections(detections: List<PoseDetection>) {
        val count = minOf(detections.size, TrackerConfig.MAX_DETECTIONS_PER_FRAME)
        val dedupedCount = dedupe.dedupe(detections, count, dedupeKept)
        keepCount = 0
        highCount = 0
        lowCount = 0
        for (k in 0 until dedupedCount) {
            val index = dedupeKept[k]
            val detection = detections[index]
            if (!(detection.score.isFinite() && detection.score >= config.scoreThreshold)) continue
            HeadMeasurement.of(detection, config, heads[index])
            if (!heads[index].valid) continue
            keep[keepCount++] = index
            if (detection.score >= config.trackHighScore) high[highCount++] = index else low[lowCount++] = index
        }
        detUsed.fill(false)
    }

    /**
     * ByteTrack order (F3/A2): confirmed and lost tracks are matched first (high detections, then low), tentative
     * tracks only against the detections that are left over, so a newborn cannot take the person of an older track.
     */
    private fun matchTracks(detections: List<PoseDetection>) {
        trackDet.fill(-1, 0, activeCount)
        matchGroup(detections, tentative = false)
        matchGroup(detections, tentative = true)
    }

    /** Two stages (high, then low detections not yet used) for the tracks whose tentative state equals [tentative]. */
    private fun matchGroup(
        detections: List<PoseDetection>,
        tentative: Boolean,
    ) {
        var count = 0
        for (pos in 0 until activeCount) {
            if ((tracks[order[pos]].state == TrackState.TENTATIVE) == tentative) stageTracks[count++] = pos
        }
        if (count == 0) return
        val freeHighCount = collectUnused(high, highCount, freeHigh)
        runStage(detections, count, freeHigh, freeHighCount, config.trackMatchMaxCost)
        var unmatched = 0
        for (row in 0 until count) {
            val pos = stageTracks[row]
            if (trackDet[pos] < 0) stageTracks[unmatched++] = pos
        }
        val freeLowCount = collectUnused(low, lowCount, freeLow)
        runStage(detections, unmatched, freeLow, freeLowCount, config.trackLowMatchMaxCost)
    }

    /** Copies the detection indices of `source[0 until count]` that no track has taken to `target`; returns how many. */
    private fun collectUnused(
        source: IntArray,
        count: Int,
        target: IntArray,
    ): Int {
        var free = 0
        for (k in 0 until count) {
            if (!detUsed[source[k]]) target[free++] = source[k]
        }
        return free
    }

    /** Assigns the tracks `stageTracks[0 until trackCount]` to the detections `candidates[0 until candidateCount]`. */
    private fun runStage(
        detections: List<PoseDetection>,
        trackCount: Int,
        candidates: IntArray,
        candidateCount: Int,
        maxCost: Float,
    ) {
        if (trackCount == 0 || candidateCount == 0) return
        for (row in 0 until trackCount) {
            val track = tracks[order[stageTracks[row]]]
            val trackLeftImg = track.boxLeftImg()
            val trackTopImg = track.boxTopImg()
            val trackRightImg = track.boxRightImg()
            val trackBottomImg = track.boxBottomImg()
            for (col in 0 until candidateCount) {
                val index = candidates[col]
                val detection = detections[index]
                val head = heads[index]
                val cost =
                    AssociationCost.cost(
                        trackLeftImg,
                        trackTopImg,
                        trackRightImg,
                        trackBottomImg,
                        detection.boxLeftImg,
                        detection.boxTopImg,
                        detection.boxRightImg,
                        detection.boxBottomImg,
                        track.x.position,
                        track.y.position,
                        head.headXImg,
                        head.headYImg,
                        track.x.pPos + head.varianceImg2,
                        track.y.pPos + head.varianceImg2,
                        track.height.position,
                        config,
                    )
                assigner.setCost(row, col, cost)
            }
        }
        assigner.assign(trackCount, candidateCount, maxCost, rowToCol, colToRow)
        for (row in 0 until trackCount) {
            val col = rowToCol[row]
            if (col >= 0) {
                trackDet[stageTracks[row]] = candidates[col]
                detUsed[candidates[col]] = true
            }
        }
    }

    /** Applies hits and misses and compacts [order] to the surviving tracks (creation order kept). */
    private fun updateTracks(
        detections: List<PoseDetection>,
        dteNs: Double,
        frame: DetectionFrame,
    ) {
        val dteMs = (dteNs / nanosPerMilli).toFloat()
        val dteS = (dteNs / nanosPerSecond).toFloat()
        var survivors = 0
        for (pos in 0 until activeCount) {
            val slot = order[pos]
            val track = tracks[slot]
            val matched = trackDet[pos] >= 0 && applyHit(track, detections[trackDet[pos]])
            track.matchedDet = if (matched) trackDet[pos] else -1
            val alive =
                if (matched) {
                    track.isHealthy()
                } else {
                    applyMiss(track, dteMs, dteS, frame) && track.isHealthy()
                }
            if (alive) {
                order[survivors++] = slot
            } else {
                if (track.state != TrackState.TENTATIVE) {
                    removeCount++
                    keepRemoved(track)
                }
                inUse[slot] = false
            }
        }
        activeCount = survivors
    }

    /**
     * Folds a matched detection into [track]; false when its head point was unusable (not valid, or a variance
     * that is not finite and positive), checked before any filter changes; then it counts as a miss.
     */
    private fun applyHit(
        track: Track,
        detection: PoseDetection,
    ): Boolean {
        track.estimator.update(detection, point)
        if (!point.valid || !(point.varianceImg2 > 0f && point.varianceImg2.isFinite())) return false
        val boxHeightImg = detection.boxBottomImg - detection.boxTopImg
        val heightNoiseImg = config.trackHeightNoiseRatio * track.height.position
        track.x.update(point.headXImg, point.varianceImg2)
        track.y.update(point.headYImg, point.varianceImg2)
        track.height.update(boxHeightImg, heightNoiseImg * heightNoiseImg)
        setOffsets(track, detection, boxHeightImg)
        track.hits++
        track.misses = 0
        track.lostMs = 0f
        track.score = detection.score
        track.headEstimated = point.estimated
        if (track.state == TrackState.TENTATIVE && track.hits >= config.trackConfirmHits) {
            track.state = TrackState.CONFIRMED
            confirmCount++
        } else if (track.state == TrackState.LOST) {
            track.state = TrackState.CONFIRMED
            recoverCount++
        }
        return true
    }

    /** Handles a frame without a match; false when the track must be removed. */
    private fun applyMiss(
        track: Track,
        dteMs: Float,
        dteS: Float,
        frame: DetectionFrame,
    ): Boolean {
        if (track.state == TrackState.TENTATIVE) {
            track.misses++
            if (track.misses > config.trackTentativeMaxMisses) return false
            track.x.decayVelocity(dteS, missTauS)
            track.y.decayVelocity(dteS, missTauS)
            track.height.decayVelocity(dteS, missTauS)
            return true
        }
        track.state = TrackState.LOST
        track.lostMs += dteMs
        track.x.decayVelocity(dteS, missTauS)
        track.y.decayVelocity(dteS, missTauS)
        track.height.decayVelocity(dteS, missTauS)
        if (track.lostMs > config.trackMaxLostMs) return false
        val marginImg = config.trackExitMarginRatio * track.height.position
        val xImg = track.x.position
        val yImg = track.y.position
        return !(xImg < -marginImg || xImg > frame.widthImg + marginImg || yImg < -marginImg || yImg > frame.heightImg + marginImg)
    }

    private fun setOffsets(
        track: Track,
        detection: PoseDetection,
        boxHeightImg: Float,
    ) {
        track.offLeft = (detection.boxLeftImg - point.headXImg) / boxHeightImg
        track.offTop = (detection.boxTopImg - point.headYImg) / boxHeightImg
        track.offRight = (detection.boxRightImg - point.headXImg) / boxHeightImg
        track.offBottom = (detection.boxBottomImg - point.headYImg) / boxHeightImg
    }

    private fun giveBirths(detections: List<PoseDetection>) {
        val trackCountBeforeBirths = activeCount
        for (k in 0 until keepCount) {
            val index = keep[k]
            val detection = detections[index]
            if (detUsed[index]) continue
            if (detection.score < config.trackBirthScore) {
                if (nearestSlot(heads[index], trackCountBeforeBirths, true) >= 0) assocMissLowBirth++
                continue
            }
            if (overlapsTakenDetection(detections, detection, trackCountBeforeBirths)) {
                assocBirthSuppressed++
                continue
            }
            if (activeCount >= maxTracks) return
            val slot = freeSlot()
            if (slot < 0) return
            if (start(tracks[slot], detection)) {
                tracks[slot].matchedDet = index
                inUse[slot] = true
                order[activeCount++] = slot
                birthCount++
                noteBirth(detections, index, tracks[slot].id, trackCountBeforeBirths)
            }
        }
    }

    /**
     * True when [detection] overlaps, with `trackBirthSuppressIou` or more, the detection that one of the first
     * [trackCount] tracks that is not tentative took in this frame: a near duplicate of a person already followed.
     */
    private fun overlapsTakenDetection(
        detections: List<PoseDetection>,
        detection: PoseDetection,
        trackCount: Int,
    ): Boolean {
        for (pos in 0 until trackCount) {
            val track = tracks[order[pos]]
            if (track.state == TrackState.TENTATIVE) continue
            val matched = track.matchedDet
            if (matched < 0 || matched >= detections.size) continue
            val other = detections[matched]
            val iou =
                BoxOverlap.iou(
                    detection.boxLeftImg,
                    detection.boxTopImg,
                    detection.boxRightImg,
                    detection.boxBottomImg,
                    other.boxLeftImg,
                    other.boxTopImg,
                    other.boxRightImg,
                    other.boxBottomImg,
                )
            if (iou >= config.trackBirthSuppressIou) return true
        }
        return false
    }

    /** Copies what the diagnostics need of the removed [track] (confirmed or lost) before its slot is reused. */
    private fun keepRemoved(track: Track) {
        val i = removedCount++
        removedId[i] = track.id
        removedXImg[i] = track.x.position
        removedYImg[i] = track.y.position
        removedHeightImg[i] = track.height.position
        removedVarXImg2[i] = track.x.pPos
        removedVarYImg2[i] = track.y.pPos
        removedLeftImg[i] = track.boxLeftImg()
        removedTopImg[i] = track.boxTopImg()
        removedRightImg[i] = track.boxRightImg()
        removedBottomImg[i] = track.boxBottomImg()
        removedLostMs[i] = track.lostMs
        removedCause[i] =
            when {
                !track.isHealthy() -> RemovalCause.OTHER
                track.lostMs > config.trackMaxLostMs -> RemovalCause.LOST_TIME
                else -> RemovalCause.EXIT
            }
    }

    /**
     * Records the birth of the detection [index] (just given the track [newId]) next to the nearest LOST track, else
     * the nearest track that is not LOST among the first [trackCount] (those that existed before this frame's births),
     * else the nearest track removed in this frame; nothing when none is within `assocMissNearRatio` heights. Only
     * reads tracks and detections; writes the diagnostics.
     */
    private fun noteBirth(
        detections: List<PoseDetection>,
        index: Int,
        newId: Int,
        trackCount: Int,
    ) {
        val detection = detections[index]
        val head = heads[index]
        val lostSlot = nearestSlot(head, trackCount, true)
        if (lostSlot >= 0) {
            pair.fromTrack(tracks[lostSlot])
            writePair(detection, head, newId, null, TrackState.CONFIRMED, Float.NaN, RemovalCause.NONE)
            return
        }
        val liveSlot = nearestSlot(head, trackCount, false)
        if (liveSlot >= 0) {
            val live = tracks[liveSlot]
            pair.fromTrack(live)
            val detIou = matchedBoxIou(detections, live, detection)
            writePair(detection, head, newId, AssociationMissReason.LIVE, live.state, detIou, RemovalCause.NONE)
            return
        }
        val removed = nearestRemovedIndex(head)
        if (removed >= 0) {
            pair.fromRemoved(removed)
            val cause = removedCause[removed]
            writePair(detection, head, newId, AssociationMissReason.REMOVED, TrackState.CONFIRMED, Float.NaN, cause)
        }
    }

    /** IoU of [detection]'s box with the box of the detection [live] matched in this frame; NaN when it matched none. */
    private fun matchedBoxIou(
        detections: List<PoseDetection>,
        live: Track,
        detection: PoseDetection,
    ): Float {
        val matched = live.matchedDet
        if (matched < 0 || matched >= detections.size) return Float.NaN
        val other = detections[matched]
        return BoxOverlap.iou(
            detection.boxLeftImg,
            detection.boxTopImg,
            detection.boxRightImg,
            detection.boxBottomImg,
            other.boxLeftImg,
            other.boxTopImg,
            other.boxRightImg,
            other.boxBottomImg,
        )
    }

    /**
     * Writes the next record for the track in [pair] and the new detection; [forcedReason] `null` = classify the pair
     * as GATE, COST or TAKEN. The values are those of [AssociationCost] (shared functions, so the numbers are equal).
     */
    private fun writePair(
        detection: PoseDetection,
        head: HeadPoint,
        newId: Int,
        forcedReason: AssociationMissReason?,
        trackState: TrackState,
        detIou: Float,
        cause: RemovalCause,
    ) {
        val heightImg = pair.heightImg
        val dxImg = head.headXImg - pair.xImg
        val dyImg = head.headYImg - pair.yImg
        val varXImg2 = pair.varXImg2 + head.varianceImg2
        val varYImg2 = pair.varYImg2 + head.varianceImg2
        val minSigmaImg = config.trackGateMinSigmaRatio * heightImg
        val gateChi2 =
            if (varXImg2 > 0f && varYImg2 > 0f) {
                AssociationCost.mahalanobis2(dxImg, dyImg, varXImg2, varYImg2, minSigmaImg)
            } else {
                Float.NaN
            }
        val iou =
            BoxOverlap.iou(
                pair.leftImg,
                pair.topImg,
                pair.rightImg,
                pair.bottomImg,
                detection.boxLeftImg,
                detection.boxTopImg,
                detection.boxRightImg,
                detection.boxBottomImg,
            )
        val cost = AssociationCost.ungatedCost(iou, dxImg, dyImg, heightImg, config)
        val highStage = detection.score >= config.trackHighScore
        val maxCost = if (highStage) config.trackMatchMaxCost else config.trackLowMatchMaxCost
        val reason = forcedReason ?: AssociationMissReason.classify(gateChi2, cost, maxCost, config.trackGateChi2)
        misses[missCount++].set(
            lostId = pair.id,
            newId = newId,
            lostMs = pair.lostMs,
            score = detection.score,
            highStage = highStage,
            gateChi2 = gateChi2,
            iou = iou,
            headRatio = sqrt(dxImg * dxImg + dyImg * dyImg) / (config.assocHeadScaleRatio * heightImg),
            hRatio = (detection.boxBottomImg - detection.boxTopImg) / heightImg,
            cost = cost,
            reason = reason,
            trackState = trackState,
            detIou = detIou,
            cause = cause,
        )
        if (reason != AssociationMissReason.LIVE && reason != AssociationMissReason.REMOVED) assocMisses++
        when (reason) {
            AssociationMissReason.GATE -> assocMissGate++
            AssociationMissReason.COST -> assocMissCost++
            AssociationMissReason.TAKEN -> assocMissTaken++
            AssociationMissReason.LIVE -> assocBirthNearLive++
            AssociationMissReason.REMOVED -> assocBirthAfterRemoval++
        }
    }

    /**
     * Pool index of the track whose head is nearest to [head] and within `assocMissNearRatio` heights, among the first
     * [trackCount] tracks: a LOST one when [lost] is true, else one that is not LOST; -1 when there is none.
     */
    private fun nearestSlot(
        head: HeadPoint,
        trackCount: Int,
        lost: Boolean,
    ): Int {
        var best = -1
        var bestDistanceImg2 = Float.MAX_VALUE
        for (pos in 0 until trackCount) {
            val slot = order[pos]
            val track = tracks[slot]
            if ((track.state == TrackState.LOST) != lost || !(track.height.position > 0f)) continue
            val dxImg = head.headXImg - track.x.position
            val dyImg = head.headYImg - track.y.position
            val distanceImg2 = dxImg * dxImg + dyImg * dyImg
            val reachImg = config.assocMissNearRatio * track.height.position
            if (distanceImg2 <= reachImg * reachImg && distanceImg2 < bestDistanceImg2) {
                best = slot
                bestDistanceImg2 = distanceImg2
            }
        }
        return best
    }

    /** Index in the removed-track scratch of the nearest one within `assocMissNearRatio` heights of [head], or -1. */
    private fun nearestRemovedIndex(head: HeadPoint): Int {
        var best = -1
        var bestDistanceImg2 = Float.MAX_VALUE
        for (i in 0 until removedCount) {
            if (!(removedHeightImg[i] > 0f)) continue
            val dxImg = head.headXImg - removedXImg[i]
            val dyImg = head.headYImg - removedYImg[i]
            val distanceImg2 = dxImg * dxImg + dyImg * dyImg
            val reachImg = config.assocMissNearRatio * removedHeightImg[i]
            if (distanceImg2 <= reachImg * reachImg && distanceImg2 < bestDistanceImg2) {
                best = i
                bestDistanceImg2 = distanceImg2
            }
        }
        return best
    }

    /** The track a diagnostics record is about (a live track or a removed one), copied into one reused object. */
    private inner class PairScratch {
        var id = 0
        var lostMs = 0f
        var xImg = 0f
        var yImg = 0f
        var heightImg = 0f
        var varXImg2 = 0f
        var varYImg2 = 0f
        var leftImg = 0f
        var topImg = 0f
        var rightImg = 0f
        var bottomImg = 0f

        fun fromTrack(track: Track) {
            id = track.id
            lostMs = track.lostMs
            xImg = track.x.position
            yImg = track.y.position
            heightImg = track.height.position
            varXImg2 = track.x.pPos
            varYImg2 = track.y.pPos
            leftImg = track.boxLeftImg()
            topImg = track.boxTopImg()
            rightImg = track.boxRightImg()
            bottomImg = track.boxBottomImg()
        }

        fun fromRemoved(i: Int) {
            id = removedId[i]
            lostMs = removedLostMs[i]
            xImg = removedXImg[i]
            yImg = removedYImg[i]
            heightImg = removedHeightImg[i]
            varXImg2 = removedVarXImg2[i]
            varYImg2 = removedVarYImg2[i]
            leftImg = removedLeftImg[i]
            topImg = removedTopImg[i]
            rightImg = removedRightImg[i]
            bottomImg = removedBottomImg[i]
        }
    }

    private val pair = PairScratch()

    private fun freeSlot(): Int {
        for (slot in 0 until maxTracks) {
            if (!inUse[slot]) return slot
        }
        return -1
    }

    /** Starts [track] on [detection]; false (and the track unused) when the detection gives no usable state. */
    private fun start(
        track: Track,
        detection: PoseDetection,
    ): Boolean {
        track.estimator.reset()
        track.estimator.update(detection, point)
        if (!point.valid) return false
        val boxHeightImg = detection.boxBottomImg - detection.boxTopImg
        val velocityNoiseImg = config.trackInitVelocitySigmaRatio * boxHeightImg
        val heightNoiseImg = config.trackHeightNoiseRatio * boxHeightImg
        val velocityVariance = velocityNoiseImg * velocityNoiseImg
        val started =
            track.x.reset(point.headXImg, point.varianceImg2, velocityVariance) &&
                track.y.reset(point.headYImg, point.varianceImg2, velocityVariance) &&
                track.height.reset(boxHeightImg, heightNoiseImg * heightNoiseImg, velocityVariance)
        if (!started) return false
        setOffsets(track, detection, boxHeightImg)
        track.id = nextId++
        track.hits = 1
        track.misses = 0
        track.lostMs = 0f
        track.score = detection.score
        track.headEstimated = point.estimated
        if (config.trackConfirmHits <= 1) {
            track.state = TrackState.CONFIRMED
            confirmCount++
        } else {
            track.state = TrackState.TENTATIVE
        }
        return true
    }

    private fun noteShown() {
        var shown = 0
        for (pos in 0 until activeCount) {
            if (tracks[order[pos]].state != TrackState.TENTATIVE) shown++
        }
        if (shown > mostShownAtOnce) mostShownAtOnce = shown
    }

    /** The reused read-only view of the active tracks. */
    private inner class View : TrackList {
        override val size: Int
            get() = activeCount

        override fun id(index: Int): Int = at(index).id

        override fun state(index: Int): TrackState = at(index).state

        override fun isShown(index: Int): Boolean = at(index).state != TrackState.TENTATIVE

        override fun headXImg(index: Int): Float = at(index).x.position

        override fun headYImg(index: Int): Float = at(index).y.position

        override fun velocityXImgPerS(index: Int): Float = at(index).x.velocity

        override fun velocityYImgPerS(index: Int): Float = at(index).y.velocity

        override fun boxLeftImg(index: Int): Float = at(index).boxLeftImg()

        override fun boxTopImg(index: Int): Float = at(index).boxTopImg()

        override fun boxRightImg(index: Int): Float = at(index).boxRightImg()

        override fun boxBottomImg(index: Int): Float = at(index).boxBottomImg()

        override fun heightImg(index: Int): Float = at(index).height.position

        override fun score(index: Int): Float = at(index).score

        override fun isHeadEstimated(index: Int): Boolean = at(index).headEstimated

        override fun lostMs(index: Int): Float = at(index).lostMs

        override fun matchedDetectionIndex(index: Int): Int = at(index).matchedDet

        private fun at(index: Int): Track {
            if (index < 0 || index >= activeCount) throw IndexOutOfBoundsException("index $index, size $activeCount")
            return tracks[order[index]]
        }
    }
}
