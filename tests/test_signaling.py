import json
from pathlib import Path

import pytest

from android_control.signaling.base import SessionAnswer, SessionOffer
from android_control.signaling.local import LocalFileSignaling


@pytest.mark.asyncio
async def test_local_file_signaling(tmp_path: Path):
    provider = LocalFileSignaling(base_dir=tmp_path)
    session_id = "test-session-123"

    offer = SessionOffer(
        session_id=session_id,
        sdp="v=0\r\no=- 123 2 IN IP4 127.0.0.1\r\ns=-\r\n",
        reason="Unit Test Verification",
        ttl_seconds=300,
    )

    # Publish offer
    await provider.publish_offer(offer)
    assert (tmp_path / f"{session_id}.offer.json").exists()

    # Simulate phone posting answer
    answer = SessionAnswer(
        session_id=session_id,
        sdp="v=0\r\no=- 456 2 IN IP4 127.0.0.1\r\ns=-\r\n",
        approved=True,
        device_name="Pixel 9a Test",
    )
    answer_file = tmp_path / f"{session_id}.answer.json"
    answer_file.write_text(answer.model_dump_json())

    # Host waits for answer
    received = await provider.wait_for_answer(session_id, timeout_seconds=2.0)
    assert received is not None
    assert received.session_id == session_id
    assert received.approved is True
    assert received.device_name == "Pixel 9a Test"

    # Close session removes files
    await provider.close_session(session_id)
    assert not (tmp_path / f"{session_id}.offer.json").exists()
    assert not (tmp_path / f"{session_id}.answer.json").exists()


def test_payload_compression_roundtrip():
    offer = SessionOffer(
        session_id="sess-roundtrip-456",
        sdp="v=0\r\no=- 999 2 IN IP4 127.0.0.1\r\ns=-\r\n",
        reason="Compression Test",
        ttl_seconds=600,
    )
    payload = offer.to_payload()
    assert isinstance(payload, str)
    assert len(payload) > 10

    decoded = SessionOffer.from_payload(payload)
    assert decoded.session_id == offer.session_id
    assert decoded.sdp == offer.sdp
    assert decoded.reason == offer.reason
    assert decoded.ttl_seconds == offer.ttl_seconds

    answer = SessionAnswer(
        session_id="sess-ans-789",
        sdp="v=0\r\no=- 888 2 IN IP4 127.0.0.1\r\ns=-\r\n",
        approved=True,
        device_name="Pixel 9a Test",
    )
    ans_payload = answer.to_payload()
    decoded_ans = SessionAnswer.from_payload(ans_payload)
    assert decoded_ans.session_id == answer.session_id
    assert decoded_ans.sdp == answer.sdp
    assert decoded_ans.approved is True


def test_ice_servers_payload_roundtrip():
    ice = [
        {
            "urls": ["turn:turn.example.com:3478", "turn:turn.example.com:443?transport=tcp"],
            "username": "agent",
            "credential": "secretpassword",
        }
    ]
    offer = SessionOffer(
        session_id="sess-turn-test",
        sdp="v=0\r\nsdp",
        ice_servers=ice,
    )
    payload = offer.to_payload()
    decoded = SessionOffer.from_payload(payload)
    assert len(decoded.ice_servers) == 1
    assert decoded.ice_servers[0]["username"] == "agent"
    assert decoded.ice_servers[0]["credential"] == "secretpassword"
    assert "turn:turn.example.com:3478" in decoded.ice_servers[0]["urls"]


def test_session_offer_adb_mode_roundtrip():
    offer = SessionOffer(
        session_id="sess-adb-mode",
        sdp="v=0\r\nsdp",
        mode="adb",
        ttl_seconds=300,
    )
    payload = offer.to_payload()
    decoded = SessionOffer.from_payload(payload)
    assert decoded.mode == "adb"
    assert decoded.ttl_seconds == 300


