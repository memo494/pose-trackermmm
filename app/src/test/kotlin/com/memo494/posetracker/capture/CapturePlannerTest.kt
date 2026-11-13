package com.memo494.posetracker.capture

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.capture.CaptureSize
import org.junit.Test

class CapturePlannerTest {
    @Test
    fun contentEqualToTheDisplay_isDisplaySizedWithTheCaptureSize() {
        val plan = CapturePlanner.plan(1220, 2712, 1220, 2712, 0.5f)

        assertThat(plan).isEqualTo(CapturePlan(CaptureSize(610, 1356), displaySized = true))
    }

    @Test
    fun contentOnePixelOff_isStillDisplaySized() {
        val plan = CapturePlanner.plan(1220, 2712, 1221, 2711, 0.5f)

        assertThat(plan!!.displaySized).isTrue()
    }

    @Test
    fun partialContent_isNotDisplaySized() {
        val plan = CapturePlanner.plan(1220, 2712, 1220, 1200, 0.5f)

        assertThat(plan!!.displaySized).isFalse()
        assertThat(plan.size).isEqualTo(CaptureSize(610, 1356))
    }

    @Test
    fun rotatedContent_isNotDisplaySized() {
        val plan = CapturePlanner.plan(1220, 2712, 2712, 1220, 0.5f)

        assertThat(plan!!.displaySized).isFalse()
    }

    @Test
    fun theCaptureSizeFollowsTheDisplayNotTheContent() {
        val plan = CapturePlanner.plan(2712, 1220, 1220, 2712, 0.5f)

        assertThat(plan!!.size).isEqualTo(CaptureSize(1356, 610))
    }

    @Test
    fun aSizeThatIsNotPositive_isUnusable() {
        assertThat(CapturePlanner.plan(0, 2712, 1220, 2712, 0.5f)).isNull()
        assertThat(CapturePlanner.plan(1220, -1, 1220, 2712, 0.5f)).isNull()
        assertThat(CapturePlanner.plan(1220, 2712, 0, 2712, 0.5f)).isNull()
        assertThat(CapturePlanner.plan(1220, 2712, 1220, 0, 0.5f)).isNull()
    }

    @Test
    fun aScaleThatIsNotUsable_isUnusable() {
        assertThat(CapturePlanner.plan(1220, 2712, 1220, 2712, 0f)).isNull()
        assertThat(CapturePlanner.plan(1220, 2712, 1220, 2712, Float.NaN)).isNull()
    }
}
