"""Typer CLI interface for android-ctl."""

from __future__ import annotations

import asyncio
import base64
import json
import subprocess
import sys
from pathlib import Path
from typing import Optional

import typer
from rich import print as rprint
from rich.console import Console

from android_control.adb_driver import AdbDriver
from android_control.config import SOCKET_PATH, STATE_PATH
from android_control.daemon import ControlDaemon, client_rpc
from android_control.protocol import (
    UiElement,
    compute_layout_diff,
    resolve_node_target,
)

app = typer.Typer(
    name="android-ctl",
    help="Serverless remote Android control (AccessibilityService & ADB) over WebRTC DataChannel",
    no_args_is_help=True,
)
screen_app = typer.Typer(
    name="screen",
    help="Capture and inspect the device screen",
    invoke_without_command=True,
)
app.add_typer(screen_app, name="screen")

input_app = typer.Typer(
    name="input",
    help="Send input events (tap, swipe, text, keyevent) mirroring adb shell input",
    no_args_is_help=True,
)
app.add_typer(input_app, name="input")

adb_app = typer.Typer(
    name="adb",
    help="Manage ADB bridge and active proxy tunnel over WebRTC",
    no_args_is_help=True,
)
app.add_typer(adb_app, name="adb")

devices_app = typer.Typer(
    name="devices",
    help="Discover, list, and select registered Android devices",
    invoke_without_command=True,
)
app.add_typer(devices_app, name="devices")

auth_app = typer.Typer(
    name="auth",
    help="Manage Google & Firebase authentication for signaling",
    no_args_is_help=True,
)
app.add_typer(auth_app, name="auth")

console = Console()


def format_relative_time(ts_ms: int) -> str:
    """Formats epoch timestamp in milliseconds to a human-friendly relative string."""
    import time

    if not ts_ms:
        return "never"
    diff_s = int(time.time() - (ts_ms / 1000.0))
    if diff_s < 10:
        return "just now"
    if diff_s < 60:
        return f"{diff_s}s ago"
    diff_m = diff_s // 60
    if diff_m < 60:
        return f"{diff_m}m ago"
    diff_h = diff_m // 60
    if diff_h < 24:
        return f"{diff_h}h ago"
    diff_d = diff_h // 24
    return f"{diff_d}d ago"


def run_async(coro):
    return asyncio.run(coro)


def get_backend(driver: Optional[str] = None):
    """Prioritizes the control method decided by the Android control app in response to request;
    falls back to standalone if no active session."""
    if driver == "a11y":
        return "daemon"
    if driver == "adb":
        return "adb"

    # Active session: check mode chosen by Android control app!
    if SOCKET_PATH.exists():
        if STATE_PATH.exists():
            try:
                state = json.loads(STATE_PATH.read_text())
                chosen = state.get("device_info", {}).get("mode", state.get("mode"))
                adb_port = state.get("adb_port", 55555)
                if chosen == "adb":
                    adb_webrtc = AdbDriver(serial=f"127.0.0.1:{adb_port}")
                    if adb_webrtc.is_connected():
                        return "adb"
                return "daemon"
            except Exception:
                pass
        return "daemon"

    # Alternative: Active WebRTC ADB bridge tunnel
    adb_webrtc = AdbDriver(serial="127.0.0.1:55555")
    if adb_webrtc.is_connected():
        return "adb"

    # Direct fallback if neither WebRTC channel is active
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
def offer(
    out: Path = typer.Option(
        Path("_scratch/session.webrtc"), "--out", "-o", help="Target session offer file"
    ),
    reason: str = typer.Option(
        "AI Agent Remote Control", "--reason", "-r", help="Reason for session"
    ),
    ttl: int = typer.Option(600, "--ttl", help="Session TTL in seconds"),
    turn_server: Optional[str] = typer.Option(
        None, "--turn-server", help="TURN server URL (e.g. turn:relay.example.com:3478)"
    ),
    turn_user: Optional[str] = typer.Option(None, "--turn-user", help="TURN server username"),
    turn_pass: Optional[str] = typer.Option(
        None, "--turn-pass", help="TURN server password/credential"
    ),
):
    """Generate a standalone WebRTC offer payload for out-of-band pairing."""
    import time
    import uuid

    from android_control.config import get_default_ice_servers
    from android_control.rtc import WebRtcHost
    from android_control.signaling.base import SessionOffer

    ice_servers = get_default_ice_servers(turn_server, turn_user, turn_pass)

    host = WebRtcHost(ice_servers=ice_servers)
    offer_sdp = run_async(host.create_offer())
    sess_id = f"sess-{uuid.uuid4().hex[:8]}"
    sess_offer = SessionOffer(
        session_id=sess_id,
        sdp=offer_sdp,
        reason=reason,
        ttl_seconds=ttl,
        timestamp_ms=int(time.time() * 1000),
        ice_servers=ice_servers,
    )
    payload = sess_offer.to_payload()
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(payload)
    rprint(f"[bold green]✔ Generated session offer:[/] {out}")
    rprint(f"[bold cyan]Payload:[/] {payload[:60]}... ({len(payload)} bytes)")
    rprint(f"[bold cyan]Deep link:[/] android-control://approve?data={payload}")


