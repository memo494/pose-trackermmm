package com.memo494.posetracker.core.json

import com.memo494.posetracker.core.TrackerConfig

/**
 * A parsed JSON value (RFC 8259). Produced by [Json.parse]; immutable. Numbers keep their source text
 * so integers beyond 2^53 are read exactly ([JsonNumber.toLong]).
 */
sealed class JsonValue

/**
 * A JSON object.
 *
 * @property members member name to value, in document order. [Json.parse] never produces duplicate names.
 */
data class JsonObject(
    val members: Map<String, JsonValue>,
) : JsonValue() {
    /** The member [name] as an object; fails closed (naming the member) when missing or of another type. */
    fun obj(name: String): JsonObject = asObject(member(name), memberLabel(name))

    /** The member [name] as an array; fails closed (naming the member) when missing or of another type. */
    fun array(name: String): JsonArray = asArray(member(name), memberLabel(name))

    /** The member [name] as a string; fails closed (naming the member) when missing or of another type. */
    fun string(name: String): String = asString(member(name), memberLabel(name))

    /**
     * The member [name] as an exact [Long]; fails closed (naming the member) when missing, not a number,
     * not an integer lexeme or outside the Long range.
     */
    fun long(name: String): Long = asLong(member(name), memberLabel(name))

    /** Like [long], and additionally fails when the value does not fit an [Int]. */
    fun int(name: String): Int = asInt(member(name), memberLabel(name))

    /** The member [name] as a [Double]; fails closed (naming the member) when missing or not a number. */
    fun double(name: String): Double = asDouble(member(name), memberLabel(name))

    private fun member(name: String): JsonValue =
        members[name] ?: throw JsonParseException("member \"$name\" is missing", JsonParseException.NO_OFFSET)
}

/**
 * A JSON array.
 *
 * @property elements the values, in document order.
 */
data class JsonArray(
    val elements: List<JsonValue>,
) : JsonValue() {
    /** Number of elements. */
    val size: Int
        get() = elements.size

    /** Element [index] as an object; fails closed (naming the index) when out of range or of another type. */
    fun obj(index: Int): JsonObject = asObject(element(index), elementLabel(index))

    /** Element [index] as a [Double]; fails closed (naming the index) when out of range or not a number. */
    fun double(index: Int): Double = asDouble(element(index), elementLabel(index))

    /**
     * Element [index] as an exact [Int]; fails closed (naming the index) when out of range, not a number,
     * not an integer lexeme or outside the Int range.
     */
    fun int(index: Int): Int = asInt(element(index), elementLabel(index))

    private fun element(index: Int): JsonValue =
        elements.getOrNull(index)
            ?: throw JsonParseException("element $index is out of range (size $size)", JsonParseException.NO_OFFSET)
}

/** A JSON string, unescaped. */
data class JsonString(
    val value: String,
) : JsonValue()

/**
 * A JSON number kept as its source text.
 *
 * @property lexeme the number exactly as written; must follow the RFC 8259 number grammar (checked on
 *   construction, [JsonParseException] otherwise).
 */
data class JsonNumber(
    val lexeme: String,
) : JsonValue() {
    init {
        if (!NUMBER.matches(lexeme)) {
            throw JsonParseException("\"$lexeme\" is not a JSON number", JsonParseException.NO_OFFSET)
        }
    }

    /**
     * The exact integer value. Accepts only an integer lexeme (`-?digits`, no fraction and no exponent)
     * that fits a [Long]; anything else throws [JsonParseException] (never rounds).
     */
    fun toLong(): Long {
        if (!INTEGER.matches(lexeme)) {
            throw JsonParseException("\"$lexeme\" is not an integer", JsonParseException.NO_OFFSET)
        }
        return lexeme.toLongOrNull()
            ?: throw JsonParseException("\"$lexeme\" is outside the Long range", JsonParseException.NO_OFFSET)
    }

    /** The value as a [Double]; may be infinite for a huge exponent, so callers that need finite check it. */
    fun toDouble(): Double = lexeme.toDouble()

    private companion object {
        val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
        val INTEGER = Regex("-?(0|[1-9][0-9]*)")
    }
}

/** A JSON `true` or `false`. */
data class JsonBoolean(
    val value: Boolean,
) : JsonValue()

/** The JSON `null`. */
object JsonNull : JsonValue() {
    override fun toString(): String = "JsonNull"
}

