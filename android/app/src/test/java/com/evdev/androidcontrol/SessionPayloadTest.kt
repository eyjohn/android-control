package com.evdev.androidcontrol

import com.evdev.androidcontrol.model.SessionPayload
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
            deviceName = "Pixel 9a"
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
    }
}

