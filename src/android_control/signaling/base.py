"""Signaling interfaces and data models."""

from __future__ import annotations

import abc
import base64
import gzip
import json
from typing import Any, Dict, Optional

from pydantic import BaseModel, Field


class SessionOffer(BaseModel):
    session_id: str
    sdp: str
    reason: str = "AI Agent Remote Control"
    ttl_seconds: int = 600
    timestamp_ms: int = 0
    extra: Dict[str, Any] = Field(default_factory=dict)

    def to_payload(self) -> str:
        data = self.model_dump()
        raw = json.dumps(data).encode("utf-8")
        compressed = gzip.compress(raw)
        return base64.b64encode(compressed).decode("ascii")

    @classmethod
    def from_payload(cls, payload: str) -> SessionOffer:
        clean = payload.strip().removeprefix("<webrtc>").removesuffix("</webrtc>").strip()
        if clean.startswith("{"):
            data = json.loads(clean)
        else:
            raw = gzip.decompress(base64.b64decode(clean))
            data = json.loads(raw.decode("utf-8"))
        return cls.model_validate(data)


class SessionAnswer(BaseModel):
    session_id: str
    sdp: str
    approved: bool = True
    device_name: str = "Pixel 9a"
    mode: str = "accessibility"
    timestamp_ms: int = 0

    def to_payload(self) -> str:
        data = self.model_dump()
        raw = json.dumps(data).encode("utf-8")
        compressed = gzip.compress(raw)
        return base64.b64encode(compressed).decode("ascii")

    @classmethod
    def from_payload(cls, payload: str) -> SessionAnswer:
        clean = payload.strip().removeprefix("<webrtc>").removesuffix("</webrtc>").strip()
        if clean.startswith("{"):
            data = json.loads(clean)
        else:
            raw = gzip.decompress(base64.b64decode(clean))
            data = json.loads(raw.decode("utf-8"))
        return cls.model_validate(data)


class SignalingProvider(abc.ABC):
    @abc.abstractmethod
    async def publish_offer(self, offer: SessionOffer) -> None:
        """Publish WebRTC SDP offer to signaling mailbox."""
        pass

    @abc.abstractmethod
    async def wait_for_answer(
        self, session_id: str, timeout_seconds: float = 120.0
    ) -> Optional[SessionAnswer]:
        """Await user approval and WebRTC SDP answer from device."""
        pass

    @abc.abstractmethod
    async def close_session(self, session_id: str) -> None:
        """Clean up active signaling session."""
        pass

    @abc.abstractmethod
    async def publish_renegotiation_offer(self, session_id: str, offer_sdp: str) -> None:
        """Publish renegotiation SDP offer upon connection loss."""
        pass

    @abc.abstractmethod
    async def wait_for_renegotiation_answer(
        self, session_id: str, timeout_seconds: float = 30.0
    ) -> Optional[str]:
        """Await renegotiation SDP answer from device."""
        pass
