package com.evdev.androidcontrol.rtc

import android.content.Context
import com.evdev.androidcontrol.model.*
import com.evdev.androidcontrol.service.ControlAccessibilityService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.webrtc.*
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class WebRtcClient(
    private val context: Context,
    private val scope: CoroutineScope,
    private val adbPortResolver: suspend () -> Int = { 5555 },
    private val onStateChange: (String) -> Unit = {},
    private val onConnectionReady: () -> Unit = {},
    private val onClosed: () -> Unit = {}
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val factory: PeerConnectionFactory = WebRtcFactoryProvider.getFactory(context)

    private var peerConnection: PeerConnection? = null
    private var controlChannel: DataChannel? = null
    private var adbChannel: DataChannel? = null

    private var activeSessionId: String? = null
    private var activeFingerprint: String? = null

    val bytesReceived = AtomicLong(0)
    val bytesTransferred = AtomicLong(0)
    val lastTrafficTime = AtomicLong(System.currentTimeMillis())
    val isControlChannelOpen = AtomicBoolean(false)

    private fun extractFingerprint(sdp: String): String {
        return sdp.lineSequence()
            .firstOrNull { it.startsWith("a=fingerprint:") }
            ?: sdp.lineSequence().firstOrNull { it.startsWith("a=ice-ufrag:") }
            ?: sdp.hashCode().toString()
    }

    private fun postState(state: String) {
        android.util.Log.i("WebRtcClient", "postState: $state")
        scope.launch(Dispatchers.Main.immediate) {
            onStateChange(state)
        }
    }

    suspend fun answerOffer(
        sessionId: String,
        offerSdp: String,
        iceServers: List<IceServerConfig> = emptyList()
    ): String {
        val offerFingerprint = extractFingerprint(offerSdp)

        // F2: Re-use only for true same-session renegotiation; otherwise reset peer connection
        if (peerConnection != null &&
            (activeSessionId != sessionId || activeFingerprint != offerFingerprint)
        ) {
            resetPeerConnection()
        }
        activeSessionId = sessionId
        activeFingerprint = offerFingerprint

        val servers = mutableListOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        for (server in iceServers) {
            if (server.urls.isNotEmpty()) {
                val builder = PeerConnection.IceServer.builder(server.urls)
                if (!server.username.isNullOrBlank()) {
                    builder.setUsername(server.username)
                }
                if (!server.credential.isNullOrBlank()) {
                    builder.setPassword(server.credential)
                }
                servers.add(builder.createIceServer())
            }
        }
        val rtcConfig = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        var hasRelayCandidate = false

        var createdPc: PeerConnection? = null

        val pcObserver = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (peerConnection !== createdPc) return
                if (candidate != null && candidate.sdp.contains("typ relay", ignoreCase = true)) {
                    hasRelayCandidate = true
                }
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                if (peerConnection !== createdPc) return
                postState("ICE: $state")
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
                if (peerConnection !== createdPc) return
                postState("State: $state")
                if (state == PeerConnection.PeerConnectionState.CONNECTED) {
                    scope.launch(Dispatchers.Main.immediate) {
                        onConnectionReady()
                    }
                } else if (state == PeerConnection.PeerConnectionState.FAILED || state == PeerConnection.PeerConnectionState.CLOSED) {
                    scope.launch(Dispatchers.Main.immediate) {
                        onClosed()
                    }
                }
            }
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {
                if (peerConnection !== createdPc) return
                if (channel == null) return
                if (channel.label().startsWith("adb")) {
                    setupAdbDataChannel(channel)
                } else {
                    controlChannel = channel
                    setupControlDataChannel(channel)
                }
            }
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        }

        val pc = peerConnection
        val activePc = if (pc != null &&
            pc.connectionState() != PeerConnection.PeerConnectionState.FAILED &&
            pc.connectionState() != PeerConnection.PeerConnectionState.CLOSED
        ) {
            createdPc = pc
            pc
        } else {
            resetPeerConnection()
            val newPc = factory.createPeerConnection(rtcConfig, pcObserver)
                ?: throw IllegalStateException("Failed to create PeerConnection")
            createdPc = newPc
            peerConnection = newPc
            newPc
        }

        // 1. Set Remote Description (Offer)
        val setRemoteDeferred = CompletableDeferred<Boolean>()
        activePc.setRemoteDescription(
            object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {}
                override fun onSetSuccess() { setRemoteDeferred.complete(true) }
                override fun onCreateFailure(error: String?) { setRemoteDeferred.completeExceptionally(RuntimeException(error)) }
                override fun onSetFailure(error: String?) { setRemoteDeferred.completeExceptionally(RuntimeException(error)) }
            },
            SessionDescription(SessionDescription.Type.OFFER, offerSdp)
        )
        setRemoteDeferred.await()

        // 2. Create Answer
        val createAnswerDeferred = CompletableDeferred<SessionDescription>()
        activePc.createAnswer(
            object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    if (desc != null) createAnswerDeferred.complete(desc)
                    else createAnswerDeferred.completeExceptionally(RuntimeException("Null SDP"))
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String?) { createAnswerDeferred.completeExceptionally(RuntimeException(error)) }
                override fun onSetFailure(error: String?) {}
            },
            MediaConstraints()
        )
        val answerDesc = createAnswerDeferred.await()

        // 3. Set Local Description (Answer)
        val setLocalDeferred = CompletableDeferred<Boolean>()
        activePc.setLocalDescription(
            object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {}
                override fun onSetSuccess() { setLocalDeferred.complete(true) }
                override fun onCreateFailure(error: String?) { setLocalDeferred.completeExceptionally(RuntimeException(error)) }
                override fun onSetFailure(error: String?) { setLocalDeferred.completeExceptionally(RuntimeException(error)) }
            },
            answerDesc
        )
        setLocalDeferred.await()

        postState("Gathering candidates...")

        // F5: Wait for ICE gathering to complete (up to 8000ms or exit early when relay candidate found)
        val startTime = System.currentTimeMillis()
        while (activePc.iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE) {
            if (hasRelayCandidate) break
            if (System.currentTimeMillis() - startTime > 8000) break
            delay(50)
        }

        val finalSdp = activePc.localDescription?.description ?: answerDesc.description
        postState("Ready (waiting for peer)")
        return finalSdp
    }

    private fun setupControlDataChannel(channel: DataChannel) {
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                if (controlChannel !== channel) return
                val state = channel.state()
                postState("Control Channel: $state")
                if (state == DataChannel.State.OPEN) {
                    isControlChannelOpen.set(true)
                    scope.launch(Dispatchers.Main.immediate) {
                        onConnectionReady()
                    }
                } else if (state == DataChannel.State.CLOSED) {
                    if (isControlChannelOpen.getAndSet(false)) {
                        scope.launch(Dispatchers.Main.immediate) {
                            onClosed()
                        }
                    }
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer?) {
                if (controlChannel !== channel) return
                if (buffer == null) return
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                val text = String(bytes, StandardCharsets.UTF_8)

                scope.launch(Dispatchers.Default) {
                    handleRpcMessage(channel, text)
                }
            }
        })
    }

    private suspend fun handleRpcMessage(channel: DataChannel, text: String) {
        lastTrafficTime.set(System.currentTimeMillis())
        val req = try {
            json.decodeFromString<RpcRequest>(text)
        } catch (_: Exception) {
            return
        }

        if (req.method == "ping") {
            val response = RpcResponse(
                id = req.id,
                result = kotlinx.serialization.json.buildJsonObject {
                    put("pong", kotlinx.serialization.json.JsonPrimitive(System.currentTimeMillis()))
                }
            )
            val resJson = json.encodeToString(RpcResponse.serializer(), response)
            val resBytes = resJson.toByteArray(StandardCharsets.UTF_8)
            channel.send(DataChannel.Buffer(ByteBuffer.wrap(resBytes), false))
            return
        }

        if (req.method == "session.teardown") {
            val response = RpcResponse(id = req.id, result = kotlinx.serialization.json.buildJsonObject {
                put("ok", kotlinx.serialization.json.JsonPrimitive(true))
            })
            val resJson = json.encodeToString(RpcResponse.serializer(), response)
            val resBytes = resJson.toByteArray(StandardCharsets.UTF_8)
            channel.send(DataChannel.Buffer(ByteBuffer.wrap(resBytes), false))
            postState("Idle (On-demand standby)")
            close()
            return
        }

        val service = ControlAccessibilityService.instance
        val response = if (service == null) {
            RpcResponse(
                id = req.id,
                error = RpcError(code = -32001, message = "AccessibilityService is disabled on device.")
            )
        } else {
            try {
                val result = service.handleRpc(req.method, req.params)
                RpcResponse(id = req.id, result = result)
            } catch (e: Exception) {
                RpcResponse(id = req.id, error = RpcError(code = -32000, message = e.message ?: "Action failed"))
            }
        }

        val resJson = json.encodeToString(RpcResponse.serializer(), response)
        val resBytes = resJson.toByteArray(StandardCharsets.UTF_8)
        val buffer = DataChannel.Buffer(ByteBuffer.wrap(resBytes), false)
        channel.send(buffer)
        lastTrafficTime.set(System.currentTimeMillis())
    }

    suspend fun sendTeardownNotice(): Boolean = withContext(Dispatchers.IO) {
        val ch = controlChannel
        if (ch != null && ch.state() == DataChannel.State.OPEN) {
            try {
                val notice = json.encodeToString(
                    RpcRequest.serializer(),
                    RpcRequest(id = "notice-teardown", method = "session.teardown")
                )
                ch.send(DataChannel.Buffer(ByteBuffer.wrap(notice.toByteArray(StandardCharsets.UTF_8)), false))
                // R3: Give channel time to flush buffer before closing
                var waitMs = 0
                while (ch.bufferedAmount() > 0 && waitMs < 500) {
                    delay(20)
                    waitMs += 20
                }
                delay(50)
                true
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }
    }

    // F3: Ordered, backpressured, multi-socket ADB bridge supporting concurrent/overlapping channels
    private fun setupAdbDataChannel(channel: DataChannel) {
        val writeChannel = Channel<ByteArray>(Channel.BUFFERED)

        val channelJob = scope.launch(Dispatchers.IO) {
            var currentSocket: Socket? = null
            var forwardJob: Job? = null

            suspend fun ensureSocket(): Socket? {
                if (currentSocket != null && !currentSocket!!.isClosed && currentSocket!!.isConnected) {
                    return currentSocket
                }
                forwardJob?.cancel()
                try {
                    val port = adbPortResolver()
                    val sock = Socket().apply {
                        connect(InetSocketAddress("127.0.0.1", port), 2000)
                    }
                    currentSocket = sock
                    android.util.Log.i("WebRtcClient", "Channel ${channel.label()}: connected to adbd 127.0.0.1:$port")

                    forwardJob = scope.launch(Dispatchers.IO) {
                        val buf = ByteArray(16 * 1024)
                        val input = sock.getInputStream()
                        try {
                            while (isActive && channel.state() == DataChannel.State.OPEN && !sock.isClosed) {
                                while (isActive && channel.state() == DataChannel.State.OPEN && channel.bufferedAmount() > 1_000_000L) {
                                    delay(10)
                                }
                                val read = input.read(buf)
                                if (read > 0) {
                                    bytesTransferred.addAndGet(read.toLong())
                                    lastTrafficTime.set(System.currentTimeMillis())
                                    channel.send(DataChannel.Buffer(ByteBuffer.wrap(buf.copyOf(read)), true))
                                } else {
                                    android.util.Log.i("WebRtcClient", "Channel ${channel.label()}: adbd socket EOF")
                                    break
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.w("WebRtcClient", "Channel ${channel.label()}: adbd read error: ${e.message}")
                        } finally {
                            try { sock.close() } catch (_: Exception) {}
                        }
                    }
                    return sock
                } catch (e: Exception) {
                    android.util.Log.e("WebRtcClient", "Channel ${channel.label()}: failed to connect to adbd: ${e.message}")
                    return null
                }
            }

            try {
                for (chunk in writeChannel) {
                    if (channel.state() != DataChannel.State.OPEN) break
                    val isCnxn = chunk.size >= 4 &&
                            chunk[0] == 0x43.toByte() &&
                            chunk[1] == 0x4e.toByte() &&
                            chunk[2] == 0x58.toByte() &&
                            chunk[3] == 0x4e.toByte()
                    if (isCnxn && currentSocket != null) {
                        android.util.Log.i("WebRtcClient", "Channel ${channel.label()}: CNXN reset")
                        try { currentSocket?.close() } catch (_: Exception) {}
                        currentSocket = null
                    }

                    val sock = ensureSocket()
                    if (sock != null) {
                        try {
                            val out = sock.getOutputStream()
                            out.write(chunk)
                            out.flush()
                        } catch (e: Exception) {
                            android.util.Log.w("WebRtcClient", "Channel ${channel.label()}: write error: ${e.message}")
                            try { sock.close() } catch (_: Exception) {}
                            currentSocket = null
                        }
                    }
                }
            } finally {
                forwardJob?.cancel()
                try { currentSocket?.close() } catch (_: Exception) {}
            }
        }

        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                val state = channel.state()
                android.util.Log.i("WebRtcClient", "Channel ${channel.label()} state: $state")
                postState("ADB Bridge: $state")
                if (state == DataChannel.State.CLOSED) {
                    writeChannel.close()
                    channelJob.cancel()
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer?) {
                if (buffer == null) return
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                bytesReceived.addAndGet(bytes.size.toLong())
                lastTrafficTime.set(System.currentTimeMillis())
                writeChannel.trySend(bytes)
            }
        })
    }

    private fun resetPeerConnection() {
        isControlChannelOpen.set(false)
        controlChannel?.dispose()
        controlChannel = null
        adbChannel?.dispose()
        adbChannel = null
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        activeSessionId = null
        activeFingerprint = null
    }

    fun close() {
        resetPeerConnection()
        scope.launch(Dispatchers.Main.immediate) {
            onClosed()
        }
    }

    companion object {
        suspend fun checkAdbPort(host: String = "127.0.0.1", port: Int = 5555, timeoutMs: Int = 1500): Boolean =
            withContext(Dispatchers.IO) {
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(host, port), timeoutMs)
                        true
                    }
                } catch (_: Exception) {
                    false
                }
            }
    }
}
