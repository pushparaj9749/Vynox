package com.vynox.core.json

/**
 * Minimal, dependency free JSON model + parser + writer.
 *
 * The Vynox engine intentionally ships without third party serialization
 * libraries so that the editing core stays portable, fast to load and
 * trivially testable on any JVM (including the plain unit test JVM used by CI).
 */
sealed class JsonValue {

    object Null : JsonValue()

    data class Bool(val value: Boolean) : JsonValue()

    data class Num(val value: Double) : JsonValue()

    data class Str(val value: String) : JsonValue()

    data class Arr(val items: List<JsonValue>) : JsonValue()

    data class Obj(val fields: Map<String, JsonValue>) : JsonValue()

    // ---------------------------------------------------------------- typing

    val isNull: Boolean get() = this === Null

    fun asObject(): Map<String, JsonValue> = when (this) {
        is Obj -> fields
        else -> emptyMap()
    }

    fun asArray(): List<JsonValue> = when (this) {
        is Arr -> items
        else -> emptyList()
    }

    fun asString(): String? = (this as? Str)?.value

    fun asDouble(): Double? = when (this) {
        is Num -> value
        is Str -> value.toDoubleOrNull()
        else -> null
    }

    fun asInt(): Int? = asDouble()?.toInt()

    fun asLong(): Long? = asDouble()?.toLong()

    fun asBool(): Boolean = when (this) {
        is Bool -> value
        is Num -> value != 0.0
        is Str -> value == "true" || value == "1"
        else -> false
    }

    fun get(key: String): JsonValue = asObject()[key] ?: Null

    companion object {
        val TRUE = Bool(true)
        val FALSE = Bool(false)

        fun of(value: Boolean): JsonValue = if (value) TRUE else FALSE
        fun of(value: Int): JsonValue = Num(value.toDouble())
        fun of(value: Long): JsonValue = Num(value.toDouble())
        fun of(value: Float): JsonValue = Num(value.toDouble())
        fun of(value: Double): JsonValue = Num(value)
        fun of(value: String): JsonValue = Str(value)
        fun of(value: List<JsonValue>): JsonValue = Arr(value)
        fun of(value: Map<String, JsonValue>): JsonValue = Obj(value)
        fun of(value: JsonValue?): JsonValue = value ?: Null
    }
}

/** Fluent builders so serialization code reads cleanly. */
class JsonObjectBuilder {
    private val fields = LinkedHashMap<String, JsonValue>()

    infix fun String.to(value: JsonValue?) { fields[this] = value ?: JsonValue.Null }
    fun put(key: String, value: JsonValue?) { fields[key] = value ?: JsonValue.Null }
    fun put(key: String, value: String?) { fields[key] = value?.let { JsonValue.Str(it) } ?: JsonValue.Null }
    fun put(key: String, value: Int?) { fields[key] = value?.let { JsonValue.Num(it.toDouble()) } ?: JsonValue.Null }
    fun put(key: String, value: Long?) { fields[key] = value?.let { JsonValue.Num(it.toDouble()) } ?: JsonValue.Null }
    fun put(key: String, value: Double?) { fields[key] = value?.let { JsonValue.Num(it) } ?: JsonValue.Null }
    fun put(key: String, value: Boolean?) { fields[key] = value?.let { JsonValue.of(it) } ?: JsonValue.Null }
    fun put(key: String, value: List<JsonValue>?) { fields[key] = value?.let { JsonValue.Arr(it) } ?: JsonValue.Null }
    fun putObject(key: String, block: JsonObjectBuilder.() -> Unit) {
        fields[key] = JsonValue.Obj(JsonObjectBuilder().apply(block).fields)
    }
    fun putArray(key: String, items: Iterable<JsonValue>) {
        fields[key] = JsonValue.Arr(items.toList())
    }

    fun build(): JsonValue.Obj = JsonValue.Obj(fields)
}

fun jsonObject(block: JsonObjectBuilder.() -> Unit): JsonValue.Obj = JsonObjectBuilder().apply(block).build()
fun jsonArray(items: Iterable<JsonValue>): JsonValue.Arr = JsonValue.Arr(items.toList())
fun jsonArrayOf(vararg items: JsonValue): JsonValue.Arr = JsonValue.Arr(items.toList())

class JsonException(message: String, val position: Int = -1) : RuntimeException(
    if (position >= 0) "$message at position $position" else message
)

/**
 * Streaming style recursive descent parser. Deliberately strict: malformed
 * project files must surface as a clear error instead of silently producing a
 * half loaded project.
 */
class JsonParser(private val source: String) {

    private var pos = 0

    fun parseDocument(): JsonValue {
        skipWhitespace()
        val value = parseValue()
        skipWhitespace()
        if (pos != source.length) fail("Unexpected trailing content")
        return value
    }

    private fun parseValue(): JsonValue {
        skipWhitespace()
        if (pos >= source.length) fail("Unexpected end of input")
        return when (val c = source[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonValue.Str(parseString())
            't' -> { expectLiteral("true"); JsonValue.TRUE }
            'f' -> { expectLiteral("false"); JsonValue.FALSE }
            'n' -> { expectLiteral("null"); JsonValue.Null }
            else -> if (c == '-' || c == '+' || c in '0'..'9') parseNumber() else fail("Unexpected character '$c'")
        }
    }

    private fun parseObject(): JsonValue.Obj {
        expect('{')
        val fields = LinkedHashMap<String, JsonValue>()
        skipWhitespace()
        if (peek() == '}') { pos++; return JsonValue.Obj(fields) }
        while (true) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            expect(':')
            fields[key] = parseValue()
            skipWhitespace()
            when (peek()) {
                ',' -> pos++
                '}' -> { pos++; return JsonValue.Obj(fields) }
                else -> fail("Expected ',' or '}' in object")
            }
        }
    }

