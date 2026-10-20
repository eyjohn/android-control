package com.evdev.androidcontrol

import com.evdev.androidcontrol.model.SessionPayload
import com.evdev.androidcontrol.session.OfferGate
import org.junit.Assert.*
import org.junit.Test

class SessionPayloadTest {

    @Test
    fun testJsonPayloadParse() {
        val json = """
            {
                "session_id": "test-123",
                "sdp": "v=0\r\no=- 1 1 IN IP4 127.0.0.1",
                "type": "offer",
                "reason": "Control Test",
                "ttl_seconds": 300,
                "approved": true
            }
        """.trimIndent()

        val parsed = SessionPayload.parse(json).getOrThrow()
        assertEquals("test-123", parsed.sessionId)
        assertEquals("v=0\r\no=- 1 1 IN IP4 127.0.0.1", parsed.sdp)
        assertEquals("Control Test", parsed.reason)
        assertEquals(300, parsed.ttlSeconds)
        assertTrue(parsed.approved)
    }

    @Test
    fun testBase64GzipRoundtrip() {
        val payload = SessionPayload(
            sessionId = "sess-roundtrip-789",
            sdp = "v=0\r\nsdp-content",
            type = "answer",
            reason = "Roundtrip Test",
            ttlSeconds = 600,
            approved = true,
            deviceName = "Pixel 11 Pro"
        )

        val b64 = payload.toBase64Gzip()
        assertNotNull(b64)
        assertTrue(b64.isNotEmpty())

        val decoded = SessionPayload.parse(b64).getOrThrow()
        assertEquals(payload.sessionId, decoded.sessionId)
        assertEquals(payload.sdp, decoded.sdp)
        assertEquals(payload.type, decoded.type)
        assertEquals(payload.reason, decoded.reason)
        assertEquals(payload.ttlSeconds, decoded.ttlSeconds)
        assertEquals(payload.approved, decoded.approved)
        assertEquals(payload.mode, decoded.mode)
        assertEquals(payload.account, decoded.account)
    }

    @Test
    fun testGoogleAccountBinding() {
        val payload = SessionPayload(
            sessionId = "sess-google-bind",
            sdp = "v=0\r\nsdp-content",
            account = "john.yakimov@gmail.com"
        )
        val b64 = payload.toBase64Gzip()
        val decoded = SessionPayload.parse(b64).getOrThrow()
        assertEquals("john.yakimov@gmail.com", decoded.account)
    }

    @Test
    fun testIceServersRoundtrip() {
        val ice = listOf(
            com.evdev.androidcontrol.model.IceServerConfig(
                urls = listOf("turn:turn.example.com:3478", "turn:turn.example.com:443?transport=tcp"),
                username = "turnuser",
                credential = "turnpassword"
            )
        )
        val payload = SessionPayload(
            sessionId = "sess-ice-test",
            sdp = "v=0\r\nsdp-content",
            iceServers = ice
        )
        val json = payload.toJson()
        val parsed = SessionPayload.parse(json).getOrThrow()
        assertEquals(1, parsed.iceServers.size)
        assertEquals(listOf("turn:turn.example.com:3478", "turn:turn.example.com:443?transport=tcp"), parsed.iceServers[0].urls)
        assertEquals("turnuser", parsed.iceServers[0].username)
        assertEquals("turnpassword", parsed.iceServers[0].credential)

        val b64 = payload.toBase64Gzip()
        val decoded = SessionPayload.parse(b64).getOrThrow()
        assertEquals(1, decoded.iceServers.size)
        assertEquals("turnuser", decoded.iceServers[0].username)
    }

    @Test
    fun testOfferGateAcceptsValidOffer() {
        val offer = SessionPayload(
            sessionId = "sess-1",
            sdp = "v=0\r\nsdp",
            account = "user@example.com",
            timestampMs = 5000L
        )
        val decision = OfferGate.evaluate(
            offer = offer,
            isConnectionsEnabled = true,
            currentUserEmail = "user@example.com",
            lastAppliedSessionId = null,
            lastAppliedSdpHash = 0,
            lastAppliedTimestampMs = 0L
        )
        assertEquals(OfferGate.Decision.Accept, decision)
    }

