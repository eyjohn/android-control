"""Signaling interfaces and data models."""

from __future__ import annotations

import abc
import base64
import gzip
import json
import os
from typing import Any, Dict, Optional

from pydantic import BaseModel, Field

from android_control.config import get_default_ice_servers


def get_current_google_account() -> str:
    from android_control.config import load_auth_session

    auth = load_auth_session()
    if auth and auth.get("email"):
        return auth["email"]
    if os.getenv("GOOGLE_ACCOUNT"):
        return os.environ["GOOGLE_ACCOUNT"]
    return "unknown"


class SessionOffer(BaseModel):
    session_id: str
    sdp: str
    reason: str = "Remote Control"
    ttl_seconds: int = 600
    account: str = Field(default_factory=get_current_google_account)
    auth_token: Optional[str] = None
    timestamp_ms: int = 0
    mode: str = "accessibility"
    ice_servers: list[Dict[str, Any]] = Field(default_factory=get_default_ice_servers)
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
    device_name: str = "Pixel 11 Pro"
    mode: str = "accessibility"
    account: Optional[str] = None
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
    async def publish_offer(
        self, offer: SessionOffer, target_device_id: Optional[str] = None
    ) -> Optional[Dict[str, Any]]:
        """Publish WebRTC SDP offer to signaling mailbox, optionally targeting a specific device."""
        pass

    @abc.abstractmethod
    async def wait_for_answer(
        self, session_id: str, timeout_seconds: float = 300.0
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
