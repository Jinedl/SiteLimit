package io.github.jinedl.sitelimit.ct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MiniJsonTest {

    @Test
    fun parsesNestedValues() {
        val m = MiniJson.parseObject("""{"a": 1, "b": [true, false, null], "c": {"d": "текст \"в\" кавычках\n"}, "e": -2.5}""")
        assertEquals(1L, m["a"])
        assertEquals(listOf(true, false, null), m["b"])
        assertEquals("текст \"в\" кавычках\n", m.obj("c")?.str("d"))
        assertEquals(-2.5, m["e"])
    }

    @Test
    fun unicodeEscape() {
        assertEquals("Ж", MiniJson.parse("\"\\u0416\""))
    }

    @Test
    fun roundTripKeepsOrderAndText() {
        val src = linkedMapOf<String, Any?>(
            "z" to "последний?\n\"да\"",
            "a" to listOf(1, 2, linkedMapOf("x" to null)),
            "n" to 15,
        )
        val back = MiniJson.parseObject(MiniJson.write(src))
        assertEquals(listOf("z", "a", "n"), back.keys.toList())
        assertEquals("последний?\n\"да\"", back["z"])
        assertEquals(15, back.int("n"))
        val pretty = MiniJson.write(src, pretty = true)
        assertTrue(pretty.contains("\n  \"a\""))
        assertEquals(back, MiniJson.parseObject(pretty))
    }

    @Test
    fun errorsHaveLineNumbers() {
        try {
            MiniJson.parse("{\n\"a\": 1,\n\"b\" 2}")
            fail("должна быть ошибка")
        } catch (e: MiniJson.Error) {
            assertTrue(e.message!!, e.message!!.contains("строка 3"))
        }
    }

    @Test
    fun rejectsTrailingGarbage() {
        try {
            MiniJson.parse("[1] x")
            fail()
        } catch (e: MiniJson.Error) {
            assertTrue(e.message!!.contains("лишний"))
        }
    }

    @Test
    fun helpers() {
        val m = MiniJson.parseObject("""{"i": 3, "s": "x", "o": {}}""")
        assertEquals(3, m.int("i"))
        assertNull(m.int("s"))
        assertEquals(emptyList<Any?>(), m.list("нет"))
    }
}
