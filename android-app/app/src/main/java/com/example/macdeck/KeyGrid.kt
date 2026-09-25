package com.example.macdeck

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.zIndex

/**
 * The keys. In edit mode (Home keys only) a key can be dragged to a new place; the new order
 * is reported once, when the drag ends. Long-pressing a Home key starts edit mode.
 */
@Composable
internal fun KeyGrid(
    app: String,
    buttons: List<DeckButton>,
    connected: Boolean,
    editing: Boolean,
    onPress: (DeckButton) -> Unit,
    onStartEditing: (() -> Unit)?,
    onRemove: (DeckButton) -> Unit,
    onReorder: (List<DeckButton>) -> Unit,
) {
    // The order being dragged into shape. One state for the whole edit session: the drag handler
    // below is a long-lived coroutine, so it must never hold on to a state that gets replaced.
    var order by remember { mutableStateOf(buttons) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    val gridState = rememberLazyGridState()
    val currentOnReorder by rememberUpdatedState(onReorder)
    val latestButtons by rememberUpdatedState(buttons)
    // New keys from the agent (or our own reorder coming back) replace the order, but never mid-drag.
    LaunchedEffect(buttons) { if (dragging == null) order = buttons }
    val keySize = 104.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.5f)

    fun keyOf(b: DeckButton) = app + ":" + b.id
    fun move(from: Int, to: Int) {
        order = order.toMutableList().apply { add(to, removeAt(from)) }
    }
    fun endDrag() {
        dragging = null
        dragOffset = Offset.Zero
        if (order.map { it.id } != latestButtons.map { it.id }) currentOnReorder(order)
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
