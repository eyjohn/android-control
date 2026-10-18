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
