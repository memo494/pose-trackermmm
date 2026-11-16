package com.memo494.posetracker.core.tracking

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.sqrt

class TrackingMetricsTest {
    private val frameStepS = 0.05

    private fun walker(
        id: Int,
        fromX: Double,
        toX: Double,
        y: Double,
    ): ScenePerson {
        val path = listOf(PathPoint(0.0, fromX, y, 100.0), PathPoint(2.0, toX, y, 100.0))
        return ScenePerson(id, path)
    }

    private fun followerTracks(
        scene: SyntheticScene,
        framesBehind: Int,
    ): List<MetricsFrame> =
        scene.frames.map { sceneFrame ->
            val source = scene.frames.getOrNull(sceneFrame.index - framesBehind)
            val tracks = source?.truth?.map { ShownTrack(it.personId, it.headXImg, it.headYImg) } ?: emptyList()
            sceneFrame.toMetricsFrame(tracks)
        }

    private fun twoStaticPeople(
        frameCount: Int,
        tracksOf: (Int) -> List<ShownTrack>,
    ): List<MetricsFrame> {
        val truth = listOf(TruthPerson(1, 100.0, 300.0, 100.0), TruthPerson(2, 500.0, 300.0, 100.0))
        return (0 until frameCount).map { MetricsFrame(it * frameStepS, truth, tracksOf(it)) }
    }

    @Test
    fun perfectFollower_hasNoSwitchesFullCoverageNoGhostsAndNoLag() {
        val scene = SyntheticScene(listOf(walker(1, 100.0, 500.0, 300.0)), durationS = 2.0, fps = 20.0)
        val metrics = TrackingMetrics(followerTracks(scene, framesBehind = 0))
        assertThat(metrics.frameCount).isEqualTo(40)
        assertThat(metrics.idSwitches(1)).isEqualTo(0)
        assertThat(metrics.coverage(1)).isEqualTo(1.0)
        assertThat(metrics.ghostFrames()).isEqualTo(0)
        assertThat(metrics.lagFrames(1)).isWithin(1e-9).of(0.0)
        assertThat(metrics.maxErrorInH(1)).isWithin(1e-9).of(0.0)
    }

    @Test
    fun crossingPeople_followedPerfectly_keepTheirTracks() {
        val people = listOf(walker(1, 100.0, 500.0, 300.0), walker(2, 500.0, 100.0, 310.0))
        val scene = SyntheticScene(people, durationS = 2.0, fps = 20.0)
        val metrics = TrackingMetrics(followerTracks(scene, framesBehind = 0))
        assertThat(metrics.idSwitches(1)).isEqualTo(0)
        assertThat(metrics.idSwitches(2)).isEqualTo(0)
        assertThat(metrics.coverage(1)).isEqualTo(1.0)
        assertThat(metrics.coverage(2)).isEqualTo(1.0)
        assertThat(metrics.ghostFrames()).isEqualTo(0)
        assertThat(metrics.matchedTrackId(20, 1)).isEqualTo(1)
        assertThat(metrics.matchedTrackId(20, 2)).isEqualTo(2)
    }

    @Test
    fun followerOneFrameBehind_hasLagOne() {
        val scene = SyntheticScene(listOf(walker(1, 100.0, 500.0, 300.0)), durationS = 2.0, fps = 20.0)
        val metrics = TrackingMetrics(followerTracks(scene, framesBehind = 1))
        assertThat(metrics.lagFrames(1)).isWithin(1e-3).of(1.0)
        assertThat(metrics.coverage(1, fromFrame = 1)).isEqualTo(1.0)
        assertThat(metrics.coverage(1)).isWithin(1e-9).of(39.0 / 40.0)
    }

    @Test
    fun followerTwoFramesBehind_underFrameJitter_hasLagTwo() {
        val scene = SyntheticScene(listOf(walker(1, 100.0, 500.0, 300.0)), durationS = 2.0, seed = 5L, frameJitterFraction = 0.3)
        val metrics = TrackingMetrics(followerTracks(scene, framesBehind = 2))
        assertThat(metrics.lagFrames(1, fromFrame = 2)).isWithin(0.3).of(2.0)
        assertThat(metrics.lagFrames(1, fromFrame = 2)).isGreaterThan(1.5)
    }

