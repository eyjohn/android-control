package com.evdev.androidcontrol.model

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.json.JSONObject

data class IceServerConfig(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            val arr = org.json.JSONArray()
            urls.forEach { arr.put(it) }
            put("urls", arr)
            if (!username.isNullOrBlank()) put("username", username)
            if (!credential.isNullOrBlank()) put("credential", credential)
        }
    }

    companion object {
        fun fromJson(obj: JSONObject): IceServerConfig {
            val urlsList = mutableListOf<String>()
            val urlsObj = obj.opt("urls")
            if (urlsObj is org.json.JSONArray) {
                for (i in 0 until urlsObj.length()) {
                    urlsList.add(urlsObj.getString(i))
                }
            } else if (urlsObj is String) {
                urlsList.add(urlsObj)
            } else if (obj.has("url")) {
                urlsList.add(obj.getString("url"))
            }
            val username = if (obj.has("username")) obj.optString("username") else null
            val cred = if (obj.has("credential")) obj.optString("credential")
            else if (obj.has("password")) obj.optString("password")
            else null
            return IceServerConfig(urls = urlsList, username = username, credential = cred)
        }
    }
}

data class SessionPayload(
    val sessionId: String,
    val sdp: String,
    val type: String = "offer",
    val reason: String = "Remote Control",
    val ttlSeconds: Int = 600,
    val approved: Boolean = true,
    val deviceName: String = "Pixel 11 Pro",
    val mode: String = "accessibility",
    val account: String? = null,
    val authToken: String? = null,
    val timestampMs: Long = System.currentTimeMillis(),
    val iceServers: List<IceServerConfig> = emptyList()
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
            put("mode", mode)
            if (account != null) put("account", account)
            if (authToken != null) put("auth_token", authToken)
            put("timestamp_ms", timestampMs)
            if (iceServers.isNotEmpty()) {
                val arr = org.json.JSONArray()
                iceServers.forEach { arr.put(it.toJson()) }
                put("ice_servers", arr)
            }
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
            val iceServersList = mutableListOf<IceServerConfig>()
            val iceServersArr = obj.optJSONArray("ice_servers")
            if (iceServersArr != null) {
                for (i in 0 until iceServersArr.length()) {
                    val item = iceServersArr.optJSONObject(i)
                    if (item != null) {
                        iceServersList.add(IceServerConfig.fromJson(item))
                    }
                }
            }

            SessionPayload(
                sessionId = obj.optString("session_id", obj.optString("secret", "sess-manual")),
                sdp = obj.optString("sdp", ""),
                type = obj.optString("type", "offer"),
                reason = obj.optString("reason", "AI Agent Remote Control"),
                ttlSeconds = obj.optInt("ttl_seconds", 600),
                approved = obj.optBoolean("approved", true),
                deviceName = obj.optString("device_name", "Pixel 11 Pro"),
                mode = obj.optString("mode", "accessibility"),
                account = if (obj.has("account")) obj.optString("account") else null,
                authToken = if (obj.has("auth_token")) obj.optString("auth_token") else null,
                timestampMs = obj.optLong("timestamp_ms", System.currentTimeMillis()),
                iceServers = iceServersList
            )
        }
    }
}