@app.command()
def answer(
    payload: str = typer.Argument(..., help="Answer payload copied from phone app"),
    out: Path = typer.Option(
        Path("_scratch/answer.webrtc"), "--out", "-o", help="Target answer file"
    ),
):
    """Save answer payload copied from phone app to complete out-of-band WebRTC handshake."""
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(payload.strip())
    rprint(f"[bold green]✔ Saved answer payload to {out}[/]")


@devices_app.callback(invoke_without_command=True)
def devices_main(ctx: typer.Context):
    """List registered devices (default) or run subcommands."""
    if ctx.invoked_subcommand is None:
        list_devices()


@devices_app.command("list")
def list_devices():
    """Discover and list all registered devices under the active Google account."""
    from rich.table import Table

    from android_control.config import get_selected_device
    from android_control.signaling.firebase import FirebaseSignaling

    signaling = FirebaseSignaling()
    devices = signaling.discover_devices()
    selected = get_selected_device()

    if not devices:
        rprint("[yellow]No registered devices found for your Google account.[/yellow]")
        rprint("[dim]Open the Android Control companion app on your phone and tap Sign In.[/dim]")
        return

    table = Table(title="Registered Android Devices", title_style="bold cyan")
    table.add_column("Device ID", style="bold green")
    table.add_column("Device Name", style="white")
    table.add_column("Account", style="dim")
    table.add_column("Mode", style="cyan")
    table.add_column("Last Seen", style="magenta")
    table.add_column("Status", style="yellow")

    for i, dev in enumerate(devices):
        dev_id = str(dev.get("device_id", ""))
        dev_name = str(dev.get("device_name", "Unknown"))
        acc = str(dev.get("account", ""))
        mode = str(dev.get("mode", "accessibility"))
        last_seen = format_relative_time(dev.get("updated_at", 0))

        is_selected = selected and dev_id.lower().startswith(selected.lower())
        is_default = not selected and i == 0

        status_text = ""
        if is_selected:
            status_text = "[bold green]✔ Selected[/]"
        elif is_default:
            status_text = "[bold cyan]● Default (Latest)[/]"

        table.add_row(dev_id, dev_name, acc, mode, last_seen, status_text)

    console.print(table)


@devices_app.command("select")
def select_device(
    device_id: str = typer.Argument(..., help="Device ID or prefix to select as default target"),
):
    """Set default device ID for remote control sessions."""
    from android_control.config import set_selected_device
    from android_control.signaling.firebase import FirebaseSignaling

    signaling = FirebaseSignaling()
    dev = signaling.resolve_target_device(device_id)
    if dev and dev.get("device_id"):
        real_id = dev["device_id"]
        dev_name = dev.get("device_name", real_id)
        set_selected_device(real_id)
        rprint(f"[bold green]✔ Selected device:[/] [cyan]{dev_name}[/] ({real_id})")
    else:
        set_selected_device(device_id)
        rprint(f"[bold green]✔ Selected device ID:[/] [cyan]{device_id}[/]")


