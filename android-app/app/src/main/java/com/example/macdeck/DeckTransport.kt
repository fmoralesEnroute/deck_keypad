package com.example.macdeck

import android.annotation.SuppressLint
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

sealed interface WsEvent {
    data class Open(val send: (String) -> Boolean) : WsEvent
    data class Message(val text: String) : WsEvent
    data class Closed(val code: Int) : WsEvent
    data class Failure(val error: Throwable) : WsEvent
}

/** One WebSocket connection per collection: collecting opens it, cancelling closes it. */
fun interface DeckTransport {
    fun session(pairing: Pairing): Flow<WsEvent>
}

class OkHttpDeckTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .build(),
) : DeckTransport, AutoCloseable {

    override fun session(pairing: Pairing): Flow<WsEvent> = callbackFlow {
        val url = pairing.url()
        val pin = pairing.certSha256Bytes()
        if (url == null || pin == null) {
            trySend(WsEvent.Failure(IllegalArgumentException("Invalid pairing")))
            close()
            return@callbackFlow
        }
        val ws = client.pinnedTo(pin).newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    trySend(WsEvent.Open(webSocket::send))
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    trySend(WsEvent.Message(text))
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    trySend(WsEvent.Closed(code))
                    close()
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    trySend(WsEvent.Failure(t))
                    close()
                }
            },
        )
        awaitClose { ws.close(1000, null) }
    }.buffer(Channel.UNLIMITED)

    override fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}

/**
 * A client that trusts exactly one certificate: the agent's self-signed one, whose SHA-256 came
 * from the QR code. Identity is proven by that pin, not by a CA or by the hostname (a LAN IP).
 */
fun OkHttpClient.pinnedTo(certSha256: ByteArray): OkHttpClient {
    val trustManager = PinnedCertTrustManager(certSha256)
    val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
    return newBuilder()
        .sslSocketFactory(ssl.socketFactory, trustManager)
        // Safe only because PinnedCertTrustManager already rejected every certificate but the paired one.
        .hostnameVerifier { _, _ -> true }
        .build()
}

// Deliberate: it rejects everything except the one certificate whose hash came from the QR code,
// which is stricter than CA validation. Covered by OkHttpDeckTransportTest.
@SuppressLint("CustomX509TrustManager")
internal class PinnedCertTrustManager(private val expectedSha256: ByteArray) : X509ExtendedTrustManager() {

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("Empty certificate chain")
        val actual = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
        if (!MessageDigest.isEqual(actual, expectedSha256)) {
            throw CertificateException("Certificate does not match the paired Mac")
        }
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
        checkServerTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
        checkServerTrusted(chain, authType)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        throw CertificateException("Client certificates are not used")

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
        checkClientTrusted(chain, authType)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
        checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
