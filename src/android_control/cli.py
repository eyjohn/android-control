"""Typer CLI interface for android-ctl."""

from __future__ import annotations

import asyncio
import base64
import json
import sys
from pathlib import Path
from typing import Optional

import typer
from rich import print as rprint
from rich.console import Console

from android_control.adb_driver import AdbDriver
from android_control.daemon import ControlDaemon, client_rpc
from android_control.protocol import (
    UiElement,
    compute_layout_diff,
    resolve_node_target,
)

app = typer.Typer(
    name="android-ctl",
    help="Zero-daemon pure AccessibilityService phone control over WebRTC DataChannel",
    no_args_is_help=True,
)
console = Console()


def run_async(coro):
    return asyncio.run(coro)


def get_backend():
    """Returns 'daemon' if active WebRTC session, otherwise 'adb' if ADB device is connected."""
    from android_control.daemon import SOCKET_PATH

    if SOCKET_PATH.exists():
        return "daemon"
    adb = AdbDriver()
    if adb.is_connected():
        return "adb"
    return "none"


@app.command()
def wifi(
    port: int = typer.Option(5555, "--port", "-p", help="TCP port for ADB over Wi-Fi"),
):
    """Enable ADB over Wi-Fi on connected USB device, discover IP, and connect."""
    adb = AdbDriver()
    if not adb.is_connected():
        rprint("[bold red]Error:[/] No authorized device found. Unlock phone and tap Allow first.")
        sys.exit(1)

    ip = adb.get_wlan_ip()
    if not ip:
        rprint("[bold red]Error:[/] Could not detect phone Wi-Fi IP. Ensure phone is on Wi-Fi.")
        sys.exit(1)

    rprint(f"[bold cyan]==>[/] Phone Wi-Fi IP: [green]{ip}[/]")
    rprint(f"[bold cyan]==>[/] Switching adbd to TCP/IP port {port}...")
    adb.enable_tcpip(port)

    rprint(f"[bold cyan]==>[/] Connecting to {ip}:{port}...")
    if adb.connect(ip, port):
        rprint(f"[bold green]✔ Connected over Wi-Fi to {ip}:{port}![/] USB can now be unplugged.")
    else:
        rprint(f"[bold yellow]Connect failed. Try:[/] adb connect {ip}:{port}")


@app.command()
def request(
    reason: str = typer.Option(
        "AI Agent Control", "--reason", "-r", help="Reason for requesting phone control"
    ),
    ttl: str = typer.Option("10m", "--ttl", help="Session time-to-live (e.g. 5m, 10m, 30m, 300s)"),
    provider: str = typer.Option(
        "firebase", "--provider", "-p", help="Signaling provider (firebase or local)"
    ),
    wait: int = typer.Option(120, "--wait", "-w", help="Seconds to wait for user approval"),
):
    """Request on-approval phone control session and establish WebRTC DataChannel."""
    # Parse TTL
    ttl_seconds = 600
    ttl = ttl.strip().lower()
    if ttl.endswith("m"):
        ttl_seconds = int(ttl[:-1]) * 60
    elif ttl.endswith("s"):
        ttl_seconds = int(ttl[:-1])
    else:
        ttl_seconds = int(ttl)

    daemon = ControlDaemon(reason=reason, ttl_seconds=ttl_seconds, provider_name=provider)
    rprint(
        f"[bold cyan]==>[/] Requesting session (SessionID: [yellow]{daemon.session_id}[/], "
        f"TTL: {ttl_seconds}s)..."
    )
    rprint(f"[bold cyan]==>[/] Reason: [italic]{reason}[/]")
    rprint(f"[bold cyan]==>[/] Waiting up to {wait}s for approval...")

    try:
        run_async(daemon.start(wait_timeout=float(wait)))
        rprint("[bold green]✔ Approved & Connected![/] WebRTC DataChannel is ready.")
        rprint(f"Device: {daemon.device_info.get('device_name', 'Pixel 9a')}")
    except Exception as e:
        rprint(f"[bold red]✘ Connection failed:[/] {e}")
        sys.exit(1)


