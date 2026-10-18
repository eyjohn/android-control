from android_control.protocol import (
    RpcRequest,
    UiElement,
    compute_layout_diff,
    resolve_node_target,
)


def test_rpc_request_serialization():
    req = RpcRequest(id="req-1", method="layout", params={"flat": True})
    dumped = req.model_dump()
    assert dumped["jsonrpc"] == "2.0"
    assert dumped["id"] == "req-1"
    assert dumped["method"] == "layout"
    assert dumped["params"] == {"flat": True}


def test_resolve_node_target():
    annotations = {
        "1": {"center": [120, 340], "bounds": [100, 300, 140, 380]},
        "14": {"center": [540, 1100], "bounds": [500, 1050, 580, 1150]},
    }
    assert resolve_node_target("#14", annotations) == [540, 1100]
    assert resolve_node_target("14", annotations) == [540, 1100]
    assert resolve_node_target("#99", annotations) is None


def test_compute_layout_diff():
    e1 = UiElement(index=1, text="Submit", bounds=[10, 20, 50, 60], clickable=True)
    e2 = UiElement(index=2, text="Cancel", bounds=[70, 20, 110, 60], clickable=True)
    e3 = UiElement(index=3, text="Help", bounds=[130, 20, 170, 60], clickable=True)

    diff = compute_layout_diff([e1, e2], [e2, e3])
    assert diff["added_count"] == 1
    assert diff["removed_count"] == 1
    assert diff["added"][0]["text"] == "Help"
    assert diff["removed"][0]["text"] == "Submit"