/**
 * Every JSON problem: malformed text from [Json.parse], and a missing or mistyped member from the typed
 * accessors.
 *
 * @property offset index of the offending character in the parsed text, or [NO_OFFSET] when the problem
 *   is not tied to a position (accessor errors).
 */
class JsonParseException(
    message: String,
    val offset: Int,
) : IllegalArgumentException(message) {
    companion object {
        /** [offset] of an error that has no position in the text. */
        const val NO_OFFSET = -1
    }
}

/**
 * Strict RFC 8259 JSON reader (recursive descent). Load-time only (replay fixtures, never per frame),
 * so it favours clarity over speed.
 *
 * Fails closed with [JsonParseException] on: empty input, data after the value, duplicate keys in one
 * object, invalid or incomplete escapes (including a bad `\uXXXX`), raw control characters (below
 * U+0020) inside strings, unterminated strings/arrays/objects, trailing commas, numbers outside the
 * grammar (`+1`, `01`, `1.`, `.5`, `-`, `1e`, `NaN`, `Infinity`), literals other than `true`, `false`
 * and `null`, and nesting deeper than [TrackerConfig.JSON_MAX_DEPTH]. Only space, tab, line feed and
 * carriage return count as whitespace.
 */
object Json {
    /** Parses [text], which must hold exactly one JSON value (plus whitespace). */
    fun parse(text: String): JsonValue = Reader(text).readDocument()

    private class Reader(
        private val text: String,
    ) {
        private var pos = 0

        fun readDocument(): JsonValue {
            skipWhitespace()
            val value = readValue(0)
            skipWhitespace()
            if (pos != text.length) fail("unexpected data after the JSON value")
            return value
        }

        private fun fail(message: String): Nothing = throw JsonParseException("$message at offset $pos", pos)

        private fun skipWhitespace() {
            while (pos < text.length && isWhitespace(text[pos])) pos++
        }

        private fun isWhitespace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\r'

        /** The character at [pos]; fails with "end of input" when there is none. */
        private fun peek(expected: String): Char {
            if (pos >= text.length) fail("unexpected end of input, expected $expected")
            return text[pos]
        }

        private fun readValue(depth: Int): JsonValue =
            when (val c = peek("a value")) {
                '{' -> readObject(depth + 1)
                '[' -> readArray(depth + 1)
                '"' -> JsonString(readString())
                't' -> readLiteral("true", JsonBoolean(true))
                'f' -> readLiteral("false", JsonBoolean(false))
                'n' -> readLiteral("null", JsonNull)
                '-', in '0'..'9' -> readNumber()
                else -> fail("unexpected character '$c'")
            }

        private fun readLiteral(
            word: String,
            value: JsonValue,
        ): JsonValue {
            if (!text.startsWith(word, pos)) fail("invalid literal, expected $word")
            pos += word.length
            return value
        }

        private fun checkDepth(depth: Int) {
            if (depth > TrackerConfig.JSON_MAX_DEPTH) fail("nesting deeper than ${TrackerConfig.JSON_MAX_DEPTH}")
        }

        private fun readObject(depth: Int): JsonObject {
            checkDepth(depth)
            pos++
            val members = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek("a member name or }") == '}') {
                pos++
                return JsonObject(members)
            }
            var more = true
            while (more) {
                skipWhitespace()
                if (peek("a member name") != '"') fail("expected a member name")
                val keyOffset = pos
                val key = readString()
                if (members.containsKey(key)) {
                    pos = keyOffset
                    fail("duplicate key \"$key\"")
                }
                skipWhitespace()
                if (peek(":") != ':') fail("expected ':' after the member name")
                pos++
                skipWhitespace()
                members[key] = readValue(depth)
                skipWhitespace()
                val separator = peek(", or }")
                if (separator != ',' && separator != '}') fail("expected ',' or '}'")
                pos++
                more = separator == ','
            }
            return JsonObject(members)
        }

        private fun readArray(depth: Int): JsonArray {
            checkDepth(depth)
            pos++
            val elements = ArrayList<JsonValue>()
            skipWhitespace()
            if (peek("a value or ]") == ']') {
                pos++
                return JsonArray(elements)
            }
            var more = true
            while (more) {
                skipWhitespace()
                elements.add(readValue(depth))
                skipWhitespace()
                val separator = peek(", or ]")
                if (separator != ',' && separator != ']') fail("expected ',' or ']'")
                pos++
                more = separator == ','
            }
            return JsonArray(elements)
        }

