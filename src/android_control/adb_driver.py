"""ADB driver backend for android-control (Wi-Fi, USB, and WebRTC bridge)."""

from __future__ import annotations

import base64
import io
import os
import re
import subprocess
import xml.etree.ElementTree as ET
from typing import Any, Dict, List, Optional

from PIL import Image, ImageDraw

from android_control.protocol import (
    LayoutPayload,
    ScreenPayload,
    UiElement,
)


class AdbDriver:
    def __init__(self, serial: Optional[str] = None):
        self.serial = serial

    def get_active_serial(self) -> Optional[str]:
        if self.serial:
            return self.serial
        env_serial = os.getenv("ANDROID_SERIAL")
        if env_serial:
            return env_serial
        dev_res = subprocess.run(["adb", "devices"], capture_output=True)
        devices = []
        for line in dev_res.stdout.decode("utf-8", errors="ignore").splitlines()[1:]:
            parts = line.split()
            if len(parts) >= 2 and parts[1] == "device":
                devices.append(parts[0])
        wifi_devs = [d for d in devices if ":" in d]
        return wifi_devs[0] if wifi_devs else (devices[0] if devices else None)

    def _run_adb(
        self, args: List[str], timeout: float = 15.0
    ) -> subprocess.CompletedProcess[bytes]:
        cmd = ["adb"]
        serial = self.get_active_serial() if args != ["devices"] else self.serial
        if serial:
            cmd.extend(["-s", serial])
        cmd.extend(args)
        return subprocess.run(cmd, capture_output=True, timeout=timeout)

    def is_connected(self) -> bool:
        res = self._run_adb(["devices"])
        lines = res.stdout.decode("utf-8", errors="ignore").strip().splitlines()
        for line in lines[1:]:
            parts = line.split()
            if len(parts) >= 2 and parts[1] == "device":
                if not self.serial or parts[0] == self.serial:
                    return True
        return False

    def get_wlan_ip(self) -> Optional[str]:
        res = self._run_adb(["shell", "ip", "-f", "inet", "addr", "show", "wlan0"])
        out = res.stdout.decode("utf-8", errors="ignore")
        match = re.search(r"inet\s+(\d+\.\d+\.\d+\.\d+)", out)
        if match:
            return match.group(1)
        return None

    def enable_tcpip(self, port: int = 5555) -> bool:
        res = self._run_adb(["tcpip", str(port)])
        return res.returncode == 0

    def connect(self, host: str, port: int = 5555) -> bool:
        res = self._run_adb(["connect", f"{host}:{port}"])
        out = res.stdout.decode("utf-8", errors="ignore")
        return "connected" in out.lower()

    def get_foreground_package(self) -> str:
        res = self._run_adb(["shell", "dumpsys", "window", "displays"])
        out = res.stdout.decode("utf-8", errors="ignore")
        match = re.search(
            r"mFocusedApp=ActivityRecord\{[^\s]+ [^\s]+ ([^/\s]+)/", out
        ) or re.search(r"mCurrentFocus=Window\{[^\s]+ [^\s]+ ([^/\s]+)/", out)
        if match:
            return match.group(1)
        return "unknown"

    def layout(self, flat: bool = True, full: bool = False) -> LayoutPayload:
        """Dumps UI layout hierarchy via UIAutomator and parses into standard UiElement list."""
        self._run_adb(["shell", "uiautomator", "dump", "/data/local/tmp/uidump.xml"])
        res = self._run_adb(["shell", "cat", "/data/local/tmp/uidump.xml"])
        xml_bytes = res.stdout

        if not xml_bytes or b"<hierarchy" not in xml_bytes:
            res = self._run_adb(["exec-out", "uiautomator", "dump", "/dev/tty"])
            xml_bytes = res.stdout

        pkg = self.get_foreground_package()
        elements: List[UiElement] = []
        screen_w, screen_h = 1080, 2400

        try:
            root = ET.fromstring(xml_bytes.decode("utf-8", errors="ignore"))
            index = 1

            for node in root.iter("node"):
                bounds_str = node.attrib.get("bounds", "")
                m = re.findall(r"\[(\d+),(\d+)\]", bounds_str)
                if len(m) == 2:
                    x1, y1 = int(m[0][0]), int(m[0][1])
                    x2, y2 = int(m[1][0]), int(m[1][1])
                    screen_w = max(screen_w, x2)
                    screen_h = max(screen_h, y2)

                    text = node.attrib.get("text", "")
                    content_desc = node.attrib.get("content-desc", "")
                    clickable = node.attrib.get("clickable", "false") == "true"
                    is_edit = "EditText" in node.attrib.get("class", "")
                    editable = node.attrib.get("focusable", "false") == "true" and is_edit
                    scrollable = node.attrib.get("scrollable", "false") == "true"
                    focused = node.attrib.get("focused", "false") == "true"
                    enabled = node.attrib.get("enabled", "true") == "true"

                    has_area = (x2 - x1) > 0 and (y2 - y1) > 0
                    is_interactive = clickable or editable or scrollable
                    has_content = bool(text or content_desc)

                    if full or (has_area and (is_interactive or has_content)):
                        cx = (x1 + x2) // 2
                        cy = (y1 + y2) // 2
                        elements.append(
                            UiElement(
                                index=index,
                                text=text or None,
                                content_desc=content_desc or None,
                                resource_id=node.attrib.get("resource-id") or None,
                                class_name=node.attrib.get("class") or None,
                                bounds=[x1, y1, x2, y2],
                                center=[cx, cy],
                                clickable=clickable,
                                editable=editable,
                                scrollable=scrollable,
                                focused=focused,
                                enabled=enabled,
                            )
                        )
                        index += 1
        except Exception:
            pass

        return LayoutPayload(
            package_name=pkg,
            screen_width=screen_w,
            screen_height=screen_h,
            elements=elements,
        )

    def screen(self, annotate: bool = True, quality: int = 80) -> ScreenPayload:
        """Captures screenshot, optionally draws #N annotations, and returns ScreenPayload."""
        res = self._run_adb(["exec-out", "screencap", "-p"])
        png_bytes = res.stdout

        if not png_bytes or len(png_bytes) < 100:
            raise RuntimeError("Failed to capture screencap from device")

        img = Image.open(io.BytesIO(png_bytes))
        width, height = img.size
        annotations: Dict[str, Dict[str, Any]] = {}

        if annotate:
            layout_data = self.layout(flat=True, full=False)
            draw = ImageDraw.Draw(img)

            for elem in layout_data.elements:
                b = elem.bounds
                if len(b) == 4:
                    x1, y1, x2, y2 = b[0], b[1], b[2], b[3]
                    draw.rectangle((x1, y1, x2, y2), outline=(255, 0, 0), width=3)

                    label = f"#{elem.index}"
                    badge_w = len(label) * 16 + 12
                    badge_h = 28
                    top_badge = max(0, y1 - badge_h)
                    draw.rectangle((x1, top_badge, x1 + badge_w, max(badge_h, y1)), fill=(0, 0, 0))
                    draw.text((x1 + 6, top_badge + 4), label, fill=(255, 255, 255))

                    annotations[str(elem.index)] = {
                        "center": elem.center,
                        "bounds": elem.bounds,
                    }

            buf = io.BytesIO()
            img.save(buf, format="WEBP", quality=quality)
            final_bytes = buf.getvalue()
        else:
            final_bytes = png_bytes

        b64 = base64.b64encode(final_bytes).decode("ascii")
        return ScreenPayload(
            image_base64=b64,
            format="webp" if annotate else "png",
            width=width,
            height=height,
            annotations=annotations,
        )

    def tap(self, x: int, y: int, duration_ms: Optional[int] = None) -> bool:
        if duration_ms and duration_ms > 100:
            res = self._run_adb(
                ["shell", "input", "swipe", str(x), str(y), str(x), str(y), str(duration_ms)]
            )
        else:
            res = self._run_adb(["shell", "input", "tap", str(x), str(y)])
        return res.returncode == 0

    def swipe(self, x1: int, y1: int, x2: int, y2: int, duration_ms: int = 300) -> bool:
        res = self._run_adb(
            ["shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), str(duration_ms)]
        )
        return res.returncode == 0

    def text(self, text: str, clear: bool = False, submit: bool = False) -> bool:
        if clear:
            # Select all and delete to reliably clear text across different input fields
            self._run_adb(["shell", "input", "keyevent", "KEYCODE_MOVE_END"])
            # Fallback multiple backspaces for fields that do not support key combos
            self._run_adb(
                [
                    "shell",
                    "input",
                    "keyevent",
                    *(["67"] * 50),
                ]
            )
        if text:
            # Escape shell special chars and whitespace for adb shell input text
            # %s represents space in adb input text
            special_chars = r'([&|;<>()$`"\*?~[\]!#])'
            escaped = re.sub(special_chars, r"\\\1", text).replace(" ", "%s")
            res = self._run_adb(["shell", "input", "text", escaped])
            if res.returncode != 0:
                return False
        if submit:
            self._run_adb(["shell", "input", "keyevent", "66"])
        return True

    def key(self, action: str) -> bool:
        key_map = {
            "BACK": "4",
            "HOME": "3",
            "RECENTS": "187",
            "POWER": "26",
            "LOCK": "26",
            "VOLUME_UP": "24",
            "VOLUME_DOWN": "25",
        }
        act = action.upper()
        if act == "NOTIFICATIONS":
            res = self._run_adb(["shell", "cmd", "statusbar", "expand-notifications"])
            return res.returncode == 0
        elif act == "QUICK_SETTINGS":
            res = self._run_adb(["shell", "cmd", "statusbar", "expand-settings"])
            return res.returncode == 0

        keycode = key_map.get(act, "4")
        res = self._run_adb(["shell", "input", "keyevent", keycode])
        return res.returncode == 0

    def shell(self, command: str) -> str:
        """Executes arbitrary adb shell command on device."""
        res = self._run_adb(["shell", command])
        return res.stdout.decode("utf-8", errors="ignore")

    def launch(self, package_or_uri: str) -> bool:
        if "://" in package_or_uri:
            res = self._run_adb(
                ["shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", package_or_uri]
            )
        else:
            res = self._run_adb(
                [
                    "shell",
                    "monkey",
                    "-p",
                    package_or_uri,
                    "-c",
                    "android.intent.category.LAUNCHER",
                    "1",
                ]
            )
        return res.returncode == 0