@app.command()
def status():
    """Print connection status, remaining TTL, and device information."""
    backend = get_backend()
    if backend == "daemon":
        try:
            res = run_async(client_rpc("session.status"))
            rprint(json.dumps(res, indent=2))
            return
        except Exception:
            pass
    elif backend == "adb":
        adb = AdbDriver()
        pkg = adb.get_foreground_package()
        rprint(
            json.dumps({"driver": "adb", "connected": True, "foreground_package": pkg}, indent=2)
        )
        return

    rprint("[bold red]Offline:[/] No active WebRTC session and no connected ADB device.")
    sys.exit(1)


@app.command()
def layout(
    flat: bool = typer.Option(True, "--flat/--hierarchical", help="Return flat element list"),
    full: bool = typer.Option(
        False, "--full", help="Include non-interactive and zero-area elements"
    ),
    diff: bool = typer.Option(False, "--diff", help="Diff against last layout to minimize tokens"),
    output: Optional[Path] = typer.Option(None, "-o", "--output", help="Save layout JSON to file"),
):
    """Dump UI layout hierarchy or interactive elements."""
    backend = get_backend()
    if backend == "none":
        rprint("[bold red]Error:[/] No active WebRTC session or connected ADB device.")
        sys.exit(1)

    try:
        if backend == "adb":
            res = AdbDriver().layout(flat=flat, full=full).model_dump()
        else:
            res = run_async(client_rpc("layout", {"flat": flat, "full": full}))

        cache_path = Path("_scratch/last_layout.json")

        if diff and cache_path.exists():
            try:
                old_data = json.loads(cache_path.read_text())
                old_elems = [UiElement.model_validate(e) for e in old_data.get("elements", [])]
                new_elems = [UiElement.model_validate(e) for e in res.get("elements", [])]
                diff_res = compute_layout_diff(old_elems, new_elems)
                diff_res["package"] = res.get("package") or res.get("package_name")
                rprint(json.dumps(diff_res, indent=2))
                return
            except Exception:
                pass

        # Cache layout
        cache_path.parent.mkdir(parents=True, exist_ok=True)
        cache_path.write_text(json.dumps(res, indent=2))

        if output:
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_text(json.dumps(res, indent=2))
            rprint(f"Saved layout to {output}")
        else:
            rprint(json.dumps(res, indent=2))
    except Exception as e:
        rprint(f"[bold red]Error:[/] {e}")
        sys.exit(1)


@app.command()
def screen(
    output: Path = typer.Option(
        Path("_scratch/screen.png"), "-o", "--output", help="Path to save screenshot PNG"
    ),
    annotate: bool = typer.Option(
        True, "--annotate/--no-annotate", help="Draw #N numbered bounding boxes"
    ),
    quality: int = typer.Option(80, "--quality", help="Image quality compression"),
):
    """Capture phone screenshot and element coordinate map."""
    backend = get_backend()
    if backend == "none":
        rprint("[bold red]Error:[/] No active WebRTC session or connected ADB device.")
        sys.exit(1)

    try:
        if backend == "adb":
            res = AdbDriver().screen(annotate=annotate, quality=quality).model_dump()
        else:
            res = run_async(client_rpc("screen", {"annotate": annotate, "quality": quality}))

        img_b64 = res.get("image_base64", "")
        if not img_b64:
            raise RuntimeError("No image data returned from device")

        img_bytes = base64.b64decode(img_b64)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(img_bytes)

        # Save annotation map alongside image
        map_path = output.with_suffix(output.suffix + ".map.json")
        annotations = res.get("annotations", {})
        map_path.write_text(json.dumps(annotations, indent=2))

        rprint(f"[bold green]✔ Screenshot saved:[/] {output} ({len(img_bytes)} bytes)")
        rprint(f"[bold green]✔ Annotation map saved:[/] {map_path} ({len(annotations)} elements)")
    except Exception as e:
        rprint(f"[bold red]Error:[/] {e}")
        sys.exit(1)


