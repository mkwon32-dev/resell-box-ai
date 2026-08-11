"""
ResellBox AI - Phase D, step 1: export weights.pt (YOLO11s) to ONNX.

TFLite is not exportable directly on Windows (ultralytics 8.4.115 restricts LiteRT export
to Linux x86 / macOS - see export/CLAUDE.md). The path is ONNX -> AI Hub compile job ->
tflite instead. This script only produces the ONNX artifact.

Per export/CLAUDE.md: this script lives in export/, artifacts go to export/models/,
scripts/ is untouched. Verification of the exported model happens separately via
`python scripts/detect_and_measure.py --weights <onnx>` - not here.
"""

import shutil
from pathlib import Path

from ultralytics import YOLO

PROJECT_ROOT = Path(__file__).resolve().parent.parent
WEIGHTS_PATH = PROJECT_ROOT / "weights.pt"
MODELS_DIR = Path(__file__).resolve().parent / "models"


def main():
    model = YOLO(str(WEIGHTS_PATH))

    print("model.names (class index order):")
    print(model.names)

    export_path = model.export(format="onnx", opset=17, simplify=True, dynamic=False, imgsz=640)
    export_path = Path(export_path)

    MODELS_DIR.mkdir(parents=True, exist_ok=True)
    dest_path = MODELS_DIR / export_path.name
    shutil.move(str(export_path), str(dest_path))

    print(f"\nexported onnx moved to: {dest_path}")


if __name__ == "__main__":
    main()
