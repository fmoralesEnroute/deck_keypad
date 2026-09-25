package com.example.macdeck

import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.zIndex
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
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
            else DeckScreen(
                state,
                onPress = vm::press,
                onUnpair = vm::unpair,
                onShowHome = vm::showHome,
                onSetPinned = vm::setPinned,
                onReorderHome = vm::reorderHome,
            )
        }

        state.usbOffer?.let { UsbPairingDialog(onAnswer = vm::answerUsbOffer) }
    }
}

@Composable
private fun UsbPairingDialog(onAnswer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = { Text("Connect over USB?") },
        text = {
            Text(
                "agent.py --usb on your Mac sent a pairing over the USB cable. " +
                    "Connect through the cable instead of Wi-Fi?"
            )
        },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text("Connect") } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text("Cancel") } },
    )
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
    onShowHome: (Boolean) -> Unit = {},
    onSetPinned: (app: String, pinned: Boolean) -> Unit = { _, _ -> },
    onReorderHome: (ids: List<String>) -> Unit = {},
) {
    var confirmUnpair by remember { mutableStateOf(false) }
    val connected = state.status == Status.Connected

    // What's on screen: the Home keys, or the keys of the app in front on the Mac.
    val home = state.home?.takeIf { state.showingHome }
    val app = home?.app ?: state.app
    val appName = home?.appName ?: state.appName
    val buttons = home?.buttons ?: state.buttons
    // The Home keys are showing: the Home view of an app, or an app with no keys of its own.
    val homeShown = home != null || state.isHome

    // Edit mode: drag Home keys to rearrange them, ✕ to remove pinned apps.
    var editing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(homeShown, connected) { if (!homeShown || !connected) editing = false }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
        val unpairButton = @Composable {
            TextButton(onClick = { confirmUnpair = true }) { Text("Unpair", color = InkMuted) }
        }
        val actionRow = state.home != null || state.isHome
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = appName.ifBlank { "Waiting for your Mac" },
                    color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                StatusLine(state.status)
            }
            if (!actionRow) unpairButton()
        }

        // Home · Pin · Unpair on an app's keys; ← App · Edit · Unpair on the Home keys.
        if (actionRow) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
            ) {
                if (editing) {
                    Text(
                        "Drag keys to rearrange",
                        color = InkMuted, fontSize = 14.sp, modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { editing = false }) { Text("Done") }
                } else {
                    if (state.home != null) {
                        HomeToggle(showingHome = home != null, appName = state.appName, onShowHome = onShowHome)
                    }
                    if (state.home != null && home == null) {
                        PinToggle(state.pin, enabled = connected) { onSetPinned(state.app, state.pin == PinState.NotPinned) }
                    }
                    if (homeShown) {
                        HeaderPill(onClick = { editing = true }, enabled = connected) {
                            Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Edit", modifier = Modifier.semantics { contentDescription = "Edit Home keys" })
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    unpairButton()
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Crossfade(targetState = app to buttons, label = "layout") { (app, buttons) ->
            if (buttons.isEmpty() && state.status == Status.Connected) {
                EmptyProfile(app)
            } else {
                KeyGrid(
                    app = app,
                    buttons = buttons,
                    connected = connected,
                    editing = editing && homeShown,
                    // `app` is the layout this key belongs to, even if a newer one arrives mid-tap.
                    onPress = { onPress(app, it) },
                    onStartEditing = if (homeShown) ({ editing = true }) else null,
                    onRemove = { b -> b.pinnedApp?.let { onSetPinned(it, false) } },
                    onReorder = { ordered -> onReorderHome(ordered.map { it.id }) },
                )
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

/**
 * The keys. In edit mode (Home keys only) a key can be dragged to a new place; the new order
 * is reported once, when the drag ends. Long-pressing a Home key starts edit mode.
 */
@Composable
private fun KeyGrid(
    app: String,
    buttons: List<DeckButton>,
    connected: Boolean,
    editing: Boolean,
    onPress: (DeckButton) -> Unit,
    onStartEditing: (() -> Unit)?,
    onRemove: (DeckButton) -> Unit,
    onReorder: (List<DeckButton>) -> Unit,
) {
    // The order being dragged into shape; starts over whenever the agent sends new keys.
    var order by remember(buttons) { mutableStateOf(buttons) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    val gridState = rememberLazyGridState()
    val currentOnReorder by rememberUpdatedState(onReorder)
    val keySize = 104.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.5f)

    fun keyOf(b: DeckButton) = app + ":" + b.id
    fun move(from: Int, to: Int) {
        order = order.toMutableList().apply { add(to, removeAt(from)) }
    }
    fun endDrag() {
        dragging = null
        dragOffset = Offset.Zero
        if (order.map { it.id } != buttons.map { it.id }) currentOnReorder(order)
    }

    val gridModifier = if (!editing) Modifier.fillMaxSize() else Modifier.fillMaxSize().pointerInput(app) {
        detectDragGestures(
            onDragStart = { pos ->
                dragging = gridState.layoutInfo.visibleItemsInfo
                    .firstOrNull { IntRect(it.offset, it.size).contains(pos.round()) }
                    ?.key as? String
                dragOffset = Offset.Zero
            },
            onDrag = { change, amount ->
                val draggedKey = dragging ?: return@detectDragGestures
                change.consume()
                dragOffset += amount
                val items = gridState.layoutInfo.visibleItemsInfo
                val dragged = items.firstOrNull { it.key == draggedKey } ?: return@detectDragGestures
                val center = dragged.offset.toOffset() + dragOffset +
                    Offset(dragged.size.width / 2f, dragged.size.height / 2f)
                val target = items.firstOrNull {
                    it.key != draggedKey && IntRect(it.offset, it.size).contains(center.round())
                } ?: return@detectDragGestures
                val from = order.indexOfFirst { keyOf(it) == draggedKey }
                val to = order.indexOfFirst { keyOf(it) == target.key }
                if (from < 0 || to < 0) return@detectDragGestures
                move(from, to)
                // The key now lays out in the target's slot; keep it under the finger.
                dragOffset += (dragged.offset - target.offset).toOffset()
            },
            onDragEnd = ::endDrag,
            onDragCancel = ::endDrag,
        )
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = keySize),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        // While editing, vertical drags move keys instead of scrolling the grid.
        userScrollEnabled = !editing,
        modifier = gridModifier,
    ) {
        itemsIndexed(order, key = { _, b -> keyOf(b) }) { index, button ->
            val isDragged = keyOf(button) == dragging
            // TalkBack can't drag: offer "Move earlier/later" actions in edit mode instead.
            val moveActions = if (!editing) emptyList() else buildList {
                if (index > 0) add(CustomAccessibilityAction("Move earlier") {
                    move(index, index - 1); currentOnReorder(order); true
                })
                if (index < order.lastIndex) add(CustomAccessibilityAction("Move later") {
                    move(index, index + 1); currentOnReorder(order); true
                })
            }
            DeckKey(
                button,
                enabled = connected,
                editing = editing,
                onLongPress = onStartEditing,
                onRemove = if (editing && button.pinnedApp != null) ({ onRemove(button) }) else null,
                accessibilityActions = moveActions,
                modifier = if (isDragged) {
                    Modifier.zIndex(1f).graphicsLayer {
                        translationX = dragOffset.x
                        translationY = dragOffset.y
                        scaleX = 1.06f
                        scaleY = 1.06f
                    }
                } else {
                    Modifier.animateItem()
                },
                onPress = { onPress(button) },
            )
        }
    }
}

@Composable
private fun HeaderPill(onClick: () -> Unit, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 14.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = KeyCap, contentColor = Ink,
            disabledContainerColor = KeyCap, disabledContentColor = InkMuted,
        ),
        modifier = Modifier.widthIn(max = 180.dp),
        content = content,
    )
}

/**
 * "+ Pin" adds the current app to the Home keys and "✓ Pinned" removes it again.
 * "✓ On Home" (not tappable) when a default Home key already opens the app.
 * Hidden entirely when the agent doesn't support pinning.
 */
@Composable
private fun PinToggle(pin: PinState, enabled: Boolean, onToggle: () -> Unit) {
    val (label, description) = when (pin) {
        PinState.Unsupported -> return
        PinState.NotPinned -> "Pin" to "Pin to Home"
        PinState.Pinned -> "Pinned" to "Pinned to Home. Unpin"
        PinState.OnHome -> "On Home" to "Already on Home"
    }
    HeaderPill(onClick = onToggle, enabled = enabled && pin != PinState.OnHome) {
        Icon(
            if (pin == PinState.NotPinned) Icons.Filled.Add else Icons.Filled.Check,
            contentDescription = null, modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(label, modifier = Modifier.semantics { contentDescription = description })
    }
}

/** "Home" while an app's keys are shown; "‹ App" to go back while the Home keys are. */
@Composable
private fun HomeToggle(showingHome: Boolean, appName: String, onShowHome: (Boolean) -> Unit) {
    HeaderPill(onClick = { onShowHome(!showingHome) }) {
        Icon(
            if (showingHome) Icons.AutoMirrored.Filled.ArrowBack else Icons.Filled.Home,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (showingHome) appName.ifBlank { "Back" } else "Home",
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            // TalkBack: say where the button goes, not just the app's name.
            modifier = Modifier.semantics {
                contentDescription = if (showingHome) "Back to ${appName.ifBlank { "app" }} keys" else "Home keys"
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
@OptIn(ExperimentalFoundationApi::class)
private fun DeckKey(
    button: DeckButton,
    enabled: Boolean,
    editing: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    accessibilityActions: List<CustomAccessibilityAction> = emptyList(),
    modifier: Modifier = Modifier,
    onPress: () -> Unit,
) {
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val glow = Color(button.color)
    val image = remember(button.image) { button.image?.let(::decodeIcon) }

    Box(
        modifier
            .aspectRatio(1f)
            .offset(y = if (pressed) 2.dp else 0.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (pressed) KeyCapPressed else KeyCap)
            .border(1.dp, if (editing) InkMuted.copy(alpha = 0.5f) else Color.Transparent, RoundedCornerShape(16.dp))
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                // In edit mode the grid owns touches (dragging), so keys don't fire.
                enabled = enabled && !editing,
                role = Role.Button,
                onLongClickLabel = onLongPress?.let { "Edit Home keys" },
                onLongClick = onLongPress?.let {
                    {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        it()
                    }
                },
            ) {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onPress()
            }
            .semantics { if (accessibilityActions.isNotEmpty()) customActions = accessibilityActions }
    ) {
        if (pressed) Box(Modifier.matchParentSize().background(glow.copy(alpha = 0.16f)))

        Column(
            Modifier.fillMaxSize().padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (image != null) {
                Image(image, contentDescription = null, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(6.dp))
            } else if (button.icon.isNotBlank() || button.pinnedApp != null) {
                Text(
                    button.icon.ifBlank { "📌" }, fontSize = 30.sp,
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

        // Edit mode: ✕ removes a pinned app from Home. 44dp to touch, 26dp to see.
        if (onRemove != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(44.dp)
                    .clickable(role = Role.Button, onClickLabel = "Remove") { onRemove() }
                    .semantics { contentDescription = "Remove ${button.label} from Home" },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(26.dp).clip(CircleShape).background(Problem),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/** Base64 PNG (an app icon from the agent) to a bitmap; null if it doesn't decode. */
private fun decodeIcon(base64: String): ImageBitmap? = runCatching {
    val bytes = java.util.Base64.getDecoder().decode(base64)
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}.getOrNull()

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
