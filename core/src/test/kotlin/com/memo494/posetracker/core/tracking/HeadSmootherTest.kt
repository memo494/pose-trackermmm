package com.memo494.posetracker.core.tracking

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

private const val STEP_20_FPS_NS = 50_000_000L
private const val HEIGHT_IMG = 200f
private const val BASE_X_IMG = 500f
private const val BASE_Y_IMG = 300f
private const val NOISE_HEIGHTS = 0.01f
private const val NOISE_FRAMES = 1000
private const val WARM_UP_FRAMES = 20
private const val SEEDS = 8
private const val MAX_JITTER_RATIO = 0.2
private const val WALK_SPEED_HEIGHTS_PER_S = 2f
private const val FPS = 20
private const val WALK_FRAMES = 80
private const val SETTLE_FRAMES = 40
private const val MAX_LAG_FRAMES = 0.6f
private const val MAX_OVERSHOOT_HEIGHTS = 0.05f
private const val POSITION_TOLERANCE_IMG = 1e-3f

class HeadSmootherTest {
    /** One frame with one id; returns the smoothed x and leaves y in `smoother.outYImg`. */
    private fun frame(
        smoother: HeadSmoother,
        timestampNs: Long,
        id: Int,
        xImg: Float,
        yImg: Float = BASE_Y_IMG,
        heightImg: Float = HEIGHT_IMG,
    ): Float {
        smoother.begin(timestampNs)
        smoother.smooth(id, xImg, yImg, heightImg)
        smoother.end()
        return smoother.outXImg
    }

    /** Root mean square of the second difference of [values] (the metric of the tracker's jitter checks). */
    private fun jitterRms(values: List<Float>): Double {
        var sum = 0.0
        for (i in 1 until values.size - 1) {
            val d = (values[i + 1] - 2 * values[i] + values[i - 1]).toDouble()
            sum += d * d
        }
        return sqrt(sum / (values.size - 2))
    }

    @Test
    fun firstSampleOfAnId_passesThrough() {
        val smoother = HeadSmoother(TrackerConfig())

        frame(smoother, 0L, 1, 123.5f, 77.25f)

        assertThat(smoother.outXImg).isEqualTo(123.5f)
        assertThat(smoother.outYImg).isEqualTo(77.25f)
        assertThat(smoother.activeCount).isEqualTo(1)
    }

    @Test
    fun constantInput_staysConstant() {
        val smoother = HeadSmoother(TrackerConfig())

        for (i in 0 until 100) frame(smoother, i * STEP_20_FPS_NS, 1, BASE_X_IMG, BASE_Y_IMG)

        assertThat(smoother.outXImg).isEqualTo(BASE_X_IMG)
        assertThat(smoother.outYImg).isEqualTo(BASE_Y_IMG)
    }

    @Test
    fun aStep_convergesToTheNewPositionAndNeverOvershoots() {
        val smoother = HeadSmoother(TrackerConfig())
        for (i in 0 until 20) frame(smoother, i * STEP_20_FPS_NS, 1, 100f)
        var highest = 0f

        for (i in 20 until 120) {
            val out = frame(smoother, i * STEP_20_FPS_NS, 1, 300f)
            highest = max(highest, out)
        }

        assertThat(smoother.outXImg).isWithin(POSITION_TOLERANCE_IMG).of(300f)
        assertThat(highest).isAtMost(300f + POSITION_TOLERANCE_IMG)
    }

