package com.example.macdeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeckScreenModelTest {

    private val homeKeys = DeckProtocol.Layout(DeckProtocol.HOME_APP, "Desktop", listOf(DeckButton("0", "Spotlight", "", 0)))
    private val appKeys = DeckState(
        status = Status.Connected, app = "com.jetbrains.pycharm", appName = "PyCharm",
        buttons = listOf(DeckButton("0", "Run", "", 0)), home = homeKeys, pin = PinState.NotPinned,
        features = DeckProtocol.REQUIRED_FEATURES,
    )

    @Test fun `an app with its own keys offers Home and Pin, not Edit`() {
        val s = appKeys.screen()
        assertEquals("PyCharm", s.appName)
        assertEquals(listOf("Run"), s.buttons.map { it.label })
        assertTrue(s.showHomeToggle && s.actionRow)
        assertFalse(s.showingHome || s.homeShown || s.canEdit)
        assertEquals(PinState.NotPinned, s.pinButton)
    }

    @Test fun `its Home view shows the Home keys, offers Edit and hides Pin`() {
        val s = appKeys.copy(showingHome = true).screen()
        assertEquals(DeckProtocol.HOME_APP, s.app)
        assertEquals(listOf("Spotlight"), s.buttons.map { it.label })
        assertTrue(s.showingHome && s.homeShown && s.canEdit)
        assertNull(s.pinButton)
    }

    @Test fun `an app without keys shows an editable Home without a Home toggle`() {
        val s = DeckState(status = Status.Connected, app = "com.x", appName = "Desktop", isHome = true,
            features = DeckProtocol.REQUIRED_FEATURES).screen()
        assertTrue(s.homeShown && s.canEdit && s.actionRow)
        assertFalse(s.showHomeToggle)
    }

    @Test fun `unsupported pinning and disconnection hide Pin and Edit`() {
        assertNull(appKeys.copy(pin = PinState.Unsupported).screen().pinButton)
        assertFalse(appKeys.copy(showingHome = true, status = Status.Reconnecting).screen().canEdit)
        assertFalse(appKeys.copy(showingHome = true, features = setOf("home", "pins")).screen().canEdit)
    }

    @Test fun `plain default keys have no action row`() {
        val s = DeckState(status = Status.Connected, app = "a", appName = "Desktop", features = DeckProtocol.REQUIRED_FEATURES).screen()
        assertFalse(s.actionRow || s.showHomeToggle || s.canEdit)
    }

    @Test fun `a connected agent missing features is flagged as outdated`() {
        assertFalse(appKeys.screen().agentOutdated)
        assertTrue(appKeys.copy(features = null).screen().agentOutdated)
        assertTrue(appKeys.copy(features = setOf("home")).screen().agentOutdated)
        assertFalse(appKeys.copy(features = null, status = Status.Connecting).screen().agentOutdated)
    }
}
