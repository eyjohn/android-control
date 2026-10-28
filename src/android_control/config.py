"""Centralized configuration and ICE/TURN server discovery for android-control."""

from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any, Dict, Optional

CONFIG_DIR = Path.home() / ".config" / "android-control"
AUTH_FILE = CONFIG_DIR / "auth.json"
CONFIG_FILE = CONFIG_DIR / "config.json"
RUN_DIR = (
    Path(os.environ["XDG_RUNTIME_DIR"]) / "android-control"
    if "XDG_RUNTIME_DIR" in os.environ
    else CONFIG_DIR / "run"
)
SOCKET_PATH = RUN_DIR / "control.sock"
STATE_PATH = RUN_DIR / "session.json"
CACHE_DIR = (
    Path(os.environ["XDG_CACHE_HOME"]) / "android-control"
    if "XDG_CACHE_HOME" in os.environ
    else Path.home() / ".cache" / "android-control"
)
DEFAULT_SCRATCH_DIR = (
    Path(os.environ["ANDROID_CONTROL_SCRATCH"])
    if "ANDROID_CONTROL_SCRATCH" in os.environ
    else CACHE_DIR
)


def get_default_scratch_path(filename: str) -> Path:
    """Returns a file path inside the default runtime/cache scratch directory."""
    DEFAULT_SCRATCH_DIR.mkdir(parents=True, exist_ok=True)
    return DEFAULT_SCRATCH_DIR / filename


def get_firebase_db_url() -> str:
    """Returns the resolved Firebase Realtime Database URL strictly from environment."""
    load_local_config()
    url = os.getenv("FIREBASE_DATABASE_URL") or os.getenv("FIREBASE_DB_URL")
    if not url:
        raise RuntimeError("Missing FIREBASE_DATABASE_URL in .env or environment")
    return url.rstrip("/")


def get_firebase_api_key() -> str:
    """Returns the resolved Firebase Web API Key strictly from environment."""
    load_local_config()
    key = os.getenv("FIREBASE_API_KEY")
    if not key:
        raise RuntimeError("Missing FIREBASE_API_KEY in .env or environment")
    return key


def get_firebase_web_client_id() -> str:
    """Returns the resolved Google OAuth Web Client ID strictly from environment."""
    load_local_config()
    client_id = os.getenv("FIREBASE_WEB_CLIENT_ID") or os.getenv("GOOGLE_CLIENT_ID")
    if not client_id:
        raise RuntimeError("Missing FIREBASE_WEB_CLIENT_ID in .env or environment")
    return client_id


def load_auth_session() -> Optional[Dict[str, Any]]:
    """Loads stored Firebase Auth session from ~/.config/android-control/auth.json."""
    if not AUTH_FILE.is_file():
        return None
    try:
        data = json.loads(AUTH_FILE.read_text(encoding="utf-8"))
        if isinstance(data, dict) and data.get("id_token"):
            return data
    except Exception:
        pass
    return None


def save_auth_session(data: Dict[str, Any]) -> None:
    """Persists Firebase Auth session to ~/.config/android-control/auth.json."""
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    AUTH_FILE.write_text(json.dumps(data, indent=2), encoding="utf-8")
    AUTH_FILE.chmod(0o600)


def clear_auth_session() -> None:
    """Clears stored Firebase Auth session."""
    if AUTH_FILE.is_file():
        try:
            AUTH_FILE.unlink()
        except Exception:
            pass


def get_selected_device() -> Optional[str]:
    """Returns the manually selected device ID if configured."""
    dev = os.getenv("ANDROID_CONTROL_DEVICE")
    if dev:
        return dev.strip()
    if CONFIG_FILE.is_file():
        try:
            cfg = json.loads(CONFIG_FILE.read_text(encoding="utf-8"))
            if isinstance(cfg, dict):
                return cfg.get("selected_device")
        except Exception:
            pass
    return None


def set_selected_device(device_id: Optional[str]) -> None:
    """Saves or clears the manually selected device ID in config.json."""
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    cfg: Dict[str, Any] = {}
    if CONFIG_FILE.is_file():
        try:
            cfg = json.loads(CONFIG_FILE.read_text(encoding="utf-8"))
        except Exception:
            pass
    if device_id:
        cfg["selected_device"] = device_id
    else:
        cfg.pop("selected_device", None)
    CONFIG_FILE.write_text(json.dumps(cfg, indent=2), encoding="utf-8")


