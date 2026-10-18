import asyncio
import json

import pytest
from aiortc import RTCPeerConnection, RTCSessionDescription

from android_control.rtc import WebRtcHost


@pytest.mark.asyncio
async def test_webrtc_host_loopback_rpc():
    # 1. Host side
    host = WebRtcHost()
    offer_sdp = await host.create_offer()

    # 2. Simulated Android device side
    remote_pc = RTCPeerConnection()
    remote_channel = None
    channel_ready = asyncio.Event()

    @remote_pc.on("datachannel")
    def on_datachannel(channel):
        nonlocal remote_channel
        remote_channel = channel

        if channel.readyState == "open":
            channel_ready.set()

        @channel.on("open")
        def on_open():
            channel_ready.set()

        @channel.on("message")
        def on_message(message):
            channel_ready.set()
            # Echo back a JSON-RPC response
            data = json.loads(message)
            resp = {
                "jsonrpc": "2.0",
                "id": data["id"],
                "result": {"ok": True, "method_called": data["method"]},
            }
            channel.send(json.dumps(resp))

    # Apply offer on simulated device and generate answer
    await remote_pc.setRemoteDescription(RTCSessionDescription(sdp=offer_sdp, type="offer"))
    answer = await remote_pc.createAnswer()
    await remote_pc.setLocalDescription(answer)

    # Apply answer on host
    await host.apply_answer(remote_pc.localDescription.sdp)

    # Wait for DataChannel to connect on both sides
    await asyncio.wait_for(host.wait_until_connected(timeout=5.0), timeout=5.0)
    await asyncio.wait_for(channel_ready.wait(), timeout=5.0)

    assert host.is_ready is True

    # 3. Test RPC dispatch
    res = await host.send_rpc("ping", {"test": 123}, timeout=3.0)
    assert res.result is not None
    assert res.result["ok"] is True
    assert res.result["method_called"] == "ping"

    # Clean up
    await host.close()
    await remote_pc.close()
