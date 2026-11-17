package com.memo494.posetracker.core.overlay

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.tracking.FakeTrack
import com.memo494.posetracker.core.tracking.FakeTrackList
import com.memo494.posetracker.core.tracking.TrackSnapshot
import org.junit.jupiter.api.Test

class LockPickerTest {
    /** A track whose head is at ([headXImg], [headYImg]) and whose box is [heightImg] high. */
    private fun track(
        id: Int,
        headXImg: Float,
        headYImg: Float = HEAD_Y_IMG,
        heightImg: Float = HEIGHT_IMG,
    ): FakeTrack =
        FakeTrack(
            id = id,
            headXImg = headXImg,
            headYImg = headYImg,
            boxLeftImg = headXImg - 20f,
            boxTopImg = 0f,
            boxRightImg = headXImg + 20f,
            boxBottomImg = heightImg,
        )

    private fun snapshot(vararg tracks: FakeTrack): TrackSnapshot = TrackSnapshot.of(FakeTrackList(*tracks), WIDTH_IMG, 600)

    @Test
    fun pick_aTapOnTheHeadPoint_returnsThatTrack() {
        val snapshot = snapshot(track(7, 100f))

        assertThat(LockPicker.pick(snapshot, 100f, HEAD_Y_IMG, RATIO)).isEqualTo(7)
    }

    @Test
    fun pick_noTracks_returnsMinusOne() {
        assertThat(LockPicker.pick(TrackSnapshot.EMPTY, 100f, HEAD_Y_IMG, RATIO)).isEqualTo(NONE)
    }

    @Test
    fun pick_returnsTheTrackIdNotTheIndex() {
        val snapshot = snapshot(track(41, 100f), track(42, 300f))

        assertThat(LockPicker.pick(snapshot, 310f, HEAD_Y_IMG, RATIO)).isEqualTo(42)
    }

    @Test
    fun pick_chooses_theNearestOfTwoInRange() {
        val snapshot = snapshot(track(1, 100f), track(2, 160f))

        assertThat(LockPicker.pick(snapshot, 140f, HEAD_Y_IMG, RATIO)).isEqualTo(2)
        assertThat(LockPicker.pick(snapshot, 120f, HEAD_Y_IMG, RATIO)).isEqualTo(1)
    }

    @Test
    fun pick_aTapJustInsideTheLimit_locks_andJustOutside_doesNot() {
        // limit = ratio 0.5 x height 200 = 100 px from the head point (100, 50)
        val snapshot = snapshot(track(3, 100f))

        assertThat(LockPicker.pick(snapshot, 100f + 99f, HEAD_Y_IMG, 0.5f)).isEqualTo(3)
        assertThat(LockPicker.pick(snapshot, 100f + 101f, HEAD_Y_IMG, 0.5f)).isEqualTo(NONE)
    }

    @Test
    fun pick_aTapExactlyOnTheLimit_locks() {
        val snapshot = snapshot(track(3, 100f))

        assertThat(LockPicker.pick(snapshot, 100f + 100f, HEAD_Y_IMG, 0.5f)).isEqualTo(3)
    }

    @Test
    fun pick_theDistanceIsMeasuredInBothAxes() {
        // limit 100 px: a tap 80 px right and 80 px below is 113 px away
        val snapshot = snapshot(track(3, 100f))

        assertThat(LockPicker.pick(snapshot, 180f, HEAD_Y_IMG + 80f, 0.5f)).isEqualTo(NONE)
        assertThat(LockPicker.pick(snapshot, 160f, HEAD_Y_IMG + 60f, 0.5f)).isEqualTo(3)
    }

