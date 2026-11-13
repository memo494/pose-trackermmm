package com.memo494.posetracker.core.replay

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.json.JsonParseException
import com.memo494.posetracker.core.model.TimestampSource
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ReplayFixtureTest {
    private val valid =
        """
        {"version":1,"frames":[
          {"timestampNs":9007199254740993,"timestampSource":"IMAGE","widthImg":640,"heightImg":480,
           "detections":[
             {"box":[10,20,110.5,220],"score":0.9,"keypointsXy":[50,30,60,32.5],"keypointScores":[0.8,0.7]},
             {"box":[0,0,0,0],"score":0,"keypointsXy":[],"keypointScores":[]}]},
          {"timestampNs":2,"timestampSource":"ARRIVAL","widthImg":320,"heightImg":240,"detections":[]}
        ]}
        """.trimIndent()

    /** Replaces [from] by [to] in the valid fixture and expects the parser to throw. */
    private fun assertRejected(
        from: String,
        to: String,
    ) {
        assertThat(valid).contains(from)
        val text = valid.replace(from, to)
        assertThrows(IllegalArgumentException::class.java) { ReplayFixture.parse(text) }
    }

    @Test
    fun validFixture_isParsedIntoDetectionFrames() {
        val frames = ReplayFixture.parse(valid)

        assertThat(frames).hasSize(2)
        val first = frames[0]
        assertThat(first.timestampNs).isEqualTo(9_007_199_254_740_993L)
        assertThat(first.timestampSource).isEqualTo(TimestampSource.IMAGE)
        assertThat(first.widthImg).isEqualTo(640)
        assertThat(first.heightImg).isEqualTo(480)
        assertThat(first.detections).hasSize(2)
        val person = first.detections[0]
        assertThat(person.boxLeftImg).isEqualTo(10f)
        assertThat(person.boxTopImg).isEqualTo(20f)
        assertThat(person.boxRightImg).isEqualTo(110.5f)
        assertThat(person.boxBottomImg).isEqualTo(220f)
        assertThat(person.score).isEqualTo(0.9f)
        assertThat(person.keypointCount).isEqualTo(2)
        assertThat(person.keypointXImg(1)).isEqualTo(60f)
        assertThat(person.keypointYImg(1)).isEqualTo(32.5f)
        assertThat(person.keypointScores[0]).isEqualTo(0.8f)
        assertThat(first.detections[1].keypointCount).isEqualTo(0)
    }

    @Test
    fun secondFrame_hasItsOwnSourceSizeAndNoDetections() {
        val second = ReplayFixture.parse(valid)[1]

        assertThat(second.timestampNs).isEqualTo(2L)
        assertThat(second.timestampSource).isEqualTo(TimestampSource.ARRIVAL)
        assertThat(second.widthImg).isEqualTo(320)
        assertThat(second.heightImg).isEqualTo(240)
        assertThat(second.detections).isEmpty()
    }

    @Test
    fun emptyFrameList_isAccepted() {
        assertThat(ReplayFixture.parse("""{"version":1,"frames":[]}""")).isEmpty()
    }

    @Test
    fun malformedJson_isRejected() {
        assertThrows(JsonParseException::class.java) { ReplayFixture.parse("") }
        assertThrows(JsonParseException::class.java) { ReplayFixture.parse(valid + "x") }
        assertThrows(JsonParseException::class.java) {
            ReplayFixture.parse("""{"version":1,"version":1,"frames":[]}""")
        }
    }

    @Test
    fun rootThatIsNotAnObject_isRejected() {
        assertThrows(IllegalArgumentException::class.java) { ReplayFixture.parse("[]") }
        assertThrows(IllegalArgumentException::class.java) { ReplayFixture.parse("1") }
    }

    @Test
    fun unknownVersion_isRejected() {
        assertRejected("\"version\":1", "\"version\":2")
        assertRejected("\"version\":1", "\"version\":0")
        assertRejected("\"version\":1", "\"version\":1.0")
        assertRejected("\"version\":1", "\"version\":\"1\"")
    }

    @Test
    fun missingTopLevelFields_areRejected() {
        assertRejected("\"version\":1,", "")
        assertRejected(",\"frames\":[", ",\"other\":[")
    }

    @Test
    fun missingFrameFields_areRejected() {
        assertRejected("\"timestampNs\":2,", "")
        assertRejected("\"timestampSource\":\"ARRIVAL\",", "")
        assertRejected("\"widthImg\":320,", "")
        assertRejected("\"heightImg\":240,", "")
        assertRejected(",\"detections\":[]", "")
    }

    @Test
    fun missingDetectionFields_areRejected() {
        assertRejected("\"box\":[10,20,110.5,220],", "")
        assertRejected("\"score\":0.9,", "")
        assertRejected("\"keypointsXy\":[50,30,60,32.5],", "")
        assertRejected(",\"keypointScores\":[0.8,0.7]", "")
    }

    @Test
    fun timestampNs_mustBeAnExactInteger() {
        assertRejected("\"timestampNs\":2", "\"timestampNs\":2.5")
        assertRejected("\"timestampNs\":2", "\"timestampNs\":2e0")
        assertRejected("\"timestampNs\":2", "\"timestampNs\":\"2\"")
        assertRejected("\"timestampNs\":2", "\"timestampNs\":9223372036854775808")
    }

    @Test
    fun timestampSource_mustBeExactlyImageOrArrival() {
        assertRejected("\"ARRIVAL\"", "\"arrival\"")
        assertRejected("\"IMAGE\"", "\"Image\"")
        assertRejected("\"IMAGE\"", "\"\"")
        assertRejected("\"IMAGE\"", "\"IMAGE \"")
        assertRejected("\"IMAGE\"", "\"OTHER\"")
        assertRejected("\"IMAGE\"", "1")
        assertRejected("\"IMAGE\"", "null")
    }

    @Test
    fun nonPositiveSize_isRejected() {
        assertRejected("\"widthImg\":640", "\"widthImg\":0")
        assertRejected("\"widthImg\":640", "\"widthImg\":-640")
        assertRejected("\"heightImg\":480", "\"heightImg\":0")
        assertRejected("\"heightImg\":480", "\"heightImg\":-1")
    }

    @Test
    fun sizeThatIsNotAnIntegerOrDoesNotFitAnInt_isRejected() {
        assertRejected("\"widthImg\":640", "\"widthImg\":640.5")
        assertRejected("\"widthImg\":640", "\"widthImg\":3000000000")
        assertRejected("\"heightImg\":480", "\"heightImg\":\"480\"")
    }

    @Test
    fun boxWithWrongLength_isRejected() {
        assertRejected("\"box\":[10,20,110.5,220]", "\"box\":[10,20,110.5]")
        assertRejected("\"box\":[10,20,110.5,220]", "\"box\":[10,20,110.5,220,230]")
        assertRejected("\"box\":[10,20,110.5,220]", "\"box\":[]")
        assertRejected("\"box\":[10,20,110.5,220]", "\"box\":{}")
    }

    @Test
    fun boxWithNonNumberOrNonFiniteValue_isRejected() {
        assertRejected("\"box\":[10,20,110.5,220]", "\"box\":[10,20,110.5,\"220\"]")
        assertRejected("\"box\":[10,20,110.5,220]", "\"box\":[10,20,110.5,null]")
        // Valid JSON numbers that overflow a Float are not finite coordinates.
        assertRejected("\"box\":[10,20,110.5,220]", "\"box\":[10,20,110.5,1e999]")
        assertRejected("\"box\":[10,20,110.5,220]", "\"box\":[10,20,110.5,1e39]")
    }

    @Test
    fun boxWithInvertedEdges_isRejected() {
        assertRejected("\"box\":[10,20,110.5,220]", "\"box\":[111,20,110.5,220]")
        assertRejected("\"box\":[10,20,110.5,220]", "\"box\":[10,221,110.5,220]")
    }

    @Test
    fun boxWithZeroSize_isAccepted() {
        val frames = ReplayFixture.parse(valid)

        assertThat(frames[0].detections[1].boxLeftImg).isEqualTo(frames[0].detections[1].boxRightImg)
    }

    @Test
    fun scoreOutsideZeroToOne_isRejected() {
        assertRejected("\"score\":0.9", "\"score\":1.01")
        assertRejected("\"score\":0.9", "\"score\":-0.01")
        assertRejected("\"score\":0.9", "\"score\":\"0.9\"")
        assertRejected("\"score\":0.9", "\"score\":1e999")
    }

    @Test
    fun scoresAtTheBounds_areAccepted() {
        val text = valid.replace("\"score\":0.9", "\"score\":1").replace("\"score\":0,", "\"score\":0.0,")

        val frames = ReplayFixture.parse(text)

        assertThat(frames[0].detections[0].score).isEqualTo(1f)
        assertThat(frames[0].detections[1].score).isEqualTo(0f)
    }

    @Test
    fun keypointScoreOutsideZeroToOne_isRejected() {
        assertRejected("\"keypointScores\":[0.8,0.7]", "\"keypointScores\":[0.8,1.5]")
        assertRejected("\"keypointScores\":[0.8,0.7]", "\"keypointScores\":[-0.1,0.7]")
        assertRejected("\"keypointScores\":[0.8,0.7]", "\"keypointScores\":[0.8,\"x\"]")
    }

    @Test
    fun keypointCoordinateNotFinite_isRejected() {
        assertRejected("\"keypointsXy\":[50,30,60,32.5]", "\"keypointsXy\":[50,30,60,1e999]")
        assertRejected("\"keypointsXy\":[50,30,60,32.5]", "\"keypointsXy\":[50,30,60,-1e39]")
        assertRejected("\"keypointsXy\":[50,30,60,32.5]", "\"keypointsXy\":[50,30,60,null]")
    }

    @Test
    fun keypointArraysOfMismatchedLength_areRejected() {
        assertRejected("\"keypointsXy\":[50,30,60,32.5]", "\"keypointsXy\":[50,30,60]")
        assertRejected("\"keypointsXy\":[50,30,60,32.5]", "\"keypointsXy\":[50,30,60,32.5,70,40]")
        assertRejected("\"keypointScores\":[0.8,0.7]", "\"keypointScores\":[0.8]")
        assertRejected("\"keypointScores\":[0.8,0.7]", "\"keypointScores\":[0.8,0.7,0.6]")
    }

    @Test
    fun framesThatAreNotObjects_areRejected() {
        assertThrows(IllegalArgumentException::class.java) { ReplayFixture.parse("""{"version":1,"frames":[1]}""") }
        assertThrows(IllegalArgumentException::class.java) { ReplayFixture.parse("""{"version":1,"frames":{}}""") }
        assertThrows(IllegalArgumentException::class.java) {
            ReplayFixture.parse(
                """{"version":1,"frames":[{"timestampNs":1,"timestampSource":"IMAGE","widthImg":1,"heightImg":1,""" +
                    """"detections":[7]}]}""",
            )
        }
    }
}
