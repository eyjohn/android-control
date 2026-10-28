"""Local TCP forwarder exposing Android adbd over WebRTC DataChannels with multi-socket support."""

from __future__ import annotations

import asyncio
import uuid
from typing import Any, Callable, Optional

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
        self.active_tasks: set[asyncio.Task[Any]] = set()

    async def start(self) -> None:
        """Starts local TCP listener bridging traffic to dynamic WebRTC ADB DataChannels."""
        self.server = await asyncio.start_server(
            self._handle_client,
            host="127.0.0.1",
            port=self.local_port,
        )
        rprint(f"[bold green]✔ ADB WebRTC bridge listening on 127.0.0.1:{self.local_port}[/]")

    async def _handle_client(
        self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter
    ) -> None:
        task = asyncio.current_task()
        if task:
            self.active_tasks.add(task)
            task.add_done_callback(self.active_tasks.discard)

        conn_id = uuid.uuid4().hex[:6]
        rprint(f"[bold cyan]==>[/] Client [{conn_id}] connected to port {self.local_port}")

        dc = self.host.create_adb_channel(conn_id)
        dc_open_event = asyncio.Event()

        @dc.on("open")
        def on_open():
            dc_open_event.set()

        @dc.on("message")
        def on_message(message):
            if self.on_activity:
                self.on_activity()
            if not writer.is_closing():
                data = message.encode("utf-8") if isinstance(message, str) else message
                writer.write(data)
                asyncio.create_task(writer.drain())

        @dc.on("close")
        def on_close():
            if not writer.is_closing():
                writer.close()

        try:
            # Wait briefly for dynamic DataChannel to open via DCEP
            await asyncio.wait_for(dc_open_event.wait(), timeout=8.0)
            while not reader.at_eof() and not writer.is_closing() and dc.readyState == "open":
                # Backpressure: wait if aiortc buffer has accumulated too much pending data
                while dc.bufferedAmount > 1_000_000:
                    await asyncio.sleep(0.01)
                chunk = await reader.read(16 * 1024)
                if not chunk:
                    break
                if self.on_activity:
                    self.on_activity()
                dc.send(chunk)
                await asyncio.sleep(0)
        except Exception:
            pass
        finally:
            writer.close()
            try:
                await writer.wait_closed()
            except Exception:
                pass
            try:
                dc.close()
            except Exception:
                pass
            rprint(f"[bold yellow]==>[/] Local ADB client [{conn_id}] disconnected.")

    async def stop(self) -> None:
        if self.server:
            self.server.close()
            await self.server.wait_closed()
            self.server = None

        for t in list(self.active_tasks):
            t.cancel()
        self.active_tasks.clear()