def test_default_ice_servers_embedded_in_offer(monkeypatch):
    monkeypatch.setenv("TURN_SERVER", "turn:turn.example.com:3478")
    monkeypatch.setenv("TURN_USERNAME", "testuser")
    monkeypatch.setenv("TURN_PASSWORD", "testpass")

    offer = SessionOffer(
        session_id="sess-default-ice",
        sdp="v=0\r\nsdp",
    )
    assert len(offer.ice_servers) >= 1
    server = offer.ice_servers[0]
    assert "turn:turn.example.com:3478" in server["urls"]
    assert server["username"] == "testuser"
    assert server["credential"] == "testpass"

    payload = offer.to_payload()
    decoded = SessionOffer.from_payload(payload)
    assert len(decoded.ice_servers) >= 1
    assert decoded.ice_servers[0]["username"] == "testuser"
    assert decoded.ice_servers[0]["credential"] == "testpass"


def test_load_local_config_from_json(tmp_path, monkeypatch):
    from android_control.config import get_default_ice_servers

    monkeypatch.delenv("TURN_SERVER", raising=False)
    monkeypatch.delenv("TURN_SERVERS", raising=False)
    monkeypatch.delenv("TURN_USERNAME", raising=False)
    monkeypatch.delenv("TURN_USER", raising=False)
    monkeypatch.delenv("TURN_PASSWORD", raising=False)
    monkeypatch.delenv("TURN_PASS", raising=False)
    monkeypatch.delenv("TURN_CREDENTIAL", raising=False)
    monkeypatch.setattr(Path, "home", lambda: tmp_path / "fake_home")

    turn_file = tmp_path / "turn.json"
    turn_file.write_text(
        json.dumps(
            {
                "urls": ["turn:localturn.example.com:3478"],
                "username": "localuser",
                "credential": "localpassword",
            }
        )
    )

    extra_turn_file = tmp_path / "backup.turn.json"
    extra_turn_file.write_text(
        json.dumps(
            {
                "urls": ["turn:backup.example.com:3478"],
                "username": "backupuser",
                "credential": "backuppassword",
            }
        )
    )

    monkeypatch.chdir(tmp_path)
    servers = get_default_ice_servers()
    assert len(servers) == 2
    urls = [s["urls"][0] for s in servers]
    assert "turn:localturn.example.com:3478" in urls
    assert "turn:backup.example.com:3478" in urls


def test_resolve_target_device_selection(monkeypatch):
    from android_control.signaling.firebase import FirebaseSignaling

    fs = FirebaseSignaling()
    mock_devices = [
        {"device_id": "dev-older", "device_name": "Pixel 9", "updated_at": 1000},
        {"device_id": "dev-latest", "device_name": "Pixel 11 Pro", "updated_at": 5000},
        {"device_id": "tablet-abc", "device_name": "Pixel Tablet", "updated_at": 3000},
    ]

    monkeypatch.setattr(fs, "discover_devices", lambda account=None: mock_devices)

    # 1. Fallback to latest check-in
    monkeypatch.setattr("android_control.config.get_selected_device", lambda: None)
    latest = fs.resolve_target_device()
    assert latest is not None
    assert (
        latest["device_id"] == "dev-older"
    )  # sorted by discover_devices in real code, but here mock_devices[0]

    # With sorted list:
    mock_sorted = sorted(mock_devices, key=lambda d: d["updated_at"], reverse=True)
    monkeypatch.setattr(fs, "discover_devices", lambda account=None: mock_sorted)
    latest = fs.resolve_target_device()
    assert latest is not None
    assert latest["device_id"] == "dev-latest"

    # 2. Configured selection takes precedence over latest
    monkeypatch.setattr("android_control.config.get_selected_device", lambda: "tablet-abc")
    chosen = fs.resolve_target_device()
    assert chosen is not None
    assert chosen["device_id"] == "tablet-abc"

    # 3. Explicit argument takes top precedence
    explicit = fs.resolve_target_device("dev-older")
    assert explicit is not None
    assert explicit["device_id"] == "dev-older"