    @Test
    fun stillPerson_hasNoConstantVelocityFramesSoLagIsNaN() {
        val still = ScenePerson(1, ScenePerson.stillPath(100.0, 300.0, 100.0, 0.0, 10.0))
        val scene = SyntheticScene(listOf(still), durationS = 1.0)
        val metrics = TrackingMetrics(followerTracks(scene, framesBehind = 0))
        assertThat(metrics.lagFrames(1).isNaN()).isTrue()
    }

    @Test
    fun trackPairSwappingPeopleOnce_isOneSwitchForEachPerson() {
        val frames =
            twoStaticPeople(10) { i ->
                if (i < 5) {
                    listOf(ShownTrack(11, 100.0, 300.0), ShownTrack(12, 500.0, 300.0))
                } else {
                    listOf(ShownTrack(11, 500.0, 300.0), ShownTrack(12, 100.0, 300.0))
                }
            }
        val metrics = TrackingMetrics(frames)
        assertThat(metrics.idSwitches(1)).isEqualTo(1)
        assertThat(metrics.idSwitches(2)).isEqualTo(1)
        assertThat(metrics.coverage(1)).isEqualTo(1.0)
        assertThat(metrics.ghostFrames()).isEqualTo(0)
    }

    @Test
    fun oneTrack_servesAtMostOnePersonAndTheNearestOrLowerIdWins() {
        val truth = listOf(TruthPerson(1, 100.0, 300.0, 100.0), TruthPerson(2, 110.0, 300.0, 100.0))
        val frames = listOf(MetricsFrame(0.0, truth, listOf(ShownTrack(9, 105.0, 300.0))))
        val tie = TrackingMetrics(frames)
        assertThat(tie.matchedTrackId(0, 1)).isEqualTo(9)
        assertThat(tie.matchedTrackId(0, 2)).isNull()
        val nearer = TrackingMetrics(listOf(MetricsFrame(0.0, truth, listOf(ShownTrack(9, 108.0, 300.0)))))
        assertThat(nearer.matchedTrackId(0, 1)).isNull()
        assertThat(nearer.matchedTrackId(0, 2)).isEqualTo(9)
    }

    @Test
    fun matching_reachesHalfAHeightAndNoFurther() {
        val truth = listOf(TruthPerson(1, 100.0, 300.0, 100.0))
        val inside = TrackingMetrics(listOf(MetricsFrame(0.0, truth, listOf(ShownTrack(1, 149.0, 300.0)))))
        val outside = TrackingMetrics(listOf(MetricsFrame(0.0, truth, listOf(ShownTrack(1, 151.0, 300.0)))))
        assertThat(inside.coverage(1)).isEqualTo(1.0)
        assertThat(outside.coverage(1)).isEqualTo(0.0)
        assertThat(outside.ghostFrames()).isEqualTo(1)
        assertThat(inside.ghostFrames()).isEqualTo(0)
    }

    @Test
    fun coverage_countsOnlyFramesFromTheGivenFrameOn() {
        val frames =
            twoStaticPeople(10) { i ->
                if (i < 5) emptyList() else listOf(ShownTrack(11, 100.0, 300.0))
            }
        val metrics = TrackingMetrics(frames)
        assertThat(metrics.coverage(1)).isWithin(1e-9).of(0.5)
        assertThat(metrics.coverage(1, fromFrame = 5)).isEqualTo(1.0)
        assertThat(metrics.coverage(1, fromFrame = 0, toFrameExclusive = 5)).isEqualTo(0.0)
        assertThat(metrics.coverage(99).isNaN()).isTrue()
    }

    @Test
    fun ghostFrames_countsTracksWithNobodyNearIncludingFramesWithoutPeople() {
        val person = listOf(TruthPerson(1, 100.0, 300.0, 100.0))
        val frames =
            listOf(
                MetricsFrame(0.0, person, listOf(ShownTrack(1, 100.0, 300.0), ShownTrack(2, 110.0, 300.0), ShownTrack(3, 400.0, 300.0))),
                MetricsFrame(0.05, emptyList(), listOf(ShownTrack(1, 100.0, 300.0))),
                MetricsFrame(0.10, emptyList(), emptyList()),
            )
        assertThat(TrackingMetrics(frames).ghostFrames()).isEqualTo(2)
    }

    @Test
    fun jitterRms_ofAnAlternatingSeries_isExact() {
        assertThat(TrackingMetrics.jitterRms(doubleArrayOf(0.0, 1.0, 0.0, 1.0, 0.0))).isWithin(1e-12).of(2.0)
    }

