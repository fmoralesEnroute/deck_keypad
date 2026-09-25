package com.example.macdeck

/**
 * What the deck screen shows, worked out from [DeckState]. Plain data, so every rule about
 * which keys and buttons appear is unit-tested without Compose.
 */
data class DeckScreenModel(
    /** The layout the keys belong to (presses carry it), its title, and its keys. */
    val app: String,
    val appName: String,
    val buttons: List<DeckButton>,
    /** The Home keys are on screen: an app's Home view, or an app with no keys of its own. */
    val homeShown: Boolean,
    /** "Home" / "← App": for apps with their own keys. */
    val showHomeToggle: Boolean,
    val showingHome: Boolean,
    /** The Pin button's state, or null when it's hidden. */
    val pinButton: PinState?,
    /** Edit mode (rearranging Home) is available. */
    val canEdit: Boolean,
    /** Home · Pin · Edit · Unpair sit in a row under the title; otherwise Unpair is beside it. */
    val actionRow: Boolean,
    /** The agent is connected but lacks features this app uses: suggest updating it. */
    val agentOutdated: Boolean,
)

fun DeckState.screen(): DeckScreenModel {
    val connected = status == Status.Connected
    val homeView = home?.takeIf { showingHome }
    val homeShown = homeView != null || isHome
    return DeckScreenModel(
        app = homeView?.app ?: app,
        appName = homeView?.appName ?: appName,
        buttons = homeView?.buttons ?: buttons,
        homeShown = homeShown,
        showHomeToggle = home != null,
        showingHome = homeView != null,
        pinButton = pin.takeIf { home != null && homeView == null && it != PinState.Unsupported },
        canEdit = homeShown && connected && features?.contains("reorder") == true,
        actionRow = home != null || isHome,
        agentOutdated = connected && features?.containsAll(DeckProtocol.REQUIRED_FEATURES) != true,
    )
}
