package com.example.macdeck

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {

    private val vm: DeckViewModel by viewModels { DeckViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        // Full screen; swipe from an edge to peek at the system bars.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        if (savedInstanceState == null) handlePairingIntent(intent)
        setContent { MacDeckApp(vm) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePairingIntent(intent)
    }

    /** `agent.py --usb` starts the app with the pairing: am start -a ….PAIR --es pairing '{…}'. */
    private fun handlePairingIntent(intent: Intent?) {
        if (intent?.action != ACTION_PAIR) return
        intent.getStringExtra(EXTRA_PAIRING)?.let(::parsePairing)?.let(vm::offerUsbPairing)
        setIntent(Intent(this, MainActivity::class.java)) // don't re-offer after a config change
    }

    companion object {
        const val ACTION_PAIR = "com.example.macdeck.PAIR"
        const val EXTRA_PAIRING = "pairing"
    }
}
