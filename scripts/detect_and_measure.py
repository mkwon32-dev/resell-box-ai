"""
ResellBox AI - bridges component 2 (YOLO damage detector) into component 1
(card_measure.py's pixel->cm math).

card_measure.py is untouched -- imported and reused as-is. This is step 2 of the
build order in scripts/CLAUDE.md ("replace fake coords with real model output"),
done only after card_measure.py's own plumbing was verified in isolation.
"""

from pathlib import Path

import cv2
from ultralytics import YOLO

import card_measure

PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_WEIGHTS = PROJECT_ROOT / "weights.pt"
DEFAULT_IMAGE = PROJECT_ROOT / "images" / "kobe_with_real_card.png"
DEBUG_IMAGE_PATH = PROJECT_ROOT / "debug" / "detect_and_measure.png"

# Card gets its own color so it's never confused with a damage box; damage is
# colored per-class since a dent and a scuff of the same length are different
# risk (root CLAUDE.md) and should look different at a glance too.
CARD_COLOR = (255, 200, 0)
CLASS_COLORS = {"dent": (0, 0, 255), "surface_damage": (0, 140, 255)}
DEFAULT_CLASS_COLOR = (0, 255, 0)


def run_detection(weights_path, image_path, conf=0.25):
    """YOLO inference -> detections list in card_measure.measure()'s expected format.

    Ultralytics' `boxes.xywh` is already CENTER x,y + width,height in original-image
    pixels (it undoes the 640 letterbox for us here on PC) -- the same convention
    Roboflow's inference API returns, which is what card_measure.damage_cm() expects.
    This convenience disappears on-device with the raw TFLite model (scripts/CLAUDE.md);
    there the letterbox has to be undone by hand before combining with card pixels.
    """
    model = YOLO(str(weights_path))
    results = model.predict(str(image_path), conf=conf, verbose=False)
    result = results[0]

    detections = []
    for box in result.boxes:
        x, y, w, h = box.xywh[0].tolist()
        cls_id = int(box.cls[0])
        detections.append({
            "x": x,
            "y": y,
            "width": w,
            "height": h,
            "class": result.names[cls_id],
            "confidence": float(box.conf[0]),
        })
    return detections


def draw_result(img, result):
    """Draw the card box and each damage box (class + size_cm label) on a copy of img.

    card_measure.py has draw_debug(), but that only visualizes card CANDIDATES for
    its own self-test (rejected contours, ground-truth comparison text) -- it has no
    notion of damage detections. So this is a new function, but it reuses what
    card_measure.py already computed (card_rect, per-detection corners, _put_text's
    glyph-drift-safe outline) rather than re-deriving any of it, so the picture can
    never disagree with measure()'s numbers.
    """
    out = img.copy()

    if result["measurable"] and result.get("card_rect") is not None:
        box = cv2.boxPoints(result["card_rect"]).astype(int)
        cv2.polylines(out, [box], True, (0, 0, 0), 4, cv2.LINE_AA)
        cv2.polylines(out, [box], True, CARD_COLOR, 2, cv2.LINE_AA)
        label_x, label_y = int(box[:, 0].min()), int(box[:, 1].min())
        card_measure._put_text(out, f"CARD {result['card_px_width']:.0f}px",
                                (label_x, max(label_y - 10, 15)), CARD_COLOR)

    for d in result["detections"]:
        color = CLASS_COLORS.get(d["class"], DEFAULT_CLASS_COLOR)
        corners = d.get("corners")
        if corners is None:
            # Not measurable -- no cm-scaled corners were computed. Reuse
            # damage_cm()'s center->corner formula anyway (cmpp=1.0 is a no-op
            # for the corners) instead of re-deriving the x-w/2 math here.
            _, corners = card_measure.damage_cm(d["x"], d["y"], d["width"], d["height"], 1.0)
        x1, y1, x2, y2 = corners

        cv2.rectangle(out, (x1, y1), (x2, y2), (0, 0, 0), 4, cv2.LINE_AA)
        cv2.rectangle(out, (x1, y1), (x2, y2), color, 2, cv2.LINE_AA)

        size_label = f"{d['size_cm']:.2f}cm" if d.get("size_cm") is not None else "size N/A"
        card_measure._put_text(out, f"{d['class']} {size_label}",
                                (x1, max(y1 - 10, 15)), color)

    if not result["measurable"]:
        card_measure._put_text(out, f"NOT MEASURABLE: {result['reason']}", (20, 40),
                                (0, 0, 255), scale=0.7, thickness=2)

    return out


def main(image_path=None, weights_path=None, conf=0.25, save_debug=True, show=False):
    image_path = Path(image_path or DEFAULT_IMAGE)
    weights_path = Path(weights_path or DEFAULT_WEIGHTS)

    img = cv2.imread(str(image_path))
    assert img is not None, f"could not read {image_path}"

    detections = run_detection(weights_path, image_path, conf=conf)
    print(f"{len(detections)} detection(s) from YOLO:")
    for d in detections:
        print(f"  {d['class']:<14} conf={d['confidence']:.2f} "
              f"center=({d['x']:.0f},{d['y']:.0f}) size=({d['width']:.0f}x{d['height']:.0f})")

    result = card_measure.measure(img, detections)

    if not result["measurable"]:
        print(f"\nNOT MEASURABLE: {result['reason']}")
    else:
        print(f"\ncard_px_width={result['card_px_width']}px  "
              f"cm_per_pixel={result['cm_per_pixel']}")
        for d in result["detections"]:
            print(f"  {d['class']:<14} conf={d['confidence']:.2f} size_cm={d['size_cm']}")

    if save_debug or show:
        debug_img = draw_result(img, result)
        if save_debug:
            DEBUG_IMAGE_PATH.parent.mkdir(parents=True, exist_ok=True)
            cv2.imwrite(str(DEBUG_IMAGE_PATH), debug_img)
            print(f"\ndebug image saved to {DEBUG_IMAGE_PATH}")
        if show:
            cv2.imshow("detect_and_measure", debug_img)
            cv2.waitKey(0)
            cv2.destroyAllWindows()

    return result


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(
        description="Run YOLO damage detection and measure results via card_measure.py")
    parser.add_argument("image", nargs="?", default=None,
                         help="path to test image (default: images/kobe_with_real_card.png)")
    parser.add_argument("--weights", default=None,
                         help="path to .pt weights (default: weights.pt)")
    parser.add_argument("--conf", type=float, default=0.25,
                         help="YOLO confidence threshold (default: 0.25)")
    parser.add_argument("--show", action="store_true",
                         help="display the debug image in a window")
    parser.add_argument("--no-save", action="store_true",
                         help="do not write debug/detect_and_measure.png")
    args = parser.parse_args()

    main(args.image, args.weights, args.conf,
         save_debug=not args.no_save, show=args.show)