@app.command()
def resolve(
    screen_path: Path = typer.Option(
        Path("_scratch/screen.png"), "--screen", help="Screenshot path"
    ),
    string: str = typer.Option(..., "--string", "-s", help="Query string, e.g. 'tap #14'"),
):
    """Resolve element tag like '#14' from screen annotation map to x y coordinates."""
    map_path = screen_path.with_suffix(screen_path.suffix + ".map.json")
    if not map_path.exists():
        rprint(f"[bold red]Error:[/] Map file {map_path} not found.")
        sys.exit(1)

    try:
        annotations = json.loads(map_path.read_text())
        coords = resolve_node_target(string, annotations)
        if not coords:
            # Try searching in string
            for part in string.split():
                if part.startswith("#"):
                    coords = resolve_node_target(part, annotations)
                    if coords:
                        break
        if coords:
            rprint(f"{coords[0]} {coords[1]}")
        else:
            rprint(f"[bold red]Error:[/] Could not resolve '{string}' in {map_path}")
            sys.exit(1)
    except Exception as e:
        rprint(f"[bold red]Error:[/] {e}")
        sys.exit(1)


@app.command()
def tap(
    x: Optional[int] = typer.Argument(None, help="X coordinate"),
    y: Optional[int] = typer.Argument(None, help="Y coordinate"),
    node: Optional[int] = typer.Option(
        None, "--node", "-n", help="Node index from layout/screenshot"
    ),
):
    """Tap coordinate or node index."""
    backend = get_backend()
    if backend == "none":
        rprint("[bold red]Error:[/] No active WebRTC session or connected ADB device.")
        sys.exit(1)

    try:
        if backend == "adb":
            adb = AdbDriver()
            if node is not None:
                map_path = Path("_scratch/screen.png.map.json")
                if map_path.exists():
                    annotations = json.loads(map_path.read_text())
                    coords = resolve_node_target(str(node), annotations)
                    if coords:
                        x, y = coords[0], coords[1]
                if x is None or y is None:
                    layout_data = adb.layout()
                    elem = next((e for e in layout_data.elements if e.index == node), None)
                    if elem and len(elem.center) == 2:
                        x, y = elem.center[0], elem.center[1]
            if x is None or y is None:
                rprint("[bold red]Error:[/] Specify coordinates x y or valid --node <index>")
                sys.exit(1)
            success = adb.tap(x, y)
            rprint(f"[bold green]✔ Tapped ({x}, {y}):[/] ok={success}")
        else:
            params = {}
            if node is not None:
                params["node_index"] = node
            elif x is not None and y is not None:
                params["x"] = x
                params["y"] = y
            else:
                rprint("[bold red]Error:[/] Specify either x y coordinates or --node <index>")
                sys.exit(1)

            res = run_async(client_rpc("tap", params))
            rprint(f"[bold green]✔ Tapped:[/] {res}")
    except Exception as e:
        rprint(f"[bold red]Error:[/] {e}")
        sys.exit(1)


@app.command("long-press")
def long_press(
    x: int = typer.Argument(..., help="X coordinate"),
    y: int = typer.Argument(..., help="Y coordinate"),
    duration_ms: int = typer.Option(600, "--duration", "-d", help="Long-press duration in ms"),
):
    """Long-press coordinates."""
    backend = get_backend()
    if backend == "none":
        rprint("[bold red]Error:[/] No active WebRTC session or connected ADB device.")
        sys.exit(1)

    try:
        if backend == "adb":
            success = AdbDriver().tap(x, y, duration_ms=duration_ms)
            rprint(f"[bold green]✔ Long-pressed ({x}, {y}):[/] ok={success}")
        else:
            res = run_async(client_rpc("tap", {"x": x, "y": y, "duration_ms": duration_ms}))
            rprint(f"[bold green]✔ Long-pressed:[/] {res}")
    except Exception as e:
        rprint(f"[bold red]Error:[/] {e}")
        sys.exit(1)


