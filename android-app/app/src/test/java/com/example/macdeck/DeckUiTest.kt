package com.example.macdeck

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.geometry.center
import androidx.compose.ui.test.longClick
import androidx.compose.ui.semantics.SemanticsActions
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

    @Test fun homeButtonShowsTheDefaultKeysAndPressesThemAsHome() {
        val pressed = mutableListOf<Pair<String, DeckButton>>()
        val home = DeckProtocol.Layout(DeckProtocol.HOME_APP, "Desktop", listOf(DeckButton("1", "Mission Control", "", 0)))
        var state by mutableStateOf(
            DeckState(status = Status.Connected, app = "com.spotify.client", appName = "Spotify", buttons = buttons, home = home)
        )
        compose.setContent {
            MacDeckTheme {
                DeckScreen(
                    state,
                    onPress = { app, b -> pressed += app to b },
                    onUnpair = {},
                    onShowHome = { state = state.copy(showingHome = it) },
                )
            }
        }

        compose.onNodeWithContentDescription("Home keys").performClick()
        compose.onNodeWithText("Mission Control").performClick()
        assertEquals(listOf(DeckProtocol.HOME_APP to home.buttons.single()), pressed)

        compose.onNodeWithContentDescription("Back to Spotify keys").performClick()
        compose.onNodeWithText("Spotlight").assertExists()
    }

    private val home = DeckProtocol.Layout(
        DeckProtocol.HOME_APP, "Desktop",
        listOf(
            DeckButton("0", "Spotlight", "🔍", 0),
            DeckButton("pin:com.jetbrains.pycharm", "PyCharm", "", 0, pinnedApp = "com.jetbrains.pycharm"),
        ),
    )
    /** The pinned PyCharm key, not the "← PyCharm" back button. */
    private val pycharmKey = hasText("PyCharm") and !hasContentDescription("Back to PyCharm keys")

    private val pycharm = DeckState(
        status = Status.Connected, app = "com.jetbrains.pycharm", appName = "PyCharm", buttons = buttons, home = home,
        pin = PinState.NotPinned, features = DeckProtocol.REQUIRED_FEATURES,
    )

    @Test fun pinTogglesTheCurrentApp() {
        val calls = mutableListOf<Pair<String, Boolean>>()
        var state by mutableStateOf(pycharm)
        compose.setContent {
            MacDeckTheme {
                DeckScreen(state, { _, _ -> }, {}, onSetPinned = { app, pinned -> calls += app to pinned; state = state.copy(pin = if (pinned) PinState.Pinned else PinState.NotPinned) })
            }
        }

        compose.onNodeWithContentDescription("Pin to Home").performClick()
        compose.onNodeWithContentDescription("Pinned to Home. Unpin").performClick()
        assertEquals(listOf("com.jetbrains.pycharm" to true, "com.jetbrains.pycharm" to false), calls)
    }

    @Test fun appsAlreadyOnHomeShowOnHomeAndCantBePinned() {
        val calls = mutableListOf<Pair<String, Boolean>>()
        compose.setContent {
            MacDeckTheme { DeckScreen(pycharm.copy(pin = PinState.OnHome), { _, _ -> }, {}, onSetPinned = { a, p -> calls += a to p }) }
        }
        compose.onNodeWithContentDescription("Already on Home").assertExists()
        compose.onNodeWithText("On Home").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Pin to Home").assertDoesNotExist()
        assertEquals(emptyList<Pair<String, Boolean>>(), calls)
    }

    @Test fun pinIsHiddenWhenTheAgentCantPin() {
        compose.setContent { MacDeckTheme { DeckScreen(pycharm.copy(pin = PinState.Unsupported), { _, _ -> }, {}) } }
        compose.onNodeWithContentDescription("Home keys").assertExists()
        compose.onNodeWithContentDescription("Pin to Home").assertDoesNotExist()
        compose.onNodeWithText("Pinned").assertDoesNotExist()
    }

    @Test fun pinIsHiddenOnTheHomeKeys() {
        compose.setContent { MacDeckTheme { DeckScreen(pycharm.copy(showingHome = true), { _, _ -> }, {}) } }
        compose.onNodeWithContentDescription("Pin to Home").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back to PyCharm keys").assertExists()
    }

    private fun homeScreen(
        state: DeckState = pycharm.copy(showingHome = true),
        onPress: (String, DeckButton) -> Unit = { _, _ -> },
        onSetPinned: (String, Boolean) -> Unit = { _, _ -> },
        onReorder: (List<String>) -> Unit = {},
    ) = compose.setContent {
        MacDeckTheme { DeckScreen(state, onPress, {}, onSetPinned = onSetPinned, onReorderHome = onReorder) }
    }

    @Test fun longPressEntersEditModeAndTheBadgeRemovesAPinnedApp() {
        val calls = mutableListOf<Pair<String, Boolean>>()
        homeScreen(onSetPinned = { app, pinned -> calls += app to pinned })

        compose.onNode(pycharmKey).performTouchInput { longClick() }
        compose.onNodeWithText("Done").assertExists()
        compose.onNodeWithContentDescription("Remove PyCharm from Home").performClick()
        assertEquals(listOf("com.jetbrains.pycharm" to false), calls)
    }

    @Test fun onlyPinnedAppsCanBeRemoved() {
        homeScreen()
        compose.onNodeWithContentDescription("Edit Home keys").performClick()
        compose.onNodeWithContentDescription("Remove PyCharm from Home").assertExists()
        compose.onNodeWithContentDescription("Remove Spotlight from Home").assertDoesNotExist()
    }

    @Test fun keysDontFireWhileEditingAndDoneLeavesEditMode() {
        val pressed = mutableListOf<String>()
        homeScreen(onPress = { _, b -> pressed += b.label })

        compose.onNodeWithContentDescription("Edit Home keys").performClick()
        compose.onNodeWithText("Spotlight").performClick()
        assertEquals(emptyList<String>(), pressed)

        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Spotlight").performClick()
        assertEquals(listOf("Spotlight"), pressed)
    }

    @Test fun draggingAKeyReordersHome() {
        val orders = mutableListOf<List<String>>()
        homeScreen(onReorder = { orders += it })
        compose.onNodeWithContentDescription("Edit Home keys").performClick()

        // Drag Spotlight (first) onto PyCharm (second), in small steps like a finger.
        val from = compose.onNodeWithText("Spotlight").fetchSemanticsNode().boundsInRoot
        val to = compose.onNode(pycharmKey).fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput {
            down(from.center)
            repeat(10) { moveBy((to.center - from.center) / 10f) }
            up()
        }
        assertEquals(listOf(listOf("pin:com.jetbrains.pycharm", "0")), orders)
    }

    /** Regression: the second drag in one edit session used to move a stale copy of the keys. */
    @Test fun everyDragInOneEditSessionIsSaved() {
        val keys = listOf("A", "B", "C").mapIndexed { i, l -> DeckButton("$i", l, "", 0) }
        var state by mutableStateOf(
            DeckState(status = Status.Connected, app = "x", appName = "Desktop", buttons = keys, isHome = true,
                features = DeckProtocol.REQUIRED_FEATURES)
        )
        val sent = mutableListOf<List<String>>()
        compose.setContent {
            MacDeckTheme {
                DeckScreen(state, { _, _ -> }, {}, onReorderHome = { ids ->
                    sent += ids
                    state = state.copy(buttons = ids.map { id -> keys.first { it.id == id } })  // as the ViewModel does
                })
            }
        }
        compose.onNodeWithContentDescription("Edit Home keys").performClick()

        drag("A", "B")   // A takes B's slot:            B A C
        drag("B", "A")   // back to the original order:  A B C (must still be saved)
        drag("C", "A")   // C takes A's (first) slot:    C A B
        assertEquals(listOf(listOf("1", "0", "2"), listOf("0", "1", "2"), listOf("2", "0", "1")), sent)
        assertEquals(listOf("C", "A", "B"), state.buttons.map { it.label })
    }

    private fun drag(from: String, onto: String) {
        val a = compose.onNodeWithText(from).fetchSemanticsNode().boundsInRoot
        val b = compose.onNodeWithText(onto).fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput {
            down(a.center)
            repeat(10) { moveBy((b.center - a.center) / 10f) }
            up()
        }
        compose.waitForIdle()
    }

    @Test fun anOutdatedAgentGetsANoticeAndNoEditOrPin() {
        homeScreen(pycharm.copy(features = null, pin = PinState.Unsupported))
        compose.onNodeWithText("agent.py on your Mac is out of date", substring = true).assertExists()
        compose.onNodeWithContentDescription("Pin to Home").assertDoesNotExist()
        compose.onNodeWithContentDescription("Home keys").performClick()
    }

    @Test fun usbPairingDialogShowsTheCertificateEnding() {
        val answers = mutableListOf<Boolean>()
        compose.setContent { MacDeckTheme { UsbPairingDialog(TestData.usbPairing) { answers += it } } }
        compose.onNodeWithText("certificate ends in …${TestData.FINGERPRINT.takeLast(6)}", substring = true).assertExists()
        compose.onNodeWithText("Connect").performClick()
        assertEquals(listOf(true), answers)
    }

    @Test fun talkBackCanMoveKeysWithoutDragging() {
        val orders = mutableListOf<List<String>>()
        homeScreen(onReorder = { orders += it })
        compose.onNodeWithContentDescription("Edit Home keys").performClick()

        val actions = compose.onNodeWithText("Spotlight").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { actions.single { it.label == "Move later" }.action() }
        assertEquals(listOf(listOf("pin:com.jetbrains.pycharm", "0")), orders)
    }

    @Test fun anAppWithoutItsOwnKeysShowsAnEditableHome() {
        homeScreen(DeckState(status = Status.Connected, app = "com.unknown", appName = "Desktop", buttons = home.buttons, isHome = true, features = DeckProtocol.REQUIRED_FEATURES))
        compose.onNodeWithContentDescription("Edit Home keys").assertExists()
        compose.onNodeWithContentDescription("Home keys").assertDoesNotExist()
    }

    @Test fun appKeysCantBeEdited() {
        homeScreen(pycharm)
        compose.onNodeWithContentDescription("Edit Home keys").assertDoesNotExist()
        compose.onNodeWithText("Spotlight").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
    }

    @Test fun noHomeButtonWithoutHomeKeys() {
        deck(Status.Connected)
        compose.onNodeWithContentDescription("Home keys").assertDoesNotExist()
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
