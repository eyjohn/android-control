"""Local file and memory signaling provider for testing and direct pairing."""

from __future__ import annotations

import asyncio
import json
import time
from pathlib import Path
from typing import Optional

from android_control.signaling.base import SessionAnswer, SessionOffer, SignalingProvider


class LocalFileSignaling(SignalingProvider):
    def __init__(self, base_dir: Path = Path("_scratch/signaling")):
        self.base_dir = base_dir
        self.base_dir.mkdir(parents=True, exist_ok=True)

    async def publish_offer(self, offer: SessionOffer) -> None:
        offer_file = self.base_dir / f"{offer.session_id}.offer.json"
        offer_file.write_text(offer.model_dump_json(indent=2))

        # Also write session.webrtc for out-of-band / webrtc-bridge pairing
        webrtc_file = Path("_scratch/session.webrtc")
        webrtc_file.write_text(offer.to_payload())

    async def wait_for_answer(
        self, session_id: str, timeout_seconds: float = 120.0
    ) -> Optional[SessionAnswer]:
        answer_file = self.base_dir / f"{session_id}.answer.json"
        webrtc_ans = Path("_scratch/answer.webrtc")
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
            await asyncio.sleep(0.2)
        return None

    async def close_session(self, session_id: str) -> None:
        for suffix in [".offer.json", ".answer.json"]:
            path = self.base_dir / f"{session_id}{suffix}"
            if path.exists():
                try:
                    path.unlink()
                except OSError:
                    pass
        for f in [Path("_scratch/session.webrtc"), Path("_scratch/answer.webrtc")]:
            if f.exists():
                try:
                    f.unlink()
                except OSError:
                    pass