@app.command()
def swipe(
    x1: int = typer.Argument(..., help="Start X"),
    y1: int = typer.Argument(..., help="Start Y"),
    x2: int = typer.Argument(..., help="End X"),
    y2: int = typer.Argument(..., help="End Y"),
    duration_ms: int = typer.Option(300, "--duration", "-d", help="Duration in ms"),
):
    """Swipe gesture between coordinates."""
    backend = get_backend()
    if backend == "none":
        rprint("[bold red]Error:[/] No active WebRTC session or connected ADB device.")
        sys.exit(1)

    try:
        if backend == "adb":
            success = AdbDriver().swipe(x1, y1, x2, y2, duration_ms=duration_ms)
            rprint(f"[bold green]✔ Swiped ({x1},{y1}) -> ({x2},{y2}):[/] ok={success}")
        else:
            res = run_async(
                client_rpc(
                    "swipe", {"x1": x1, "y1": y1, "x2": x2, "y2": y2, "duration_ms": duration_ms}
                )
            )
            rprint(f"[bold green]✔ Swiped:[/] {res}")
    except Exception as e:
        rprint(f"[bold red]Error:[/] {e}")
        sys.exit(1)


@app.command()
def text(
    content: str = typer.Argument(..., help="Text to input"),
    clear: bool = typer.Option(False, "--clear", help="Clear field before typing"),
    submit: bool = typer.Option(False, "--submit", help="Press Enter/Submit after typing"),
    node: Optional[int] = typer.Option(None, "--node", "-n", help="Target node index"),
):
    """Input text into focused field or specified node."""
    backend = get_backend()
    if backend == "none":
        rprint("[bold red]Error:[/] No active WebRTC session or connected ADB device.")
        sys.exit(1)

    try:
        if backend == "adb":
            adb = AdbDriver()
            if node is not None:
                # Tap node first to focus
                map_path = Path("_scratch/screen.png.map.json")
                if map_path.exists():
                    annotations = json.loads(map_path.read_text())
                    coords = resolve_node_target(str(node), annotations)
                    if coords:
                        adb.tap(coords[0], coords[1])
            success = adb.text(content, clear=clear, submit=submit)
            rprint(f"[bold green]✔ Entered text:[/] ok={success}")
        else:
            params = {"text": content, "clear": clear, "submit": submit}
            if node is not None:
                params["node_index"] = node
            res = run_async(client_rpc("text", params))
            rprint(f"[bold green]✔ Entered text:[/] {res}")
    except Exception as e:
        rprint(f"[bold red]Error:[/] {e}")
        sys.exit(1)


@app.command()
def key(
    action: str = typer.Argument(
        ...,
        help="Global key action: BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, LOCK",
    ),
):
    """Trigger system navigation or global hardware action."""
    action = action.upper()
    backend = get_backend()
    if backend == "none":
        rprint("[bold red]Error:[/] No active WebRTC session or connected ADB device.")
        sys.exit(1)

    try:
        if backend == "adb":
            success = AdbDriver().key(action)
            rprint(f"[bold green]✔ Key action {action}:[/] ok={success}")
        else:
            res = run_async(client_rpc("key", {"action": action}))
            rprint(f"[bold green]✔ Key action {action}:[/] {res}")
    except Exception as e:
        rprint(f"[bold red]Error:[/] {e}")
        sys.exit(1)


@app.command()
def launch(
    target: str = typer.Argument(..., help="Package name (e.g. com.whatsapp) or URI intent"),
):
    """Launch an application or URI intent."""
    backend = get_backend()
    if backend == "none":
        rprint("[bold red]Error:[/] No active WebRTC session or connected ADB device.")
        sys.exit(1)

    try:
        if backend == "adb":
            success = AdbDriver().launch(target)
            rprint(f"[bold green]✔ Launched {target}:[/] ok={success}")
        else:
            res = run_async(client_rpc("app.launch", {"package": target}))
            rprint(f"[bold green]✔ Launched {target}:[/] {res}")
    except Exception as e:
        rprint(f"[bold red]Error:[/] {e}")
        sys.exit(1)


@app.command()
def packages():
    """List launchable applications on device."""
    try:
        res = run_async(client_rpc("app.list"))
        rprint(json.dumps(res, indent=2))
    except Exception as e:
        rprint(f"[bold red]Error:[/] {e}")
        sys.exit(1)


@app.command()
def disconnect():
    """Cleanly terminate the active remote session."""
    try:
        res = run_async(client_rpc("session.disconnect"))
        rprint(f"[bold yellow]✔ Disconnected:[/] {res}")
    except Exception as e:
        rprint(f"[bold red]Error:[/] {e}")
        sys.exit(1)


def main():
    app()


if __name__ == "__main__":
    main()
