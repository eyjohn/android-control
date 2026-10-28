"""Local file and memory signaling provider for testing and direct pairing."""

from __future__ import annotations

import asyncio
import json
import time
from pathlib import Path
from typing import Optional

from android_control.adb_driver import AdbDriver
from android_control.config import get_default_scratch_path
from android_control.signaling.base import SessionAnswer, SessionOffer, SignalingProvider


class LocalFileSignaling(SignalingProvider):
    def __init__(self, base_dir: Optional[Path] = None):
        self.base_dir = base_dir or get_default_scratch_path("signaling")
        self.base_dir.mkdir(parents=True, exist_ok=True)
        self.adb = AdbDriver()

    def _clean_device_answer(self) -> None:
        try:
            self.adb._run_adb(
                ["shell", "run-as", "com.evdev.androidcontrol", "rm", "-f", "files/answer.webrtc"],
                timeout=2.0,
            )
            self.adb._run_adb(
                [
                    "shell",
                    "rm",
                    "-f",
                    "/sdcard/Android/data/com.evdev.androidcontrol/files/answer.webrtc",
                ],
                timeout=2.0,
            )
        except Exception:
            pass

    def _dispatch_adb_offer(self, payload: str, session_id: str, account: str = "") -> None:
        try:
            self.adb._run_adb(
                [
                    "shell",
                    "am",
                    "broadcast",
                    "-a",
                    "com.evdev.androidcontrol.LOCAL_OFFER",
                    "-p",
                    "com.evdev.androidcontrol",
                    "--es",
                    "data",
                    payload,
                    "--es",
                    "session_id",
                    session_id,
                    "--es",
                    "account",
                    account,
                ],
                timeout=5.0,
            )
        except Exception:
            pass

    def _fetch_device_answer(self) -> Optional[str]:
        try:
            res = self.adb._run_adb(
                ["shell", "run-as", "com.evdev.androidcontrol", "cat", "files/answer.webrtc"],
                timeout=2.0,
            )
            if res.returncode == 0 and res.stdout.strip():
                content = res.stdout.decode("utf-8", errors="ignore").strip()
                if content:
                    self._clean_device_answer()
                    return content
        except Exception:
            pass

        try:
            res = self.adb._run_adb(
                [
                    "shell",
                    "cat",
                    "/sdcard/Android/data/com.evdev.androidcontrol/files/answer.webrtc",
                ],
                timeout=2.0,
            )
            if res.returncode == 0 and res.stdout.strip():
                content = res.stdout.decode("utf-8", errors="ignore").strip()
                if content:
                    self._clean_device_answer()
                    return content
        except Exception:
            pass
        return None

    async def publish_offer(
        self, offer: SessionOffer, target_device_id: Optional[str] = None
    ) -> Optional[dict]:
        offer_file = self.base_dir / f"{offer.session_id}.offer.json"
        offer_file.write_text(offer.model_dump_json(indent=2))

        # Also write session.webrtc for out-of-band / webrtc-bridge pairing
        webrtc_file = get_default_scratch_path("session.webrtc")
        payload = offer.to_payload()
        webrtc_file.write_text(payload)

        # Clear any stale answers on device and dispatch intent
        self._clean_device_answer()
        self._dispatch_adb_offer(payload, offer.session_id, offer.account)
        return {"device_id": target_device_id or "local", "device_name": "Local Device (ADB)"}

    async def wait_for_answer(
        self, session_id: str, timeout_seconds: float = 300.0
    ) -> Optional[SessionAnswer]:
        answer_file = self.base_dir / f"{session_id}.answer.json"
        webrtc_ans = get_default_scratch_path("answer.webrtc")
        start_time = time.time()
        while time.time() - start_time < timeout_seconds:
            if answer_file.exists():
                try:
                    data = json.loads(answer_file.read_text())
                    return SessionAnswer.model_validate(data)
                except Exception:
                    pass
            if webrtc_ans.exists():
                try:
                    raw = webrtc_ans.read_text()
                    ans = SessionAnswer.from_payload(raw)
                    return ans
                except Exception:
                    pass

            dev_ans = self._fetch_device_answer()
            if dev_ans:
                try:
                    ans = SessionAnswer.from_payload(dev_ans)
                    webrtc_ans.write_text(dev_ans)
                    return ans
                except Exception:
                    pass

            await asyncio.sleep(0.3)
        return None

    async def close_session(self, session_id: str) -> None:
        for suffix in [".offer.json", ".answer.json"]:
            path = self.base_dir / f"{session_id}{suffix}"
            if path.exists():
                try:
                    path.unlink()
                except OSError:
                    pass
        for f in [
            get_default_scratch_path("session.webrtc"),
            get_default_scratch_path("answer.webrtc"),
        ]:
            if f.exists():
                try:
                    f.unlink()
                except OSError:
                    pass

    async def publish_renegotiation_offer(self, session_id: str, offer_sdp: str) -> None:
        reneg_file = self.base_dir / f"{session_id}.reneg_offer.json"
        reneg_file.write_text(json.dumps({"session_id": session_id, "sdp": offer_sdp}))
        get_default_scratch_path("session.webrtc").write_text(offer_sdp)
        self._clean_device_answer()
        self._dispatch_adb_offer(offer_sdp, session_id)

    async def wait_for_renegotiation_answer(
        self, session_id: str, timeout_seconds: float = 30.0
    ) -> Optional[str]:
        ans_file = self.base_dir / f"{session_id}.reneg_answer.json"
        webrtc_ans = get_default_scratch_path("answer.webrtc")
        start_time = time.time()
        while time.time() - start_time < timeout_seconds:
            if ans_file.exists():
                try:
                    data = json.loads(ans_file.read_text())
                    return str(data.get("sdp", ""))
                except Exception:
                    pass
            if webrtc_ans.exists():
                try:
                    raw = webrtc_ans.read_text().strip()
                    if "v=0" in raw:
                        return raw
                    ans = SessionAnswer.from_payload(raw)
                    return ans.sdp
                except Exception:
                    pass

            dev_ans = self._fetch_device_answer()
            if dev_ans:
                try:
                    raw = dev_ans.strip()
                    if "v=0" in raw:
                        webrtc_ans.write_text(raw)
                        return raw
                    ans = SessionAnswer.from_payload(raw)
                    webrtc_ans.write_text(dev_ans)
                    return ans.sdp
                except Exception:
                    pass

            await asyncio.sleep(0.3)
        return None
