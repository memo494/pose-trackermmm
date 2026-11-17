package com.memo494.posetracker.core.tracking

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TrackSnapshotTest {
    @Test
    fun empty_hasNoTracks() {
        assertThat(TrackSnapshot.EMPTY.count).isEqualTo(0)
    }

    @Test
    fun empty_hasImageSizeZeroByZero() {
        assertThat(TrackSnapshot.EMPTY.widthImg).isEqualTo(0)
        assertThat(TrackSnapshot.EMPTY.heightImg).isEqualTo(0)
    }

    @Test
    fun of_storesTheImageSizeOfTheFrame() {
        val snapshot = TrackSnapshot.of(FakeTrackList(FakeTrack(1)), WIDTH_IMG, HEIGHT_IMG)

        assertThat(snapshot.widthImg).isEqualTo(WIDTH_IMG)
        assertThat(snapshot.heightImg).isEqualTo(HEIGHT_IMG)
    }

    @Test
    fun of_aListWithoutShownTracks_returnsTheSharedEmptySnapshot() {
        val none = TrackSnapshot.of(FakeTrackList(), WIDTH_IMG, HEIGHT_IMG)
        val onlyTentative =
            TrackSnapshot.of(FakeTrackList(FakeTrack(1, shown = false), FakeTrack(2, shown = false)), WIDTH_IMG, HEIGHT_IMG)

        assertThat(none).isSameInstanceAs(TrackSnapshot.EMPTY)
        assertThat(onlyTentative).isSameInstanceAs(TrackSnapshot.EMPTY)
    }

    @Test
    fun of_keepsOnlyTheShownTracks_inListOrder() {
        val list =
            FakeTrackList(
                FakeTrack(7),
                FakeTrack(3, shown = false),
                FakeTrack(9, lost = true),
                FakeTrack(4, shown = false),
                FakeTrack(5),
            )

        val snapshot = TrackSnapshot.of(list, WIDTH_IMG, HEIGHT_IMG)

        assertThat(snapshot.count).isEqualTo(3)
        assertThat(snapshot.id(0)).isEqualTo(7)
        assertThat(snapshot.id(1)).isEqualTo(9)
        assertThat(snapshot.id(2)).isEqualTo(5)
    }

    @Test
    fun of_copiesEveryFieldOfEveryShownTrack() {
        val list =
            FakeTrackList(
                FakeTrack(1, headXImg = 11f, headYImg = 12f, boxLeftImg = 1f, boxTopImg = 2f, boxRightImg = 3f, boxBottomImg = 4f),
                FakeTrack(
                    2,
                    lost = true,
                    headEstimated = true,
                    headXImg = 21f,
                    headYImg = 22f,
                    boxLeftImg = 5f,
                    boxTopImg = 6f,
                    boxRightImg = 7f,
                    boxBottomImg = 8f,
                ),
            )

        val snapshot = TrackSnapshot.of(list, WIDTH_IMG, HEIGHT_IMG)

        assertThat(snapshot.isLost(0)).isFalse()
        assertThat(snapshot.isHeadEstimated(0)).isFalse()
        assertThat(snapshot.headXImg(0)).isEqualTo(11f)
        assertThat(snapshot.headYImg(0)).isEqualTo(12f)
        assertThat(snapshot.boxLeftImg(0)).isEqualTo(1f)
        assertThat(snapshot.boxTopImg(0)).isEqualTo(2f)
        assertThat(snapshot.boxRightImg(0)).isEqualTo(3f)
        assertThat(snapshot.boxBottomImg(0)).isEqualTo(4f)
        assertThat(snapshot.isLost(1)).isTrue()
        assertThat(snapshot.isHeadEstimated(1)).isTrue()
        assertThat(snapshot.headXImg(1)).isEqualTo(21f)
        assertThat(snapshot.headYImg(1)).isEqualTo(22f)
        assertThat(snapshot.boxLeftImg(1)).isEqualTo(5f)
        assertThat(snapshot.boxTopImg(1)).isEqualTo(6f)
        assertThat(snapshot.boxRightImg(1)).isEqualTo(7f)
        assertThat(snapshot.boxBottomImg(1)).isEqualTo(8f)
    }

    @Test
    fun of_isACopy_laterChangesOfTheListDoNotShowThrough() {
        val entries = mutableListOf(FakeTrack(1, headXImg = 10f))
        val snapshot = TrackSnapshot.of(FakeTrackList(entries), WIDTH_IMG, HEIGHT_IMG)

        entries[0] = FakeTrack(2, headXImg = 99f)
        entries.add(FakeTrack(3))

        assertThat(snapshot.count).isEqualTo(1)
        assertThat(snapshot.id(0)).isEqualTo(1)
        assertThat(snapshot.headXImg(0)).isEqualTo(10f)
    }

    @Test
    fun of_copiesNonFiniteValuesAsTheyAre() {
        val list = FakeTrackList(FakeTrack(1, headXImg = Float.NaN, boxLeftImg = Float.POSITIVE_INFINITY))
        val snapshot = TrackSnapshot.of(list, WIDTH_IMG, HEIGHT_IMG)

        assertThat(snapshot.headXImg(0).isNaN()).isTrue()
        assertThat(snapshot.boxLeftImg(0)).isEqualTo(Float.POSITIVE_INFINITY)
    }

    @Test
    fun anIndexOutOfRange_throws() {
        val snapshot = TrackSnapshot.of(FakeTrackList(FakeTrack(1), FakeTrack(2, shown = false)), WIDTH_IMG, HEIGHT_IMG)

        for (index in listOf(-1, 1, 2, Int.MAX_VALUE)) {
            assertThrows(IndexOutOfBoundsException::class.java) { snapshot.id(index) }
            assertThrows(IndexOutOfBoundsException::class.java) { snapshot.isLost(index) }
            assertThrows(IndexOutOfBoundsException::class.java) { snapshot.isHeadEstimated(index) }
            assertThrows(IndexOutOfBoundsException::class.java) { snapshot.headXImg(index) }
            assertThrows(IndexOutOfBoundsException::class.java) { snapshot.headYImg(index) }
            assertThrows(IndexOutOfBoundsException::class.java) { snapshot.boxLeftImg(index) }
            assertThrows(IndexOutOfBoundsException::class.java) { snapshot.boxTopImg(index) }
            assertThrows(IndexOutOfBoundsException::class.java) { snapshot.boxRightImg(index) }
            assertThrows(IndexOutOfBoundsException::class.java) { snapshot.boxBottomImg(index) }
        }
    }

    @Test
    fun anyIndexOfTheEmptySnapshot_throws() {
        assertThrows(IndexOutOfBoundsException::class.java) { TrackSnapshot.EMPTY.id(0) }
        assertThrows(IndexOutOfBoundsException::class.java) { TrackSnapshot.EMPTY.headXImg(0) }
    }

    @Test
    fun ofSmoothed_theFirstFrameOfAnId_equalsThePlainSnapshot() {
        val list = FakeTrackList(FakeTrack(1, headXImg = 100f, headYImg = 50f), FakeTrack(2, lost = true, headXImg = 300f))

        val smoothed = TrackSnapshot.ofSmoothed(list, WIDTH_IMG, HEIGHT_IMG, HeadSmoother(TrackerConfig()), 0L)

        assertSameContent(smoothed, TrackSnapshot.of(list, WIDTH_IMG, HEIGHT_IMG))
    }

    @Test
    fun ofSmoothed_laterFrames_moveTheHeadLessThanTheTrackerAndTheBoxByTheSameDelta() {
        val smoother = HeadSmoother(TrackerConfig())
        TrackSnapshot.ofSmoothed(FakeTrackList(FakeTrack(1, headXImg = 100f)), WIDTH_IMG, HEIGHT_IMG, smoother, 0L)
        val moved = FakeTrack(1, headXImg = 140f, boxLeftImg = 120f, boxRightImg = 160f)

        val snapshot = TrackSnapshot.ofSmoothed(FakeTrackList(moved), WIDTH_IMG, HEIGHT_IMG, smoother, FRAME_NS)

        assertThat(snapshot.headXImg(0)).isGreaterThan(100f)
        assertThat(snapshot.headXImg(0)).isLessThan(140f)
        assertThat(snapshot.headYImg(0)).isEqualTo(50f)
        assertThat(snapshot.boxLeftImg(0) - snapshot.headXImg(0)).isWithin(TOLERANCE_IMG).of(120f - 140f)
        assertThat(snapshot.boxRightImg(0) - snapshot.headXImg(0)).isWithin(TOLERANCE_IMG).of(160f - 140f)
        assertThat(snapshot.boxTopImg(0)).isEqualTo(40f)
        assertThat(snapshot.boxBottomImg(0)).isEqualTo(240f)
    }

    @Test
    fun ofSmoothed_keepsIdsFlagsAndOrderAndLeavesTentativeTracksOut() {
        val smoother = HeadSmoother(TrackerConfig())
        val list =
            FakeTrackList(
                FakeTrack(7, lost = true, headEstimated = true),
                FakeTrack(3, shown = false),
                FakeTrack(5),
            )

        val snapshot = TrackSnapshot.ofSmoothed(list, WIDTH_IMG, HEIGHT_IMG, smoother, 0L)

        assertThat(snapshot.count).isEqualTo(2)
        assertThat(snapshot.id(0)).isEqualTo(7)
        assertThat(snapshot.isLost(0)).isTrue()
        assertThat(snapshot.isHeadEstimated(0)).isTrue()
        assertThat(snapshot.id(1)).isEqualTo(5)
        assertThat(snapshot.isLost(1)).isFalse()
        assertThat(snapshot.widthImg).isEqualTo(WIDTH_IMG)
        assertThat(smoother.activeCount).isEqualTo(2)
    }

    @Test
    fun ofSmoothed_aFullPool_neverEvictsAnIdThatIsStillShown() {
        val smoother = HeadSmoother(TrackerConfig(maxTracks = 2))
        val first = FakeTrackList(FakeTrack(2, headXImg = 200f), FakeTrack(1, headXImg = 100f))
        TrackSnapshot.ofSmoothed(first, WIDTH_IMG, HEIGHT_IMG, smoother, 0L)

        val second = FakeTrackList(FakeTrack(3, headXImg = 300f), FakeTrack(2, headXImg = 240f))
        val snapshot = TrackSnapshot.ofSmoothed(second, WIDTH_IMG, HEIGHT_IMG, smoother, FRAME_NS)

        assertThat(snapshot.headXImg(0)).isEqualTo(300f)
        assertThat(snapshot.headXImg(1)).isGreaterThan(200f)
        assertThat(snapshot.headXImg(1)).isLessThan(240f)
        assertThat(smoother.activeCount).isEqualTo(2)
    }

    @Test
    fun ofSmoothed_withoutShownTracks_returnsEmptyAndFreesTheSlots() {
        val smoother = HeadSmoother(TrackerConfig())
        TrackSnapshot.ofSmoothed(FakeTrackList(FakeTrack(1)), WIDTH_IMG, HEIGHT_IMG, smoother, 0L)
        assertThat(smoother.activeCount).isEqualTo(1)

        val none = TrackSnapshot.ofSmoothed(FakeTrackList(FakeTrack(1, shown = false)), WIDTH_IMG, HEIGHT_IMG, smoother, FRAME_NS)

        assertThat(none).isSameInstanceAs(TrackSnapshot.EMPTY)
        assertThat(smoother.activeCount).isEqualTo(0)
    }

    @Test
    fun ofSmoothed_withASmootherThatIsOff_equalsThePlainSnapshotOnEveryFrame() {
        val smoother = HeadSmoother(TrackerConfig(smoothEnabled = false))
        for (i in 0 until 3) {
            val list = FakeTrackList(FakeTrack(1, headXImg = 100f + 13f * i, boxLeftImg = 80f + 13f * i))

            val smoothed = TrackSnapshot.ofSmoothed(list, WIDTH_IMG, HEIGHT_IMG, smoother, i * FRAME_NS)

            assertSameContent(smoothed, TrackSnapshot.of(list, WIDTH_IMG, HEIGHT_IMG))
        }
    }

    @Test
    fun ofSmoothed_aNonFiniteHead_leavesTheBoxWhereItIs() {
        val smoother = HeadSmoother(TrackerConfig())
        TrackSnapshot.ofSmoothed(FakeTrackList(FakeTrack(1)), WIDTH_IMG, HEIGHT_IMG, smoother, 0L)

        val snapshot =
            TrackSnapshot.ofSmoothed(FakeTrackList(FakeTrack(1, headXImg = Float.NaN)), WIDTH_IMG, HEIGHT_IMG, smoother, FRAME_NS)

        assertThat(snapshot.headXImg(0).isNaN()).isTrue()
        assertThat(snapshot.boxLeftImg(0)).isEqualTo(80f)
        assertThat(snapshot.boxRightImg(0)).isEqualTo(120f)
    }

    @Test
    fun ofIdle_keepsTheLastSmoothedPointOfAKnownIdAndPassesANewIdThrough() {
        val smoother = HeadSmoother(TrackerConfig())
        TrackSnapshot.ofSmoothed(FakeTrackList(FakeTrack(1, headXImg = 100f)), WIDTH_IMG, HEIGHT_IMG, smoother, 0L)
        val moved = FakeTrackList(FakeTrack(1, headXImg = 140f, boxLeftImg = 120f, boxRightImg = 160f))
        val lastFrame = TrackSnapshot.ofSmoothed(moved, WIDTH_IMG, HEIGHT_IMG, smoother, FRAME_NS)
        val idleList =
            FakeTrackList(
                FakeTrack(1, headXImg = 140f, boxLeftImg = 120f, boxRightImg = 160f),
                FakeTrack(2, headXImg = 400f, boxLeftImg = 380f, boxRightImg = 420f),
            )

        val idle = TrackSnapshot.ofIdle(idleList, WIDTH_IMG, HEIGHT_IMG, smoother, 0f, MAX_LOST_MS)

        assertThat(idle.count).isEqualTo(2)
        assertThat(idle.headXImg(0)).isEqualTo(lastFrame.headXImg(0))
        assertThat(idle.boxLeftImg(0)).isEqualTo(lastFrame.boxLeftImg(0))
        assertThat(idle.boxRightImg(0)).isEqualTo(lastFrame.boxRightImg(0))
        assertThat(idle.headXImg(1)).isEqualTo(400f)
        assertThat(idle.boxLeftImg(1)).isEqualTo(380f)
        assertThat(smoother.activeCount).isEqualTo(1)
    }

    @Test
    fun ofIdle_doesNotAdvanceTheSmoother() {
        val withIdle = HeadSmoother(TrackerConfig())
        val without = HeadSmoother(TrackerConfig())
        val first = FakeTrackList(FakeTrack(1, headXImg = 100f))
        val second = FakeTrackList(FakeTrack(1, headXImg = 140f))
        for (smoother in listOf(withIdle, without)) TrackSnapshot.ofSmoothed(first, WIDTH_IMG, HEIGHT_IMG, smoother, 0L)

        TrackSnapshot.ofIdle(second, WIDTH_IMG, HEIGHT_IMG, withIdle, 0f, MAX_LOST_MS)
        TrackSnapshot.ofIdle(FakeTrackList(), WIDTH_IMG, HEIGHT_IMG, withIdle, 0f, MAX_LOST_MS)

        val a = TrackSnapshot.ofSmoothed(second, WIDTH_IMG, HEIGHT_IMG, withIdle, FRAME_NS)
        val b = TrackSnapshot.ofSmoothed(second, WIDTH_IMG, HEIGHT_IMG, without, FRAME_NS)
        assertSameContent(a, b)
        assertThat(withIdle.activeCount).isEqualTo(1)
    }

    @Test
    fun of_aLostTrack_carriesTheLostMsOfTheTracker() {
        val list = FakeTrackList(FakeTrack(1, lost = true, lostMs = 250f), FakeTrack(2))

        val snapshot = TrackSnapshot.of(list, WIDTH_IMG, HEIGHT_IMG)

        assertThat(snapshot.lostMs(0)).isEqualTo(250f)
        assertThat(snapshot.lostMs(1)).isEqualTo(0f)
    }

    @Test
    fun of_aTrackThatIsNotLost_hasLostMsZeroWhateverTheListReports() {
        val list = FakeTrackList(FakeTrack(1, lostMs = 300f))

        assertThat(TrackSnapshot.of(list, WIDTH_IMG, HEIGHT_IMG).lostMs(0)).isEqualTo(0f)
    }

    @Test
    fun lostMs_withAnIndexOutsideTheSnapshot_throws() {
        val snapshot = TrackSnapshot.of(FakeTrackList(FakeTrack(1, lost = true)), WIDTH_IMG, HEIGHT_IMG)

        assertThrows(IndexOutOfBoundsException::class.java) { snapshot.lostMs(1) }
        assertThrows(IndexOutOfBoundsException::class.java) { snapshot.lostMs(-1) }
    }

    @Test
    fun ofSmoothed_aLostTrack_carriesTheLostMsOfTheTracker() {
        val smoother = HeadSmoother(TrackerConfig())
        val list = FakeTrackList(FakeTrack(1, lost = true, lostMs = 250f), FakeTrack(2))

        val snapshot = TrackSnapshot.ofSmoothed(list, WIDTH_IMG, HEIGHT_IMG, smoother, 0L)

        assertThat(snapshot.lostMs(0)).isEqualTo(250f)
        assertThat(snapshot.lostMs(1)).isEqualTo(0f)
    }

    @Test
    fun ofIdle_aLostTrack_addsTheIdleTimeToTheLostMs() {
        val smoother = HeadSmoother(TrackerConfig())
        val list = FakeTrackList(FakeTrack(1, lost = true, lostMs = 250f), FakeTrack(2))

        val idle = TrackSnapshot.ofIdle(list, WIDTH_IMG, HEIGHT_IMG, smoother, 300f, MAX_LOST_MS)

        assertThat(idle.lostMs(0)).isWithin(TOLERANCE_IMG).of(550f)
        assertThat(idle.isLost(0)).isTrue()
    }

    @Test
    fun ofIdle_aTrackThatIsNotLost_staysAtZero() {
        val smoother = HeadSmoother(TrackerConfig())
        val list = FakeTrackList(FakeTrack(1), FakeTrack(2, lost = true, lostMs = 100f))

        val idle = TrackSnapshot.ofIdle(list, WIDTH_IMG, HEIGHT_IMG, smoother, 300f, MAX_LOST_MS)

        assertThat(idle.lostMs(0)).isEqualTo(0f)
        assertThat(idle.lostMs(1)).isWithin(TOLERANCE_IMG).of(400f)
    }

    @Test
    fun ofIdle_capsTheLostMsAtTheLimit() {
        val smoother = HeadSmoother(TrackerConfig())
        val list = FakeTrackList(FakeTrack(1, lost = true, lostMs = 700f))

        val idle = TrackSnapshot.ofIdle(list, WIDTH_IMG, HEIGHT_IMG, smoother, 500f, MAX_LOST_MS)

        assertThat(idle.lostMs(0)).isEqualTo(MAX_LOST_MS)
    }

    @Test
    fun ofIdle_withNoIdleTime_keepsTheLostMsOfTheTracker() {
        val smoother = HeadSmoother(TrackerConfig())
        val list = FakeTrackList(FakeTrack(1, lost = true, lostMs = 250f))

        assertThat(TrackSnapshot.ofIdle(list, WIDTH_IMG, HEIGHT_IMG, smoother, 0f, MAX_LOST_MS).lostMs(0)).isEqualTo(250f)
        assertThat(TrackSnapshot.ofIdle(list, WIDTH_IMG, HEIGHT_IMG, smoother, -40f, MAX_LOST_MS).lostMs(0)).isEqualTo(250f)
        assertThat(TrackSnapshot.ofIdle(list, WIDTH_IMG, HEIGHT_IMG, smoother, Float.NaN, MAX_LOST_MS).lostMs(0)).isEqualTo(250f)
    }

    private fun assertSameContent(
        actual: TrackSnapshot,
        expected: TrackSnapshot,
    ) {
        assertThat(actual.count).isEqualTo(expected.count)
        assertThat(actual.widthImg).isEqualTo(expected.widthImg)
        assertThat(actual.heightImg).isEqualTo(expected.heightImg)
        for (i in 0 until expected.count) {
            assertThat(actual.id(i)).isEqualTo(expected.id(i))
            assertThat(actual.isLost(i)).isEqualTo(expected.isLost(i))
            assertThat(actual.lostMs(i)).isEqualTo(expected.lostMs(i))
            assertThat(actual.isHeadEstimated(i)).isEqualTo(expected.isHeadEstimated(i))
            assertThat(actual.headXImg(i)).isEqualTo(expected.headXImg(i))
            assertThat(actual.headYImg(i)).isEqualTo(expected.headYImg(i))
            assertThat(actual.boxLeftImg(i)).isEqualTo(expected.boxLeftImg(i))
            assertThat(actual.boxTopImg(i)).isEqualTo(expected.boxTopImg(i))
            assertThat(actual.boxRightImg(i)).isEqualTo(expected.boxRightImg(i))
            assertThat(actual.boxBottomImg(i)).isEqualTo(expected.boxBottomImg(i))
        }
    }

    @Test
    fun indexOfId_findsTheIndexOfAShownTrack() {
        val snapshot = TrackSnapshot.of(FakeTrackList(FakeTrack(5), FakeTrack(9, shown = false), FakeTrack(12)), WIDTH_IMG, HEIGHT_IMG)

        assertThat(snapshot.indexOfId(5)).isEqualTo(0)
        assertThat(snapshot.indexOfId(12)).isEqualTo(1)
    }

    @Test
    fun indexOfId_anIdThatIsNotInTheSnapshot_isMinusOne() {
        val snapshot = TrackSnapshot.of(FakeTrackList(FakeTrack(5), FakeTrack(9, shown = false)), WIDTH_IMG, HEIGHT_IMG)

        assertThat(snapshot.indexOfId(9)).isEqualTo(-1)
        assertThat(snapshot.indexOfId(77)).isEqualTo(-1)
        assertThat(snapshot.indexOfId(-1)).isEqualTo(-1)
        assertThat(TrackSnapshot.EMPTY.indexOfId(5)).isEqualTo(-1)
    }

    private companion object {
        const val WIDTH_IMG = 1356
        const val HEIGHT_IMG = 610
        const val FRAME_NS = 50_000_000L
        const val TOLERANCE_IMG = 1e-3f
        const val MAX_LOST_MS = 800f
    }
}
