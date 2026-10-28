"""WebRTC PeerConnection and dual DataChannel (control + adb) transport using aiortc."""

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

from android_control.config import get_default_ice_servers
from android_control.protocol import RpcRequest, RpcResponse


class WebRtcHost:
    def __init__(
        self,
        stun_servers: Optional[list[str]] = None,
        ice_servers: Optional[list[Dict[str, Any]]] = None,
        on_state_change: Optional[Callable[[str], None]] = None,
        on_adb_data: Optional[Callable[[bytes], None]] = None,
    ):
        servers = stun_servers or ["stun:stun.l.google.com:19302"]
        rtc_ice_servers = [RTCIceServer(urls=servers)]
        effective_ice = ice_servers if ice_servers is not None else get_default_ice_servers()
        if effective_ice:
            for s in effective_ice:
                raw_urls = s.get("urls") or s.get("url", [])
                urls = [raw_urls] if isinstance(raw_urls, str) else list(raw_urls)
                rtc_ice_servers.append(
                    RTCIceServer(
                        urls=urls,
                        username=s.get("username"),
                        credential=s.get("credential") or s.get("password"),
                    )
                )
        self.config = RTCConfiguration(iceServers=rtc_ice_servers)
        self.pc: Optional[RTCPeerConnection] = None
        self.channel: Optional[Any] = None
        self.adb_channel: Optional[Any] = None
        self.on_state_change = on_state_change
        self.on_adb_data = on_adb_data
        self._pending_responses: Dict[str, asyncio.Future[RpcResponse]] = {}
        self._channel_open_event = asyncio.Event()
        self._connected = False

    async def create_offer(self) -> str:
        """Creates RTCPeerConnection, sets up control + adb DataChannels, returns SDP offer."""
        self.pc = RTCPeerConnection(configuration=self.config)

        @self.pc.on("connectionstatechange")
        async def on_connection_state_change():
            if self.pc:
                state = self.pc.connectionState
                if self.on_state_change:
                    self.on_state_change(state)
                if state in ["connected", "completed"]:
                    self._connected = True
                elif state in ["failed", "closed", "disconnected"]:
                    self._connected = False
                    self._channel_open_event.clear()

        # 1. Control channel (JSON-RPC)
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

    async def renegotiate_offer(self) -> str:
        """Generates a new SDP offer for renegotiation upon connection loss."""
        if not self.pc or self.pc.connectionState in ["closed", "failed"]:
            await self.close()
            return await self.create_offer()

        offer = await self.pc.createOffer()
        await self.pc.setLocalDescription(offer)
        for _ in range(15):
            if self.pc.iceGatheringState == "complete":
                break
            await asyncio.sleep(0.1)

        if not self.pc.localDescription:
            raise RuntimeError("Failed to generate local SDP description for renegotiation")
        return self.pc.localDescription.sdp

    async def apply_answer(self, answer_sdp: str) -> None:
        """Applies remote SDP answer from device and waits for DataChannel open."""
        if not self.pc:
            raise RuntimeError("PeerConnection not initialized. Call create_offer first.")

        answer = RTCSessionDescription(sdp=answer_sdp, type="answer")
        await self.pc.setRemoteDescription(answer)

    async def wait_until_connected(self, timeout: float = 30.0) -> None:
        """Wait until control DataChannel is open and ready to transmit."""
        try:
            await asyncio.wait_for(self._channel_open_event.wait(), timeout=timeout)
        except asyncio.TimeoutError:
            ice_state = self.pc.iceConnectionState if self.pc else "unknown"
            conn_state = self.pc.connectionState if self.pc else "unknown"
            raise TimeoutError(
                f"WebRTC DataChannel handshake timed out after {timeout}s "
                f"(ICE state: {ice_state}, connection state: {conn_state})"
            )

    @property
    def is_ready(self) -> bool:
        if not self._connected or not self.pc or not self.channel:
            return False
        if self.pc.connectionState in ["disconnected", "failed", "closed"]:
            return False
        return self.channel.readyState == "open" and self._channel_open_event.is_set()

    def create_adb_channel(self, channel_id: str) -> Any:
        """Dynamically creates an independent ADB DataChannel for a client connection."""
        if not self.pc:
            raise RuntimeError("PeerConnection not initialized")
        return self.pc.createDataChannel(f"adb-{channel_id}", ordered=True)

    def send_adb_bytes(self, data: bytes) -> None:
        """Sends binary data chunk through ADB WebRTC DataChannel to phone."""
        if self.adb_channel and self.adb_channel.readyState == "open":
            self.adb_channel.send(data)

    def _handle_message(self, message: Any) -> None:
        try:
            if isinstance(message, bytes):
                text = message.decode("utf-8")
            else:
                text = str(message)

            data = json.loads(text)
            if "method" in data:
                # Inbound request/notification from device
                method = data.get("method")
                if method == "session.teardown":
                    self._connected = False
                    self._channel_open_event.clear()
                    if self.on_state_change:
                        self.on_state_change("teardown_received")
                return

            resp = RpcResponse.model_validate(data)
            future = self._pending_responses.pop(resp.id, None)
            if future and not future.done():
                future.set_result(resp)
        except Exception:
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
        """Closes DataChannels and PeerConnection."""
        self._connected = False
        self._channel_open_event.clear()

        for fut in self._pending_responses.values():
            if not fut.done():
                fut.cancel()
        self._pending_responses.clear()

        if self.adb_channel:
            try:
                self.adb_channel.close()
            except Exception:
                pass
            self.adb_channel = None

        if self.channel:
            try:
                self.channel.close()
            except Exception:
                pass
            self.channel = None

        if self.pc:
            try:
                await self.pc.close()
                await asyncio.sleep(0.05)
            except Exception:
                pass
            self.pc = None