@devices_app.command("clear")
def clear_device():
    """Clear selected device override (reverts to latest checked-in device)."""
    from android_control.config import set_selected_device

    set_selected_device(None)
    rprint(
        "[bold green]✔ Cleared device override.[/] "
        "Sessions will now target the latest checked-in device."
    )


def _exchange_and_save_token(id_token: str, api_key: str):
    import time
    import urllib.error
    import urllib.request

    from android_control.config import save_auth_session

    url = f"https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key={api_key}"
    data = json.dumps(
        {
            "postBody": f"id_token={id_token}&providerId=google.com",
            "requestUri": "http://localhost",
            "returnIdpCredential": True,
            "returnSecureToken": True,
        }
    ).encode("utf-8")

    req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=10.0) as resp:
            res = json.loads(resp.read().decode("utf-8"))
            fb_token = res.get("idToken")
            refresh_token = res.get("refreshToken")
            email = res.get("email")
            uid = res.get("localId")
            expires_in = int(res.get("expiresIn", 3600))

            session = {
                "id_token": fb_token,
                "refresh_token": refresh_token,
                "email": email,
                "uid": uid,
                "expires_at": time.time() + expires_in,
            }
            save_auth_session(session)
            rprint(
                f"[bold green]✔ Successfully logged in as [bold cyan]{email}[/] (UID: {uid})![/]"
            )
    except urllib.error.HTTPError as e:
        err_body = e.read().decode()
        rprint(f"[bold red]✘ Authentication exchange failed:[/] HTTP {e.code} - {err_body}")
        sys.exit(1)
    except Exception as e:
        rprint(f"[bold red]✘ Authentication error:[/] {e}")
        sys.exit(1)


@auth_app.command("status")
def auth_status():
    """Display active authentication status and credentials."""
    from android_control.signaling.firebase import get_active_credentials

    token, auth_type, email, uid = get_active_credentials()

    if not token or auth_type == "none":
        rprint("[bold red]Not authenticated.[/]")
        rprint("Run [cyan]android-ctl auth login[/] to authenticate via Google Sign-In.")
        sys.exit(1)

    rprint("[bold cyan]==>[/] Active Auth Provider: [bold green]FIREBASE APP AUTH[/]")
    rprint(f"Account Email: [bold]{email}[/]")
    if uid:
        rprint(f"Firebase UID: [cyan]{uid}[/]")


@auth_app.command("logout")
def auth_logout():
    """Clear stored Firebase credentials."""
    from android_control.config import clear_auth_session

    clear_auth_session()
    rprint("[bold green]✔ Logged out from Firebase Auth.[/]")


