"""WebRTC PeerConnection and DataChannel RPC transport using aiortc."""

from __future__ import annotations

import asyncio
import json
import uuid
from typing import Any, Callable, Dict, Optional

from aiortc import (
    RTCConfiguration,
    RTCIceServer,
    RTCPeerConnection,
    RTCSessionDescription,
)

from android_control.protocol import RpcRequest, RpcResponse


class WebRtcHost:
    def __init__(
        self,
        stun_servers: Optional[list[str]] = None,
        on_state_change: Optional[Callable[[str], None]] = None,
    ):
        servers = stun_servers or ["stun:stun.l.google.com:19302"]
        self.config = RTCConfiguration(iceServers=[RTCIceServer(urls=servers)])
        self.pc: Optional[RTCPeerConnection] = None
        self.channel: Optional[Any] = None
        self.on_state_change = on_state_change
        self._pending_responses: Dict[str, asyncio.Future[RpcResponse]] = {}
        self._channel_open_event = asyncio.Event()
        self._connected = False

    async def create_offer(self) -> str:
        """Creates RTCPeerConnection, sets up DataChannel, and returns complete SDP offer."""
        self.pc = RTCPeerConnection(configuration=self.config)

        @self.pc.on("connectionstatechange")
        async def on_connection_state_change():
            if self.pc:
                state = self.pc.connectionState
                if self.on_state_change:
                    self.on_state_change(state)
                if state in ["connected", "completed"]:
                    self._connected = True
                elif state in ["failed", "closed"]:
                    self._connected = False

        self.channel = self.pc.createDataChannel("control", ordered=True)

        @self.channel.on("open")
        def on_open():
            self._channel_open_event.set()

        @self.channel.on("message")
        def on_message(message):
            self._handle_message(message)

        @self.channel.on("close")
        def on_close():
            self._channel_open_event.clear()
            self._connected = False

        offer = await self.pc.createOffer()
        await self.pc.setLocalDescription(offer)

        # Wait briefly for STUN candidates to gather into local description
        for _ in range(15):
            if self.pc.iceGatheringState == "complete":
                break
            await asyncio.sleep(0.1)

        if not self.pc.localDescription:
            raise RuntimeError("Failed to generate local SDP description")

        return self.pc.localDescription.sdp

    async def apply_answer(self, answer_sdp: str) -> None:
        """Applies remote SDP answer from device and waits for DataChannel open."""
        if not self.pc:
            raise RuntimeError("PeerConnection not initialized. Call create_offer first.")

        answer = RTCSessionDescription(sdp=answer_sdp, type="answer")
        await self.pc.setRemoteDescription(answer)

    async def wait_until_connected(self, timeout: float = 30.0) -> None:
        """Wait until DataChannel is open and ready to transmit."""
        await asyncio.wait_for(self._channel_open_event.wait(), timeout=timeout)

    @property
    def is_ready(self) -> bool:
        return self.channel is not None and self.channel.readyState == "open"

    def _handle_message(self, message: Any) -> None:
        try:
            if isinstance(message, bytes):
                text = message.decode("utf-8")
            else:
                text = str(message)

            data = json.loads(text)
            resp = RpcResponse.model_validate(data)
            future = self._pending_responses.pop(resp.id, None)
            if future and not future.done():
                future.set_result(resp)
        except Exception:
            # Drop malformed response or log
            pass

    async def send_rpc(
        self,
        method: str,
        params: Optional[Dict[str, Any]] = None,
        timeout: float = 15.0,
    ) -> RpcResponse:
        """Sends a JSON-RPC request over WebRTC DataChannel and awaits response."""
        if not self.is_ready or not self.channel:
            raise RuntimeError("WebRTC DataChannel is not open.")

        req_id = f"req-{uuid.uuid4().hex[:8]}"
        req = RpcRequest(id=req_id, method=method, params=params or {})

        loop = asyncio.get_running_loop()
        future: asyncio.Future[RpcResponse] = loop.create_future()
        self._pending_responses[req_id] = future

        payload = req.model_dump_json()
        self.channel.send(payload)

        try:
            return await asyncio.wait_for(future, timeout=timeout)
        except asyncio.TimeoutError:
            self._pending_responses.pop(req_id, None)
            raise TimeoutError(f"RPC method '{method}' timed out after {timeout}s")

    async def close(self) -> None:
        """Closes DataChannel and PeerConnection."""
        self._connected = False
        self._channel_open_event.clear()

        # Cancel any pending futures
        for fut in self._pending_responses.values():
            if not fut.done():
                fut.cancel()
        self._pending_responses.clear()

        if self.channel:
            try:
                self.channel.close()
            except Exception:
                pass
            self.channel = None

        if self.pc:
            try:
                await self.pc.close()
            except Exception:
                pass
            self.pc = None
