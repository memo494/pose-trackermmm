package com.memo494.posetracker.core.relock

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** Unit tests of [TorsoRect.coveredShareBy] (F5: an occluder over the locked person's torso). */
class TorsoRectTest {
    private val rect = TorsoRect().apply { set(10, 10, 30, 30) }

    @Test
    fun aBoxOverTheWholeRectangle_coversAllOfIt() {
        assertThat(rect.coveredShareBy(0f, 0f, 100f, 100f)).isEqualTo(1f)
    }

    @Test
    fun aBoxOverTheRightHalf_coversHalf() {
        assertThat(rect.coveredShareBy(20f, 0f, 100f, 100f)).isWithin(TOLERANCE).of(0.5f)
    }

    @Test
    fun aBoxThatOnlyTouchesOrMissesTheRectangle_coversNothing() {
        assertThat(rect.coveredShareBy(30f, 10f, 50f, 30f)).isEqualTo(0f)
        assertThat(rect.coveredShareBy(40f, 40f, 50f, 50f)).isEqualTo(0f)
    }

    @Test
    fun aBoxInsideTheRectangle_coversItsAreaShare() {
        assertThat(rect.coveredShareBy(15f, 15f, 25f, 25f)).isWithin(TOLERANCE).of(0.25f)
    }

    @Test
    fun anEmptyRectangleOrABoxThatIsNotFinite_failsClosedAsFullyCovered() {
        assertThat(TorsoRect().coveredShareBy(0f, 0f, 10f, 10f)).isEqualTo(1f)
        assertThat(rect.coveredShareBy(Float.NaN, 0f, 10f, 10f)).isEqualTo(1f)
        assertThat(rect.coveredShareBy(0f, 0f, Float.POSITIVE_INFINITY, 10f)).isEqualTo(1f)
    }

    private companion object {
        const val TOLERANCE = 0.001f
    }
}
