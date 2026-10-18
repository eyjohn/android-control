package com.evdev.androidcontrol

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import com.evdev.androidcontrol.model.ControlMode
import com.evdev.androidcontrol.model.SessionPayload
import com.evdev.androidcontrol.rtc.WebRtcClient
import com.evdev.androidcontrol.ui.AndroidControlTheme
import com.evdev.androidcontrol.ui.MainScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var rtcClient: WebRtcClient? = null
    private var sessionStatus by mutableStateOf("Idle (Waiting for request)")
    private var controlMode by mutableStateOf(ControlMode.ACCESSIBILITY)
    private var incomingOffer by mutableStateOf<SessionPayload?>(null)
    private var generatedAnswer by mutableStateOf<String?>(null)
    private var isAdbReachable by mutableStateOf<Boolean?>(null)
    private var rxBytes by mutableStateOf(0L)
    private var txBytes by mutableStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkAdbStatus()
        handleIntent(intent)

        // Metrics monitoring loop
        lifecycleScope.launch {
            while (isActive) {
                rtcClient?.let {
                    rxBytes = it.bytesReceived.get()
                    txBytes = it.bytesTransferred.get()
                }
                delay(1000)
            }
        }

        setContent {
            AndroidControlTheme {
                MainScreen(
                    controlMode = controlMode,
                    onModeChange = { controlMode = it },
                    sessionStatus = sessionStatus,
                    incomingOffer = incomingOffer,
                    generatedAnswer = generatedAnswer,
                    isAdbReachable = isAdbReachable,
                    rxBytes = rxBytes,
                    txBytes = txBytes,
                    onCheckAdb = { checkAdbStatus() },
                    onLoadOffer = { loadOfferPayload(it) },
                    onApprove = { approveSession() },
                    onCopyAnswer = { copyAnswerToClipboard() },
                    onDisconnect = { disconnectSession() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun checkAdbStatus() {
        lifecycleScope.launch {
            isAdbReachable = WebRtcClient.checkAdbPort("127.0.0.1", 5555)
        }
    }

    private fun handleIntent(intent: Intent?) {
        val uri: Uri? = intent?.data
        if (uri != null) {
            val rawData = uri.getQueryParameter("data")
                ?: uri.getQueryParameter("offer")
                ?: uri.getQueryParameter("sdp")

            if (!rawData.isNullOrBlank()) {
                loadOfferPayload(rawData)
                return
            }

            if (uri.scheme == "android-control" && uri.host == "approve") {
                val reason = uri.getQueryParameter("reason") ?: "AI Agent Remote Control"
                val ttl = uri.getQueryParameter("ttl")?.toIntOrNull() ?: 600
                val sdp = uri.getQueryParameter("sdp") ?: ""
                incomingOffer = SessionPayload(
                    sessionId = "intent-" + System.currentTimeMillis().toString().takeLast(6),
                    sdp = sdp,
                    reason = reason,
                    ttlSeconds = ttl
                )
                sessionStatus = "Approval pending: $reason"
            }
        }
    }

    private fun loadOfferPayload(raw: String) {
        val res = SessionPayload.parse(raw)
        res.onSuccess { offer ->
            incomingOffer = offer
            sessionStatus = "Offer loaded: ${offer.reason} (ID: ${offer.sessionId})"
            Toast.makeText(this, "Session offer loaded successfully", Toast.LENGTH_SHORT).show()
        }.onFailure {
            sessionStatus = "Error parsing offer payload: ${it.localizedMessage}"
            Toast.makeText(this, "Failed to parse offer payload", Toast.LENGTH_LONG).show()
        }
    }

    private fun approveSession() {
        val offer = incomingOffer
        if (offer == null || offer.sdp.isBlank()) {
            sessionStatus = "Error: Missing WebRTC Offer SDP"
            return
        }

        sessionStatus = "Connecting WebRTC ($controlMode mode)..."
        lifecycleScope.launch {
            try {
                rtcClient = WebRtcClient(
                    context = this@MainActivity,
                    scope = lifecycleScope,
                    onStateChange = { sessionStatus = it }
                )
                val answerSdp = rtcClient?.answerOffer(offer.sdp)
                if (answerSdp != null) {
                    val answer = SessionPayload(
                        sessionId = offer.sessionId,
                        sdp = answerSdp,
                        type = "answer",
                        reason = offer.reason,
                        ttlSeconds = offer.ttlSeconds,
                        approved = true,
                        deviceName = "Pixel 9a"
                    )
                    generatedAnswer = answer.toBase64Gzip()
                    sessionStatus = "Connected! WebRTC DataChannels active."
                }
            } catch (e: Exception) {
                sessionStatus = "Connection error: ${e.message}"
            }
        }
    }

    private fun copyAnswerToClipboard() {
        val ans = generatedAnswer ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("WebRTC Answer", ans)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Answer copied to clipboard!", Toast.LENGTH_SHORT).show()
    }

    private fun disconnectSession() {
        rtcClient?.close()
        rtcClient = null
        sessionStatus = "Disconnected"
        incomingOffer = null
        generatedAnswer = null
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectSession()
    }
}
