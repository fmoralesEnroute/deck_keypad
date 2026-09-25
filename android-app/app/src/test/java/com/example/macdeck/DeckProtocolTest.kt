package com.example.macdeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test

class DeckProtocolTest {

    @Test fun `parses a layout`() {
        val l = DeckProtocol.parseLayout(
            """{"type":"layout","app":"com.x","appName":"X","buttons":[{"id":"0","label":"Go","icon":"🚀","color":"#112233"}]}"""
        )!!
        assertEquals("com.x", l.app)
        assertEquals("X", l.appName)
        assertEquals(listOf(DeckButton("0", "Go", "🚀", 0xFF112233.toInt())), l.buttons)
    }

    @Test fun `skips malformed buttons instead of failing the whole layout`() {
        val l = DeckProtocol.parseLayout(
            """{"type":"layout","buttons":[{"label":"no id"}, 42, null, {"id":"1","label":"ok"}]}"""
        )!!
        assertEquals(listOf("1"), l.buttons.map { it.id })
    }

    @Test fun `json null is empty, not the string null`() {
        val l = DeckProtocol.parseLayout("""{"type":"layout","app":null,"buttons":[{"id":"0","label":null}]}""")!!
        assertEquals("", l.app)
        assertEquals("", l.buttons.single().label)
    }

    @Test fun `missing buttons means an empty profile`() {
        assertEquals(emptyList<DeckButton>(), DeckProtocol.parseLayout("""{"type":"layout"}""")!!.buttons)
    }

    @Test fun `ignores non-layout and non-json messages`() {
        assertNull(DeckProtocol.parseLayout("""{"type":"pong"}"""))
        assertNull(DeckProtocol.parseLayout("not json"))
        assertNull(DeckProtocol.parseLayout("[]"))
    }

    @Test fun `bad colors fall back to the default`() {
        val l = DeckProtocol.parseLayout(
            """{"type":"layout","buttons":[{"id":"0","color":"red"},{"id":"1","color":"#12"},{"id":"2"}]}"""
        )!!
        assertEquals(List(3) { DeckProtocol.DEFAULT_COLOR }, l.buttons.map { it.color })
    }

    @Test fun `parses rgb and argb colors`() {
        assertEquals(0xFF5B6770.toInt(), DeckProtocol.parseColor("#5B6770"))
        assertEquals(0x805B6770, DeckProtocol.parseColor("#805b6770")?.toLong()?.and(0xFFFFFFFF))
        assertNull(DeckProtocol.parseColor("#+12345"))
        assertNull(DeckProtocol.parseColor("5B6770"))
    }

    @Test fun `builds hello and press messages`() {
        assertJson("""{"type":"hello","token":"t"}""", DeckProtocol.hello("t"))
        assertJson("""{"type":"press","id":"3","app":"com.x"}""", DeckProtocol.press("3", "com.x"))
    }

    private fun assertJson(expected: String, actual: String) {
        assertTrue("$actual != $expected", JSONObject(expected).similar(JSONObject(actual)))
    }
}