    @Test
    fun jitterRms_ofALineIsZero_skipsNaNTriplesAndIsNaNWithoutTriples() {
        assertThat(TrackingMetrics.jitterRms(doubleArrayOf(1.0, 3.0, 5.0, 7.0))).isWithin(1e-12).of(0.0)
        val withGap = doubleArrayOf(0.0, 1.0, Double.NaN, 1.0, 0.0, 1.0)
        assertThat(TrackingMetrics.jitterRms(withGap)).isWithin(1e-12).of(2.0)
        assertThat(TrackingMetrics.jitterRms(doubleArrayOf(1.0, 2.0)).isNaN()).isTrue()
    }

    private fun overshootFrames(trackX: DoubleArray): List<MetricsFrame> =
        trackX.indices.map { i ->
            val truth = listOf(TruthPerson(1, minOf(10.0 * i, 100.0), 300.0, 100.0))
            MetricsFrame(i * frameStepS, truth, listOf(ShownTrack(4, trackX[i], 300.0)))
        }

    @Test
    fun overshoot_ofAnOvershootingSeries_isTheLargestExcessInHeights() {
        val trackX =
            doubleArrayOf(0.0, 10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0, 100.0, 105.0, 108.0, 104.0, 101.0, 100.0, 100.0)
        val metrics = TrackingMetrics(overshootFrames(trackX))
        assertThat(metrics.overshoot(1, stopTimeS = 0.5)).isWithin(1e-9).of(0.08)
    }

    @Test
    fun overshoot_ofATrackThatArrivesFromBehind_isZero() {
        val trackX =
            doubleArrayOf(0.0, 10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0, 95.0, 98.0, 99.0, 100.0, 100.0)
        val metrics = TrackingMetrics(overshootFrames(trackX))
        assertThat(metrics.overshoot(1, stopTimeS = 0.5)).isEqualTo(0.0)
    }

    @Test
    fun overshoot_countsOnlyTheDirectionOfTravel() {
        val trackX =
            doubleArrayOf(0.0, 10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0, 100.0, 95.0, 92.0, 95.0, 100.0)
        val metrics = TrackingMetrics(overshootFrames(trackX))
        assertThat(metrics.overshoot(1, stopTimeS = 0.5)).isEqualTo(0.0)
    }

    @Test
    fun series_giveTheMatchedTrackAndTheRawMeasurementWithNaNWhereMissing() {
        val dropout = TimeInterval(0.5, 1.0)
        val person = ScenePerson(1, ScenePerson.stillPath(100.0, 300.0, 100.0, 0.0, 10.0), dropouts = listOf(dropout))
        val scene = SyntheticScene(listOf(person), durationS = 2.0, fps = 20.0)
        val metrics = TrackingMetrics(followerTracks(scene, framesBehind = 0))
        val raw = metrics.rawSeries(1, Axis.X)
        val shown = metrics.matchedTrackSeries(1, Axis.Y, fromFrame = 8, toFrameExclusive = 12)
        assertThat(raw).hasLength(40)
        assertThat(raw[0]).isWithin(1e-3).of(100.0)
        assertThat(raw[12].isNaN()).isTrue()
        assertThat(shown).hasLength(4)
        assertThat(shown[0]).isEqualTo(300.0)
        assertThat(shown[3]).isEqualTo(300.0)
    }

    @Test
    fun rawSeriesOfNoisyDetections_hasTheJitterTheNoiseImplies() {
        val person = ScenePerson(1, ScenePerson.stillPath(500.0, 300.0, 200.0, 0.0, 1000.0))
        val clean = SyntheticScene(listOf(person), durationS = 200.0)
        val noisy = SyntheticScene(listOf(person), durationS = 200.0, seed = 3L, keypointNoiseRatio = 0.01)
        val cleanJitter = TrackingMetrics.jitterRms(TrackingMetrics(followerTracks(clean, 0)).rawSeries(1, Axis.X))
        val noisyJitter = TrackingMetrics.jitterRms(TrackingMetrics(followerTracks(noisy, 0)).rawSeries(1, Axis.X))
        assertThat(cleanJitter).isWithin(1e-3).of(0.0)
        // Mean of five independent keypoints with sigma 2 px: sigma 2/sqrt(5); the second difference has variance 6 sigma^2.
        assertThat(noisyJitter).isWithin(0.15).of(sqrt(6.0 * 4.0 / 5.0))
    }
}
