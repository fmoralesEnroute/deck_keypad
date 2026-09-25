package com.example.macdeck

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun MacDeckApp(vm: DeckViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()

    // Hold the socket only while the app is visible: no background pings or retry loop.
    LifecycleStartEffect(vm) {
        vm.start()
        onStopOrDispose { vm.stop() }
    }

    // A keypad on your desk shouldn't sleep, but only while it's actually usable.
    val view = LocalView.current
    val connected = state.status == Status.Connected
    DisposableEffect(view, connected) {
        view.keepScreenOn = connected
        onDispose { view.keepScreenOn = false }
    }

    MacDeckTheme {
        Box(
            Modifier
                .fillMaxSize()
                .background(Graphite)
                .safeDrawingPadding()
        ) {
            if (state.pairing == null) PairScreen(onPair = vm::pair)
            else DeckScreen(
                state,
                onPress = vm::press,
                onUnpair = vm::unpair,
                onShowHome = vm::showHome,
                onSetPinned = vm::setPinned,
                onReorderHome = vm::reorderHome,
            )
        }

        state.usbOffer?.let { UsbPairingDialog(it, onAnswer = vm::answerUsbOffer) }
    }
}

/**
 * Confirms a pairing received as an intent. Any app on the phone could send one, so the dialog
 * doesn't claim where it came from; it shows the certificate's last characters, which
 * `agent.py --usb` prints too, so they can be compared.
 */
@Composable
internal fun UsbPairingDialog(offer: Pairing, onAnswer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = { Text("Connect over USB?") },
        text = {
            Text(
                "A pairing for a Mac on the USB cable was received. Connect only if you just started " +
                    "agent.py --usb, and its certificate ends in …${offer.certSha256.takeLast(6)} " +
                    "(the agent prints this)."
            )
        },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text("Connect") } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text("Cancel") } },
    )
}
