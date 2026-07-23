"""Vercel serverless entry: exposes the FastAPI app from backend/app.py."""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from app import app  # noqa: E402,F401