    @Test
    fun pick_theLimitIsTheRatioTimesThatTracksOwnHeight() {
        // the far small person has a limit of 0.5 x 40 = 20 px; the tall one 0.5 x 400 = 200 px
        val snapshot = snapshot(track(1, 100f, heightImg = 400f), track(2, 500f, heightImg = 40f))

        assertThat(LockPicker.pick(snapshot, 530f, HEAD_Y_IMG, 0.5f)).isEqualTo(NONE)
        assertThat(LockPicker.pick(snapshot, 515f, HEAD_Y_IMG, 0.5f)).isEqualTo(2)
        assertThat(LockPicker.pick(snapshot, 280f, HEAD_Y_IMG, 0.5f)).isEqualTo(1)
    }

    @Test
    fun pick_aNearerTrackOutOfItsOwnRange_doesNotBeatAFurtherOneInRange() {
        // track 1 (small, limit 10) is 15 px from the tap, track 2 (tall, limit 200) is 100 px away
        val snapshot = snapshot(track(1, 115f, heightImg = 20f), track(2, 0f, heightImg = 400f))

        assertThat(LockPicker.pick(snapshot, 100f, HEAD_Y_IMG, 0.5f)).isEqualTo(2)
    }

    @Test
    fun pick_aTie_goesToTheLowerIndex() {
        val snapshot = snapshot(track(9, 80f), track(4, 120f))

        assertThat(LockPicker.pick(snapshot, 100f, HEAD_Y_IMG, RATIO)).isEqualTo(9)
    }

    @Test
    fun pick_aLostTrackCanBeLocked() {
        val snapshot = snapshot(FakeTrack(id = 5, lost = true, headXImg = 100f, headYImg = HEAD_Y_IMG))

        assertThat(LockPicker.pick(snapshot, 100f, HEAD_Y_IMG, RATIO)).isEqualTo(5)
    }

    @Test
    fun pick_aNonFiniteTap_returnsMinusOne() {
        val snapshot = snapshot(track(1, 100f))

        assertThat(LockPicker.pick(snapshot, Float.NaN, HEAD_Y_IMG, RATIO)).isEqualTo(NONE)
        assertThat(LockPicker.pick(snapshot, 100f, Float.NaN, RATIO)).isEqualTo(NONE)
        assertThat(LockPicker.pick(snapshot, Float.POSITIVE_INFINITY, HEAD_Y_IMG, RATIO)).isEqualTo(NONE)
        assertThat(LockPicker.pick(snapshot, 100f, Float.NEGATIVE_INFINITY, RATIO)).isEqualTo(NONE)
    }

    @Test
    fun pick_aRatioThatIsNotAPositiveNumber_returnsMinusOne() {
        val snapshot = snapshot(track(1, 100f))

        assertThat(LockPicker.pick(snapshot, 100f, HEAD_Y_IMG, Float.NaN)).isEqualTo(NONE)
        assertThat(LockPicker.pick(snapshot, 100f, HEAD_Y_IMG, Float.POSITIVE_INFINITY)).isEqualTo(NONE)
        assertThat(LockPicker.pick(snapshot, 100f, HEAD_Y_IMG, 0f)).isEqualTo(NONE)
        assertThat(LockPicker.pick(snapshot, 100f, HEAD_Y_IMG, -1f)).isEqualTo(NONE)
    }

    @Test
    fun pick_aTrackWithANonFiniteHeadOrNoHeight_isSkipped() {
        val badHead = track(1, Float.NaN)
        val noHeight = track(2, 100f, heightImg = 0f).copy(boxTopImg = 0f, boxBottomImg = 0f)
        val good = track(3, 100f)

        assertThat(LockPicker.pick(snapshot(badHead, noHeight, good), 100f, HEAD_Y_IMG, RATIO)).isEqualTo(3)
        assertThat(LockPicker.pick(snapshot(badHead, noHeight), 100f, HEAD_Y_IMG, RATIO)).isEqualTo(NONE)
    }

    private companion object {
        const val NONE = -1
        const val WIDTH_IMG = 800
        const val HEAD_Y_IMG = 50f
        const val HEIGHT_IMG = 200f
        const val RATIO = 1f
    }
}
