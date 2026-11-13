package com.memo494.posetracker.core.json

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class JsonTest {
    private fun assertRejected(text: String) {
        assertThrows(JsonParseException::class.java) { Json.parse(text) }
    }

    private fun nested(depth: Int): String = "[".repeat(depth) + "]".repeat(depth)

    // ---- valid documents --------------------------------------------------------------------------

    @Test
    fun literals_areParsed() {
        assertThat(Json.parse("true")).isEqualTo(JsonBoolean(true))
        assertThat(Json.parse("false")).isEqualTo(JsonBoolean(false))
        assertThat(Json.parse("null")).isEqualTo(JsonNull)
    }

    @Test
    fun whitespaceAroundTheValue_isIgnored() {
        assertThat(Json.parse(" \t\r\n null \n")).isEqualTo(JsonNull)
    }

    @Test
    fun emptyContainers_areParsed() {
        assertThat(Json.parse("{}")).isEqualTo(JsonObject(emptyMap()))
        assertThat(Json.parse("[]")).isEqualTo(JsonArray(emptyList()))
        assertThat(Json.parse(" [ ] ")).isEqualTo(JsonArray(emptyList()))
        assertThat(Json.parse(" { } ")).isEqualTo(JsonObject(emptyMap()))
    }

    @Test
    fun nestedDocument_isParsedWithEveryValueKind() {
        val parsed = Json.parse("""{"a":[1,"x",{"b":null}],"c":{"d":true,"e":false},"f":-2.5e1}""")

        val expected =
            JsonObject(
                linkedMapOf(
                    "a" to JsonArray(listOf(JsonNumber("1"), JsonString("x"), JsonObject(mapOf("b" to JsonNull)))),
                    "c" to JsonObject(linkedMapOf("d" to JsonBoolean(true), "e" to JsonBoolean(false))),
                    "f" to JsonNumber("-2.5e1"),
                ),
            )
        assertThat(parsed).isEqualTo(expected)
    }

    @Test
    fun objectMembers_keepInsertionOrder() {
        val parsed = Json.parse("""{"z":1,"a":2,"m":3}""") as JsonObject

        assertThat(parsed.members.keys.toList()).containsExactly("z", "a", "m").inOrder()
    }

    @Test
    fun stringEscapes_allDecode() {
        val parsed = Json.parse("\"\\\" \\\\ \\/ \\b \\f \\n \\r \\t\"") as JsonString

        assertThat(parsed.value).isEqualTo("\" \\ / \b \u000C \n \r \t")
    }

    @Test
    fun unicodeEscape_decodesBothCases() {
        assertThat((Json.parse("\"\\u0041\\u00e9\\u00E9\"") as JsonString).value).isEqualTo("A\u00e9\u00e9")
    }

    @Test
    fun unicodeEscape_surrogatePair_formsOneCodePoint() {
        val parsed = Json.parse("\"\\uD83D\\uDE00\"") as JsonString

        assertThat(parsed.value.codePointAt(0)).isEqualTo(0x1F600)
        assertThat(parsed.value.length).isEqualTo(2)
    }

    @Test
    fun nonAsciiCharactersInStrings_arePreserved() {
        assertThat((Json.parse("\"h\u00e9llo \u4e16\"") as JsonString).value).isEqualTo("h\u00e9llo \u4e16")
    }

    @Test
    fun numbers_keepTheirLexeme() {
        val lexemes = listOf("0", "-0", "7", "-12", "1.5", "-0.25", "1e3", "1E3", "1e+3", "1e-3", "2.5E-10", "0.0e0")
        for (lexeme in lexemes) {
            assertThat(Json.parse(lexeme)).isEqualTo(JsonNumber(lexeme))
        }
    }

    @Test
    fun numbers_convertToDouble() {
        assertThat(JsonNumber("1.5").toDouble()).isEqualTo(1.5)
        assertThat(JsonNumber("-2.5e1").toDouble()).isEqualTo(-25.0)
        assertThat(JsonNumber("3").toDouble()).isEqualTo(3.0)
    }

    @Test
    fun longAbove2Pow53_isReadExactly() {
        // 2^53 + 1 cannot be held by a Double; the lexeme path must keep every digit.
        val parsed = Json.parse("9007199254740993") as JsonNumber

        assertThat(parsed.toLong()).isEqualTo(9_007_199_254_740_993L)
        assertThat(JsonNumber("9223372036854775807").toLong()).isEqualTo(Long.MAX_VALUE)
        assertThat(JsonNumber("-9223372036854775808").toLong()).isEqualTo(Long.MIN_VALUE)
    }

    @Test
    fun toLong_nonIntegerOrOutOfRange_throws() {
        for (lexeme in listOf("1.0", "1e2", "1E2", "9223372036854775808", "-9223372036854775809")) {
            assertThrows(JsonParseException::class.java) { JsonNumber(lexeme).toLong() }
        }
    }

    @Test
    fun jsonNumber_outsideTheGrammar_cannotBeBuilt() {
        for (lexeme in listOf("", "+1", "01", "1.", ".5", "-", "1e", "NaN", "0x10", " 1")) {
            assertThrows(JsonParseException::class.java) { JsonNumber(lexeme) }
        }
    }

    @Test
    fun nestingExactlyAtTheLimit_isAccepted() {
        val parsed = Json.parse(nested(TrackerConfig.JSON_MAX_DEPTH))

        assertThat(parsed).isInstanceOf(JsonArray::class.java)
    }

    @Test
    fun nestingOneAboveTheLimit_isRejected() {
        assertRejected(nested(TrackerConfig.JSON_MAX_DEPTH + 1))
    }

    @Test
    fun objectNestingAboveTheLimit_isRejected() {
        val depth = TrackerConfig.JSON_MAX_DEPTH + 1
        assertRejected("{\"a\":".repeat(depth) + "1" + "}".repeat(depth))
    }

    @Test
    fun veryDeepNesting_isRejectedWithoutOverflowingTheStack() {
        assertRejected("[".repeat(100_000))
    }

    // ---- rejections -------------------------------------------------------------------------------

    @Test
    fun emptyOrWhitespaceOnlyInput_isRejected() {
        assertRejected("")
        assertRejected("   ")
        assertRejected("\n\t")
    }

    @Test
    fun trailingData_isRejected() {
        assertRejected("1 2")
        assertRejected("{} {}")
        assertRejected("[]]")
        assertRejected("null x")
        assertRejected("\"a\"\"b\"")
    }

    @Test
    fun duplicateKeys_areRejected() {
        assertRejected("""{"a":1,"a":2}""")
        assertRejected("""{"a":1,"b":{"x":1,"x":1}}""")
    }

    @Test
    fun sameKeyInDifferentObjects_isAccepted() {
        val parsed = Json.parse("""[{"a":1},{"a":2}]""") as JsonArray

        assertThat(parsed.size).isEqualTo(2)
    }

    @Test
    fun invalidEscapes_areRejected() {
        assertRejected("\"\\x\"")
        assertRejected("\"\\a\"")
        assertRejected("\"\\U0041\"")
        assertRejected("\"\\'\"")
        assertRejected("\"\\ \"")
    }

    @Test
    fun badOrIncompleteUnicodeEscapes_areRejected() {
        assertRejected("\"\\u\"")
        assertRejected("\"\\u12\"")
        assertRejected("\"\\u12G4\"")
        assertRejected("\"\\uXYZW\"")
        assertRejected("\"\\u123")
        assertRejected("\"\\u00e")
    }

    @Test
    fun backslashAtTheEnd_isRejected() {
        assertRejected("\"abc\\")
    }

    @Test
    fun rawControlCharactersInStrings_areRejected() {
        assertRejected("\"a\nb\"")
        assertRejected("\"a\tb\"")
        assertRejected("\"a\u0000b\"")
        assertRejected("\"a\u001Fb\"")
    }

    @Test
    fun rawControlCharacterAsKey_isRejected() {
        assertRejected("{\"a\nb\":1}")
    }

    @Test
    fun spaceInsideAString_isAccepted() {
        assertThat((Json.parse("\"a b\"") as JsonString).value).isEqualTo("a b")
    }

    @Test
    fun unterminatedStringsArraysAndObjects_areRejected() {
        assertRejected("\"abc")
        assertRejected("[")
        assertRejected("[1")
        assertRejected("[1,")
        assertRejected("{")
        assertRejected("{\"a\"")
        assertRejected("{\"a\":")
        assertRejected("{\"a\":1")
        assertRejected("{\"a\":1,")
    }

    @Test
    fun trailingCommas_areRejected() {
        assertRejected("[1,]")
        assertRejected("[,]")
        assertRejected("[,1]")
        assertRejected("""{"a":1,}""")
        assertRejected("{,}")
    }

    @Test
    fun missingSeparators_areRejected() {
        assertRejected("[1 2]")
        assertRejected("""{"a" 1}""")
        assertRejected("""{"a":1 "b":2}""")
        assertRejected("""{"a"::1}""")
        assertRejected("""{1:2}""")
        assertRejected("""{a:1}""")
    }

    @Test
    fun mismatchedBrackets_areRejected() {
        assertRejected("[1}")
        assertRejected("""{"a":1]""")
    }

    @Test
    fun numbersOutsideTheGrammar_areRejected() {
        val texts = listOf("+1", "01", "-01", "00", "1.", ".5", "-", "-.5", "1e", "1e+", "1E-", "1.e3", "NaN", "Inf")
        for (text in texts) {
            assertRejected(text)
        }
        assertRejected("Infinity")
        assertRejected("-Infinity")
        assertRejected("0x10")
        assertRejected("[1.]")
        assertRejected("[01]")
    }

    @Test
    fun literalsOtherThanTrueFalseNull_areRejected() {
        val texts = listOf("True", "TRUE", "False", "NULL", "Null", "nul", "tru", "fals", "undefined", "nil", "t", "n")
        for (text in texts) {
            assertRejected(text)
        }
    }

    @Test
    fun singleQuotedStringsAndComments_areRejected() {
        assertRejected("'a'")
        assertRejected("[1] // c")
        assertRejected("/* c */ 1")
    }

    @Test
    fun nonJsonWhitespace_isRejected() {
        assertRejected("\u000B1")
        assertRejected("\u00A01")
        assertRejected("\uFEFF1")
    }

    @Test
    fun parseException_carriesTheCharacterOffset() {
        val error = assertThrows(JsonParseException::class.java) { Json.parse("[1, 2, x]") }

        assertThat(error.offset).isEqualTo(7)
    }

    @Test
    fun trailingDataException_pointsAtTheFirstExtraCharacter() {
        val error = assertThrows(JsonParseException::class.java) { Json.parse("{} x") }

        assertThat(error.offset).isEqualTo(3)
    }

    @Test
    fun duplicateKeyException_pointsAtTheSecondKey() {
        val error = assertThrows(JsonParseException::class.java) { Json.parse("""{"a":1,"a":2}""") }

        assertThat(error.offset).isEqualTo(7)
    }

    // ---- typed accessors --------------------------------------------------------------------------

    private val sample =
        Json.parse(
            """{"o":{"x":1},"a":[1,2.5,"s",{"k":1}],"s":"text","n":42,"d":1.5,"big":3000000000,"b":true}""",
        ) as JsonObject

    @Test
    fun accessors_returnTypedMembers() {
        assertThat(sample.obj("o").long("x")).isEqualTo(1L)
        assertThat(sample.array("a").size).isEqualTo(4)
        assertThat(sample.string("s")).isEqualTo("text")
        assertThat(sample.long("n")).isEqualTo(42L)
        assertThat(sample.int("n")).isEqualTo(42)
        assertThat(sample.double("d")).isEqualTo(1.5)
        assertThat(sample.double("n")).isEqualTo(42.0)
    }

    @Test
    fun arrayAccessors_returnTypedElements() {
        val array = sample.array("a")

        assertThat(array.double(0)).isEqualTo(1.0)
        assertThat(array.double(1)).isEqualTo(2.5)
        assertThat(array.obj(3).long("k")).isEqualTo(1L)
    }

    @Test
    fun accessor_missingMember_failsWithTheMemberName() {
        val error = assertThrows(JsonParseException::class.java) { sample.string("nope") }

        assertThat(error.message).contains("nope")
        assertThat(error.offset).isEqualTo(JsonParseException.NO_OFFSET)
    }

    @Test
    fun accessor_wrongType_failsWithTheMemberName() {
        for (name in listOf("o", "a", "n", "b")) {
            val error = assertThrows(JsonParseException::class.java) { sample.string(name) }
            assertThat(error.message).contains("\"$name\"")
        }
        assertThat(assertThrows(JsonParseException::class.java) { sample.obj("a") }.message).contains("\"a\"")
        assertThat(assertThrows(JsonParseException::class.java) { sample.array("o") }.message).contains("\"o\"")
        assertThat(assertThrows(JsonParseException::class.java) { sample.long("s") }.message).contains("\"s\"")
        assertThat(assertThrows(JsonParseException::class.java) { sample.double("b") }.message).contains("\"b\"")
    }

    @Test
    fun long_ofANonIntegerMember_failsWithTheMemberName() {
        val error = assertThrows(JsonParseException::class.java) { sample.long("d") }

        assertThat(error.message).contains("\"d\"")
    }

    @Test
    fun int_outsideTheIntRange_failsWithTheMemberName() {
        val error = assertThrows(JsonParseException::class.java) { sample.int("big") }

        assertThat(error.message).contains("\"big\"")
        assertThat(sample.long("big")).isEqualTo(3_000_000_000L)
    }

    @Test
    fun arrayAccessor_wrongTypeOrOutOfRange_fails() {
        val array = sample.array("a")

        assertThrows(JsonParseException::class.java) { array.double(2) }
        assertThrows(JsonParseException::class.java) { array.obj(0) }
        assertThrows(JsonParseException::class.java) { array.double(4) }
        assertThrows(JsonParseException::class.java) { array.double(-1) }
    }

    @Test
    fun jsonParseException_isAnIllegalArgumentException() {
        assertThat(JsonParseException("x", 0)).isInstanceOf(IllegalArgumentException::class.java)
    }
}
