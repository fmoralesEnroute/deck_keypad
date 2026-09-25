package com.example.macdeck

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeckUiTest {

    @get:Rule val compose = createComposeRule()

    private val buttons = listOf(
        DeckButton("0", "Spotlight", "🔍", 0xFF5B6770.toInt()),
        DeckButton("1", "", "⏯", 0xFF3F7CAC.toInt()),
    )

    private fun deck(
        status: Status,
        onPress: (String, DeckButton) -> Unit = { _, _ -> },
        onUnpair: () -> Unit = {},
    ) =
        compose.setContent {
            MacDeckTheme {
                DeckScreen(
                    DeckState(status = status, app = "com.apple.finder", appName = "Desktop", buttons = buttons),
                    onPress, onUnpair,
                )
            }
        }

    @Test fun keysAreButtonsAndPressThem() {
        val pressed = mutableListOf<Pair<String, DeckButton>>()
        deck(Status.Connected, onPress = { app, button -> pressed += app to button })

        compose.onNodeWithText("Spotlight")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHasClickAction()
            .assertIsEnabled()
            .performClick()
        assertEquals(listOf("com.apple.finder" to buttons[0]), pressed)
    }

    @Test fun decorativeEmojiIsHiddenFromTalkBackButIconOnlyKeysKeepIt() {
        deck(Status.Connected)
        compose.onNodeWithText("🔍").assertDoesNotExist()
        compose.onNodeWithText("⏯").assertExists()
    }

    @Test fun keysAreDisabledWhileDisconnected() {
        deck(Status.Reconnecting)
        compose.onNodeWithText("Spotlight").assertIsNotEnabled()
    }

    @Test fun statusIsAnnouncedAsALiveRegion() {
        deck(Status.Connected)
        compose.onNode(
            hasText("Connected") and SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
        ).assertExists()
    }

    @Test fun unpairAsksForConfirmation() {
        var unpaired = false
        deck(Status.Connected, onUnpair = { unpaired = true })

        compose.onNode(hasText("Unpair") and !hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("Unpair this Mac?").assertExists()
        compose.onNode(hasText("Unpair") and hasAnyAncestor(isDialog())).performClick()
        assertTrue(unpaired)
    }

    @Test fun emptyProfileShowsTheBundleId() {
        compose.setContent {
            MacDeckTheme { DeckScreen(DeckState(status = Status.Connected, app = "com.figma.Desktop"), { _, _ -> }, {}) }
        }
        compose.onNodeWithText("com.figma.Desktop", substring = true).assertExists()
    }

    @Test fun manualPairingSubmitsWhatWasTyped() {
        val attempts = mutableListOf<Pairing>()
        compose.setContent { MacDeckTheme { PairScreen(onPair = { attempts += it; it.isValid }) } }

        compose.onNodeWithText("Connect").assertIsNotEnabled()
        compose.onNodeWithText("Mac IP address").performTextInput("192.168.1.20")
        compose.onNodeWithText("Token").performTextInput("tok")
        compose.onNodeWithText("Certificate").performTextInput(TestData.FINGERPRINT)
        compose.onNodeWithText("Connect").performScrollTo().assertIsEnabled().performClick()

        assertEquals(listOf(TestData.pairing), attempts)
    }

    @Test fun manualPairingExplainsInvalidInput() {
        compose.setContent { MacDeckTheme { PairScreen(onPair = { it.isValid }) } }

        compose.onNodeWithText("Mac IP address").performTextInput("192.168.1.20:8765")
        compose.onNodeWithText("Token").performTextInput("tok")
        compose.onNodeWithText("Certificate").performTextInput(TestData.FINGERPRINT)
        compose.onNodeWithText("Connect").performScrollTo().performClick()

        compose.onNodeWithText("Check the IP address", substring = true).assertExists()
    }
}
