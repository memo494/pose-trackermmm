package com.memo494.posetracker.core.replay

import com.memo494.posetracker.core.json.Json
import com.memo494.posetracker.core.json.JsonArray
import com.memo494.posetracker.core.json.JsonObject
import com.memo494.posetracker.core.model.DetectionFrame
import com.memo494.posetracker.core.model.PoseDetection
import com.memo494.posetracker.core.model.TimestampSource

/**
 * Reader for replay fixtures: recorded detections that the app replays instead of running the model
 * (ARCHITECTURE §3 "replay I/O"). Load-time only, never per frame.
 *
 * Format v1, one JSON document (strict RFC 8259, see [Json]):
 * ```
 * {"version":1,"frames":[
 *   {"timestampNs":123,"timestampSource":"IMAGE"|"ARRIVAL","widthImg":640,"heightImg":480,
 *    "detections":[
 *      {"box":[left,top,right,bottom],"score":0.9,
 *       "keypointsXy":[x0,y0,x1,y1,...],"keypointScores":[s0,s1,...]}]}]}
 * ```
 * - `version` must be the integer 1.
 * - `timestampNs` is an integer number of nanoseconds (System.nanoTime() timebase), read exactly as a
 *   Long (values above 2^53 are kept); a fraction or exponent is rejected.
 * - `timestampSource` must be exactly `"IMAGE"` or `"ARRIVAL"`.
 * - `widthImg` / `heightImg` are positive integers: the size of the Img space, in pixels.
 * - `box` has exactly four finite numbers, Img pixels, with `right >= left` and `bottom >= top`.
 * - `score` and every entry of `keypointScores` are finite and in [0, 1], unitless.
 * - `keypointsXy` holds Img-pixel coordinates, interleaved x and y; it has exactly twice as many
 *   entries as `keypointScores`. Every coordinate is finite.
 * - All fields are required and no unknown-field or default handling exists: anything missing or
 *   invalid throws (fail closed, never a guessed value). Numbers are validated after conversion to
 *   Float, so a value that overflows a Float counts as not finite.
 */
object ReplayFixture {
    private const val FORMAT_VERSION = 1L

    /** Numbers in a `box`: left, top, right, bottom. */
    private const val BOX_COORDINATE_COUNT = 4

    /** Numbers per keypoint in `keypointsXy`: x and y. */
    private const val XY_PER_KEYPOINT = 2

    private const val MIN_SCORE = 0f
    private const val MAX_SCORE = 1f

    /**
     * Parses a v1 fixture into one [DetectionFrame] per entry of `frames`, in file order.
     *
     * @throws IllegalArgumentException (a `JsonParseException` for malformed JSON or a missing or
     *   mistyped field) for anything the format does not allow.
     */
    fun parse(text: String): List<DetectionFrame> {
        val root = Json.parse(text)
        require(root is JsonObject) { "fixture root must be an object" }
        val version = root.long("version")
        require(version == FORMAT_VERSION) { "unsupported fixture version $version, expected $FORMAT_VERSION" }
        val frames = root.array("frames")
        return List(frames.size) { index -> readFrame(frames.obj(index), index) }
    }

    private fun readFrame(
        frame: JsonObject,
        index: Int,
    ): DetectionFrame {
        val source = frame.string("timestampSource")
        val timestampSource =
            when (source) {
                "IMAGE" -> TimestampSource.IMAGE
                "ARRIVAL" -> TimestampSource.ARRIVAL
                else -> throw IllegalArgumentException("frame $index: timestampSource \"$source\" is unknown")
            }
        val widthImg = frame.int("widthImg")
        val heightImg = frame.int("heightImg")
        require(widthImg > 0 && heightImg > 0) { "frame $index: size ${widthImg}x$heightImg must be positive" }
        val detections = frame.array("detections")
        return DetectionFrame(
            timestampNs = frame.long("timestampNs"),
            timestampSource = timestampSource,
            widthImg = widthImg,
            heightImg = heightImg,
            detections = List(detections.size) { i -> readDetection(detections.obj(i), "frame $index detection $i") },
        )
    }

    private fun readDetection(
        detection: JsonObject,
        where: String,
    ): PoseDetection {
        val box = detection.array("box")
        require(box.size == BOX_COORDINATE_COUNT) {
            "$where: box has ${box.size} numbers, expected $BOX_COORDINATE_COUNT"
        }
        val left = finiteFloat(box, 0, "$where box")
        val top = finiteFloat(box, 1, "$where box")
        val right = finiteFloat(box, 2, "$where box")
        val bottom = finiteFloat(box, 3, "$where box")
        require(right >= left && bottom >= top) { "$where: box [$left, $top, $right, $bottom] is inverted" }
        val score = checkedScore(detection.double("score").toFloat(), "$where score")
        val xy = detection.array("keypointsXy")
        val scores = detection.array("keypointScores")
        require(xy.size.toLong() == XY_PER_KEYPOINT.toLong() * scores.size) {
            "$where: keypointsXy has ${xy.size} numbers for ${scores.size} keypointScores"
        }
        return PoseDetection(
            boxLeftImg = left,
            boxTopImg = top,
            boxRightImg = right,
            boxBottomImg = bottom,
            score = score,
            keypointsXyImg = FloatArray(xy.size) { finiteFloat(xy, it, "$where keypointsXy") },
            keypointScores =
                FloatArray(scores.size) { checkedScore(scores.double(it).toFloat(), "$where keypointScores[$it]") },
        )
    }

    private fun finiteFloat(
        array: JsonArray,
        index: Int,
        what: String,
    ): Float {
        val value = array.double(index).toFloat()
        require(value.isFinite()) { "$what[$index] is not a finite number" }
        return value
    }

    private fun checkedScore(
        value: Float,
        what: String,
    ): Float {
        require(value.isFinite() && value in MIN_SCORE..MAX_SCORE) { "$what $value is not a finite number in [0, 1]" }
        return value
    }
}
