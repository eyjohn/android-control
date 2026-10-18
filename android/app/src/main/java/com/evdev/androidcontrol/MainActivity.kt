package com.evdev.androidcontrol

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import com.evdev.androidcontrol.rtc.WebRtcClient
import com.evdev.androidcontrol.ui.MainScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var rtcClient: WebRtcClient? = null
    private var sessionStatus by mutableStateOf("Idle (Waiting for request)")
    private var incomingReason by mutableStateOf<String?>(null)
    private var incomingTtl by mutableStateOf<Int?>(null)
    private var incomingOfferSdp by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        handleIntent(intent)

        setContent {
            MainScreen(
                sessionStatus = sessionStatus,
                incomingReason = incomingReason,
                incomingTtl = incomingTtl,
                onApprove = { approveSession() },
                onDisconnect = { disconnectSession() }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri: Uri? = intent?.data
        if (uri != null && uri.scheme == "android-control" && uri.host == "approve") {
            incomingReason = uri.getQueryParameter("reason") ?: "AI Agent Remote Control"
            incomingTtl = uri.getQueryParameter("ttl")?.toIntOrNull() ?: 600
            incomingOfferSdp = uri.getQueryParameter("sdp")
            sessionStatus = "Approval pending: $incomingReason"
        }
    }

    private fun approveSession() {
        val sdp = incomingOfferSdp
        if (sdp.isNullOrBlank()) {
            sessionStatus = "Error: Missing WebRTC Offer in request"
            return
        }

        sessionStatus = "Connecting WebRTC..."
        lifecycleScope.launch {
            try {
                rtcClient = WebRtcClient(
                    context = this@MainActivity,
                    scope = lifecycleScope,
                    onStateChange = { sessionStatus = it }
                )
                val answerSdp = rtcClient?.answerOffer(sdp)
                sessionStatus = "Connected! WebRTC DataChannel Active."
                incomingReason = null
            } catch (e: Exception) {
                sessionStatus = "Connection error: ${e.message}"
            }
        }
    }

    private fun disconnectSession() {
        rtcClient?.close()
        rtcClient = null
        sessionStatus = "Disconnected"
        incomingReason = null
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectSession()
    }
}

