package com.memo494.posetracker.modelimport

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test

class ModelStatusHolderTest {
    private val builtIn = ModelStatus(ModelSource.BUILT_IN, null, null, null, inputWidth = 416, inputHeight = 416)
    private val fellBack = ModelStatus(ModelSource.BUILT_IN, null, ModelFallback.FILE_MISSING, null, inputWidth = 416, inputHeight = 416)

    @Before
    fun setUp() = ModelStatusHolder.clear()

    @After
    fun tearDown() = ModelStatusHolder.clear()

    @Test
    fun beforeAnySession_thereIsNoStatus() {
        assertThat(ModelStatusHolder.latest()).isNull()
    }

    @Test
    fun theLastPublishedStatus_isTheOneRead() {
        ModelStatusHolder.publish(builtIn)
        ModelStatusHolder.publish(fellBack)

        assertThat(ModelStatusHolder.latest()).isEqualTo(fellBack)
    }

    @Test
    fun aStatusPublishedOnAnotherThread_isSeenByTheReader() {
        val writer = Thread { ModelStatusHolder.publish(fellBack) }

        writer.start()
        writer.join()

        assertThat(ModelStatusHolder.latest()).isEqualTo(fellBack)
    }

    @Test
    fun clear_forgetsTheStatus() {
        ModelStatusHolder.publish(builtIn)

        ModelStatusHolder.clear()

        assertThat(ModelStatusHolder.latest()).isNull()
    }
}
