package com.example.macdeck

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeckViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val store = FakeStore(TestData.pairing)
    private val transport = FakeTransport()
    private val network = FakeNetwork()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(backoff: Long = 1_000) = DeckViewModel(store, transport, network) { backoff }

    @Test fun `connects on start, says hello and shows the layout`() = runTest {
        transport.next { emit(open()); emit(WsEvent.Message(TestData.layoutJson)); awaitCancellation() }
        val vm = vm()
        vm.start()
        runCurrent()

        assertEquals(1, transport.sent.size)
        assertEquals(JSONObject(DeckProtocol.hello("tok")).toMap(), JSONObject(transport.sent.single()).toMap())
        with(vm.state.value) {
            assertEquals(Status.Connected, status)
            assertEquals("X", appName)
            assertEquals(listOf("Go"), buttons.map { it.label })
        }
    }

    @Test fun `does not connect before start`() = runTest {
        vm()
        advanceUntilIdle()
        assertEquals(0, transport.opened)
    }

    @Test fun `press sends the app the button was drawn for, not the newest layout`() = runTest {
        val newerLayout = """{"type":"layout","app":"com.google.android.studio","buttons":[{"id":"0"}]}"""
        transport.next {
            emit(open())
            emit(WsEvent.Message(TestData.layoutJson))
            emit(WsEvent.Message(newerLayout))
            awaitCancellation()
        }
        val vm = vm()
        vm.start()
        runCurrent()
        assertEquals("com.google.android.studio", vm.state.value.app)

        // The UI passes the app of the grid the tap started on.
        vm.press("com.x", DeckButton("0", "Go", "", 0))
        assertEquals(JSONObject(DeckProtocol.press("0", "com.x")).toMap(), JSONObject(transport.sent.last()).toMap())
    }

    @Test fun `home switches to the default keys and back`() = runTest {
        transport.next { emit(open()); emit(WsEvent.Message(TestData.layoutWithHome("com.spotify.client"))); awaitCancellation() }
        val vm = vm()
        vm.start()
        runCurrent()

        vm.showHome(true)
        assertTrue(vm.state.value.showingHome)
        assertEquals("Desktop", vm.state.value.home!!.appName)
        vm.showHome(false)
        assertFalse(vm.state.value.showingHome)
    }

    @Test fun `home can't be shown for an app without its own keys`() = runTest {
        transport.next { emit(open()); emit(WsEvent.Message(TestData.layoutJson)); awaitCancellation() }
        val vm = vm()
        vm.start()
        runCurrent()

        vm.showHome(true)
        assertFalse(vm.state.value.showingHome)
    }

    @Test fun `a different app on the Mac leaves home, the same app re-sent doesn't`() = runTest {
        val layouts = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        transport.next { emit(open()); for (l in layouts) emit(WsEvent.Message(l)) }
        val vm = vm()
        vm.start()
        layouts.send(TestData.layoutWithHome("com.spotify.client"))
        runCurrent()
        vm.showHome(true)

        layouts.send(TestData.layoutWithHome("com.spotify.client"))
        runCurrent()
        assertTrue(vm.state.value.showingHome)

        layouts.send(TestData.layoutWithHome("com.jetbrains.pycharm"))
        runCurrent()
        assertFalse(vm.state.value.showingHome)
        layouts.close()
    }

    @Test fun `pin sends to the agent and updates the header right away`() = runTest {
        transport.next { emit(open()); emit(WsEvent.Message(TestData.layoutWithHome("com.jetbrains.pycharm"))); awaitCancellation() }
        val vm = vm()
        vm.start()
        runCurrent()
        assertEquals(PinState.NotPinned, vm.state.value.pin)

        vm.setPinned("com.jetbrains.pycharm", true)
        assertEquals(PinState.Pinned, vm.state.value.pin)
        assertEquals(JSONObject(DeckProtocol.pin("com.jetbrains.pycharm", true)).toMap(), JSONObject(transport.sent.last()).toMap())
    }

    @Test fun `unpinning removes the key from Home right away`() = runTest {
        val withPin = """{"type":"layout","app":"com.spotify.client","pinned":false,"buttons":[{"id":"0"}],
            "home":{"buttons":[{"id":"0","label":"Spotlight"},{"id":"pin:com.jetbrains.pycharm","label":"PyCharm","pinned":"com.jetbrains.pycharm"}]}}"""
        transport.next { emit(open()); emit(WsEvent.Message(withPin)); awaitCancellation() }
        val vm = vm()
        vm.start()
        runCurrent()

        vm.setPinned("com.jetbrains.pycharm", false)
        assertEquals(listOf("Spotlight"), vm.state.value.home!!.buttons.map { it.label })
        assertEquals(JSONObject(DeckProtocol.pin("com.jetbrains.pycharm", false)).toMap(), JSONObject(transport.sent.last()).toMap())
    }

    @Test fun `reordering Home is sent and applied right away`() = runTest {
        val layout = """{"type":"layout","app":"com.unknown","isHome":true,"buttons":[{"id":"0","label":"A"},{"id":"1","label":"B"},{"id":"pin:x","label":"X","pinned":"x"}]}"""
        transport.next { emit(open()); emit(WsEvent.Message(layout)); awaitCancellation() }
        val vm = vm()
        vm.start()
        runCurrent()
        assertTrue(vm.state.value.isHome)

        vm.reorderHome(listOf("pin:x", "1", "0"))
        assertEquals(listOf("X", "B", "A"), vm.state.value.buttons.map { it.label })
        assertEquals(JSONObject(DeckProtocol.reorder(listOf("pin:x", "1", "0"))).toMap(), JSONObject(transport.sent.last()).toMap())

        vm.setPinned("x", false) // removing a pinned app from an editable Home
        assertEquals(listOf("B", "A"), vm.state.value.buttons.map { it.label })
    }

    @Test fun `pin does nothing while disconnected`() = runTest {
        val vm = vm()
        vm.setPinned("com.x", true)
        assertEquals(PinState.Unsupported, vm.state.value.pin)
    }

    @Test fun `an app already on Home, or an agent without pins, can't be pinned`() = runTest {
        val onHome = """{"type":"layout","app":"com.spotify.client","pinned":true,"onHome":true,"buttons":[{"id":"0"}],"home":{"buttons":[]}}"""
        val oldAgent = """{"type":"layout","app":"com.jetbrains.pycharm","buttons":[{"id":"0"}],"home":{"buttons":[]}}"""
        for ((layout, expected) in listOf(onHome to PinState.OnHome, oldAgent to PinState.Unsupported)) {
            transport.next { emit(open()); emit(WsEvent.Message(layout)); awaitCancellation() }
            val vm = vm()
            vm.start()
            runCurrent()
            val sentBefore = transport.sent.size

            vm.setPinned(vm.state.value.app, true)
            assertEquals(expected, vm.state.value.pin)
            assertEquals(sentBefore, transport.sent.size)
            vm.stop()
        }
    }

    @Test fun `a USB pairing from the agent needs confirmation`() = runTest {
        store.pairing = TestData.pairing.copy(host = "10.4.0.32")
        transport.next { emit(open()); awaitCancellation() }
        transport.next { emit(open()); awaitCancellation() }
        val vm = vm()
        vm.start()
        runCurrent()

        vm.offerUsbPairing(TestData.usbPairing)
        assertEquals(TestData.usbPairing, vm.state.value.usbOffer)
        assertEquals("10.4.0.32", store.pairing!!.host) // nothing changes until the user says yes

        vm.answerUsbOffer(accept = true)
        runCurrent()
        assertNull(vm.state.value.usbOffer)
        assertEquals(TestData.usbPairing, store.pairing)
        assertEquals(2, transport.opened)
    }

    @Test fun `declining a USB pairing keeps the current one`() = runTest {
        val vm = vm()
        vm.offerUsbPairing(TestData.usbPairing)
        vm.answerUsbOffer(accept = false)
        assertNull(vm.state.value.usbOffer)
        assertEquals(TestData.pairing, store.pairing)
    }

    @Test fun `only USB pairings can be offered, and not the one already in use`() = runTest {
        val vm = vm()
        vm.offerUsbPairing(TestData.pairing.copy(host = "203.0.113.9")) // a network host: ignored
        assertNull(vm.state.value.usbOffer)

        store.pairing = TestData.usbPairing
        val paired = vm()
        paired.offerUsbPairing(TestData.usbPairing)
        assertNull(paired.state.value.usbOffer)
    }

    @Test fun `bad token moves to Rejected and stops retrying`() = runTest {
        transport.next { emit(open()); emit(WsEvent.Closed(DeckProtocol.CLOSE_BAD_TOKEN)) }
        val vm = vm()
        vm.start()
        advanceUntilIdle()

        assertEquals(Status.Rejected, vm.state.value.status)
        assertEquals(1, transport.opened)
    }

    @Test fun `failures back off and reconnect`() = runTest {
        transport.next { emit(WsEvent.Failure(RuntimeException("down"))) }
        transport.next { emit(open()); emit(WsEvent.Message(TestData.layoutJson)); awaitCancellation() }
        val vm = vm(backoff = 5_000)

        vm.state.test {
            assertEquals(Status.Connecting, awaitItem().status)
            vm.start()
            assertEquals(Status.Reconnecting, awaitItem().status)
            advanceTimeBy(4_999)
            assertEquals(1, transport.opened)
            advanceTimeBy(2)
            assertEquals(Status.Connected, awaitItem().status)
            assertEquals(2, transport.opened)
        }
    }

    @Test fun `a new network skips the backoff`() = runTest {
        transport.next { emit(WsEvent.Failure(RuntimeException("down"))) }
        transport.next { awaitCancellation() }
        val vm = vm(backoff = 60_000)
        vm.start()
        runCurrent()
        assertEquals(1, transport.opened)

        network.changes.emit(Unit)
        runCurrent()
        assertEquals(2, transport.opened)
    }

    @Test fun `stop closes the socket and nothing retries while stopped`() = runTest {
        transport.next { emit(open()); awaitCancellation() }
        val vm = vm()
        vm.start()
        runCurrent()
        vm.stop()
        advanceUntilIdle()

        assertEquals(1, transport.cancelled)
        assertEquals(1, transport.opened)
    }

    @Test fun `invalid pairing is refused and not saved`() = runTest {
        store.pairing = null
        val vm = vm()
        vm.start()

        assertFalse(vm.pair(TestData.pairing.copy(host = "10.0.0.1:8765")))
        assertNull(store.pairing)
        assertNull(vm.state.value.pairing)
        advanceUntilIdle()
        assertEquals(0, transport.opened)
    }

    @Test fun `pair saves and connects`() = runTest {
        store.pairing = null
        transport.next { emit(open()); awaitCancellation() }
        val vm = vm()
        vm.start()

        assertTrue(vm.pair(TestData.pairing))
        runCurrent()
        assertEquals(TestData.pairing, store.pairing)
        assertEquals(1, transport.opened)
    }

    @Test fun `unpair disconnects and forgets the Mac`() = runTest {
        transport.next { emit(open()); awaitCancellation() }
        val vm = vm()
        vm.start()
        runCurrent()
        vm.unpair()
        advanceUntilIdle()

        assertNull(store.pairing)
        assertEquals(DeckState(), vm.state.value)
        assertEquals(1, transport.cancelled)
    }

    private fun open() = WsEvent.Open { transport.sent += it; true }
}

private class FakeStore(var pairing: Pairing?) : PairingStore {
    override fun load() = pairing
    override fun save(pairing: Pairing) { this.pairing = pairing }
    override fun clear() { pairing = null }
}

private class FakeTransport : DeckTransport {
    private val sessions = ArrayDeque<suspend kotlinx.coroutines.flow.FlowCollector<WsEvent>.() -> Unit>()
    val sent = mutableListOf<String>()
    var opened = 0
    var cancelled = 0

    fun next(block: suspend kotlinx.coroutines.flow.FlowCollector<WsEvent>.() -> Unit) = sessions.addLast(block)

    override fun session(pairing: Pairing): Flow<WsEvent> = flow {
        opened++
        val block = sessions.removeFirstOrNull() ?: { awaitCancellation() }
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            cancelled++
            throw e
        }
    }
}

private class FakeNetwork : NetworkMonitor {
    val changes = MutableSharedFlow<Unit>()
    override fun networkChanges() = changes
}
