package com.memo494.posetracker.core.tracking

import com.memo494.posetracker.core.TrackerConfig
import org.junit.jupiter.api.Test

class ScratchF4Test {
    private fun sc(p: ScenePerson, d: Double, fps: Double, j: Double, seed: Long) =
        SyntheticScene(listOf(p), d, fps, seed, j, keypointNoiseRatio = 0.01, boxNoiseRatio = 0.01)

    @Test
    fun print() {
        for ((fps, j) in listOf(12.0 to 0.0, 12.0 to 0.3, 20.0 to 0.0, 20.0 to 0.3)) {
            for (hps in listOf(1.0, 2.0)) {
                val sec = (1300.0 - 60.0) / (hps * 200.0)
                val p = ScenePerson(1, listOf(PathPoint(0.0, 60.0, 150.0, 200.0), PathPoint(sec, 1300.0, 150.0, 200.0)))
                val w = (1L..6L).map { DisplayModel.meanErrorInH(runDisplayed(sc(p, sec, fps, j, it), TrackerConfig()), p, 1.0, sec - 0.1) }
                val w0 = (1L..6L).map { DisplayModel.meanErrorInH(runDisplayed(sc(p, sec, fps, j, it), TrackerConfig(displayLeadEnabled = false)), p, 1.0, sec - 0.1) }
                val q = ScenePerson(1, listOf(PathPoint(0.0, 60.0, 150.0, 200.0), PathPoint(2.0, 60.0 + hps * 400, 150.0, 200.0), PathPoint(3.5, 60.0 + hps * 400, 150.0, 200.0)))
                val st = (1L..6L).map { s -> DisplayModel.stopOf(runDisplayed(sc(q, 3.5, fps, j, s), TrackerConfig()), q, 2.0, 0.05) }
                val longest = (1L..6L).maxOf { s -> sc(q, 3.5, fps, j, s).frames.zipWithNext { a, b -> b.timeS - a.timeS }.max() }
                println("M fps=$fps j=$j hps=$hps walk lead=${"%.3f".format(w.max())} none=${"%.3f".format(w0.max())} ratio=${"%.2f".format((w zip w0).maxOf { it.first / it.second })} | stop overshoot=${"%.4f".format(st.maxOf { it.overshootInH })} (limit 0.20) past=${"%.3f".format(st.maxOf { it.secondsPastAfterStop })} limit=${"%.3f".format(2 * longest + 0.075)}")
            }
        }
    }
}