    @Test
    fun testOfferGateRejectsWhenConnectionsDisabled() {
        val offer = SessionPayload(
            sessionId = "sess-1",
            sdp = "v=0\r\nsdp",
            account = "user@example.com"
        )
        val decision = OfferGate.evaluate(
            offer = offer,
            isConnectionsEnabled = false,
            currentUserEmail = "user@example.com",
            lastAppliedSessionId = null,
            lastAppliedSdpHash = 0,
            lastAppliedTimestampMs = 0L
        )
        assertTrue(decision is OfferGate.Decision.Reject)
        assertEquals("Connections disabled — request blocked", (decision as OfferGate.Decision.Reject).reason)
    }

    @Test
    fun testOfferGateRejectsWhenSignedOut() {
        val offer = SessionPayload(
            sessionId = "sess-1",
            sdp = "v=0\r\nsdp",
            account = "user@example.com"
        )
        val decision = OfferGate.evaluate(
            offer = offer,
            isConnectionsEnabled = true,
            currentUserEmail = null,
            lastAppliedSessionId = null,
            lastAppliedSdpHash = 0,
            lastAppliedTimestampMs = 0L
        )
        assertTrue(decision is OfferGate.Decision.Reject)
        assertEquals("Signed out — sign in to allow connection", (decision as OfferGate.Decision.Reject).reason)
    }

    @Test
    fun testOfferGateRejectsAccountMismatch() {
        val offer = SessionPayload(
            sessionId = "sess-1",
            sdp = "v=0\r\nsdp",
            account = "attacker@example.com"
        )
        val decision = OfferGate.evaluate(
            offer = offer,
            isConnectionsEnabled = true,
            currentUserEmail = "user@example.com",
            lastAppliedSessionId = null,
            lastAppliedSdpHash = 0,
            lastAppliedTimestampMs = 0L
        )
        assertTrue(decision is OfferGate.Decision.Reject)
        assertTrue((decision as OfferGate.Decision.Reject).reason.contains("Account mismatch"))
    }

    @Test
    fun testOfferGateIgnoresDuplicateOffer() {
        val offer = SessionPayload(
            sessionId = "sess-1",
            sdp = "v=0\r\nsdp",
            account = "user@example.com",
            timestampMs = 5000L
        )
        val decision = OfferGate.evaluate(
            offer = offer,
            isConnectionsEnabled = true,
            currentUserEmail = "user@example.com",
            lastAppliedSessionId = "sess-1",
            lastAppliedSdpHash = "v=0\r\nsdp".hashCode(),
            lastAppliedTimestampMs = 5000L
        )
        assertEquals(OfferGate.Decision.IgnoreDuplicate, decision)
    }

    @Test
    fun testOfferGateIgnoresOlderTimestamp() {
        val offer = SessionPayload(
            sessionId = "sess-new",
            sdp = "v=0\r\nnew-sdp",
            account = "user@example.com",
            timestampMs = 4000L
        )
        val decision = OfferGate.evaluate(
            offer = offer,
            isConnectionsEnabled = true,
            currentUserEmail = "user@example.com",
            lastAppliedSessionId = "sess-old",
            lastAppliedSdpHash = 12345,
            lastAppliedTimestampMs = 5000L
        )
        assertEquals(OfferGate.Decision.IgnoreDuplicate, decision)
    }

    @Test
    fun testOfferGateAcceptsSameSessionNewerSdpRenegotiation() {
        val offer = SessionPayload(
            sessionId = "sess-1",
            sdp = "v=0\r\nreneg-sdp",
            account = "user@example.com",
            timestampMs = 6000L
        )
        val decision = OfferGate.evaluate(
            offer = offer,
            isConnectionsEnabled = true,
            currentUserEmail = "user@example.com",
            lastAppliedSessionId = "sess-1",
            lastAppliedSdpHash = "v=0\r\nold-sdp".hashCode(),
            lastAppliedTimestampMs = 5000L
        )
        assertEquals(OfferGate.Decision.Accept, decision)
    }
}