    @Test
    fun aStillPersonWithNoise_isMuchSteadierThanTheInput_at20Fps() {
        var worstRatio = 0.0
        for (seed in 1..SEEDS) {
            val random = Random(seed.toLong())
            val smoother = HeadSmoother(TrackerConfig())
            val rawXs = ArrayList<Float>()
            val rawYs = ArrayList<Float>()
            val outXs = ArrayList<Float>()
            val outYs = ArrayList<Float>()
            for (i in 0 until NOISE_FRAMES) {
                val x = BASE_X_IMG + NOISE_HEIGHTS * HEIGHT_IMG * random.nextGaussian().toFloat()
                val y = BASE_Y_IMG + NOISE_HEIGHTS * HEIGHT_IMG * random.nextGaussian().toFloat()
                smoother.begin(i * STEP_20_FPS_NS)
                smoother.smooth(1, x, y, HEIGHT_IMG)
                smoother.end()
                if (i < WARM_UP_FRAMES) continue
                rawXs.add(x)
                rawYs.add(y)
                outXs.add(smoother.outXImg)
                outYs.add(smoother.outYImg)
            }
            val outJitter = jitterRms(outXs) * jitterRms(outXs) + jitterRms(outYs) * jitterRms(outYs)
            val rawJitter = jitterRms(rawXs) * jitterRms(rawXs) + jitterRms(rawYs) * jitterRms(rawYs)
            worstRatio = max(worstRatio, sqrt(outJitter / rawJitter))
        }

        assertThat(worstRatio).isLessThan(MAX_JITTER_RATIO)
    }

    @Test
    fun aPersonWalkingAtConstantSpeed_isFollowedWithLessThanSixTenthsOfAFrameOfLag() {
        val smoother = HeadSmoother(TrackerConfig())
        val pxPerFrame = WALK_SPEED_HEIGHTS_PER_S * HEIGHT_IMG / FPS
        var worstLagFrames = 0f

        for (i in 0 until WALK_FRAMES) {
            val truth = 100f + pxPerFrame * i
            val out = frame(smoother, i * STEP_20_FPS_NS, 1, truth)
            if (i >= SETTLE_FRAMES) worstLagFrames = max(worstLagFrames, (truth - out) / pxPerFrame)
        }

        assertThat(worstLagFrames).isAtMost(MAX_LAG_FRAMES)
        assertThat(worstLagFrames).isGreaterThan(0f)
    }

    @Test
    fun afterAnAbruptStop_theSmoothedPointDoesNotShootPastTheStopPosition() {
        val smoother = HeadSmoother(TrackerConfig())
        val pxPerFrame = WALK_SPEED_HEIGHTS_PER_S * HEIGHT_IMG / FPS
        val stopXImg = 100f + pxPerFrame * (WALK_FRAMES - 1)
        var overshootImg = 0f

        for (i in 0 until WALK_FRAMES + 40) {
            val truth = if (i < WALK_FRAMES) 100f + pxPerFrame * i else stopXImg
            val out = frame(smoother, i * STEP_20_FPS_NS, 1, truth)
            overshootImg = max(overshootImg, out - stopXImg)
        }

        assertThat(overshootImg / HEIGHT_IMG).isAtMost(MAX_OVERSHOOT_HEIGHTS)
        assertThat(smoother.outXImg).isWithin(1f).of(stopXImg)
    }

    @Test
    fun aTallerTrack_isSmoothedMoreAtTheSameSpeedInImgPixels() {
        val tall = HeadSmoother(TrackerConfig())
        val short = HeadSmoother(TrackerConfig())
        var tallLagImg = 0f
        var shortLagImg = 0f

        for (i in 0 until WALK_FRAMES) {
            val truth = 100f + 20f * i
            tallLagImg = truth - frame(tall, i * STEP_20_FPS_NS, 1, truth, heightImg = 400f)
            shortLagImg = truth - frame(short, i * STEP_20_FPS_NS, 1, truth, heightImg = 100f)
        }

        assertThat(shortLagImg).isLessThan(tallLagImg)
    }

    @Test
    fun theTwoAxesShareOneCutoff_soADiagonalMoveKeepsItsDirection() {
        val smoother = HeadSmoother(TrackerConfig())
        var lastXImg = 0f

        for (i in 0 until WALK_FRAMES) {
            smoother.begin(i * STEP_20_FPS_NS)
            smoother.smooth(1, 100f + 10f * i, 50f + 10f * i, HEIGHT_IMG)
            smoother.end()
            lastXImg = smoother.outXImg
        }

        assertThat(smoother.outYImg - 50f).isWithin(POSITION_TOLERANCE_IMG).of(lastXImg - 100f)
    }

