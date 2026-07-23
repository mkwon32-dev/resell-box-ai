#!/usr/bin/env python3
"""ResellBox AI backend: the FastAPI contract the Flutter app expects.

POST /analyze  (multipart field "photo")
  -> Roboflow hosted detection (box_ai_detection, dent/surface_damage)
  -> per-detection sizing via the box-face measurement ladder
     (sneaker-box-dataset/measurement/measure_box_face.py)
  -> JSON: {image, predictions[{x,y,width,height,class,confidence,
            width_cm?,height_cm?}], scale_source}
Verdict is intentionally omitted -- the app computes it locally and a
backend verdict can only escalate, never downgrade.

Config (env):
  ROBOFLOW_API_KEY   required (put it in backend/.env, which is gitignored)
  ROBOFLOW_MODEL_ID  default "box_ai_detection/9"
  ROBOFLOW_BASE_URL  default "https://detect.roboflow.com"
  CONFIDENCE         default "0.4"

Run from repo root:
  sneaker-box-dataset/.venv/bin/python -m uvicorn app:app \
      --app-dir backend --host 0.0.0.0 --port 8000
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

import cv2
import httpx
import numpy as np
from fastapi import FastAPI, File, HTTPException, UploadFile

# Vendored copy of sneaker-box-dataset/measurement/measure_box_face.py so the
# backend deploys self-contained; keep the two in sync.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from measure_box_face import measure  # noqa: E402


def _load_dotenv() -> None:
    env = Path(__file__).parent / ".env"
    if not env.exists():
        return
    for line in env.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, _, value = line.partition("=")
            os.environ.setdefault(key.strip(), value.strip())


_load_dotenv()

MODEL_ID = os.environ.get("ROBOFLOW_MODEL_ID", "box_ai_detection/9")
BASE_URL = os.environ.get("ROBOFLOW_BASE_URL", "https://detect.roboflow.com")
CONFIDENCE = float(os.environ.get("CONFIDENCE", "0.4"))

app = FastAPI(title="ResellBox AI backend")


@app.get("/health")
def health() -> dict:
    return {
        "ok": True,
        "model": MODEL_ID,
        "key_configured": bool(os.environ.get("ROBOFLOW_API_KEY")),
    }


async def _detect(image_bytes: bytes) -> list[dict]:
    api_key = os.environ.get("ROBOFLOW_API_KEY")
    if not api_key:
        raise HTTPException(503, "ROBOFLOW_API_KEY not configured")
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.post(
            f"{BASE_URL}/{MODEL_ID}",
            params={"api_key": api_key, "confidence": CONFIDENCE},
            files={"file": ("photo.jpg", image_bytes, "image/jpeg")},
        )
    if resp.status_code != 200:
        raise HTTPException(502, f"Roboflow inference failed: {resp.status_code}")
    return resp.json().get("predictions", [])


@app.post("/analyze")
async def analyze(photo: UploadFile = File(...)) -> dict:
    image_bytes = await photo.read()
    img = cv2.imdecode(np.frombuffer(image_bytes, np.uint8), cv2.IMREAD_COLOR)
    if img is None:
        raise HTTPException(400, "not a decodable image")
    h, w = img.shape[:2]

    raw = await _detect(image_bytes)

    # Size each detection through the measurement ladder. scale_source for
    # the response is the best tier achieved across detections (they share
    # one photo, so in practice tiers agree); "none" when nothing sized.
    order = {"none": 0, "box_edge": 1, "box_face": 2}
    scale_source = "none"
    predictions = []
    for p in raw:
        pred = {
            "x": float(p["x"]),
            "y": float(p["y"]),
            "width": float(p["width"]),
            "height": float(p["height"]),
            "class": str(p.get("class", "unknown")),
            "confidence": float(p.get("confidence", 0)),
        }
        m = measure(img, (pred["x"], pred["y"], pred["width"], pred["height"]))
        if m["width_cm"] is not None:
            pred["width_cm"] = m["width_cm"]
            pred["height_cm"] = m["height_cm"]
        if order[m["scale_source"]] > order[scale_source]:
            scale_source = m["scale_source"]
        predictions.append(pred)

    return {
        "image": {"width": w, "height": h},
        "predictions": predictions,
        "scale_source": scale_source,
    }
