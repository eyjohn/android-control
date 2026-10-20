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
from android_control.config import get_default_ice_servers
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
        turn_server: Optional[str] = None,
        turn_user: Optional[str] = None,
        turn_pass: Optional[str] = None,
        ice_servers: Optional[list[Dict[str, Any]]] = None,
        requested_mode: Optional[str] = None,
        adb_port: int = 55555,
        target_device: Optional[str] = None,
    ):
        self.reason = reason
        self.ttl_seconds = ttl_seconds
        self.provider_name = provider_name
        self.requested_mode = requested_mode
        self.adb_port = adb_port
        self.target_device = target_device
        self.target_device_info: Optional[Dict[str, Any]] = None
        self.session_id = f"sess-{uuid.uuid4().hex[:8]}"
        self.idle_timeout_seconds: float = 300.0  # 5 minutes by default
        self.last_activity_time: float = time.time()

        self.ice_servers: list[Dict[str, Any]] = (
            list(ice_servers)
            if ice_servers is not None
            else get_default_ice_servers(turn_server, turn_user, turn_pass)
        )

        self.host = WebRtcHost(ice_servers=self.ice_servers)
        self.adb_bridge = AdbWebRtcBridge(
            self.host, local_port=self.adb_port, on_activity=self._touch_activity
        )
        self.signaling: SignalingProvider = self._resolve_signaling()
        self.server: Optional[asyncio.Server] = None
        self.start_time: float = 0
        self.device_info: Dict[str, Any] = {}
        self._running = False
        self._stop_event: asyncio.Event = asyncio.Event()

    def _touch_activity(self) -> None:
        self.last_activity_time = time.time()

    def _resolve_signaling(self) -> SignalingProvider:
        if self.provider_name == "firebase":
            fb = FirebaseSignaling()
            if fb.is_configured:
                return fb
        return LocalFileSignaling()

    async def start(self, wait_timeout: float = 300.0) -> None:
        """Publishes offer, waits for device answer, establishes WebRTC, and binds IPC socket."""
        self.start_time = time.time()
        self.last_activity_time = time.time()
        self._running = True

        # 1. Create WebRTC offer
        offer_sdp = await self.host.create_offer()
        offer = SessionOffer(
            session_id=self.session_id,
            sdp=offer_sdp,
            reason=self.reason,
            ttl_seconds=self.ttl_seconds,
            timestamp_ms=int(time.time() * 1000),
            mode=self.requested_mode or "accessibility",
            ice_servers=self.ice_servers,
        )

        # 2. Publish offer
        target_dev = await self.signaling.publish_offer(offer, self.target_device)
        if target_dev:
            self.target_device_info = target_dev

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
            "mode": getattr(answer, "mode", "accessibility"),
        }

        # 4. Connect WebRTC
        await self.host.apply_answer(answer.sdp)
        await self.host.wait_until_connected(timeout=20.0)

        # 5. Start ADB tunnel and auto-connect
        if self.device_info.get("mode") == "adb":
            try:
                await self.adb_bridge.start()
                subprocess.run(
                    ["adb", "connect", f"127.0.0.1:{self.adb_port}"], capture_output=True
                )
            except Exception:
                pass

        # 6. Start local IPC server
        SOCKET_PATH.parent.mkdir(parents=True, exist_ok=True)
        if SOCKET_PATH.exists():
            SOCKET_PATH.unlink()

        self.server = await asyncio.start_unix_server(self._handle_client, path=str(SOCKET_PATH))
        self._save_state()

        # 7. Background monitors: TTL, idle teardown, and liveness heartbeat
        asyncio.create_task(self._ttl_monitor())
        asyncio.create_task(self._idle_monitor())
        asyncio.create_task(self._heartbeat_loop())

    def _save_state(self) -> None:
        state = {
            "session_id": self.session_id,
            "pid": os.getpid(),
            "start_time": self.start_time,
            "ttl_seconds": self.ttl_seconds,
            "reason": self.reason,
            "device_info": self.device_info,
            "mode": self.device_info.get("mode", "accessibility"),
            "connected": self.host.is_ready,
            "socket": str(SOCKET_PATH),
        }
        STATE_PATH.write_text(json.dumps(state, indent=2))

    async def _ttl_monitor(self) -> None:
        while self._running:
            if self.ttl_seconds > 0:
                elapsed = time.time() - self.start_time
                if elapsed >= self.ttl_seconds:
                    await self.stop()
                    break
            await asyncio.sleep(2.0)

    async def _idle_monitor(self) -> None:
        """Tears down WebRTC connection after idle timeout to preserve battery."""
        while self._running:
            await asyncio.sleep(2.0)
            if (
                self.idle_timeout_seconds > 0
                and self.host.is_ready
                and (time.time() - self.last_activity_time >= self.idle_timeout_seconds)
            ):
                await self._teardown_rtc()

    async def _heartbeat_loop(self) -> None:
        """Periodically ping device over WebRTC control channel when connected."""
        failed_count = 0
        while self._running:
            await asyncio.sleep(10.0)
            if self.host.is_ready:
                try:
                    res = await self.host.send_rpc("ping", {}, timeout=4.0)
                    if res.error:
                        failed_count += 1
                    else:
                        failed_count = 0
                except Exception:
                    failed_count += 1

                if failed_count >= 2:
                    failed_count = 0
                    await self._teardown_rtc()

    async def _teardown_rtc(self) -> None:
        """Cleanly tears down WebRTC connection while preserving session IPC."""
        try:
            await self.host.send_rpc("session.teardown", {}, timeout=2.0)
        except Exception:
            pass
        try:
            subprocess.run(["adb", "disconnect", f"127.0.0.1:{self.adb_port}"], capture_output=True)
            await self.adb_bridge.stop()
        except Exception:
            pass
        await self.host.close()
        self.host = WebRtcHost(ice_servers=self.ice_servers)
        self.adb_bridge = AdbWebRtcBridge(
            self.host, local_port=self.adb_port, on_activity=self._touch_activity
        )
        self._save_state()

    async def _connect_on_demand(self) -> None:
        """Wakes device app and connects WebRTC on demand."""
        self._touch_activity()
        # T3: Clean up previous PC before creating a new one to prevent PC leaks
        if self.host.pc is not None:
            await self._teardown_rtc()
        offer_sdp = await self.host.create_offer()
        offer = SessionOffer(
            session_id=self.session_id,
            sdp=offer_sdp,
            reason=self.reason,
            ttl_seconds=0,
            timestamp_ms=int(time.time() * 1000),
            mode=self.requested_mode or "accessibility",
            ice_servers=self.ice_servers,
        )
        await self.signaling.publish_offer(offer, self.target_device)
        answer = await self.signaling.wait_for_answer(self.session_id, timeout_seconds=25.0)
        if not answer or not answer.approved:
            raise TimeoutError("On-demand wake timeout: Device did not respond.")
        await self.host.apply_answer(answer.sdp)
        await self.host.wait_until_connected(timeout=15.0)
        chosen_mode = getattr(answer, "mode", self.device_info.get("mode", "accessibility"))
        self.device_info["mode"] = chosen_mode
        if chosen_mode == "adb":
            try:
                await self.adb_bridge.start()
                subprocess.run(
                    ["adb", "connect", f"127.0.0.1:{self.adb_port}"], capture_output=True
                )
            except Exception:
                pass
        self._touch_activity()
        self._save_state()

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

            self._touch_activity()
            if method == "session.disconnect":
                resp = RpcResponse(id=req_id, result={"ok": True, "message": "Disconnected"})
                writer.write(resp.model_dump_json().encode("utf-8") + b"\n")
                await writer.drain()
                writer.close()
                asyncio.create_task(self.stop())
                return

            if method == "session.status":
                remaining = (
                    max(0, int(self.ttl_seconds - (time.time() - self.start_time)))
                    if self.ttl_seconds > 0
                    else 0
                )
                idle_sec = int(time.time() - self.last_activity_time)
                resp = RpcResponse(
                    id=req_id,
                    result={
                        "connected": self.host.is_ready,
                        "state": "connected" if self.host.is_ready else "standby_idle",
                        "session_id": self.session_id,
                        "idle_seconds": idle_sec,
                        "idle_timeout_seconds": self.idle_timeout_seconds,
                        "remaining_ttl_seconds": remaining,
                        "reason": self.reason,
                        "device": self.device_info,
                    },
                )
                writer.write(resp.model_dump_json().encode("utf-8") + b"\n")
                await writer.drain()
                writer.close()
                return

            # On-demand connect if WebRTC was torn down during idle
            if not self.host.is_ready:
                await self._connect_on_demand()

            # Forward to phone via WebRTC DataChannel
            rpc_res = await self.host.send_rpc(method=method, params=params, timeout=20.0)
            self._touch_activity()
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

    async def wait_until_stopped(self) -> None:
        """Blocks until session is stopped or terminated."""
        await self._stop_event.wait()

    async def stop(self) -> None:
        self._running = False
        self._stop_event.set()
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
