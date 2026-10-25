"""JSON-RPC 2.0 protocol models, layout models, and diff utilities."""

from __future__ import annotations

from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field


class RpcRequest(BaseModel):
    jsonrpc: str = "2.0"
    id: str
    method: str
    params: Dict[str, Any] = Field(default_factory=dict)


class RpcError(BaseModel):
    code: int
    message: str
    data: Optional[Any] = None


class RpcResponse(BaseModel):
    jsonrpc: str = "2.0"
    id: str
    result: Optional[Dict[str, Any]] = None
    error: Optional[RpcError] = None


class UiElement(BaseModel):
    index: int
    text: Optional[str] = None
    content_desc: Optional[str] = None
    resource_id: Optional[str] = None
    class_name: Optional[str] = None
    bounds: List[int] = Field(default_factory=list)  # [x1, y1, x2, y2]
    center: List[int] = Field(default_factory=list)  # [cx, cy]
    clickable: bool = False
    editable: bool = False
    scrollable: bool = False
    focused: bool = False
    enabled: bool = True


class LayoutPayload(BaseModel):
    package_name: str
    screen_width: int
    screen_height: int
    elements: List[UiElement] = Field(default_factory=list)


class ScreenPayload(BaseModel):
    image_base64: str
    format: str = "webp"
    width: int
    height: int
    annotations: Dict[str, Dict[str, Any]] = Field(default_factory=dict)


def resolve_node_target(query: str, annotations: Dict[str, Any]) -> Optional[List[int]]:
    """Resolves '#N' query into [x, y] coordinates from annotations map."""
    clean = query.strip()
    if clean.startswith("#"):
        clean = clean[1:]
    node = annotations.get(clean) or annotations.get(f"#{clean}")
    if isinstance(node, dict):
        center = node.get("center")
        if isinstance(center, list) and len(center) == 2:
            return [int(center[0]), int(center[1])]
    return None


def compute_layout_diff(
    old_elements: List[UiElement],
    new_elements: List[UiElement],
) -> Dict[str, Any]:
    """Computes concise added, removed, and changed interactive elements between two layouts."""
    old_map = {f"{e.resource_id or ''}:{e.text or ''}:{e.bounds}": e for e in old_elements}
    new_map = {f"{e.resource_id or ''}:{e.text or ''}:{e.bounds}": e for e in new_elements}

    added = [e.model_dump() for k, e in new_map.items() if k not in old_map]
    removed = [e.model_dump() for k, e in old_map.items() if k not in new_map]

    return {
        "added_count": len(added),
        "removed_count": len(removed),
        "added": added,
        "removed": removed,
    }
