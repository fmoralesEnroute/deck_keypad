package com.example.macdeck

import org.json.JSONArray
import org.json.JSONObject

/** Whether the app in front can be pinned to the Home keys, and whether it already is. */
enum class PinState {
    /** The agent is too old to pin apps: hide the Pin button. */
    Unsupported,
    NotPinned,
    Pinned,
    /** A default Home key already opens this app (e.g. Spotify), so there's nothing to pin. */
    OnHome,
}

data class DeckButton(
    val id: String,
    val label: String,
    val icon: String,
    val color: Int,
    /** Base64 PNG, e.g. the app's real macOS icon; shown instead of [icon]. */
    val image: String? = null,
    /** Set on Home keys for pinned apps: the bundle ID, so the key can be unpinned. */
    val pinnedApp: String? = null,
)

/** Wire format shared with macos-agent/agent.py. Pure Kotlin, so it runs in JVM unit tests. */
object DeckProtocol {

    /** Close code the agent uses when the token doesn't match. */
    const val CLOSE_BAD_TOKEN = 4003

    const val DEFAULT_COLOR = 0xFF5B6770.toInt()

    /** The app id presses from the Home keys carry; the agent maps it to its "default" profile. */
    const val HOME_APP = "macdeck.home"

    /** [home] is the default keys, sent alongside an app that has its own profile. */
    data class Layout(
        val app: String,
        val appName: String,
        val buttons: List<DeckButton>,
        val home: Layout? = null,
        val pin: PinState = PinState.Unsupported,
        /** These are the Home keys themselves (the app in front has no keys of its own). */
        val isHome: Boolean = false,
    )

    fun hello(token: String): String = JSONObject().put("type", "hello").put("token", token).toString()

    fun press(id: String, app: String): String =
        JSONObject().put("type", "press").put("id", id).put("app", app).toString()

    /** The Home keys' new order, by button id. */
    fun reorder(ids: List<String>): String =
        JSONObject().put("type", "reorder").put("ids", JSONArray(ids)).toString()

    fun pin(app: String, pinned: Boolean): String =
        JSONObject().put("type", if (pinned) "pin" else "unpin").put("app", app).toString()

    /** Returns null for anything that isn't a layout. Malformed buttons are skipped, not fatal. */
    fun parseLayout(text: String): Layout? {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (msg.optStringOrNull("type") != "layout") return null
        val home = msg.optJSONObject("home")?.let { parseKeys(it, defaultApp = HOME_APP) }
        val pin = when {
            msg.optBoolean("onHome") -> PinState.OnHome
            !msg.has("pinned") -> PinState.Unsupported
            msg.optBoolean("pinned") -> PinState.Pinned
            else -> PinState.NotPinned
        }
        return parseKeys(msg, defaultApp = "").copy(home = home, pin = pin, isHome = msg.optBoolean("isHome"))
    }

    private fun parseKeys(msg: JSONObject, defaultApp: String): Layout {
        val arr = msg.optJSONArray("buttons") ?: JSONArray()
        val buttons = (0 until arr.length()).mapNotNull { i ->
            val b = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = b.optStringOrNull("id") ?: return@mapNotNull null
            DeckButton(
                id = id,
                label = b.optStringOrNull("label").orEmpty(),
                // "app:…"/"logo:…" are image references an older agent may pass through: never text.
                icon = b.optStringOrNull("icon").orEmpty().takeUnless { it.startsWith("app:") || it.startsWith("logo:") }.orEmpty(),
                color = parseColor(b.optStringOrNull("color")) ?: DEFAULT_COLOR,
                image = b.optStringOrNull("image")?.takeIf { it.isNotBlank() },
                pinnedApp = b.optStringOrNull("pinned")?.takeIf { it.isNotBlank() },
            )
        }
        return Layout(
            app = msg.optStringOrNull("app") ?: defaultApp,
            appName = msg.optStringOrNull("appName").orEmpty(),
            buttons = buttons,
        )
    }

    /** "#RRGGBB" or "#AARRGGBB" as ARGB, else null. */
    fun parseColor(s: String?): Int? {
        val t = s?.trim() ?: return null
        if (!t.startsWith("#")) return null
        val hex = t.substring(1)
        if (hex.any { it !in "0123456789abcdefABCDEF" }) return null
        val v = hex.toLongOrNull(16) ?: return null
        return when (hex.length) {
            6 -> (0xFF000000L or v).toInt()
            8 -> v.toInt()
            else -> null
        }
    }

    // Android's optString() returns the literal "null" for JSON null, and coerces numbers.
    private fun JSONObject.optStringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else opt(key)?.toString()
}