@auth_app.command("login")
def auth_login(
    token: Optional[str] = typer.Option(
        None, "--token", "-t", help="Manually paste Google ID token"
    ),
    port: int = typer.Option(8085, "--port", "-p", help="Local HTTP port for OAuth callback"),
):
    """Authenticate using Google Sign-In and exchange for Firebase Auth credential."""
    import http.server
    import threading
    import urllib.parse
    import uuid
    import webbrowser

    from android_control.config import (
        get_firebase_api_key,
        get_firebase_web_client_id,
    )

    api_key = get_firebase_api_key()
    web_client_id = get_firebase_web_client_id()

    # 1. Manual token input path
    if token:
        _exchange_and_save_token(token.strip(), api_key)
        return

    # 2. Interactive OAuth loopback flow
    redirect_uri = f"http://127.0.0.1:{port}/oauth2callback"
    auth_url = (
        f"https://accounts.google.com/o/oauth2/v2/auth?"
        f"client_id={urllib.parse.quote(web_client_id)}&"
        f"redirect_uri={urllib.parse.quote(redirect_uri)}&"
        f"response_type=id_token&"
        f"scope=openid%20email%20profile&"
        f"nonce={uuid.uuid4().hex}"
    )

    rprint(f"[bold cyan]==>[/] Opening browser for Google Sign-In on port {port}...")
    rprint(f"[dim]If browser does not open, visit:[/dim]\n[blue underline]{auth_url}[/]\n")

    captured_token = {"id_token": None}
    server_closed = threading.Event()

    class OAuthHandler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            if "oauth2callback" in self.path:
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.end_headers()
                html = """
                <html><body>
                <h2>Completing Android Control authentication...</h2>
                <script>
                if (window.location.hash) {
                    var params = new URLSearchParams(window.location.hash.substring(1));
                    var token = params.get('id_token');
                    if (token) {
                        fetch('/submit_token?id_token=' + encodeURIComponent(token))
                            .then(function() {
                                document.body.innerHTML = '<h2>✔ Done!</h2>';
                            });
                    }
                }
                </script>
                </body></html>
                """
                self.wfile.write(html.encode("utf-8"))
            elif "submit_token" in self.path:
                parsed = urllib.parse.urlparse(self.path)
                qs = urllib.parse.parse_qs(parsed.query)
                captured_token["id_token"] = qs.get("id_token", [None])[0]
                self.send_response(200)
                self.send_header("Content-Type", "text/plain")
                self.end_headers()
                self.wfile.write(b"OK")
                server_closed.set()
            else:
                self.send_response(404)
                self.end_headers()

        def log_message(self, format, *args):
            pass

    server = http.server.HTTPServer(("127.0.0.1", port), OAuthHandler)
    server_thread = threading.Thread(target=server.serve_forever, daemon=True)
    server_thread.start()

    try:
        webbrowser.open(auth_url)
    except Exception:
        pass

    try:
        rprint("[bold yellow]Waiting for browser login...[/] (Press Ctrl+C to abort)")
        server_closed.wait(timeout=180.0)
    except KeyboardInterrupt:
        rprint("\n[yellow]Login cancelled.[/yellow]")
        server.shutdown()
        return

    server.shutdown()

    id_token = captured_token["id_token"]
    if not id_token:
        rprint("[bold red]✘ Login failed:[/] No ID token received.")
        sys.exit(1)

    _exchange_and_save_token(id_token, api_key)


@app.command()
def request(
    reason: str = typer.Option(
        "AI Agent Control", "--reason", "-r", help="Reason for requesting phone control"
    ),
    ttl: str = typer.Option(
        "0", "--ttl", help="Session time-to-live (0 or persistent for unlimited)"
    ),
    provider: str = typer.Option(
        "firebase", "--provider", "-p", help="Signaling provider (firebase or local)"
    ),
    device: Optional[str] = typer.Option(
        None, "--device", "-d", help="Target device ID or prefix (defaults to selected/latest)"
    ),
    wait: int = typer.Option(300, "--wait", "-w", help="Seconds to wait for user approval"),
    turn_server: Optional[str] = typer.Option(
        None, "--turn-server", help="TURN server URL (e.g. turn:relay.example.com:3478)"
    ),
    turn_user: Optional[str] = typer.Option(None, "--turn-user", help="TURN server username"),
    turn_pass: Optional[str] = typer.Option(
        None, "--turn-pass", help="TURN server password/credential"
    ),
):
    """Request on-approval phone control session and establish WebRTC DataChannel."""
    ttl = ttl.strip().lower()
    if ttl in ("0", "persistent", "unlimited", "infinite", "none"):
        ttl_seconds = 0
    elif ttl.endswith("m"):
        ttl_seconds = int(ttl[:-1]) * 60
    elif ttl.endswith("s"):
        ttl_seconds = int(ttl[:-1])
    else:
        ttl_seconds = int(ttl)

    daemon = ControlDaemon(
        reason=reason,
        ttl_seconds=ttl_seconds,
        provider_name=provider,
        turn_server=turn_server,
        turn_user=turn_user,
        turn_pass=turn_pass,
        target_device=device,
    )
    ttl_desc = "persistent" if ttl_seconds == 0 else f"{ttl_seconds}s"
    rprint(
        f"[bold cyan]==>[/] Requesting session (SessionID: [yellow]{daemon.session_id}[/], "
        f"Type: [green]{ttl_desc}[/])..."
    )
    rprint(f"[bold cyan]==>[/] Waiting up to {wait}s for connection handshake...")

    try:

        async def _run_session():
            await daemon.start(wait_timeout=float(wait))
            chosen_mode = daemon.device_info.get("mode", "accessibility")
            dev_name = daemon.device_info.get("device_name") or (
                daemon.target_device_info.get("device_name")
                if daemon.target_device_info
                else "Pixel 11 Pro"
            )
            rprint("[bold green]✔ Connected![/] WebRTC DataChannel is ready.")
            rprint(f"Device: [bold]{dev_name}[/]")
            rprint(f"Control Method (chosen by phone): [bold cyan]{chosen_mode}[/]")
            rprint(
                "[dim]Session active. Listening for commands via IPC. "
                "Press Ctrl+C to terminate.[/dim]"
            )
            await daemon.wait_until_stopped()

        run_async(_run_session())
    except KeyboardInterrupt:
        rprint("\n[yellow]Session stopped by user.[/yellow]")
        run_async(daemon.stop())
    except Exception as e:
        rprint(f"[bold red]✘ Connection failed:[/] {e}")
        sys.exit(1)


