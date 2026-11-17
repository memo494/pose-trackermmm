package com.memo494.posetracker.core.tracking

import com.google.common.truth.Truth.assertThat
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

    private companion object {
        const val WIDTH_IMG = 1356
        const val HEIGHT_IMG = 610
    }
}