def load_env_file(path: Path) -> bool:
    """Loads KEY=VALUE pairs into os.environ if not already present."""
    if not path.is_file():
        return False
    loaded = False
    try:
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            k = k.strip()
            v = v.strip().strip("'\"")
            if k and k not in os.environ:
                os.environ[k] = v
                loaded = True
    except Exception:
        pass
    return loaded


def _parse_ice_entry(data: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    """Extracts a standard WebRTC RTCIceServer dict from a JSON entry."""
    urls = data.get("urls") or data.get("url") or data.get("server") or data.get("servers")
    if not urls:
        # Fall back to host/port construction if urls is missing
        host = data.get("host")
        if host:
            port = data.get("port", 3478)
            urls = [f"turn:{host}:{port}?transport=udp", f"turn:{host}:{port}?transport=tcp"]
    if not urls:
        return None

    url_list = [urls] if isinstance(urls, str) else list(urls)
    entry: Dict[str, Any] = {"urls": url_list}

    username = data.get("username") or data.get("user")
    if username:
        entry["username"] = str(username)

    credential = data.get("credential") or data.get("password") or data.get("pass")
    if credential:
        entry["credential"] = str(credential)

    return entry


def load_local_config() -> list[Dict[str, Any]]:
    """Attempts to load local config from .env and turn.json / *.turn.json files.

    Returns any loaded ICE server configurations.
    """
    cwd = Path.cwd()

    # 1. Load .env files if TURN_SERVER not already in environment
    if not (os.getenv("TURN_SERVER") or os.getenv("TURN_SERVERS")):
        env_candidates = [
            cwd / ".env",
            cwd.parent / ".env",
            Path.home() / ".config" / "android-control" / ".env",
        ]
        for candidate in env_candidates:
            if candidate.is_file() and load_env_file(candidate):
                if os.getenv("TURN_SERVER") or os.getenv("TURN_SERVERS"):
                    break

    # 2. Search for turn.json and *.turn.json files in cwd, parent, ~/.config/android-control
    found_servers: list[Dict[str, Any]] = []

    search_dirs = [
        cwd,
        cwd.parent,
        Path.home() / ".config" / "android-control",
    ]

    for directory in search_dirs:
        if not directory.is_dir():
            continue

        dir_servers: list[Dict[str, Any]] = []

        # Find exact turn.json first, then any other *.turn.json
        candidate_files: list[Path] = []
        exact = directory / "turn.json"
        if exact.is_file():
            candidate_files.append(exact)

        for p in sorted(directory.glob("*.turn.json")):
            if p.is_file() and p not in candidate_files:
                candidate_files.append(p)

        for json_file in candidate_files:
            try:
                content = json.loads(json_file.read_text(encoding="utf-8"))
                items = content if isinstance(content, list) else [content]
                for item in items:
                    if isinstance(item, dict):
                        entry = _parse_ice_entry(item)
                        if entry:
                            dir_servers.append(entry)
            except Exception:
                pass

        # If servers found in a higher-priority directory, use them and stop falling back
        if dir_servers:
            found_servers = dir_servers
            break

    return found_servers


def get_default_ice_servers(
    turn_server: Optional[str] = None,
    turn_user: Optional[str] = None,
    turn_pass: Optional[str] = None,
) -> list[Dict[str, Any]]:
    """Resolves ICE servers from arguments, env, or local turn.json / *.turn.json files."""
    json_servers = load_local_config()

    server = turn_server or os.getenv("TURN_SERVER") or os.getenv("TURN_SERVERS") or ""
    user = turn_user or os.getenv("TURN_USERNAME") or os.getenv("TURN_USER") or ""
    password = (
        turn_pass
        or os.getenv("TURN_PASSWORD")
        or os.getenv("TURN_PASS")
        or os.getenv("TURN_CREDENTIAL")
        or ""
    )

    if server:
        urls = [u.strip() for u in server.split(",") if u.strip()]
        ice_entry: Dict[str, Any] = {"urls": urls}
        if user:
            ice_entry["username"] = user
        if password:
            ice_entry["credential"] = password
        return [ice_entry]

    return json_servers
