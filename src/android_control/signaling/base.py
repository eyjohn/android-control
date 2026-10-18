"""Signaling interfaces and data models."""

from __future__ import annotations

import abc
from typing import Any, Dict, Optional

from pydantic import BaseModel, Field


class SessionOffer(BaseModel):
    session_id: str
    sdp: str
    reason: str = "AI Agent Remote Control"
    ttl_seconds: int = 600
    timestamp_ms: int = 0
    extra: Dict[str, Any] = Field(default_factory=dict)


class SessionAnswer(BaseModel):
    session_id: str
    sdp: str
    approved: bool = True
    device_name: str = "Pixel 9a"
    timestamp_ms: int = 0


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