    @Test
    fun aFrameTimeThatIsNotAfterTheLastOne_returnsTheStoredPointAndChangesNothing() {
        val withBogus = HeadSmoother(TrackerConfig())
        val without = HeadSmoother(TrackerConfig())
        frame(withBogus, 0L, 1, 100f)
        frame(without, 0L, 1, 100f)
        frame(withBogus, STEP_20_FPS_NS, 1, 110f)
        val stored = frame(without, STEP_20_FPS_NS, 1, 110f)

        val sameTime = frame(withBogus, STEP_20_FPS_NS, 1, 500f)
        val olderTime = frame(withBogus, STEP_20_FPS_NS / 2, 1, 900f)

        assertThat(sameTime).isEqualTo(stored)
        assertThat(olderTime).isEqualTo(stored)
        val next = frame(withBogus, 2 * STEP_20_FPS_NS, 1, 120f)
        assertThat(next).isEqualTo(frame(without, 2 * STEP_20_FPS_NS, 1, 120f))
    }

    @Test
    fun idsAreIndependent() {
        val smoother = HeadSmoother(TrackerConfig())
        val alone = HeadSmoother(TrackerConfig())
        var aloneOut = 0f
        var outOfFirst = 0f

        for (i in 0 until 30) {
            val t = i * STEP_20_FPS_NS
            smoother.begin(t)
            smoother.smooth(1, 100f + 3f * i, 50f, HEIGHT_IMG)
            outOfFirst = smoother.outXImg
            smoother.smooth(2, 900f - 7f * i, 400f, HEIGHT_IMG)
            smoother.end()
            aloneOut = frame(alone, t, 1, 100f + 3f * i, 50f)
        }

        assertThat(outOfFirst).isEqualTo(aloneOut)
        assertThat(smoother.activeCount).isEqualTo(2)
    }

    @Test
    fun anIdThatIsNotSmoothedInAFrame_losesItsSlot_andStartsAgainWhenItComesBack() {
        val smoother = HeadSmoother(TrackerConfig())
        frame(smoother, 0L, 1, 100f)
        frame(smoother, STEP_20_FPS_NS, 1, 110f)
        smoother.begin(2 * STEP_20_FPS_NS)
        smoother.smooth(2, 400f, 100f, HEIGHT_IMG)
        smoother.end()
        assertThat(smoother.activeCount).isEqualTo(1)
        assertThat(smoother.recall(1)).isFalse()

        frame(smoother, 3 * STEP_20_FPS_NS, 1, 800f)

        assertThat(smoother.outXImg).isEqualTo(800f)
    }

    @Test
    fun freedSlotsAreReused_soMoreIdsThanSlotsPassOverTime() {
        val config = TrackerConfig(maxTracks = TrackerConfig.MIN_MAX_TRACKS)
        val smoother = HeadSmoother(config)

        for (id in 1..50) {
            frame(smoother, id * STEP_20_FPS_NS, id, id * 10f)
            assertThat(smoother.outXImg).isEqualTo(id * 10f)
            assertThat(smoother.activeCount).isEqualTo(1)
        }
        frame(smoother, 60 * STEP_20_FPS_NS, 50, 600f)
        assertThat(smoother.outXImg).isLessThan(600f)
        assertThat(smoother.outXImg).isGreaterThan(500f)
    }

