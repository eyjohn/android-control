package com.evdev.androidcontrol.rtc

import android.content.Context
import com.evdev.androidcontrol.model.*
import com.evdev.androidcontrol.service.ControlAccessibilityService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.webrtc.*
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
    private var dataChannel: DataChannel? = null

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
                dataChannel = channel
                setupDataChannel(channel)
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

    private fun setupDataChannel(channel: DataChannel?) {
        channel?.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                onStateChange("DataChannel: ${channel.state()}")
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

    fun close() {
        dataChannel?.dispose()
        dataChannel = null
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
    }
}

