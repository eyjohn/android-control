"""Firebase Realtime Database REST + Google Account OAuth2 signaling provider."""

from __future__ import annotations

import asyncio
import json
import os
import time
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any, Dict, List, Optional

from android_control.config import (
    get_firebase_api_key,
    get_firebase_db_url,
    load_auth_session,
    save_auth_session,
)
from android_control.signaling.base import (
    SessionAnswer,
    SessionOffer,
    SignalingProvider,
    get_current_google_account,
)
from android_control.signaling.local import LocalFileSignaling


def get_firebase_auth_token() -> tuple[Optional[str], Optional[str], Optional[str]]:
    """Retrieves or refreshes Firebase ID token from ~/.config/android-control/auth.json.

    Returns (id_token, email, uid).
    """
    session = load_auth_session()
    if not session:
        return None, None, None

    id_token = session.get("id_token")
    refresh_token = session.get("refresh_token")
    email = session.get("email")
    uid = session.get("uid")
    expires_at = session.get("expires_at", 0)

    now = time.time()
    if id_token and now + 300 < expires_at:
        return id_token, email, uid

    if refresh_token:
        try:
            api_key = get_firebase_api_key()
            url = f"https://securetoken.googleapis.com/v1/token?key={api_key}"
            data = urllib.parse.urlencode(
                {"grant_type": "refresh_token", "refresh_token": refresh_token}
            ).encode("utf-8")
            req = urllib.request.Request(
                url,
                data=data,
                headers={"Content-Type": "application/x-www-form-urlencoded"},
            )
            with urllib.request.urlopen(req, timeout=5.0) as resp:
                res = json.loads(resp.read().decode("utf-8"))
                new_id_token = res.get("id_token")
                new_refresh_token = res.get("refresh_token", refresh_token)
                expires_in = int(res.get("expires_in", 3600))
                new_uid = res.get("user_id", uid)

                session.update(
                    {
                        "id_token": new_id_token,
                        "refresh_token": new_refresh_token,
                        "expires_at": now + expires_in,
                        "uid": new_uid,
                    }
                )
                save_auth_session(session)
                return new_id_token, email, new_uid
        except Exception:
            pass

    return (id_token, email, uid) if id_token else (None, None, None)


def get_active_credentials() -> tuple[Optional[str], str, str, Optional[str]]:
    """Returns active Firebase credentials (token, auth_type, email, uid)."""
    fb_token, fb_email, fb_uid = get_firebase_auth_token()
    if fb_token:
        return fb_token, "firebase", fb_email or "", fb_uid

    return None, "none", "", None