    private fun parseArray(): JsonValue.Arr {
        expect('[')
        val items = ArrayList<JsonValue>()
        skipWhitespace()
        if (peek() == ']') { pos++; return JsonValue.Arr(items) }
        while (true) {
            items.add(parseValue())
            skipWhitespace()
            when (peek()) {
                ',' -> pos++
                ']' -> { pos++; return JsonValue.Arr(items) }
                else -> fail("Expected ',' or ']' in array")
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            if (pos >= source.length) fail("Unterminated string")
            when (val c = source[pos++]) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (pos >= source.length) fail("Unterminated escape")
                    when (val e = source[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > source.length) fail("Bad unicode escape")
                            val hex = source.substring(pos, pos + 4)
                            val code = hex.toIntOrNull(16) ?: fail("Bad unicode escape '$hex'")
                            sb.append(code.toChar())
                            pos += 4
                        }
                        else -> fail("Bad escape '\\$e'")
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun parseNumber(): JsonValue {
        val start = pos
        if (peek() == '-' || peek() == '+') pos++
        while (pos < source.length && source[pos] in '0'..'9') pos++
        if (pos < source.length && source[pos] == '.') {
            pos++
            while (pos < source.length && source[pos] in '0'..'9') pos++
        }
        if (pos < source.length && (source[pos] == 'e' || source[pos] == 'E')) {
            pos++
            if (pos < source.length && (source[pos] == '+' || source[pos] == '-')) pos++
            while (pos < source.length && source[pos] in '0'..'9') pos++
        }
        val text = source.substring(start, pos)
        val value = text.toDoubleOrNull() ?: fail("Bad number '$text'")
        return JsonValue.Num(value)
    }

    private fun skipWhitespace() {
        while (pos < source.length) {
            when (source[pos]) {
                ' ', '\t', '\n', '\r', '\u000C', '\uFEFF' -> pos++
                else -> return
            }
        }
    }

    private fun expect(expected: Char) {
        if (pos >= source.length || source[pos] != expected) fail("Expected '$expected'")
        pos++
    }

    private fun expectLiteral(literal: String) {
        if (!source.startsWith(literal, pos)) fail("Expected literal '$literal'")
        pos += literal.length
    }

    private fun peek(): Char = if (pos < source.length) source[pos] else '\u0000'

    private fun fail(message: String): Nothing = throw JsonException(message, pos)
}

/** Pretty printer used for .vnx payloads (human readable + diff friendly). */
object JsonWriter {

    fun write(value: JsonValue, pretty: Boolean = true): String {
        val sb = StringBuilder(1024)
        writeValue(sb, value, pretty, 0)
        return sb.toString()
    }

    private fun writeValue(sb: StringBuilder, value: JsonValue, pretty: Boolean, depth: Int) {
        when (value) {
            JsonValue.Null -> sb.append("null")
            is JsonValue.Bool -> sb.append(if (value.value) "true" else "false")
            is JsonValue.Num -> writeNumber(sb, value.value)
            is JsonValue.Str -> writeString(sb, value.value)
            is JsonValue.Arr -> writeArray(sb, value.items, pretty, depth)
            is JsonValue.Obj -> writeObject(sb, value.fields, pretty, depth)
        }
    }

    private fun writeArray(sb: StringBuilder, items: List<JsonValue>, pretty: Boolean, depth: Int) {
        if (items.isEmpty()) { sb.append("[]"); return }
        sb.append('[')
        items.forEachIndexed { index, item ->
            if (index > 0) sb.append(',')
            if (pretty) newLine(sb, depth + 1)
            writeValue(sb, item, pretty, depth + 1)
        }
        if (pretty) newLine(sb, depth)
        sb.append(']')
    }

    private fun writeObject(sb: StringBuilder, fields: Map<String, JsonValue>, pretty: Boolean, depth: Int) {
        if (fields.isEmpty()) { sb.append("{}"); return }
        sb.append('{')
        var first = true
        for ((key, value) in fields) {
            if (value === JsonValue.Null && key.startsWith("__")) continue
            if (!first) sb.append(',')
            first = false
            if (pretty) newLine(sb, depth + 1)
            writeString(sb, key)
            sb.append(':')
            if (pretty) sb.append(' ')
            writeValue(sb, value, pretty, depth + 1)
        }
        if (pretty) newLine(sb, depth)
        sb.append('}')
    }

    private fun newLine(sb: StringBuilder, depth: Int) {
        sb.append('\n')
        repeat(depth) { sb.append("  ") }
    }

    private fun writeNumber(sb: StringBuilder, value: Double) {
        if (value.isNaN() || value.isInfinite()) { sb.append('0'); return }
        if (value == Math.floor(value) && !value.isInfinite() && Math.abs(value) < 1e15) {
            sb.append(value.toLong())
        } else {
            sb.append(value)
        }
    }

    private fun writeString(sb: StringBuilder, value: String) {
        sb.append('"')
        for (c in value) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c < ' ' -> {
                    sb.append("\\u")
                    val hex = c.code.toString(16).padStart(4, '0')
                    sb.append(hex)
                }
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }
}

object Json {
    fun parse(text: String): JsonValue = JsonParser(text).parseDocument()
    fun tryParse(text: String): JsonValue? = try { parse(text) } catch (e: JsonException) { null }
    fun write(value: JsonValue, pretty: Boolean = true): String = JsonWriter.write(value, pretty)
}