    @Test
    fun aFullPool_passesAnExtraIdThroughUnchangedAndKeepsTheOthers() {
        val smoother = HeadSmoother(TrackerConfig(maxTracks = 2))
        smoother.begin(0L)
        smoother.smooth(1, 100f, 100f, HEIGHT_IMG)
        smoother.smooth(2, 200f, 100f, HEIGHT_IMG)
        smoother.smooth(3, 300f, 100f, HEIGHT_IMG)
        smoother.end()
        assertThat(smoother.outXImg).isEqualTo(300f)
        assertThat(smoother.activeCount).isEqualTo(2)

        smoother.begin(STEP_20_FPS_NS)
        smoother.smooth(1, 140f, 100f, HEIGHT_IMG)
        val first = smoother.outXImg
        smoother.smooth(2, 240f, 100f, HEIGHT_IMG)
        smoother.smooth(3, 350f, 100f, HEIGHT_IMG)
        smoother.end()

        assertThat(first).isGreaterThan(100f)
        assertThat(first).isLessThan(140f)
        assertThat(smoother.outXImg).isEqualTo(350f)
        assertThat(smoother.activeCount).isEqualTo(2)
    }

    @Test
    fun aMarkedId_isNeverEvictedByTheFullPoolFallback() {
        val smoother = HeadSmoother(TrackerConfig(maxTracks = 2))
        smoother.begin(0L)
        smoother.smooth(2, 200f, 100f, HEIGHT_IMG)
        smoother.smooth(1, 100f, 100f, HEIGHT_IMG)
        smoother.end()

        smoother.begin(STEP_20_FPS_NS)
        smoother.mark(3)
        smoother.mark(2)
        smoother.smooth(3, 300f, 100f, HEIGHT_IMG)
        smoother.smooth(2, 240f, 100f, HEIGHT_IMG)
        smoother.end()

        assertThat(smoother.outXImg).isGreaterThan(200f)
        assertThat(smoother.outXImg).isLessThan(240f)
        assertThat(smoother.activeCount).isEqualTo(2)
    }

    @Test
    fun disabled_isTheIdentity_andHoldsNoState() {
        val smoother = HeadSmoother(TrackerConfig(smoothEnabled = false))

        for (i in 0 until 10) {
            val x = 100f + 37f * (i % 3)
            frame(smoother, i * STEP_20_FPS_NS, 1, x, x / 2)
            assertThat(smoother.outXImg).isEqualTo(x)
            assertThat(smoother.outYImg).isEqualTo(x / 2)
        }

        assertThat(smoother.activeCount).isEqualTo(0)
        assertThat(smoother.recall(1)).isFalse()
    }

    @Test
    fun reset_forgetsEveryId_soTheNextSampleIsAFirstSample() {
        val smoother = HeadSmoother(TrackerConfig())
        frame(smoother, 0L, 1, 100f)
        frame(smoother, STEP_20_FPS_NS, 1, 110f)

        smoother.reset()

        assertThat(smoother.activeCount).isEqualTo(0)
        assertThat(smoother.recall(1)).isFalse()
        assertThat(frame(smoother, 2 * STEP_20_FPS_NS, 1, 700f)).isEqualTo(700f)
    }

    @Test
    fun recall_givesTheStoredPointOfAKnownIdAndChangesNothing() {
        val reference = HeadSmoother(TrackerConfig())
        val smoother = HeadSmoother(TrackerConfig())
        var storedOfFirst = 0f
        for (i in 0 until 5) {
            val t = i * STEP_20_FPS_NS
            frame(reference, t, 1, 100f + 9f * i)
            smoother.begin(t)
            smoother.smooth(1, 100f + 9f * i, BASE_Y_IMG, HEIGHT_IMG)
            storedOfFirst = smoother.outXImg
            smoother.smooth(2, 700f, BASE_Y_IMG, HEIGHT_IMG)
            smoother.end()
        }

        val known = smoother.recall(1)
        val recalled = smoother.outXImg
        val unknown = smoother.recall(3)

        assertThat(known).isTrue()
        assertThat(recalled).isEqualTo(storedOfFirst)
        assertThat(unknown).isFalse()
        assertThat(smoother.outXImg).isEqualTo(storedOfFirst)
        smoother.begin(5 * STEP_20_FPS_NS)
        smoother.smooth(1, 160f, BASE_Y_IMG, HEIGHT_IMG)
        assertThat(smoother.outXImg).isEqualTo(frame(reference, 5 * STEP_20_FPS_NS, 1, 160f))
    }

