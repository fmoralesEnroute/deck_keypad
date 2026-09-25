package com.example.macdeck

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min

enum class Status { Connecting, Connected, Reconnecting, Rejected }

data class DeckState(
    val pairing: Pairing? = null,
    val status: Status = Status.Connecting,
    val app: String = "",
    val appName: String = "",
    val buttons: List<DeckButton> = emptyList(),
    /** The default keys, when the current app has its own; null hides the Home button. */
    val home: DeckProtocol.Layout? = null,
    val showingHome: Boolean = false,
    /** Whether the current app is (or can be) pinned to the Home keys. */
    val pin: PinState = PinState.Unsupported,
    /** [buttons] are the Home keys themselves (the app in front has no keys of its own). */
    val isHome: Boolean = false,
    /** A pairing `agent.py --usb` sent over the cable, waiting for the user to confirm. */
    val usbOffer: Pairing? = null,
)

/** 1s, 2s, 4s, 8s, then 10s between attempts. */
fun defaultBackoff(attempt: Int): Long = min(1000L shl min(attempt, 4), 10_000L)

/**
 * Owns the connection to the paired Mac. Every state change happens on viewModelScope's
 * (main) thread: socket callbacks arrive as a Flow, so there's nothing to synchronize.
 *
 * The connection only runs between [start] and [stop], which the UI ties to ON_START/ON_STOP,
 * so a backgrounded app neither holds a socket open nor retries.
 */
class DeckViewModel(
    private val store: PairingStore,
    private val transport: DeckTransport,
    private val network: NetworkMonitor = NetworkMonitor.None,
    private val backoff: (attempt: Int) -> Long = ::defaultBackoff,
) : ViewModel() {

    private val _state = MutableStateFlow(DeckState(pairing = store.load()))
    val state = _state.asStateFlow()

    private var started = false
    private var session: Job? = null
    private var send: ((String) -> Boolean)? = null

    fun start() {
        started = true
        launchSession()
    }

    fun stop() {
        started = false
        cancelSession()
    }

    /** Saves and connects to [pairing]. Returns false, changing nothing, if it isn't valid. */
    fun pair(pairing: Pairing): Boolean {
        if (!pairing.isValid) return false
        cancelSession()
        store.save(pairing)
        _state.value = DeckState(pairing = pairing)
        launchSession()
        return true
    }

    /** Switch between the current app's keys and the Home (default) keys, locally. */
    fun showHome(show: Boolean) {
        _state.update { it.copy(showingHome = show && it.home != null) }
    }

    /** Pin or unpin [app] on the Home keys. The agent stores it and sends fresh layouts. */
    fun setPinned(app: String, pinned: Boolean) {
        // Nothing to toggle for an app that's already a default Home key, or an agent without pins.
        if (app == state.value.app && state.value.pin !in setOf(PinState.Pinned, PinState.NotPinned)) return
        if (send?.invoke(DeckProtocol.pin(app, pinned)) != true) return
        _state.update { s ->
            s.copy(
                pin = if (s.app == app) (if (pinned) PinState.Pinned else PinState.NotPinned) else s.pin,
                // Drop the key right away rather than waiting for the agent's new layout.
                home = if (pinned) s.home else s.home?.let { h ->
                    h.copy(buttons = h.buttons.filterNot { it.pinnedApp == app })
                },
                buttons = if (pinned || !s.isHome) s.buttons else s.buttons.filterNot { it.pinnedApp == app },
            )
        }
    }

    /** Save the Home keys' new order ([ids] are button ids). The agent stores it for next time. */
    fun reorderHome(ids: List<String>) {
        if (send?.invoke(DeckProtocol.reorder(ids)) != true) return
        fun List<DeckButton>.sorted(): List<DeckButton> {
            val byId = associateBy { it.id }
            return ids.mapNotNull(byId::get) + filterNot { it.id in ids }
        }
        _state.update { s ->
            s.copy(
                home = s.home?.let { it.copy(buttons = it.buttons.sorted()) },
                buttons = if (s.isHome) s.buttons.sorted() else s.buttons,
            )
        }
    }

    /**
     * A pairing handed over by `agent.py --usb` (via adb). Only USB pairings are accepted this way,
     * and only after the user confirms, so another app can't silently re-pair the phone.
     */
    fun offerUsbPairing(pairing: Pairing) {
        if (!pairing.isValid || !pairing.isUsb || pairing == state.value.pairing) return
        _state.update { it.copy(usbOffer = pairing) }
    }

    fun answerUsbOffer(accept: Boolean) {
        val offer = state.value.usbOffer ?: return
        _state.update { it.copy(usbOffer = null) }
        if (accept) pair(offer)
    }

    fun unpair() {
        cancelSession()
        store.clear()
        _state.value = DeckState()
    }

    /**
     * [app] must be the app the button was drawn for, not whatever is current by the time
     * the tap lands: a layout can arrive mid-tap (e.g. clicking a mirrored phone brings its
     * window to the front on the Mac), and the agent resolves the button id within [app].
     */
    fun press(app: String, button: DeckButton) {
        send?.invoke(DeckProtocol.press(button.id, app))
    }

    private fun launchSession() {
        val pairing = state.value.pairing ?: return
        if (!started || session?.isActive == true) return
        session = viewModelScope.launch { runSession(pairing) }
    }

    private fun cancelSession() {
        session?.cancel()
        session = null
        send = null
    }

    private suspend fun runSession(pairing: Pairing) {
        var attempt = 0
        while (true) {
            _state.update { it.copy(status = if (attempt == 0) Status.Connecting else Status.Reconnecting) }

            var closeCode: Int? = null
            transport.session(pairing).collect { event ->
                when (event) {
                    is WsEvent.Open -> {
                        send = event.send
                        event.send(DeckProtocol.hello(pairing.token))
                    }
                    is WsEvent.Message -> DeckProtocol.parseLayout(event.text)?.let { layout ->
                        attempt = 0
                        _state.update {
                            it.copy(
                                status = Status.Connected,
                                app = layout.app,
                                appName = layout.appName,
                                buttons = layout.buttons,
                                home = layout.home,
                                pin = layout.pin,
                                isHome = layout.isHome,
                                // A different app on the Mac brings its keys back; a re-sent
                                // layout for the same app (e.g. after reconnecting) doesn't.
                                showingHome = it.showingHome && layout.app == it.app && layout.home != null,
                            )
                        }
                    }
                    is WsEvent.Closed -> closeCode = event.code
                    is WsEvent.Failure -> Unit
                }
            }
            send = null

            if (closeCode == DeckProtocol.CLOSE_BAD_TOKEN) {
                _state.update { it.copy(status = Status.Rejected, buttons = emptyList()) }
                return
            }

            _state.update { it.copy(status = Status.Reconnecting) }
            // Wait out the backoff, but retry at once if the phone lands on a new network.
            val networkChanged = withTimeoutOrNull(backoff(attempt++)) { network.networkChanges().first() } != null
            if (networkChanged) attempt = 0
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY])
                val transport = OkHttpDeckTransport()
                DeckViewModel(SharedPrefsPairingStore(app), transport, AndroidNetworkMonitor(app))
                    .apply { addCloseable(transport) }
            }
        }
    }
}
