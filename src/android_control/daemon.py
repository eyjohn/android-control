"""Local daemon managing WebRTC peer connection and exposing local IPC socket."""

from __future__ import annotations

import asyncio
import json
import os
import subprocess
import time
import uuid
from pathlib import Path
from typing import Any, Dict, Optional

from android_control.adb_bridge import AdbWebRtcBridge
from android_control.protocol import RpcError, RpcResponse
from android_control.rtc import WebRtcHost
from android_control.signaling.base import SessionOffer, SignalingProvider
from android_control.signaling.firebase import FirebaseSignaling
from android_control.signaling.local import LocalFileSignaling

SOCKET_PATH = Path("_scratch/control.sock")
STATE_PATH = Path("_scratch/session.json")


class ControlDaemon:
    def __init__(
        self,
        reason: str = "AI Agent Remote Control",
        ttl_seconds: int = 600,
        provider_name: str = "firebase",
    ):
        self.reason = reason
        self.ttl_seconds = ttl_seconds
        self.provider_name = provider_name
        self.session_id = f"sess-{uuid.uuid4().hex[:8]}"
        self.host = WebRtcHost()
        self.adb_bridge = AdbWebRtcBridge(self.host, local_port=55555)
        self.signaling: SignalingProvider = self._resolve_signaling()
        self.server: Optional[asyncio.Server] = None
        self.start_time: float = 0
        self.device_info: Dict[str, Any] = {}
        self._running = False

    def _resolve_signaling(self) -> SignalingProvider:
        if self.provider_name == "firebase":
            fb = FirebaseSignaling()
            if fb.is_configured:
                return fb
        return LocalFileSignaling()

    async def start(self, wait_timeout: float = 120.0) -> None:
        """Publishes offer, waits for device answer, establishes WebRTC, and binds IPC socket."""
        self.start_time = time.time()
        self._running = True

        # 1. Create WebRTC offer
        offer_sdp = await self.host.create_offer()
        offer = SessionOffer(
            session_id=self.session_id,
            sdp=offer_sdp,
            reason=self.reason,
            ttl_seconds=self.ttl_seconds,
            timestamp_ms=int(time.time() * 1000),
        )

        # 2. Publish offer
        await self.signaling.publish_offer(offer)

        # 3. Await answer
        answer = await self.signaling.wait_for_answer(self.session_id, timeout_seconds=wait_timeout)
        if not answer or not answer.approved:
            await self.host.close()
            await self.signaling.close_session(self.session_id)
            raise TimeoutError("Approval timeout or rejected by device.")

        self.device_info = {
            "device_name": answer.device_name,
            "session_id": self.session_id,
            "approved_at": answer.timestamp_ms,
        }

        # 4. Connect WebRTC
        await self.host.apply_answer(answer.sdp)
        await self.host.wait_until_connected(timeout=20.0)

        # 5. Start ADB tunnel and auto-connect
        try:
            await self.adb_bridge.start()
            subprocess.run(["adb", "connect", "127.0.0.1:55555"], capture_output=True)
        except Exception:
            pass

        # 6. Start local IPC server
        SOCKET_PATH.parent.mkdir(parents=True, exist_ok=True)
        if SOCKET_PATH.exists():
            SOCKET_PATH.unlink()

        self.server = await asyncio.start_unix_server(self._handle_client, path=str(SOCKET_PATH))
        self._save_state()

        # 6. TTL Monitor loop
        asyncio.create_task(self._ttl_monitor())

        # 7. Auto-renegotiate connection health monitor
        self._was_connected = True
        self._renegotiating = False
        asyncio.create_task(self._connection_monitor())

    def _save_state(self) -> None:
        state = {
            "session_id": self.session_id,
            "pid": os.getpid(),
            "start_time": self.start_time,
            "ttl_seconds": self.ttl_seconds,
            "reason": self.reason,
            "device_info": self.device_info,
            "socket": str(SOCKET_PATH),
        }
        STATE_PATH.write_text(json.dumps(state, indent=2))

    async def _ttl_monitor(self) -> None:
        while self._running:
            elapsed = time.time() - self.start_time
            if elapsed >= self.ttl_seconds:
                await self.stop()
                break
            await asyncio.sleep(1.0)

    async def _connection_monitor(self) -> None:
        """Monitors WebRTC connection health and auto-renegotiates
        over signaling channel if lost."""
        while self._running:
            await asyncio.sleep(2.0)
            if self._renegotiating:
                continue
            if not self.host.is_ready and self._was_connected:
                self._renegotiating = True
                try:
                    await self._auto_renegotiate()
                except Exception:
                    pass
                finally:
                    self._renegotiating = False

    async def _auto_renegotiate(self) -> None:
        """Performs automatic ICE restart / renegotiation via open signaling channel."""
        try:
            reneg_offer = await self.host.renegotiate_offer()
            await self.signaling.publish_renegotiation_offer(self.session_id, reneg_offer)
            answer_sdp = await self.signaling.wait_for_renegotiation_answer(
                self.session_id, timeout_seconds=30.0
            )
            if answer_sdp:
                await self.host.apply_answer(answer_sdp)
                await self.host.wait_until_connected(timeout=20.0)
                self._was_connected = True
        except Exception:
            pass

    async def _handle_client(
        self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter
    ) -> None:
        try:
            line = await reader.readline()
            if not line:
                writer.close()
                return

            req = json.loads(line.decode("utf-8"))
            method = req.get("method")
            params = req.get("params", {})
            req_id = req.get("id", "req-0")

            if method == "session.disconnect":
                resp = RpcResponse(id=req_id, result={"ok": True, "message": "Disconnected"})
                writer.write(resp.model_dump_json().encode("utf-8") + b"\n")
                await writer.drain()
                writer.close()
                asyncio.create_task(self.stop())
                return

            if method == "session.status":
                remaining = max(0, int(self.ttl_seconds - (time.time() - self.start_time)))
                resp = RpcResponse(
                    id=req_id,
                    result={
                        "connected": self.host.is_ready,
                        "session_id": self.session_id,
                        "remaining_ttl_seconds": remaining,
                        "reason": self.reason,
                        "device": self.device_info,
                    },
                )
                writer.write(resp.model_dump_json().encode("utf-8") + b"\n")
                await writer.drain()
                writer.close()
                return

            # Forward to phone via WebRTC DataChannel
            rpc_res = await self.host.send_rpc(method=method, params=params, timeout=20.0)
            writer.write(rpc_res.model_dump_json().encode("utf-8") + b"\n")
            await writer.drain()
        except Exception as e:
            err_resp = RpcResponse(
                id=req.get("id", "req-err") if "req" in locals() else "req-err",
                error=RpcError(code=-32000, message=str(e)),
            )
            writer.write(err_resp.model_dump_json().encode("utf-8") + b"\n")
            await writer.drain()
        finally:
            writer.close()

    async def stop(self) -> None:
        self._running = False
        try:
            subprocess.run(["adb", "disconnect", "127.0.0.1:55555"], capture_output=True)
            await self.adb_bridge.stop()
        except Exception:
            pass

        if self.server:
            self.server.close()
            await self.server.wait_closed()
            self.server = None

        await self.host.close()
        await self.signaling.close_session(self.session_id)

        if SOCKET_PATH.exists():
            SOCKET_PATH.unlink()
        if STATE_PATH.exists():
            STATE_PATH.unlink()


async def client_rpc(
    method: str, params: Optional[Dict[str, Any]] = None, timeout: float = 25.0
) -> Dict[str, Any]:
    """Helper for CLI commands to dispatch RPC to local daemon via Unix socket."""
    if not SOCKET_PATH.exists():
        raise RuntimeError("No active session. Run 'android-ctl request' first.")

    reader, writer = await asyncio.open_unix_connection(str(SOCKET_PATH))
    req_id = f"cli-{uuid.uuid4().hex[:6]}"
    req = {"jsonrpc": "2.0", "id": req_id, "method": method, "params": params or {}}
    writer.write(json.dumps(req).encode("utf-8") + b"\n")
    await writer.drain()

    line = await asyncio.wait_for(reader.readline(), timeout=timeout)
    writer.close()
    await writer.wait_closed()

    data = json.loads(line.decode("utf-8"))
    if "error" in data and data["error"]:
        raise RuntimeError(f"RPC Error [{data['error']['code']}]: {data['error']['message']}")
    return data.get("result", {})
