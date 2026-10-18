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
