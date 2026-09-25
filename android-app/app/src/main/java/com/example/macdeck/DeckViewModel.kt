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