def _run_adb_proxy(
    port: int = 55555,
    wait: int = 300,
    provider: str = "firebase",
    device: Optional[str] = None,
    turn_server: Optional[str] = None,
    turn_user: Optional[str] = None,
    turn_pass: Optional[str] = None,
    auto_connect: bool = True,
):
    """Maintain an active WebRTC ADB tunnel open, forwarding connections immediately."""
    daemon = ControlDaemon(
        reason="ADB Active Proxy Tunnel",
        ttl_seconds=0,
        provider_name=provider,
        turn_server=turn_server,
        turn_user=turn_user,
        turn_pass=turn_pass,
        requested_mode="adb",
        adb_port=port,
        target_device=device,
    )
    # Active tunnel mode: disable idle teardown so tunnel stays permanently open
    daemon.idle_timeout_seconds = 0.0

    rprint(
        f"[bold cyan]==>[/] Requesting active ADB proxy tunnel "
        f"(SessionID: [yellow]{daemon.session_id}[/], Local Port: [green]{port}[/])..."
    )
    rprint(f"[bold cyan]==>[/] Waiting up to {wait}s for device handshake...")

    try:

        async def _run():
            await daemon.start(wait_timeout=float(wait))
            device_name = daemon.device_info.get("device_name") or (
                daemon.target_device_info.get("device_name")
                if daemon.target_device_info
                else "Pixel 11 Pro"
            )
            chosen_mode = daemon.device_info.get("mode", "adb")

            rprint("[bold green]✔ Active ADB tunnel established![/] DataChannel is live.")
            rprint(f"• Endpoint: [bold cyan]127.0.0.1:{port}[/]")
            rprint(f"• Device: [bold green]{device_name}[/] ({chosen_mode})")

            if auto_connect:
                rprint(f"[bold cyan]==>[/] Auto-connecting local ADB to 127.0.0.1:{port}...")
                res = subprocess.run(
                    ["adb", "connect", f"127.0.0.1:{port}"],
                    capture_output=True,
                    text=True,
                )
                out = res.stdout.strip()
                if "connected" in out.lower():
                    rprint(f"[bold green]✔ ADB connected:[/] {out}")
                else:
                    rprint(f"[bold yellow]ADB connect output:[/] {out}")

            rprint(
                "[dim]Maintaining active tunnel. Forwarding all ADB connections immediately.\n"
                f"Run 'adb -s 127.0.0.1:{port} <command>' or 'android-ctl shell <cmd>' directly.\n"
                "Press Ctrl+C to terminate proxy.[/dim]"
            )

            # Heartbeat task: sends periodic ping to keep traffic timestamp fresh
            async def _heartbeat():
                while daemon._running:
                    await asyncio.sleep(25.0)
                    if daemon.host.is_ready:
                        try:
                            await daemon.host.send_rpc("ping", {}, timeout=5.0)
                        except Exception:
                            pass

            hb_task = asyncio.create_task(_heartbeat())
            try:
                await daemon.wait_until_stopped()
            finally:
                hb_task.cancel()

        run_async(_run())
    except KeyboardInterrupt:
        rprint("\n[yellow]Stopping ADB proxy tunnel...[/yellow]")
        if auto_connect:
            subprocess.run(["adb", "disconnect", f"127.0.0.1:{port}"], capture_output=True)
        run_async(daemon.stop())
        rprint("[bold green]✔ Tunnel closed cleanly.[/bold green]")
    except Exception as e:
        rprint(f"[bold red]✘ Proxy failed:[/] {e}")
        sys.exit(1)


