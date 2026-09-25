package com.example.macdeck

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
@OptIn(ExperimentalLayoutApi::class)
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
    val screen = state.screen()

    // Edit mode: drag Home keys to rearrange them, ✕ to remove pinned apps.
    var editing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(screen.canEdit) { if (!screen.canEdit) editing = false }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
        val unpairButton = @Composable {
            TextButton(onClick = { confirmUnpair = true }) { Text("Unpair", color = InkMuted) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = screen.appName.ifBlank { "Waiting for your Mac" },
                    color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                StatusLine(state.status)
            }
            if (!screen.actionRow) unpairButton()
        }

        // Home · Pin · Unpair on an app's keys; ← App · Edit · Unpair on the Home keys.
        // Wraps onto a second line at large font sizes instead of squeezing.
        if (screen.actionRow) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
            ) {
                if (editing) {
                    Text(
                        "Drag keys to rearrange",
                        color = InkMuted, fontSize = 14.sp,
                        modifier = Modifier.weight(1f).align(Alignment.CenterVertically),
                    )
                    Button(onClick = { editing = false }) { Text("Done") }
                } else {
                    if (screen.showHomeToggle) {
                        HomeToggle(showingHome = screen.showingHome, appName = state.appName, onShowHome = onShowHome)
                    }
                    screen.pinButton?.let { pin ->
                        PinToggle(pin, enabled = connected) { onSetPinned(state.app, pin == PinState.NotPinned) }
                    }
                    if (screen.canEdit) {
                        HeaderPill(onClick = { editing = true }) {
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

        if (screen.agentOutdated) OutdatedAgentNotice()

        Spacer(Modifier.height(12.dp))

        Crossfade(targetState = screen.app to screen.buttons, label = "layout") { (app, buttons) ->
            if (buttons.isEmpty() && connected) {
                EmptyProfile(app)
            } else {
                KeyGrid(
                    app = app,
                    buttons = buttons,
                    connected = connected,
                    editing = editing && screen.canEdit,
                    // `app` is the layout this key belongs to, even if a newer one arrives mid-tap.
                    onPress = { onPress(app, it) },
                    onStartEditing = if (screen.canEdit) ({ editing = true }) else null,
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

/** The agent doesn't announce everything this app uses, so some buttons are hidden. */
@Composable
private fun OutdatedAgentNotice() {
    Text(
        "agent.py on your Mac is out of date, so Home, pinning or rearranging may be missing. " +
            "Update it and restart it.",
        color = Waiting, fontSize = 13.sp,
        modifier = Modifier
            .padding(top = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(KeyCap)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
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

