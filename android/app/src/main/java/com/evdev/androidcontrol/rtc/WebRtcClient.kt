package com.evdev.androidcontrol.rtc

import android.content.Context
import com.evdev.androidcontrol.model.*
import com.evdev.androidcontrol.service.ControlAccessibilityService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.webrtc.*
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class WebRtcClient(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onStateChange: (String) -> Unit = {}
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var controlChannel: DataChannel? = null
    private var adbChannel: DataChannel? = null

    private var adbSocket: Socket? = null
    private var adbForwardJob: Job? = null

    init {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    suspend fun answerOffer(offerSdp: String): String {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        val pcObserver = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                onStateChange("ICE: $state")
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
                onStateChange("State: $state")
            }
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {
                if (channel == null) return
                if (channel.label() == "adb") {
                    adbChannel = channel
                    setupAdbDataChannel(channel)
                } else {
                    controlChannel = channel
                    setupControlDataChannel(channel)
                }
            }
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        }

        peerConnection = factory?.createPeerConnection(rtcConfig, pcObserver)
            ?: throw IllegalStateException("Failed to create PeerConnection")

        // 1. Set Remote Description (Offer)
        val setRemoteDeferred = CompletableDeferred<Boolean>()
        peerConnection?.setRemoteDescription(
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
        peerConnection?.createAnswer(
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
        peerConnection?.setLocalDescription(
            object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {}
                override fun onSetSuccess() { setLocalDeferred.complete(true) }
                override fun onCreateFailure(error: String?) { setLocalDeferred.completeExceptionally(RuntimeException(error)) }
                override fun onSetFailure(error: String?) { setLocalDeferred.completeExceptionally(RuntimeException(error)) }
            },
            answerDesc
        )
        setLocalDeferred.await()

        return answerDesc.description
    }

    private fun setupControlDataChannel(channel: DataChannel) {
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                onStateChange("Control Channel: ${channel.state()}")
            }

            override fun onMessage(buffer: DataChannel.Buffer?) {
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
        val req = try {
            json.decodeFromString<RpcRequest>(text)
        } catch (e: Exception) {
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
    }

    private fun setupAdbDataChannel(channel: DataChannel) {
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                val state = channel.state()
                onStateChange("ADB Bridge: $state")
                if (state == DataChannel.State.OPEN) {
                    startAdbForwarder(channel)
                } else if (state == DataChannel.State.CLOSED) {
                    stopAdbForwarder()
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer?) {
                if (buffer == null) return
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                scope.launch(Dispatchers.IO) {
                    try {
                        val sock = getOrCreateAdbSocket()
                        if (sock != null && !sock.isClosed) {
                            val out = sock.getOutputStream()
                            out.write(bytes)
                            out.flush()
                        }
                    } catch (e: Exception) {
                        closeAdbSocket()
                    }
                }
            }
        })
    }

    private fun startAdbForwarder(channel: DataChannel) {
        adbForwardJob?.cancel()
        adbForwardJob = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(16 * 1024)
            while (isActive && channel.state() == DataChannel.State.OPEN) {
                val sock = getOrCreateAdbSocket()
                if (sock == null || sock.isClosed) {
                    kotlinx.coroutines.delay(500)
                    continue
                }
                try {
                    val read = sock.getInputStream().read(buf)
                    if (read > 0) {
                        val chunk = buf.copyOf(read)
                        val sendBuf = DataChannel.Buffer(ByteBuffer.wrap(chunk), true)
                        channel.send(sendBuf)
                    } else if (read < 0) {
                        closeAdbSocket()
                    }
                } catch (e: Exception) {
                    closeAdbSocket()
                }
            }
        }
    }

    @Synchronized
    private fun getOrCreateAdbSocket(): Socket? {
        val s = adbSocket
        if (s != null && !s.isClosed && s.isConnected) {
            return s
        }
        return try {
            val newSocket = Socket()
            newSocket.connect(InetSocketAddress("127.0.0.1", 5555), 2000)
            adbSocket = newSocket
            newSocket
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    private fun closeAdbSocket() {
        try {
            adbSocket?.close()
        } catch (_: Exception) {}
        adbSocket = null
    }

    private fun stopAdbForwarder() {
        adbForwardJob?.cancel()
        adbForwardJob = null
        closeAdbSocket()
    }

    fun close() {
        stopAdbForwarder()
        controlChannel?.dispose()
        controlChannel = null
        adbChannel?.dispose()
        adbChannel = null
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
    }
}