@adb_app.command("proxy")
def adb_proxy(
    port: int = typer.Option(55555, "--port", "-P", help="Local TCP port to bind for ADB bridge"),
    wait: int = typer.Option(300, "--wait", "-w", help="Seconds to wait for session connection"),
    provider: str = typer.Option("firebase", "--provider", "-p", help="Signaling provider"),
    device: Optional[str] = typer.Option(
        None, "--device", "-d", help="Target device ID or prefix (defaults to selected/latest)"
    ),
    turn_server: Optional[str] = typer.Option(None, "--turn-server", help="TURN server URL"),
    turn_user: Optional[str] = typer.Option(None, "--turn-user", help="TURN server username"),
    turn_pass: Optional[str] = typer.Option(None, "--turn-pass", help="TURN server password"),
    auto_connect: bool = typer.Option(
        True, "--connect/--no-connect", help="Run 'adb connect 127.0.0.1:<port>' automatically"
    ),
):
    """Maintain an active WebRTC ADB tunnel open, forwarding connections immediately."""
    _run_adb_proxy(
        port=port,
        wait=wait,
        provider=provider,
        device=device,
        turn_server=turn_server,
        turn_user=turn_user,
        turn_pass=turn_pass,
        auto_connect=auto_connect,
    )


@app.command("proxy")
def proxy_alias(
    port: int = typer.Option(55555, "--port", "-P", help="Local TCP port to bind for ADB bridge"),
    wait: int = typer.Option(300, "--wait", "-w", help="Seconds to wait for session connection"),
    provider: str = typer.Option("firebase", "--provider", "-p", help="Signaling provider"),
    device: Optional[str] = typer.Option(
        None, "--device", "-d", help="Target device ID or prefix (defaults to selected/latest)"
    ),
    turn_server: Optional[str] = typer.Option(None, "--turn-server", help="TURN server URL"),
    turn_user: Optional[str] = typer.Option(None, "--turn-user", help="TURN server username"),
    turn_pass: Optional[str] = typer.Option(None, "--turn-pass", help="TURN server password"),
    auto_connect: bool = typer.Option(
        True, "--connect/--no-connect", help="Run 'adb connect 127.0.0.1:<port>' automatically"
    ),
):
    """Maintain an active WebRTC ADB tunnel open (alias for 'android-ctl adb proxy')."""
    _run_adb_proxy(
        port=port,
        wait=wait,
        provider=provider,
        device=device,
        turn_server=turn_server,
        turn_user=turn_user,
        turn_pass=turn_pass,
        auto_connect=auto_connect,
    )


@app.command()
def status():
    """Print connection status, active driver, and device information."""
    # 1. Active WebRTC daemon session
    if SOCKET_PATH.exists():
        try:
            res = run_async(client_rpc("session.status"))
            chosen = "accessibility"
            adb_port = 55555
            if STATE_PATH.exists():
                try:
                    st = json.loads(STATE_PATH.read_text())
                    chosen = st.get("device_info", {}).get("mode", st.get("mode", "accessibility"))
                    adb_port = st.get("adb_port", 55555)
                except Exception:
                    pass

            res["driver"] = chosen
            res["decided_by"] = "control_app"
            res["transport"] = "webrtc"
            adb_bridge = AdbDriver(serial=f"127.0.0.1:{adb_port}")
            res["adb_bridge_ready"] = adb_bridge.is_connected()
            rprint(json.dumps(res, indent=2))
            return
        except Exception:
            pass

    # 2. Active WebRTC ADB bridge
    adb_webrtc = AdbDriver(serial="127.0.0.1:55555")
    if adb_webrtc.is_connected():
        pkg = adb_webrtc.get_foreground_package()
        rprint(
            json.dumps(
                {
                    "driver": "adb_webrtc",
                    "transport": "webrtc_bridge",
                    "target": "127.0.0.1:55555",
                    "connected": True,
                    "foreground_package": pkg,
                    "capabilities": [
                        "full_shell",
                        "screencap",
                        "uiautomator",
                        "input_events",
                        "pm_am",
                    ],
                },
                indent=2,
            )
        )
        return

    # 3. Direct ADB (USB / Local Wi-Fi) fallback
    adb = AdbDriver()
    if adb.is_connected():
        pkg = adb.get_foreground_package()
        serial = adb.get_active_serial() or "unknown"
        mode = "wifi" if ":" in serial else "usb"
        rprint(
            json.dumps(
                {
                    "driver": "adb_direct",
                    "transport": mode,
                    "target": serial,
                    "connected": True,
                    "foreground_package": pkg,
                    "capabilities": [
                        "full_shell",
                        "screencap",
                        "uiautomator",
                        "input_events",
                        "pm_am",
                    ],
                },
                indent=2,
            )
        )
        return

    rprint(
        "[bold red]Offline:[/] No connected ADB device and no active Accessibility WebRTC session."
    )
    sys.exit(1)


