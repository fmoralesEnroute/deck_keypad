package com.example.macdeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * protocol/layout-example.json is a real layout message from the agent, checked by the agent's
 * tests too (macos-agent/tests/test_protocol.py): if either side changes the format, one fails.
 */
class ProtocolContractTest {

    private val example = File("../../protocol/layout-example.json").readText()

    @Test fun `the app understands the agent's layout message`() {
        val l = DeckProtocol.parseLayout(example)!!
        assertEquals("com.jetbrains.pycharm", l.app)
        assertEquals("PyCharm", l.appName)
        assertEquals(DeckProtocol.REQUIRED_FEATURES, l.features)
        assertEquals(PinState.Pinned, l.pin)
        assertFalse(l.isHome)
        assertEquals(listOf("Run"), l.buttons.map { it.label })

        val home = l.home!!
        assertEquals(DeckProtocol.HOME_APP, home.app)
        assertEquals(listOf("PyCharm", "Spotlight", "Spotify", "Lock"), home.buttons.map { it.label })
        assertEquals("com.jetbrains.pycharm", home.buttons.first().pinnedApp)
        // Every image a key refers to arrives in the same message (first time on a connection).
        val refs = home.buttons.mapNotNull { it.image }
        assertTrue(refs.isNotEmpty() && refs.all { it.startsWith("sha1:") })
        assertEquals(refs.toSet(), l.images.keys)
    }
}
