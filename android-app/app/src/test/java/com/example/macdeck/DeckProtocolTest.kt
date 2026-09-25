package com.example.macdeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun `parses the home keys sent with an app layout`() {
        val l = DeckProtocol.parseLayout(
            """{"type":"layout","app":"com.spotify.client","appName":"Spotify","buttons":[{"id":"1","label":"Play"}],
               "home":{"app":"macdeck.home","appName":"Desktop","buttons":[{"id":"1","label":"Mission Control"}]}}"""
        )!!
        val home = l.home!!
        assertEquals(DeckProtocol.HOME_APP, home.app)
        assertEquals("Desktop", home.appName)
        assertEquals(listOf("Mission Control"), home.buttons.map { it.label })
        assertNull(home.home)
    }

    @Test fun `parses app icons, pinned apps and the pinned flag`() {
        val l = DeckProtocol.parseLayout(
            """{"type":"layout","app":"com.jetbrains.pycharm","pinned":true,"buttons":[],
               "home":{"buttons":[{"id":"pin:com.jetbrains.pycharm","label":"PyCharm","image":"iVBORw0=","pinned":"com.jetbrains.pycharm"},
                                   {"id":"0","label":"Spotlight","image":""}]}}"""
        )!!
        assertEquals(PinState.Pinned, l.pin)
        val (pinnedKey, plainKey) = l.home!!.buttons
        assertEquals("iVBORw0=", pinnedKey.image)
        assertEquals("com.jetbrains.pycharm", pinnedKey.pinnedApp)
        assertNull(plainKey.image)
        assertNull(plainKey.pinnedApp)
    }

    @Test fun `pin state tells apart unsupported, pinned, not pinned and already on Home`() {
        fun pin(extra: String) = DeckProtocol.parseLayout("""{"type":"layout","app":"a"$extra}""")!!.pin
        assertEquals(PinState.Unsupported, pin(""))            // an agent without pinning
        assertEquals(PinState.NotPinned, pin(""","pinned":false"""))
        assertEquals(PinState.Pinned, pin(""","pinned":true"""))
        assertEquals(PinState.OnHome, pin(""","pinned":true,"onHome":true"""))
    }

    @Test fun `parses isHome and builds reorder messages`() {
        assertTrue(DeckProtocol.parseLayout("""{"type":"layout","isHome":true}""")!!.isHome)
        assertFalse(DeckProtocol.parseLayout(TestData.layoutJson)!!.isHome)
        assertJson("""{"type":"reorder","ids":["pin:a","0","1"]}""", DeckProtocol.reorder(listOf("pin:a", "0", "1")))
    }

    @Test fun `parses features, and pins need the pins feature`() {
        val l = DeckProtocol.parseLayout("""{"type":"layout","features":["home","pins"],"pinned":false}""")!!
        assertEquals(setOf("home", "pins"), l.features)
        assertEquals(PinState.NotPinned, l.pin)
        val noPins = DeckProtocol.parseLayout("""{"type":"layout","features":["home"],"pinned":false}""")!!
        assertEquals(PinState.Unsupported, noPins.pin)
        assertNull(DeckProtocol.parseLayout(TestData.layoutJson)!!.features)   // an agent too old to say
    }

    @Test fun `parses the images sent with a layout`() {
        val l = DeckProtocol.parseLayout(
            """{"type":"layout","buttons":[{"id":"0","image":"sha1:ab"}],"images":{"sha1:ab":"iVBORw0=","bad":null}}"""
        )!!
        assertEquals(mapOf("sha1:ab" to "iVBORw0="), l.images)
        assertEquals("sha1:ab", l.buttons.single().image)   // the ViewModel swaps in the bytes
    }

    @Test fun `image references are never shown as text`() {
        val l = DeckProtocol.parseLayout(
            """{"type":"layout","buttons":[{"id":"0","icon":"app:com.spotify.client"},{"id":"1","icon":"logo:spotify-icon"},{"id":"2","icon":"🎵"}]}"""
        )!!
        assertEquals(listOf("", "", "🎵"), l.buttons.map { it.icon })
    }

    @Test fun `builds pin and unpin messages`() {
        assertJson("""{"type":"pin","app":"com.x"}""", DeckProtocol.pin("com.x", pinned = true))
        assertJson("""{"type":"unpin","app":"com.x"}""", DeckProtocol.pin("com.x", pinned = false))
    }

    @Test fun `home keys default to the home app id and are optional`() {
        assertEquals(
            DeckProtocol.HOME_APP,
            DeckProtocol.parseLayout("""{"type":"layout","home":{"buttons":[]}}""")!!.home!!.app,
        )
        assertNull(DeckProtocol.parseLayout(TestData.layoutJson)!!.home)
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
