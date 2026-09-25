package com.example.macdeck

import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

// Graphite body, darker key caps, each key lit from below in its own color.
internal val Graphite = Color(0xFF1E2023)
private val KeyCap = Color(0xFF2C2F34)
private val KeyCapPressed = Color(0xFF25282C)
private val Ink = Color(0xFFECEAE6)
private val InkMuted = Color(0xFF9A9FA6)
private val Live = Color(0xFF4CC38A)
private val Waiting = Color(0xFFE0A84A)
private val Problem = Color(0xFFE0674A)

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
            else DeckScreen(state, onPress = vm::press, onUnpair = vm::unpair)
        }
    }
}

@Composable
internal fun MacDeckTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF8FBCE0),
            background = Graphite,
            surface = Graphite,
            onSurface = Ink,
        ),
        content = content,
    )
}

// ------------------------------------------------------------------ deck ---

@Composable
internal fun DeckScreen(
    state: DeckState,
    onPress: (app: String, button: DeckButton) -> Unit,
    onUnpair: () -> Unit,
) {
    var confirmUnpair by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = state.appName.ifBlank { "Waiting for your Mac" },
                    color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                StatusLine(state.status)
            }
            TextButton(onClick = { confirmUnpair = true }) {
                Text("Unpair", color = InkMuted)
            }
        }

        Spacer(Modifier.height(16.dp))

        Crossfade(targetState = state.app to state.buttons, label = "layout") { (app, buttons) ->
            if (buttons.isEmpty() && state.status == Status.Connected) {
                EmptyProfile(app)
            } else {
                val keySize = 104.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.5f)
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = keySize),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(buttons, key = { app + ":" + it.id }) { button ->
                        // `app` is the layout this key belongs to, even if a newer one arrives mid-tap.
                        DeckKey(button, enabled = state.status == Status.Connected) { onPress(app, button) }
                    }
                }
            }
        }
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("Unpair this Mac?") },
            text = { Text("You'll need to scan the pairing code from agent.py again to reconnect.") },
            confirmButton = {
                TextButton(onClick = { confirmUnpair = false; onUnpair() }) { Text("Unpair") }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnpair = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun StatusLine(status: Status) {
    val (color, text) = when (status) {
        Status.Connected -> Live to "Connected"
        Status.Connecting -> Waiting to "Connecting…"
        Status.Reconnecting -> Waiting to "Can't reach your Mac. Retrying… (is agent.py running?)"
        Status.Rejected -> Problem to "Your Mac rejected the pairing. Unpair and scan again."
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(top = 2.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text, color = InkMuted, fontSize = 13.sp)
    }
}

@Composable
private fun EmptyProfile(bundleId: String) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text("No keys for this app yet", color = Ink, fontSize = 18.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            "Add a profile under \"apps\" in profiles.json with the key \"$bundleId\". " +
                "The agent reloads the file as soon as you save it.",
            color = InkMuted, fontSize = 14.sp,
        )
    }
}

@Composable
private fun DeckKey(button: DeckButton, enabled: Boolean, onPress: () -> Unit) {
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val glow = Color(button.color)

    Box(
        Modifier
            .aspectRatio(1f)
            .offset(y = if (pressed) 2.dp else 0.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (pressed) KeyCapPressed else KeyCap)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button) {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onPress()
            }
    ) {
        if (pressed) Box(Modifier.matchParentSize().background(glow.copy(alpha = 0.16f)))

        Column(
            Modifier.fillMaxSize().padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (button.icon.isNotBlank()) {
                Text(
                    button.icon, fontSize = 30.sp,
                    modifier = if (button.label.isNotBlank()) Modifier.clearAndSetSemantics {} else Modifier,
                )
                Spacer(Modifier.height(6.dp))
            }
            Text(
                button.label,
                color = if (enabled) Ink else InkMuted,
                fontSize = 13.sp, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }

        // The backlight strip: brighter and taller while the key is held down.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(if (pressed) 6.dp else 4.dp)
                .background(if (enabled) glow else glow.copy(alpha = 0.35f))
        )
    }
}

// --------------------------------------------------------------- pairing ---

@Composable
internal fun PairScreen(onPair: (Pairing) -> Boolean) {
    // Saveable, so typed details survive dark-mode/font/locale changes and process death.
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("8765") }
    var token by rememberSaveable { mutableStateOf("") }
    var cert by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@rememberLauncherForActivityResult
        val p = parsePairing(contents)
        error = if (p != null && onPair(p)) null
        else "That QR code isn't a MacDeck pairing code. Update agent.py and scan again."
    }

    // Centered and capped on tablets/landscape. Scrolls when the keyboard shrinks the space
    // (the root's safeDrawingPadding already includes IME insets).
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Text("Pair with your Mac", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Run agent.py on your Mac, then scan the code it prints. " +
                    "Your phone and Mac need to be on the same Wi-Fi.",
                color = InkMuted, fontSize = 15.sp,
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    error = null
                    scanner.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("Scan the code shown by agent.py")
                            .setBeepEnabled(false)
                            .setOrientationLocked(false)
                    )
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text("Scan pairing code") }

            Spacer(Modifier.height(28.dp))
            Text("Or enter the details by hand", color = InkMuted, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = host, onValueChange = { host = it.trim() },
                    label = { Text("Mac IP address") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.weight(2f),
                )
                OutlinedTextField(
                    value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text("Port") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            // Secrets: no autocorrect, and a password keyboard so the IME doesn't learn them.
            val secretKeyboard = KeyboardOptions(
                keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Next,
            )
            OutlinedTextField(
                value = token, onValueChange = { token = it.trim() },
                label = { Text("Token") }, singleLine = true,
                keyboardOptions = secretKeyboard,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = cert, onValueChange = { cert = it.trim() },
                label = { Text("Certificate") }, singleLine = true,
                keyboardOptions = secretKeyboard.copy(imeAction = ImeAction.Done),
                supportingText = { Text("The \"certificate\" value agent.py prints") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    val p = Pairing(host, port.toIntOrNull() ?: 0, token, cert)
                    error = if (onPair(p)) null else "Check the IP address, port and certificate."
                },
                enabled = host.isNotBlank() && token.isNotBlank() && cert.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Connect") }

            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    it, color = Problem, fontSize = 14.sp,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                )
            }
        }
    }
}

// --------------------------------------------------------------- previews ---

private val previewButtons = listOf(
    DeckButton("0", "Spotlight", "🔍", 0xFF5B6770.toInt()),
    DeckButton("1", "Mission Control", "🪟", 0xFF3F7CAC.toInt()),
    DeckButton("2", "Screenshot", "📸", 0xFF8E6C8A.toInt()),
    DeckButton("3", "VS Code", "💻", 0xFF2F7FC1.toInt()),
)

@Preview(widthDp = 400, heightDp = 800)
@Composable
private fun DeckScreenPreview() = MacDeckTheme {
    Box(Modifier.background(Graphite)) {
        DeckScreen(
            DeckState(status = Status.Connected, appName = "Desktop", buttons = previewButtons),
            onPress = { _, _ -> }, onUnpair = {},
        )
    }
}

@Preview(widthDp = 400, heightDp = 800)
@Composable
private fun PairScreenPreview() = MacDeckTheme {
    Box(Modifier.background(Graphite)) { PairScreen(onPair = { true }) }
}
