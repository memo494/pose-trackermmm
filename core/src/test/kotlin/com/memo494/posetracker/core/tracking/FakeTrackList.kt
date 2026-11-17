package com.memo494.posetracker.core.tracking

/** One entry of a [FakeTrackList]; positions in Img pixels. */
internal data class FakeTrack(
    val id: Int,
    val shown: Boolean = true,
    val lost: Boolean = false,
    val headEstimated: Boolean = false,
    val headXImg: Float = 100f,
    val headYImg: Float = 50f,
    val boxLeftImg: Float = 80f,
    val boxTopImg: Float = 40f,
    val boxRightImg: Float = 120f,
    val boxBottomImg: Float = 240f,
    /** What `lostMs` reports; `null` = 40 ms for a lost track (alpha 0.95, under one fade step) and 0 otherwise. */
    val lostMs: Float? = null,
)

/** A hand-built [TrackList] for tests; [state] is `TENTATIVE` when not shown, `LOST` when lost, else `CONFIRMED`. */
internal class FakeTrackList(
    private val entries: List<FakeTrack>,
) : TrackList {
    constructor(vararg entries: FakeTrack) : this(entries.toList())

    override val size: Int get() = entries.size

    override fun id(index: Int): Int = entries[index].id

    override fun state(index: Int): TrackState =
        when {
            !entries[index].shown -> TrackState.TENTATIVE
            entries[index].lost -> TrackState.LOST
            else -> TrackState.CONFIRMED
        }

    override fun isShown(index: Int): Boolean = entries[index].shown

    override fun headXImg(index: Int): Float = entries[index].headXImg

    override fun headYImg(index: Int): Float = entries[index].headYImg

    override fun velocityXImgPerS(index: Int): Float = 0f

    override fun velocityYImgPerS(index: Int): Float = 0f

    override fun boxLeftImg(index: Int): Float = entries[index].boxLeftImg

    override fun boxTopImg(index: Int): Float = entries[index].boxTopImg

    override fun boxRightImg(index: Int): Float = entries[index].boxRightImg

    override fun boxBottomImg(index: Int): Float = entries[index].boxBottomImg

    override fun heightImg(index: Int): Float = entries[index].boxBottomImg - entries[index].boxTopImg

    override fun score(index: Int): Float = 0.9f

    override fun isHeadEstimated(index: Int): Boolean = entries[index].headEstimated

    override fun lostMs(index: Int): Float = entries[index].lostMs ?: if (entries[index].lost) DEFAULT_LOST_MS else 0f

    private companion object {
        const val DEFAULT_LOST_MS = 40f
    }
}
