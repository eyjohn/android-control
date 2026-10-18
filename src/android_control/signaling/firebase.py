"""Firebase Realtime Database + FCM signaling provider."""

from __future__ import annotations

import asyncio
import os
import time
from pathlib import Path
from typing import Optional

from android_control.signaling.base import SessionAnswer, SessionOffer, SignalingProvider


class FirebaseSignaling(SignalingProvider):
    def __init__(
        self,
        database_url: Optional[str] = None,
        credentials_path: Optional[Path] = None,
        target_fcm_token: Optional[str] = None,
    ):
        self.database_url = database_url or os.getenv("FIREBASE_DATABASE_URL")
        self.target_fcm_token = target_fcm_token or os.getenv("ANDROID_FCM_TOKEN")

        cred_env = os.getenv("FIREBASE_CREDENTIALS")
        self.credentials_path: Optional[Path] = None
        if credentials_path:
            self.credentials_path = credentials_path
        elif cred_env:
            self.credentials_path = Path(cred_env)
        else:
            default_path = Path.home() / ".config" / "android-control" / "firebase-credentials.json"
            if default_path.exists():
                self.credentials_path = default_path

        self._app = None
        self._init_firebase()

    def _init_firebase(self) -> None:
        if not self.database_url or not self.credentials_path or not self.credentials_path.exists():
            return

        try:
            import firebase_admin
            from firebase_admin import credentials

            if not firebase_admin._apps:
                cred = credentials.Certificate(str(self.credentials_path))
                self._app = firebase_admin.initialize_app(
                    cred,
                    {"databaseURL": self.database_url},
                )
            else:
                self._app = firebase_admin.get_app()
        except Exception:
            # Will be surfaced when publish_offer is called
            self._app = None

    @property
    def is_configured(self) -> bool:
        return self._app is not None and self.database_url is not None

    async def publish_offer(self, offer: SessionOffer) -> None:
        if not self.is_configured:
            raise RuntimeError(
                "Firebase is not configured. Set FIREBASE_DATABASE_URL and provide credentials at "
                "~/.config/android-control/firebase-credentials.json or FIREBASE_CREDENTIALS."
            )

        from firebase_admin import db, messaging

        ref = db.reference(f"sessions/{offer.session_id}")
        ref.child("offer").set(offer.model_dump())

        # Trigger high-priority push notification if target FCM token known
        if self.target_fcm_token:
            try:
                msg = messaging.Message(
                    notification=messaging.Notification(
                        title="📱 Phone Control Requested",
                        body=f"Eva: {offer.reason} ({offer.ttl_seconds // 60}m)",
                    ),
                    data={
                        "session_id": offer.session_id,
                        "type": "control_request",
                        "ttl": str(offer.ttl_seconds),
                    },
                    android=messaging.AndroidConfig(
                        priority="high",
                        notification=messaging.AndroidNotification(
                            channel_id="android_control_requests",
                            priority="high",
                            default_sound=True,
                            click_action="com.evdev.androidcontrol.APPROVE",
                        ),
                    ),
                    token=self.target_fcm_token,
                )
                messaging.send(msg)
            except Exception:
                pass

    async def wait_for_answer(
        self, session_id: str, timeout_seconds: float = 120.0
    ) -> Optional[SessionAnswer]:
        if not self.is_configured:
            raise RuntimeError("Firebase is not configured.")

        from firebase_admin import db

        ref = db.reference(f"sessions/{session_id}/answer")
        start = time.time()
        while time.time() - start < timeout_seconds:
            val = ref.get()
            if val and isinstance(val, dict):
                return SessionAnswer.model_validate(val)
            await asyncio.sleep(0.5)
        return None

    async def close_session(self, session_id: str) -> None:
        if not self.is_configured:
            return

        from firebase_admin import db

        try:
            db.reference(f"sessions/{session_id}").delete()
        except Exception:
            pass