@app.command()
def shell(
    command: str = typer.Argument(..., help="Shell command to run on device via ADB"),
):
    """Run arbitrary adb shell command on device (ADB mode only)."""
    adb = AdbDriver()
    if not adb.is_connected():
        rprint("[bold red]Error:[/] ADB device not connected. Shell commands require ADB mode.")
        sys.exit(1)

    output = adb.shell(command)
    print(output, end="")


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


def do_screen_capture(
    output: Path = Path("_scratch/screen.png"),
    annotate: bool = True,
    quality: int = 80,
) -> None:
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


def do_screen_resolve(
    screen_path: Path = Path("_scratch/screen.png"),
    string: str = "",
) -> None:
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


@screen_app.callback(invoke_without_command=True)
def screen_main(
    ctx: typer.Context,
    output: Path = typer.Option(
        Path("_scratch/screen.png"), "-o", "--output", help="Path to save screenshot PNG"
    ),
    annotate: bool = typer.Option(
        True, "--annotate/--no-annotate", help="Draw #N numbered bounding boxes"
    ),
    quality: int = typer.Option(80, "--quality", help="Image quality compression"),
):
    """Capture and inspect device screen (defaults to capture)."""
    if ctx.invoked_subcommand is None:
        do_screen_capture(output=output, annotate=annotate, quality=quality)


@screen_app.command("capture")
def screen_capture(
    output: Path = typer.Option(
        Path("_scratch/screen.png"), "-o", "--output", help="Path to save screenshot PNG"
    ),
    annotate: bool = typer.Option(
        True, "--annotate/--no-annotate", help="Draw #N numbered bounding boxes"
    ),
    quality: int = typer.Option(80, "--quality", help="Image quality compression"),
):
    """Outputs the device screen to a PNG."""
    do_screen_capture(output=output, annotate=annotate, quality=quality)


@screen_app.command("resolve")
def screen_resolve(
    screen_path: Path = typer.Option(
        Path("_scratch/screen.png"), "--screen", "--screenshot", help="Screenshot path"
    ),
    string: str = typer.Option(..., "--string", "-s", help="Query string, e.g. 'tap #14'"),
):
    """Targets UI elements visually. Substitutes bounding box coordinates."""
    do_screen_resolve(screen_path=screen_path, string=string)


@app.command()
def resolve(
    screen_path: Path = typer.Option(
        Path("_scratch/screen.png"), "--screen", "--screenshot", help="Screenshot path"
    ),
    string: str = typer.Option(..., "--string", "-s", help="Query string, e.g. 'tap #14'"),
):
    """Resolve element tag like '#14' from screen annotation map to x y coordinates."""
    do_screen_resolve(screen_path=screen_path, string=string)


# -----------------------------------------------------------------------------
# Input pass-through / a11y reinterpretation commands (mirrors adb shell input)
# -----------------------------------------------------------------------------


