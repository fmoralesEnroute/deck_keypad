package com.example.macdeck

import android.content.Context

interface PairingStore {
    fun load(): Pairing?
    fun save(pairing: Pairing)
    fun clear()
}

/**
 * App-private SharedPreferences. They're excluded from cloud backup and device transfer
 * (see res/xml/data_extraction_rules.xml), so the token stays on this phone.
 */
class SharedPrefsPairingStore(context: Context) : PairingStore {

    private val prefs = context.getSharedPreferences("macdeck", Context.MODE_PRIVATE)

    override fun load(): Pairing? {
        val host = prefs.getString(KEY_HOST, null) ?: return null
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        val cert = prefs.getString(KEY_CERT, null)
        val pairing = cert?.let { Pairing(host, prefs.getInt(KEY_PORT, 8765), token, it) }
        // Pairings from before TLS have no certificate pin; drop them so the user re-scans.
        if (pairing == null || !pairing.isValid) clear()
        return pairing?.takeIf { it.isValid }
    }

    override fun save(pairing: Pairing) {
        prefs.edit()
            .putString(KEY_HOST, pairing.host)
            .putInt(KEY_PORT, pairing.port)
            .putString(KEY_TOKEN, pairing.token)
            .putString(KEY_CERT, pairing.certSha256)
            .apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_TOKEN = "token"
        const val KEY_CERT = "cert_sha256"
    }
}
