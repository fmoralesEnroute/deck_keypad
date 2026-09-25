package com.example.macdeck

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Golden screenshots in src/test/snapshots.
 *   Record: ./gradlew recordRoborazziDebug     Verify: ./gradlew verifyRoborazziDebug
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h800dp-xhdpi")
class DeckScreenshotTest {

    @get:Rule val compose = createComposeRule()

    private val buttons = listOf(
        DeckButton("0", "Spotlight", "🔍", 0xFF5B6770.toInt()),
        DeckButton("1", "Mission Control", "🪟", 0xFF3F7CAC.toInt()),
        DeckButton("2", "Screenshot", "📸", 0xFF8E6C8A.toInt()),
        DeckButton("3", "VS Code", "💻", 0xFF2F7FC1.toInt()),
        DeckButton("4", "Spotify", "🎵", 0xFF1DB954.toInt()),
        DeckButton("5", "Lock", "🔒", 0xFFB5543A.toInt()),
    )

    private fun capture(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            MacDeckTheme { Box(Modifier.fillMaxSize().background(Graphite)) { content() } }
        }
        compose.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }

    private fun deck(status: Status) = capture("deck_${status.name.lowercase()}") {
        DeckScreen(DeckState(status = status, appName = "Desktop", buttons = buttons), { _, _ -> }, {})
    }

    @Test fun deckConnected() = deck(Status.Connected)
    @Test fun deckReconnecting() = deck(Status.Reconnecting)

    @Config(fontScale = 2f)
    @Test fun deckLargeFont() = capture("deck_font_2x") {
        DeckScreen(DeckState(status = Status.Connected, appName = "Desktop", buttons = buttons), { _, _ -> }, {})
    }

    @Config(qualifiers = "w800dp-h400dp-land-xhdpi")
    @Test fun deckLandscape() = capture("deck_landscape") {
        DeckScreen(DeckState(status = Status.Connected, appName = "Desktop", buttons = buttons), { _, _ -> }, {})
    }

    private val home = DeckProtocol.Layout(DeckProtocol.HOME_APP, "Desktop", buttons)
    private val spotify = DeckState(
        status = Status.Connected, app = "com.spotify.client", appName = "Spotify", home = home,
        buttons = listOf(
            DeckButton("0", "Previous", "⏮️", 0xFF1DB954.toInt()),
            DeckButton("1", "Play/Pause", "⏯️", 0xFF1DB954.toInt()),
            DeckButton("2", "Next", "⏭️", 0xFF1DB954.toInt()),
        ),
    )

    @Test fun deckAppWithHomeButton() = capture("deck_app_home_button") { DeckScreen(spotify, { _, _ -> }, {}) }
    @Test fun deckShowingHome() = capture("deck_showing_home") {
        DeckScreen(spotify.copy(showingHome = true), { _, _ -> }, {})
    }

    private val spotifyIcon = java.util.Base64.getEncoder().encodeToString(
        javaClass.getResourceAsStream("/spotify_icon.png")!!.readBytes()
    )
    private val pycharm = DeckState(
        status = Status.Connected, app = "com.jetbrains.pycharm", appName = "PyCharm", pin = PinState.Pinned,
        buttons = listOf(
            DeckButton("0", "Run", "▶️", 0xFF15803D.toInt()),
            DeckButton("1", "Debug", "🐞", 0xFFC2410C.toInt()),
            DeckButton("2", "Stop", "⏹️", 0xFFB5543A.toInt()),
        ),
        home = home.copy(
            buttons = buttons.map { if (it.label == "Spotify") it.copy(icon = "", image = spotifyIcon) else it } +
                DeckButton("pin:com.jetbrains.pycharm", "PyCharm", "", 0xFF5B6770.toInt(), pinnedApp = "com.jetbrains.pycharm"),
        ),
    )

    @Test fun deckEditingHome() {
        compose.setContent {
            MacDeckTheme {
                Box(Modifier.fillMaxSize().background(Graphite)) { DeckScreen(pycharm.copy(showingHome = true), { _, _ -> }, {}) }
            }
        }
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Edit Home keys")).performClick()
        compose.onRoot().captureRoboImage("src/test/snapshots/deck_editing_home.png")
    }

    @Test fun deckAppAlreadyOnHome() = capture("deck_on_home") {
        DeckScreen(spotify.copy(pin = PinState.OnHome), { _, _ -> }, {})
    }

    @Test fun deckPinnedApp() = capture("deck_pinned_app") { DeckScreen(pycharm, { _, _ -> }, {}) }
    @Test fun deckHomeWithRealIconAndPin() = capture("deck_home_icons_pins") {
        DeckScreen(pycharm.copy(showingHome = true), { _, _ -> }, {})
    }

    @Test fun pairScreen() = capture("pair") { PairScreen(onPair = { true }) }

    @Config(qualifiers = "w800dp-h400dp-land-xhdpi")
    @Test fun pairScreenLandscape() = capture("pair_landscape") { PairScreen(onPair = { true }) }
}