class FirebaseSignaling(SignalingProvider):
    def __init__(
        self,
        database_url: Optional[str] = None,
        credentials_path: Optional[Path] = None,
        target_fcm_token: Optional[str] = None,
    ):
        self.database_url = (database_url or get_firebase_db_url()).rstrip("/")
        self.target_fcm_token = target_fcm_token or os.getenv("ANDROID_FCM_TOKEN")
        self.credentials_path = credentials_path
        self._local_fallback = LocalFileSignaling()

    @property
    def is_configured(self) -> bool:
        token, _, _, _ = get_active_credentials()
        return bool(self.database_url and token)

    def _rtdb_request(
        self, method: str, path: str, data: Optional[Dict[str, Any]] = None
    ) -> Optional[Any]:
        token, _, _, _ = get_active_credentials()
        if not token:
            raise RuntimeError("No active authentication. Run 'android-ctl auth login'.")

        sep = "&" if "?" in path else "?"
        url = f"{self.database_url}{path}{sep}auth={token}"
        headers = {"Content-Type": "application/json; charset=utf-8"}

        body = json.dumps(data).encode("utf-8") if data is not None else None
        req = urllib.request.Request(
            url,
            data=body,
            method=method,
            headers=headers,
        )
        with urllib.request.urlopen(req, timeout=6.0) as resp:
            raw = resp.read().decode("utf-8").strip()
            if not raw or raw == "null":
                return None
            return json.loads(raw)

    def discover_devices(self, account: Optional[str] = None) -> List[Dict[str, Any]]:
        """Discover devices registered in Firebase RTDB under the user's account/UID."""
        token, auth_type, current_email, current_uid = get_active_credentials()
        target_account = (account or current_email or get_current_google_account()).lower()

        devices_map: Dict[str, Dict[str, Any]] = {}

        # 1. Check user-partitioned path if UID is known
        if current_uid:
            try:
                user_devs = self._rtdb_request("GET", f"/users/{current_uid}/devices.json")
                if isinstance(user_devs, dict):
                    for dev_id, info in user_devs.items():
                        if isinstance(info, dict):
                            devices_map[dev_id] = info
            except Exception:
                pass

        # 2. Check legacy / fallback global path
        try:
            legacy_devs = self._rtdb_request("GET", "/android_control/devices.json")
            if isinstance(legacy_devs, dict):
                for dev_id, info in legacy_devs.items():
                    if isinstance(info, dict):
                        dev_acc = str(info.get("account", "")).lower()
                        if not target_account or dev_acc == target_account:
                            if dev_id not in devices_map:
                                devices_map[dev_id] = info
        except Exception:
            pass

        # Sort descending by updated_at (most recently checked-in device first)
        return sorted(
            devices_map.values(),
            key=lambda d: d.get("updated_at", 0),
            reverse=True,
        )

    def resolve_target_device(
        self,
        target_device_id: Optional[str] = None,
        account: Optional[str] = None,
    ) -> Optional[Dict[str, Any]]:
        """Resolves target device by ID prefix, saved preference, or latest check-in fallback."""
        devices = self.discover_devices(account)
        if not devices:
            if target_device_id:
                return {"device_id": target_device_id, "device_name": target_device_id}
            return None

        # 1. Exact or prefix match on explicitly requested target_device_id
        if target_device_id:
            tid = target_device_id.lower()
            for d in devices:
                did = str(d.get("device_id", "")).lower()
                if did == tid or did.startswith(tid):
                    return d
            return {"device_id": target_device_id, "device_name": target_device_id}

        # 2. Preferred selected device from local configuration
        from android_control.config import get_selected_device as _get_selected_device

        saved = _get_selected_device()
        if saved:
            saved_lower = saved.lower()
            for d in devices:
                did = str(d.get("device_id", "")).lower()
                if did == saved_lower or did.startswith(saved_lower):
                    return d

        # 3. Fallback: Most recently checked-in device (devices[0])
        return devices[0]

    async def publish_offer(
        self, offer: SessionOffer, target_device_id: Optional[str] = None
    ) -> Optional[Dict[str, Any]]:
        if not self.is_configured:
            raise RuntimeError(
                "Firebase signaling requires authentication ('android-ctl auth login')."
            )

        payload_dict = offer.model_dump()
        target_dev = await asyncio.to_thread(
            self.resolve_target_device, target_device_id, offer.account
        )

        _, _, _, uid = get_active_credentials()
        target_uid = (target_dev.get("uid") if target_dev else None) or uid

        # 1. Clear stale answer & write session offer to Firebase RTDB
        await asyncio.to_thread(
            self._rtdb_request,
            "DELETE",
            f"/android_control/sessions/{offer.session_id}/answer.json",
        )
        await asyncio.to_thread(
            self._rtdb_request,
            "PUT",
            f"/android_control/sessions/{offer.session_id}/offer.json",
            payload_dict,
        )

        if target_uid:
            try:
                await asyncio.to_thread(
                    self._rtdb_request,
                    "DELETE",
                    f"/users/{target_uid}/sessions/{offer.session_id}/answer.json",
                )
                await asyncio.to_thread(
                    self._rtdb_request,
                    "PUT",
                    f"/users/{target_uid}/sessions/{offer.session_id}/offer.json",
                    payload_dict,
                )
            except Exception:
                pass

        # 2. Deliver to target device inbox ONLY
        delivered_to_rtdb = False
        dev_id = target_dev.get("device_id") if target_dev else target_device_id
        if dev_id:
            if target_uid:
                try:
                    await asyncio.to_thread(
                        self._rtdb_request,
                        "PUT",
                        f"/users/{target_uid}/devices/{dev_id}/inbox.json",
                        payload_dict,
                    )
                    delivered_to_rtdb = True
                except Exception:
                    pass

            try:
                await asyncio.to_thread(
                    self._rtdb_request,
                    "PUT",
                    f"/android_control/devices/{dev_id}/inbox.json",
                    payload_dict,
                )
                delivered_to_rtdb = True
            except Exception:
                pass

        # 3. Only fire local ADB fallback if RTDB delivery was unavailable
        if not delivered_to_rtdb:
            try:
                await self._local_fallback.publish_offer(offer, dev_id)
            except Exception:
                pass

        return target_dev

    async def wait_for_answer(
        self, session_id: str, timeout_seconds: float = 300.0
    ) -> Optional[SessionAnswer]:
        start = time.time()
        _, _, _, uid = get_active_credentials()
        while time.time() - start < timeout_seconds:
            # Check user-partitioned path if UID is known
            if uid:
                try:
                    val = await asyncio.to_thread(
                        self._rtdb_request,
                        "GET",
                        f"/users/{uid}/sessions/{session_id}/answer.json",
                    )
                    if isinstance(val, dict) and val.get("sdp"):
                        return SessionAnswer.model_validate(val)
                except Exception:
                    pass

            # Check global path
            try:
                val = await asyncio.to_thread(
                    self._rtdb_request,
                    "GET",
                    f"/android_control/sessions/{session_id}/answer.json",
                )
                if isinstance(val, dict) and val.get("sdp"):
                    return SessionAnswer.model_validate(val)
            except Exception:
                pass

            # Check local answer file fallback
            try:
                dev_ans = await asyncio.to_thread(self._local_fallback._fetch_device_answer)
                if dev_ans:
                    return SessionAnswer.from_payload(dev_ans)
            except Exception:
                pass

            await asyncio.sleep(0.5)
        return None

    async def close_session(self, session_id: str) -> None:
        _, _, _, uid = get_active_credentials()
        try:
            await asyncio.to_thread(
                self._rtdb_request,
                "DELETE",
                f"/android_control/sessions/{session_id}.json",
            )
        except Exception:
            pass

        if uid:
            try:
                await asyncio.to_thread(
                    self._rtdb_request,
                    "DELETE",
                    f"/users/{uid}/sessions/{session_id}.json",
                )
            except Exception:
                pass

        try:
            await self._local_fallback.close_session(session_id)
        except Exception:
            pass

    async def publish_renegotiation_offer(self, session_id: str, offer_sdp: str) -> None:
        offer = SessionOffer(
            session_id=session_id,
            sdp=offer_sdp,
            reason="Renegotiation",
            ttl_seconds=0,
            timestamp_ms=int(time.time() * 1000),
        )
        await self.publish_offer(offer)

    async def wait_for_renegotiation_answer(
        self, session_id: str, timeout_seconds: float = 30.0
    ) -> Optional[str]:
        ans = await self.wait_for_answer(session_id, timeout_seconds=timeout_seconds)
        return ans.sdp if ans else None
