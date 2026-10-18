package com.evdev.androidcontrol.model

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.json.JSONObject

data class SessionPayload(
    val sessionId: String,
    val sdp: String,
    val type: String = "offer",
    val reason: String = "Remote Control",
    val ttlSeconds: Int = 600,
    val approved: Boolean = true,
    val deviceName: String = "Pixel 9a",
    val timestampMs: Long = System.currentTimeMillis()
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("session_id", sessionId)
            put("type", type)
            put("sdp", sdp)
            put("reason", reason)
            put("ttl_seconds", ttlSeconds)
            put("approved", approved)
            put("device_name", deviceName)
            put("timestamp_ms", timestampMs)
        }.toString()
    }

    fun toBase64Gzip(): String {
        val jsonStr = toJson()
        val byteOut = ByteArrayOutputStream()
        GZIPOutputStream(byteOut).use { gzip ->
            gzip.write(jsonStr.toByteArray(StandardCharsets.UTF_8))
        }
        return Base64.getEncoder().encodeToString(byteOut.toByteArray())
    }

    companion object {
        fun parse(raw: String): Result<SessionPayload> = runCatching {
            val clean = raw.trim()
                .removePrefix("<webrtc>")
                .removeSuffix("</webrtc>")
                .removePrefix("android-control://")
                .removePrefix("webrtc-bridge://")
                .trim()

            val jsonStr = if (clean.startsWith("{")) {
                clean
            } else {
                try {
                    val bytes = Base64.getDecoder().decode(clean)
                    val byteIn = ByteArrayInputStream(bytes)
                    val gzipIn = GZIPInputStream(byteIn)
                    gzipIn.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                } catch (e: Exception) {
                    // Fallback: raw SDP string
                    if (clean.contains("v=0")) {
                        return@runCatching SessionPayload(
                            sessionId = "raw-" + System.currentTimeMillis(),
                            sdp = clean
                        )
                    }
                    throw e
                }
            }

            val obj = JSONObject(jsonStr)
            SessionPayload(
                sessionId = obj.optString("session_id", obj.optString("secret", "sess-manual")),
                sdp = obj.optString("sdp", ""),
                type = obj.optString("type", "offer"),
                reason = obj.optString("reason", "AI Agent Remote Control"),
                ttlSeconds = obj.optInt("ttl_seconds", 600),
                approved = obj.optBoolean("approved", true),
                deviceName = obj.optString("device_name", "Pixel 9a"),
                timestampMs = obj.optLong("timestamp_ms", System.currentTimeMillis())
            )
        }
    }
}