    @Test
    fun aPointThatIsNotFinite_passesThroughAndNeverEntersTheState() {
        val withBad = HeadSmoother(TrackerConfig())
        val clean = HeadSmoother(TrackerConfig())
        frame(withBad, 0L, 1, 100f)
        frame(clean, 0L, 1, 100f)

        val nan = frame(withBad, STEP_20_FPS_NS, 1, Float.NaN)
        val infinite = frame(withBad, 2 * STEP_20_FPS_NS, 1, 100f, Float.POSITIVE_INFINITY)
        val infiniteY = withBad.outYImg
        val noHeight = frame(withBad, 3 * STEP_20_FPS_NS, 1, 5000f, heightImg = 0f)

        assertThat(nan.isNaN()).isTrue()
        assertThat(infinite).isEqualTo(100f)
        assertThat(infiniteY).isEqualTo(Float.POSITIVE_INFINITY)
        assertThat(noHeight).isEqualTo(5000f)
        val after = frame(withBad, 4 * STEP_20_FPS_NS, 1, 120f)
        assertThat(after).isEqualTo(frame(clean, 4 * STEP_20_FPS_NS, 1, 120f))
        assertThat(abs(after - 100f)).isLessThan(20f)
    }

    @Test
    fun aNonFinitePointOfAnUnknownId_takesNoSlot() {
        val smoother = HeadSmoother(TrackerConfig())

        frame(smoother, 0L, 1, Float.NaN)

        assertThat(smoother.activeCount).isEqualTo(0)
    }

    /** Smooths one frame of id 1 and gives it a lead of [leadXImg]; returns the smoother. */
    private fun smootherWithLead(leadXImg: Float): HeadSmoother {
        val smoother = HeadSmoother(TrackerConfig())
        frame(smoother, 0L, 1, BASE_X_IMG)
        frame(smoother, STEP_20_FPS_NS, 1, BASE_X_IMG)
        smoother.settleLead(leadXImg, 0f, HEIGHT_IMG, fade = true)
        return smoother
    }

    @Test
    fun settleLead_afterAFrameTimeThatIsNotAfterTheLastOne_decaysTheLeadToZeroInsteadOfHoldingIt() {
        val smoother = smootherWithLead(20f)

        smoother.begin(STEP_20_FPS_NS)
        smoother.smooth(1, BASE_X_IMG, BASE_Y_IMG, HEIGHT_IMG)
        smoother.settleLead(0f, 0f, HEIGHT_IMG, fade = true)
        smoother.end()

        assertThat(smoother.outLeadXImg).isEqualTo(0f)
        assertThat(smoother.recall(1)).isTrue()
        assertThat(smoother.outLeadXImg).isEqualTo(0f)
    }

    @Test
    fun settleLead_afterAPointThatIsNotFinite_decaysTheLeadToZero() {
        val smoother = smootherWithLead(20f)

        smoother.begin(2 * STEP_20_FPS_NS)
        smoother.smooth(1, Float.NaN, BASE_Y_IMG, HEIGHT_IMG)
        smoother.settleLead(0f, 0f, HEIGHT_IMG, fade = true)

        assertThat(smoother.outLeadXImg).isEqualTo(0f)
    }

    @Test
    fun settleLead_afterRecall_passesTheTargetThroughAndKeepsTheStoredLead() {
        val smoother = smootherWithLead(20f)
        smoother.recall(1)

        smoother.settleLead(99f, 0f, HEIGHT_IMG, fade = false)
        smoother.recall(1)

        assertThat(smoother.outLeadXImg).isEqualTo(20f)
    }

    @Test
    fun settleLead_afterEnd_passesTheTargetThroughAndKeepsTheStoredLead() {
        val smoother = smootherWithLead(20f)
        smoother.end()

        smoother.settleLead(99f, 0f, HEIGHT_IMG, fade = false)
        smoother.recall(1)

        assertThat(smoother.outLeadXImg).isEqualTo(20f)
    }
}
