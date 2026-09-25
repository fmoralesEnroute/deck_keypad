package com.example.macdeck

import okhttp3.HttpUrl
import org.json.JSONObject
import java.util.Base64

/**
 * Everything needed to reach one Mac: where it is, the shared token, and the SHA-256 of the
 * agent's self-signed TLS certificate (base64url), which the app pins instead of trusting a CA.
 */
data class Pairing(val host: String, val port: Int, val token: String, val certSha256: String) {

    /** Null when host/port can't form a URL. OkHttp expresses wss:// as https:// on HttpUrl. */
    fun url(): HttpUrl? = runCatching {
        HttpUrl.Builder()
            .scheme("https")
            .host(host)   // accepts IPv6 without brackets, rejects "host:port"
            .port(port)   // rejects 0 and > 65535
            .build()
    }.getOrNull()

    val isValid: Boolean
        get() = token.isNotBlank() && url() != null && certSha256Bytes() != null

    fun certSha256Bytes(): ByteArray? =
        runCatching { Base64.getUrlDecoder().decode(certSha256.trim()) }.getOrNull()?.takeIf { it.size == 32 }
}

/**
 * Parses the JSON the agent puts in its QR code:
 * {"h":"192.168.1.20","p":8765,"t":"…","f":"<base64url sha-256 of the certificate>"}
 * Returns null for anything that isn't a complete, connectable pairing.
 */
fun parsePairing(text: String): Pairing? = runCatching {
    val o = JSONObject(text)
    Pairing(o.getString("h"), o.getInt("p"), o.getString("t"), o.getString("f"))
}.getOrNull()?.takeIf { it.isValid }
