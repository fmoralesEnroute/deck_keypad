package com.example.macdeck

import org.json.JSONArray
import org.json.JSONObject

data class DeckButton(val id: String, val label: String, val icon: String, val color: Int)

/** Wire format shared with macos-agent/agent.py. Pure Kotlin, so it runs in JVM unit tests. */
object DeckProtocol {

    /** Close code the agent uses when the token doesn't match. */
    const val CLOSE_BAD_TOKEN = 4003

    const val DEFAULT_COLOR = 0xFF5B6770.toInt()

    data class Layout(val app: String, val appName: String, val buttons: List<DeckButton>)

    fun hello(token: String): String = JSONObject().put("type", "hello").put("token", token).toString()

    fun press(id: String, app: String): String =
        JSONObject().put("type", "press").put("id", id).put("app", app).toString()

    /** Returns null for anything that isn't a layout. Malformed buttons are skipped, not fatal. */
    fun parseLayout(text: String): Layout? {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (msg.optStringOrNull("type") != "layout") return null

        val arr = msg.optJSONArray("buttons") ?: JSONArray()
        val buttons = (0 until arr.length()).mapNotNull { i ->
            val b = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = b.optStringOrNull("id") ?: return@mapNotNull null
            DeckButton(
                id = id,
                label = b.optStringOrNull("label").orEmpty(),
                icon = b.optStringOrNull("icon").orEmpty(),
                color = parseColor(b.optStringOrNull("color")) ?: DEFAULT_COLOR,
            )
        }
        return Layout(
            app = msg.optStringOrNull("app").orEmpty(),
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
