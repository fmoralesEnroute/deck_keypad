package com.example.macdeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTest {

    @Test fun `valid pairing builds an https url`() {
        val p = TestData.pairing
        assertTrue(p.isValid)
        assertEquals("https://192.168.1.20:8765/", p.url().toString())
    }

    @Test fun `host with an embedded port is rejected, not crashed on`() {
        assertFalse(TestData.pairing.copy(host = "192.168.1.20:8765").isValid)
    }

    @Test fun `out of range ports are rejected`() {
        assertFalse(TestData.pairing.copy(port = 0).isValid)
        assertFalse(TestData.pairing.copy(port = 70000).isValid)
    }

    @Test fun `ipv6 hosts work without brackets`() {
        assertEquals("https://[fe80::1]:8765/", TestData.pairing.copy(host = "fe80::1").url().toString())
    }

    @Test fun `blank token and bad fingerprints are rejected`() {
        assertFalse(TestData.pairing.copy(token = " ").isValid)
        assertFalse(TestData.pairing.copy(certSha256 = "not base64!").isValid)
        assertFalse(TestData.pairing.copy(certSha256 = "AAAA").isValid) // wrong length
    }

    @Test fun `parses the agent QR payload`() {
        val p = parsePairing("""{"h":"192.168.1.20","p":8765,"t":"tok","f":"${TestData.FINGERPRINT}"}""")
        assertEquals(TestData.pairing, p)
    }

    @Test fun `rejects QR payloads without a certificate or with bad values`() {
        assertNull(parsePairing("""{"h":"192.168.1.20","p":8765,"t":"tok"}"""))
        assertNull(parsePairing("""{"h":"192.168.1.20","p":99999,"t":"tok","f":"${TestData.FINGERPRINT}"}"""))
        assertNull(parsePairing("https://example.com"))
    }
}

object TestData {
    /** base64url (no padding) of 32 zero bytes. */
    const val FINGERPRINT = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    val pairing = Pairing("192.168.1.20", 8765, "tok", FINGERPRINT)
    val layoutJson = """{"type":"layout","app":"com.x","appName":"X","buttons":[{"id":"0","label":"Go"}]}"""
}
