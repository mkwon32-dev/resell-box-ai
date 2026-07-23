#!/usr/bin/env python3
"""Contract test for the backend, Roboflow stubbed out -- verifies the
/analyze response matches what app/lib/data/models/analysis_result.dart
parses. Run: sneaker-box-dataset/.venv/bin/python backend/test_app.py
"""

from __future__ import annotations

import sys
from pathlib import Path

from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).parent))
import app as backend  # noqa: E402

REPO = Path(__file__).resolve().parent.parent
FULL_BOX = REPO / "sneaker-box-dataset/full_box_damage_images/dent/dent_001.jpg"


async def fake_detect(image_bytes: bytes) -> list[dict]:
    # Centre-ish dent, mimicking the Roboflow detect response shape.
    return [
        {"x": 150, "y": 150, "width": 60, "height": 40,
         "class": "dent", "confidence": 0.91},
    ]


def main() -> int:
    backend._detect = fake_detect  # type: ignore[assignment]
    # re-bind the route's captured global
    client = TestClient(backend.app)

    r = client.get("/health")
    assert r.status_code == 200, r.text

    with FULL_BOX.open("rb") as f:
        r = client.post("/analyze", files={"photo": ("dent.jpg", f, "image/jpeg")})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["image"]["width"] > 0
    assert body["scale_source"] in ("none", "box_edge", "box_face")
    p = body["predictions"][0]
    for key in ("x", "y", "width", "height", "class", "confidence"):
        assert key in p, f"missing {key}"
    if body["scale_source"] != "none":
        assert "width_cm" in p and "height_cm" in p
    print("contract ok:", {k: body[k] for k in ("image", "scale_source")})
    print("prediction:", p)

    r = client.post("/analyze", files={"photo": ("junk.bin", b"nope", "image/jpeg")})
    assert r.status_code == 400, r.status_code
    print("bad-image 400 ok")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
