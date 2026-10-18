"""Local TCP forwarder exposing Android adbd over WebRTC DataChannel."""

from __future__ import annotations

import asyncio
from typing import Callable, Optional

from rich import print as rprint

from android_control.rtc import WebRtcHost


class AdbWebRtcBridge:
    def __init__(
        self,
        host: WebRtcHost,
        local_port: int = 55555,
        on_activity: Optional[Callable[[], None]] = None,
    ):
        self.host = host
        self.local_port = local_port
        self.on_activity = on_activity
        self.server: Optional[asyncio.Server] = None
        self.active_writer: Optional[asyncio.StreamWriter] = None

        # Wire incoming WebRTC binary bytes to active TCP client
        self.host.on_adb_data = self._on_remote_bytes

    def _on_remote_bytes(self, data: bytes) -> None:
        if self.on_activity:
            self.on_activity()
        if self.active_writer and not self.active_writer.is_closing():
            try:
                self.active_writer.write(data)
            except Exception:
                pass

    async def start(self) -> None:
        """Starts local TCP listener bridging traffic to WebRTC ADB DataChannel."""
        self.server = await asyncio.start_server(
            self._handle_client,
            host="127.0.0.1",
            port=self.local_port,
        )
        rprint(f"[bold green]✔ ADB WebRTC bridge listening on 127.0.0.1:{self.local_port}[/]")

    async def _handle_client(
        self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter
    ) -> None:
        self.active_writer = writer
        rprint("[bold cyan]==>[/] Local ADB client connected to 127.0.0.1 bridge.")

        try:
            while not reader.at_eof() and not writer.is_closing():
                chunk = await reader.read(16 * 1024)
                if not chunk:
                    break
                if self.on_activity:
                    self.on_activity()
                self.host.send_adb_bytes(chunk)
                await asyncio.sleep(0)
        except Exception:
            pass
        finally:
            if self.active_writer == writer:
                self.active_writer = None
            writer.close()
            try:
                await writer.wait_closed()
            except Exception:
                pass
            rprint("[bold yellow]==>[/] Local ADB client disconnected from bridge.")

    async def stop(self) -> None:
        if self.active_writer:
            self.active_writer.close()
            self.active_writer = None

        if self.server:
            self.server.close()
            await self.server.wait_closed()
            self.server = None
