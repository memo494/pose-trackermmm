package com.memo494.posetracker.core.modelimport

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ModelBlocklistTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)

    @Test
    fun encode_isSortedAndCommaSeparated() {
        assertThat(ModelBlocklist.encode(setOf(b, a))).isEqualTo("$a,$b")
    }

    @Test
    fun encode_ofNothing_isEmptyText() {
        assertThat(ModelBlocklist.encode(emptySet())).isEmpty()
    }

    @Test
    fun decode_roundTrips() {
        assertThat(ModelBlocklist.decode(ModelBlocklist.encode(setOf(a, b)))).containsExactly(a, b)
    }

    @Test
    fun decode_nullAndEmpty_areTheEmptySet() {
        assertThat(ModelBlocklist.decode(null)).isEmpty()
        assertThat(ModelBlocklist.decode("")).isEmpty()
    }

    @Test
    fun decode_dropsEveryValueThatIsNotASha() {
        val text = "$a,not-a-sha,${"A".repeat(64)},${"b".repeat(63)},$b,"

        assertThat(ModelBlocklist.decode(text)).containsExactly(a, b)
    }

    @Test
    fun encode_writesOnlyValidShas() {
        assertThat(ModelBlocklist.encode(setOf(a, "junk"))).isEqualTo(a)
    }
}