        private fun readString(): String {
            pos++
            val out = StringBuilder()
            var c = peek("the closing quote")
            while (c != '"') {
                if (c == '\\') {
                    readEscape(out)
                } else if (c < ' ') {
                    fail("raw control character in a string")
                } else {
                    out.append(c)
                    pos++
                }
                c = peek("the closing quote")
            }
            pos++
            return out.toString()
        }

        private fun readEscape(out: StringBuilder) {
            pos++
            val escape = peek("an escape character")
            if (escape == 'u') {
                readUnicodeEscape(out)
                return
            }
            val decoded =
                when (escape) {
                    '"' -> '"'
                    '\\' -> '\\'
                    '/' -> '/'
                    'b' -> '\b'
                    'f' -> '\u000C'
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    else -> fail("invalid escape '\\$escape'")
                }
            out.append(decoded)
            pos++
        }

        private fun readUnicodeEscape(out: StringBuilder) {
            pos++
            var code = 0
            repeat(TrackerConfig.JSON_UNICODE_ESCAPE_DIGITS) {
                val digit = hexValue(peek("a hex digit"))
                if (digit < 0) fail("invalid hex digit in a unicode escape")
                code = code * TrackerConfig.JSON_HEX_RADIX + digit
                pos++
            }
            out.append(code.toChar())
        }

        /** Value of the ASCII hex digit [c], or -1 when it is not one (Unicode digits do not count). */
        private fun hexValue(c: Char): Int =
            when (c) {
                in '0'..'9' -> c - '0'
                in 'a'..'f' -> c - 'a' + TrackerConfig.JSON_DECIMAL_RADIX
                in 'A'..'F' -> c - 'A' + TrackerConfig.JSON_DECIMAL_RADIX
                else -> -1
            }

        private fun isDigitAt(index: Int): Boolean = index < text.length && text[index] in '0'..'9'

        private fun skipDigits(what: String) {
            if (!isDigitAt(pos)) fail("expected a digit in the $what")
            while (isDigitAt(pos)) pos++
        }

        private fun readNumber(): JsonNumber {
            val start = pos
            if (text[pos] == '-') pos++
            if (isDigitAt(pos) && text[pos] == '0') {
                pos++
                if (isDigitAt(pos)) fail("leading zero in a number")
            } else {
                skipDigits("integer part")
            }
            if (pos < text.length && text[pos] == '.') {
                pos++
                skipDigits("fraction")
            }
            if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
                pos++
                if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
                skipDigits("exponent")
            }
            return JsonNumber(text.substring(start, pos))
        }
    }
}

private fun memberLabel(name: String): String = "member \"$name\""

private fun elementLabel(index: Int): String = "element $index"

private fun typeName(value: JsonValue): String =
    when (value) {
        is JsonObject -> "an object"
        is JsonArray -> "an array"
        is JsonString -> "a string"
        is JsonNumber -> "a number"
        is JsonBoolean -> "a boolean"
        JsonNull -> "null"
    }

private fun wrongType(
    label: String,
    expected: String,
    value: JsonValue,
): Nothing = throw JsonParseException("$label must be $expected but is ${typeName(value)}", JsonParseException.NO_OFFSET)

private fun asObject(
    value: JsonValue,
    label: String,
): JsonObject = value as? JsonObject ?: wrongType(label, "an object", value)

private fun asArray(
    value: JsonValue,
    label: String,
): JsonArray = value as? JsonArray ?: wrongType(label, "an array", value)

private fun asString(
    value: JsonValue,
    label: String,
): String = (value as? JsonString ?: wrongType(label, "a string", value)).value

private fun asNumber(
    value: JsonValue,
    label: String,
): JsonNumber = value as? JsonNumber ?: wrongType(label, "a number", value)

private fun asDouble(
    value: JsonValue,
    label: String,
): Double = asNumber(value, label).toDouble()

private fun asLong(
    value: JsonValue,
    label: String,
): Long {
    val number = asNumber(value, label)
    try {
        return number.toLong()
    } catch (e: JsonParseException) {
        throw JsonParseException("$label: ${e.message}", JsonParseException.NO_OFFSET)
    }
}

private fun asInt(
    value: JsonValue,
    label: String,
): Int {
    val long = asLong(value, label)
    if (long < Int.MIN_VALUE || long > Int.MAX_VALUE) {
        throw JsonParseException("$label: $long is outside the Int range", JsonParseException.NO_OFFSET)
    }
    return long.toInt()
}