@input_app.command("tap")
def input_tap(
    x: int = typer.Argument(..., help="X coordinate"),
    y: int = typer.Argument(..., help="Y coordinate"),
):
    """Send tap event to coordinates (mirrors 'adb shell input tap x y')."""
    backend = get_backend()
    if backend == "none":
        rprint("[bold red]Error:[/] No active WebRTC session or connected ADB device.")
        sys.exit(1)

    try:
        if backend == "adb":
            success = AdbDriver().tap(x, y)
            rprint(f"[bold green]✔ Tapped ({x}, {y}):[/] ok={success}")
        else:
            res = run_async(client_rpc("tap", {"x": x, "y": y}))
            rprint(f"[bold green]✔ Tapped:[/] {res}")
    except Exception as e:
        rprint(f"[bold red]Error:[/] {e}")
        sys.exit(1)


@input_app.command("swipe")
def input_swipe(
    x1: int = typer.Argument(..., help="Start X"),
    y1: int = typer.Argument(..., help="Start Y"),
    x2: int = typer.Argument(..., help="End X"),
    y2: int = typer.Argument(..., help="End Y"),
    duration_ms: int = typer.Argument(300, help="Duration in ms (default 300)"),
):
    """Send swipe gesture (mirrors 'adb shell input swipe x1 y1 x2 y2 [duration]')."""
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


@input_app.command("text")
def input_text(
    content: str = typer.Argument(..., help="Text to input (supports %s for spaces)"),
):
    """Input text into focused field (mirrors 'adb shell input text <string>')."""
    backend = get_backend()
    if backend == "none":
        rprint("[bold red]Error:[/] No active WebRTC session or connected ADB device.")
        sys.exit(1)

    try:
        if backend == "adb":
            success = AdbDriver().text(content)
            rprint(f"[bold green]✔ Entered text:[/] ok={success}")
        else:
            unescaped = content.replace("%s", " ")
            res = run_async(client_rpc("text", {"text": unescaped, "submit": False}))
            rprint(f"[bold green]✔ Entered text:[/] {res}")
    except Exception as e:
        rprint(f"[bold red]Error:[/] {e}")
        sys.exit(1)


@input_app.command("keyevent")
def input_keyevent(
    code: str = typer.Argument(
        ..., help="Keycode number or action name (e.g. 4, 3, 187, 66, KEYCODE_BACK)"
    ),
):
    """Trigger key event (mirrors 'adb shell input keyevent <code/name>')."""
    backend = get_backend()
    if backend == "none":
        rprint("[bold red]Error:[/] No active WebRTC session or connected ADB device.")
        sys.exit(1)

    try:
        if backend == "adb":
            res = AdbDriver().shell(f"input keyevent {code}")
            rprint(f"[bold green]✔ Keyevent {code}:[/] {res.strip() or 'ok'}")
        else:
            code_upper = code.upper()
            KEY_MAP = {
                "3": "HOME",
                "KEYCODE_HOME": "HOME",
                "HOME": "HOME",
                "4": "BACK",
                "KEYCODE_BACK": "BACK",
                "BACK": "BACK",
                "187": "RECENTS",
                "KEYCODE_APP_SWITCH": "RECENTS",
                "APP_SWITCH": "RECENTS",
                "RECENTS": "RECENTS",
                "26": "LOCK",
                "KEYCODE_POWER": "LOCK",
                "POWER": "LOCK",
                "LOCK": "LOCK",
                "82": "MENU",
                "83": "NOTIFICATIONS",
                "NOTIFICATIONS": "NOTIFICATIONS",
                "QUICK_SETTINGS": "QUICK_SETTINGS",
            }
            if code_upper in ("66", "KEYCODE_ENTER", "ENTER"):
                res = run_async(client_rpc("text", {"text": "", "submit": True}))
                rprint(f"[bold green]✔ Key action ENTER (submit):[/] {res}")
            elif code_upper in KEY_MAP:
                action = KEY_MAP[code_upper]
                res = run_async(client_rpc("key", {"action": action}))
                rprint(f"[bold green]✔ Key action {action}:[/] {res}")
            else:
                rprint(
                    f"[yellow]Note: Accessibility mode reinterpreting keycode {code_upper}...[/]"
                )
                res = run_async(client_rpc("key", {"action": code_upper}))
                rprint(f"[bold green]✔ Key action {code_upper}:[/] {res}")
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
