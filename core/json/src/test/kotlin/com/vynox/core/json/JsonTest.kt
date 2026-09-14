package com.vynox.core.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTest {

    @Test
    fun parsesScalarsAndLiterals() {
        assertEquals(JsonValue.Num(42.0), Json.parse("42"))
        assertEquals(JsonValue.Num(-1.5), Json.parse("-1.5"))
        assertEquals(JsonValue.Str("vynox"), Json.parse("\"vynox\""))
        assertEquals(JsonValue.TRUE, Json.parse("true"))
        assertEquals(JsonValue.FALSE, Json.parse("false"))
        assertEquals(JsonValue.Null, Json.parse("null"))
    }

    @Test
    fun parsesNestedStructures() {
        val value = Json.parse("""{"a":[1,2,{"b":3}],"c":{"d":[]}}""")
        assertEquals(3.0, value.get("a").asArray()[2].get("b").asDouble()!!, 0.0)
        assertTrue(value.get("c").get("d").asArray().isEmpty())
    }

    @Test
    fun handlesEscapesAndUnicode() {
        val value = Json.parse("""{"s":"line\nbreak \"quoted\" \\ \u00e9"}""")
        assertEquals("line\nbreak \"quoted\" \\ \u00e9", value.get("s").asString())
    }

    @Test
    fun roundTripsThroughWriter() {
        val original = jsonObject {
            put("name", "My Project")
            put("fps", 30)
            put("duration", 12.5)
            put("enabled", true)
            putObject("canvas") {
                put("width", 1920)
                put("height", 1080)
            }
            putArray("layers", listOf(JsonValue.of(1), JsonValue.of("two")))
        }
        val text = Json.write(original)
        val reparsed = Json.parse(text)
        assertEquals(original, reparsed)
    }

    @Test(expected = JsonException::class)
    fun rejectsMalformedInput() {
        Json.parse("""{"a": }""")
    }

    @Test(expected = JsonException::class)
    fun rejectsTrailingContent() {
        Json.parse("{} {}")
    }

    @Test
    fun writersEscapesControlCharacters() {
        val text = Json.write(JsonValue.Str("a\tb\n\"c\""))
        assertEquals("\"a\\tb\\n\\\"c\\\"\"", text)
    }

    @Test
    fun coercesTypesDefensively() {
        val value = Json.parse("""{"n":"12.5","b":1}""")
        assertEquals(12.5, value.get("n").asDouble()!!, 0.0)
        assertEquals(true, value.get("b").asBool())
        assertEquals(0.0, value.get("missing").asDouble() ?: 0.0, 0.0)
    }
}
