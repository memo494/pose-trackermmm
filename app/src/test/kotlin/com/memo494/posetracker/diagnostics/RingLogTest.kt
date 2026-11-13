package com.memo494.posetracker.diagnostics

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import org.junit.Test

class RingLogTest {
    @Test
    fun emptyLog_hasAnEmptySnapshot() {
        assertThat(RingLog(capacity = 3).snapshot()).isEmpty()
    }

    @Test
    fun linesBelowCapacity_areKeptOldestFirst() {
        val log = RingLog(capacity = 3)

        log.add("a")
        log.add("b")

        assertThat(log.snapshot()).containsExactly("a", "b").inOrder()
    }

    @Test
    fun oneLineOverCapacity_dropsTheOldestAndKeepsTheNewestInOrder() {
        val log = RingLog(capacity = 3)

        for (i in 1..4) log.add("line $i")

        assertThat(log.snapshot()).containsExactly("line 2", "line 3", "line 4").inOrder()
    }

    @Test
    fun manyLinesOverCapacity_keepTheNewestCapacityLines() {
        val log = RingLog(capacity = 5)

        for (i in 1..23) log.add("line $i")

        assertThat(log.snapshot()).containsExactly("line 19", "line 20", "line 21", "line 22", "line 23").inOrder()
    }

    @Test
    fun defaultCapacity_isTheConfiguredRingSize() {
        val log = RingLog()

        for (i in 1..TrackerConfig.LOG_RING_CAPACITY + 1) log.add("line $i")

        assertThat(log.snapshot()).hasSize(TrackerConfig.LOG_RING_CAPACITY)
        assertThat(log.snapshot().first()).isEqualTo("line 2")
    }

    @Test
    fun lineLongerThanTheLimit_isCut() {
        val log = RingLog(capacity = 2)

        log.add("x".repeat(TrackerConfig.LOG_LINE_MAX_CHARS + 50))

        assertThat(log.snapshot().single()).hasLength(TrackerConfig.LOG_LINE_MAX_CHARS)
    }

    @Test
    fun lineAtTheLimit_isKeptWhole() {
        val log = RingLog(capacity = 2)
        val line = "y".repeat(TrackerConfig.LOG_LINE_MAX_CHARS)

        log.add(line)

        assertThat(log.snapshot().single()).isEqualTo(line)
    }

    @Test
    fun snapshot_isACopyThatLaterAddsDoNotChange() {
        val log = RingLog(capacity = 3)
        log.add("a")
        val before = log.snapshot()

        log.add("b")

        assertThat(before).containsExactly("a")
        assertThat(log.snapshot()).containsExactly("a", "b").inOrder()
    }

    @Test
    fun concurrentAddsFromTwoThreads_loseNothingBelowCapacity() {
        val perThread = 200
        val log = RingLog(capacity = perThread * 2)
        val threads =
            listOf("p", "q").map { prefix ->
                Thread { for (i in 0 until perThread) log.add("$prefix$i") }
            }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val lines = log.snapshot()
        assertThat(lines).hasSize(perThread * 2)
        assertThat(lines.toSet()).hasSize(perThread * 2)
        assertThat(lines.filter { it.startsWith("p") }).isEqualTo((0 until perThread).map { "p$it" })
        assertThat(lines.filter { it.startsWith("q") }).isEqualTo((0 until perThread).map { "q$it" })
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroCapacity_isRejected() {
        RingLog(capacity = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeCapacity_isRejected() {
        RingLog(capacity = -1)
    }
}
