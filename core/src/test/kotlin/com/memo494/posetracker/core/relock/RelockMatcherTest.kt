package com.memo494.posetracker.core.relock

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import org.junit.jupiter.api.Test

/** Unit tests of [RelockMatcher] (docs/plans/M4.md, M4c T1). */
class RelockMatcherTest {
    private val config = TrackerConfig()
    private val matcher = RelockMatcher(config)

    @Test
    fun aNewMatcher_isNotPendingAndNeverExpires() {
        assertThat(matcher.isPending).isFalse()
        assertThat(matcher.isExpired(Long.MAX_VALUE)).isFalse()
    }

    @Test
    fun startPending_waitsUntilCancelled() {
        matcher.startPending(START_NS)
        assertThat(matcher.isPending).isTrue()

        matcher.cancel()

        assertThat(matcher.isPending).isFalse()
        assertThat(matcher.isExpired(START_NS + 2 * config.relockMemoryNs)).isFalse()
    }

    @Test
    fun pending_expiresOnlyAfterTheMemoryOfFrameTime() {
        matcher.startPending(START_NS)

        assertThat(matcher.isExpired(START_NS)).isFalse()
        assertThat(matcher.isExpired(START_NS + config.relockMemoryNs - 1L)).isFalse()
        assertThat(matcher.isExpired(START_NS + config.relockMemoryNs)).isFalse()
        assertThat(matcher.isExpired(START_NS + config.relockMemoryNs + 1L)).isTrue()
    }

    @Test
    fun theMemory_comesFromTheConfig() {
        val short = RelockMatcher(TrackerConfig(relockMemoryMs = 2000L))
        short.startPending(START_NS)

        assertThat(short.isExpired(START_NS + 1_999_000_000L)).isFalse()
        assertThat(short.isExpired(START_NS + 2_001_000_000L)).isTrue()
    }

    @Test
    fun aFrameTimeThatGoesBackwards_countsAsNoTimePassed() {
        matcher.startPending(START_NS)

        assertThat(matcher.isExpired(START_NS - config.relockMemoryNs * 2)).isFalse()
    }

    @Test
    fun startingAgain_restartsTheClock() {
        matcher.startPending(START_NS)
        matcher.startPending(START_NS + config.relockMemoryNs)

        assertThat(matcher.isExpired(START_NS + config.relockMemoryNs + 1L)).isFalse()
        assertThat(matcher.isExpired(START_NS + 2 * config.relockMemoryNs + 1L)).isTrue()
    }

    @Test
    fun judge_acceptsACloseCandidateWhoseNeighboursAreFarther() {
        assertThat(matcher.judge(0.18f, 0.41f)).isEqualTo(RelockMatcher.Verdict.ACCEPT)
    }

    @Test
    fun judge_acceptsACandidateWhenNobodyElseIsShown() {
        assertThat(matcher.judge(0.18f, Float.POSITIVE_INFINITY)).isEqualTo(RelockMatcher.Verdict.ACCEPT)
    }

    @Test
    fun judge_rejectsACandidateFartherThanTheLimit() {
        assertThat(matcher.judge(0.41f, Float.POSITIVE_INFINITY)).isEqualTo(RelockMatcher.Verdict.TOO_FAR)
    }

    @Test
    fun judge_acceptsACandidateExactlyAtTheLimit() {
        val exact = RelockMatcher(TrackerConfig(relockMaxDistance = 0.5f))

        assertThat(exact.judge(0.5f, Float.POSITIVE_INFINITY)).isEqualTo(RelockMatcher.Verdict.ACCEPT)
        assertThat(exact.judge(0.5001f, Float.POSITIVE_INFINITY)).isEqualTo(RelockMatcher.Verdict.TOO_FAR)
    }

    @Test
    fun judge_rejectsTwoSimilarCandidates_noMatterHowCloseTheyAre() {
        assertThat(matcher.judge(0.05f, 0.06f)).isEqualTo(RelockMatcher.Verdict.AMBIGUOUS)
        assertThat(matcher.judge(0.05f, 0.05f)).isEqualTo(RelockMatcher.Verdict.AMBIGUOUS)
        assertThat(matcher.judge(0.20f, 0.25f)).isEqualTo(RelockMatcher.Verdict.AMBIGUOUS)
    }

    @Test
    fun judge_aNeighbourNearerThanTheCandidate_isAmbiguous() {
        assertThat(matcher.judge(0.25f, 0.10f)).isEqualTo(RelockMatcher.Verdict.AMBIGUOUS)
    }

    @Test
    fun judge_acceptsAMarginExactlyAsLargeAsTheConfigured() {
        val exact = RelockMatcher(TrackerConfig(relockMinMargin = 0.25f))

        assertThat(exact.judge(0.25f, 0.5f)).isEqualTo(RelockMatcher.Verdict.ACCEPT)
        assertThat(exact.judge(0.25f, 0.4999f)).isEqualTo(RelockMatcher.Verdict.AMBIGUOUS)
    }

    @Test
    fun judge_aZeroMarginAcceptsATie() {
        val noMargin = RelockMatcher(TrackerConfig(relockMinMargin = 0f))

        assertThat(noMargin.judge(0.2f, 0.2f)).isEqualTo(RelockMatcher.Verdict.ACCEPT)
    }

    @Test
    fun judge_numbersThatAreNotNumbers_neverAccept() {
        assertThat(matcher.judge(Float.NaN, 0.9f)).isEqualTo(RelockMatcher.Verdict.TOO_FAR)
        assertThat(matcher.judge(0.1f, Float.NaN)).isEqualTo(RelockMatcher.Verdict.AMBIGUOUS)
        assertThat(matcher.judge(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)).isEqualTo(RelockMatcher.Verdict.TOO_FAR)
    }

    private companion object {
        const val START_NS = 5_000_000_000L
    }
}
