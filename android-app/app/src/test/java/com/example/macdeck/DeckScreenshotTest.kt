package com.example.macdeck

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
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

    @Test fun pairScreen() = capture("pair") { PairScreen(onPair = { true }) }

    @Config(qualifiers = "w800dp-h400dp-land-xhdpi")
    @Test fun pairScreenLandscape() = capture("pair_landscape") { PairScreen(onPair = { true }) }
}
