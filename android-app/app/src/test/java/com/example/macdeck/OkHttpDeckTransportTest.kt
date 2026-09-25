package com.example.macdeck

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.time.Duration.Companion.seconds

/** Real OkHttp client against a real TLS WebSocket server with a self-signed certificate. */
class OkHttpDeckTransportTest {

    private val cert = HeldCertificate.Builder().commonName("MacDeck").build()
    private val server = MockWebServer()
    private val transport = OkHttpDeckTransport()
    private val received = LinkedBlockingQueue<String>()

    @Before fun setUp() {
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false)
        server.start()
    }

    @After fun tearDown() {
        transport.close()
        server.shutdown()
    }

    private fun pairing(fingerprint: ByteArray = MessageDigest.getInstance("SHA-256").digest(cert.certificate.encoded)) =
        Pairing(server.hostName, server.port, "tok", Base64.getUrlEncoder().withoutPadding().encodeToString(fingerprint))

    private fun agent(onHello: (WebSocket) -> Unit) = server.enqueue(
        MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                received += text
                onHello(webSocket)
            }
            override fun onOpen(webSocket: WebSocket, response: Response) = Unit
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
        })
    )

    @Test fun `pinned certificate connects over TLS and exchanges messages`() = runTest {
        agent { it.send(TestData.layoutJson) }

        transport.session(pairing()).test(timeout = 5.seconds) {
            val open = awaitItem() as WsEvent.Open
            open.send(DeckProtocol.hello("tok"))
            assertEquals(WsEvent.Message(TestData.layoutJson), awaitItem())
            assertEquals(DeckProtocol.hello("tok"), received.poll(5, TimeUnit.SECONDS))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `bad token close code reaches the collector`() = runTest {
        agent { it.close(DeckProtocol.CLOSE_BAD_TOKEN, "bad token") }

        transport.session(pairing()).test(timeout = 5.seconds) {
            (awaitItem() as WsEvent.Open).send(DeckProtocol.hello("wrong"))
            assertEquals(WsEvent.Closed(DeckProtocol.CLOSE_BAD_TOKEN), awaitItem())
            awaitComplete()
        }
    }

    @Test fun `a certificate that doesn't match the pin is refused`() = runTest {
        agent { }

        transport.session(pairing(fingerprint = ByteArray(32))).test(timeout = 5.seconds) {
            val failure = awaitItem() as WsEvent.Failure
            assertTrue("was ${failure.error}", failure.error is SSLException)
            awaitComplete()
        }
    }

    @Test fun `an invalid pairing fails without touching the network`() = runTest {
        transport.session(TestData.pairing.copy(port = 0)).test {
            assertTrue(awaitItem() is WsEvent.Failure)
            awaitComplete()
        }
        assertEquals(0, server.requestCount)
    }
}
