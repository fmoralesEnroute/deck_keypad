package com.example.macdeck

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

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


@Preview(widthDp = 400, heightDp = 800)
@Composable
private fun PairScreenPreview() = MacDeckTheme {
    Box(Modifier.background(Graphite)) { PairScreen(onPair = { true }) }
}
