package com.example.macdeck

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color

// Graphite body, darker key caps, each key lit from below in its own color.
internal val Graphite = Color(0xFF1E2023)
internal val KeyCap = Color(0xFF2C2F34)
internal val KeyCapPressed = Color(0xFF25282C)
internal val Ink = Color(0xFFECEAE6)
internal val InkMuted = Color(0xFF9A9FA6)
internal val Live = Color(0xFF4CC38A)
internal val Waiting = Color(0xFFE0A84A)
internal val Problem = Color(0xFFE0674A)

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
