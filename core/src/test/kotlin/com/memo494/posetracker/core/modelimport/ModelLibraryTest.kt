package com.memo494.posetracker.core.modelimport

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ModelLibraryTest {
    private fun meta(
        name: String,
        digit: Char,
    ) = ImportedModelMeta(name, digit.toString().repeat(64), 1_000_000L, 640, 640, 300)

    private val a = meta("a.tflite", 'a')
    private val b = meta("b.tflite", 'b')
    private val c = meta("c.tflite", 'c')

    @Test
    fun empty_hasNoModel() {
        assertThat(ModelLibrary.EMPTY.size).isEqualTo(0)
        assertThat(ModelLibrary.EMPTY.find(a.sha256)).isNull()
    }

    @Test
    fun add_putsTheModelAtTheEnd() {
        val first = (ModelLibrary.EMPTY.add(a, 4) as LibraryAdd.Added).library
        val second = (first.add(b, 4) as LibraryAdd.Added).library

        assertThat(second.entries).containsExactly(a, b).inOrder()
        assertThat(second.find(b.sha256)).isEqualTo(b)
        assertThat(second.contains(a.sha256)).isTrue()
    }

    @Test
    fun add_sameShaTwice_isADuplicateEvenWithAnotherName() {
        val library = ModelLibrary.of(listOf(a))

        assertThat(library.add(a.copy(displayName = "renamed.tflite"), 4)).isEqualTo(LibraryAdd.Duplicate)
    }

    @Test
    fun add_toAFullLibrary_isFull() {
        val library = ModelLibrary.of(listOf(a, b))

        assertThat(library.add(c, 2)).isEqualTo(LibraryAdd.Full)
    }

    @Test
    fun add_aDuplicateToAFullLibrary_isStillADuplicate() {
        val library = ModelLibrary.of(listOf(a, b))

        assertThat(library.add(b, 2)).isEqualTo(LibraryAdd.Duplicate)
    }

    @Test
    fun add_lastFreePlace_isAccepted() {
        val library = ModelLibrary.of(listOf(a, b))

        assertThat(library.add(c, 3)).isInstanceOf(LibraryAdd.Added::class.java)
    }

    @Test
    fun remove_dropsOnlyThatModel() {
        val library = ModelLibrary.of(listOf(a, b, c))

        assertThat(library.remove(b.sha256).entries).containsExactly(a, c).inOrder()
    }

    @Test
    fun remove_anUnknownModel_changesNothing() {
        val library = ModelLibrary.of(listOf(a))

        assertThat(library.remove(b.sha256)).isEqualTo(library)
    }

    @Test
    fun of_keepsTheFirstOfEachShaAndAppliesNoLimit() {
        val library = ModelLibrary.of(listOf(a, a.copy(displayName = "again.tflite"), b, c))

        assertThat(library.entries).containsExactly(a, b, c).inOrder()
    }

    @Test
    fun aLibraryLargerThanTheLimit_canStillBeRemovedFrom() {
        val library = ModelLibrary.of(listOf(a, b, c))

        assertThat(library.add(meta("d.tflite", 'd'), 2)).isEqualTo(LibraryAdd.Full)
        assertThat(library.remove(a.sha256).size).isEqualTo(2)
    }
}
