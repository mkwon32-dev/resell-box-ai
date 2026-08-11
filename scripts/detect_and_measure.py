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
DEBUG_STEPS_DIR = PROJECT_ROOT / "debug"

# Font scale is expressed relative to this reference width, then scaled by the
# actual (post-resize) image width -- so labels stay legible whether resize_long_side
# landed on a portrait or landscape frame.
FONT_REF_WIDTH = 1280

# YOLO and the card detector must see the same pixel grid, or cm_per_pixel comes
# from one coordinate system and damage boxes from another -> silent cm scale bug.
# Resize once here and hand the identical array to both.
RESIZE_LONG_SIDE = 1280

# Card gets its own color so it's never confused with a damage box; damage is
# colored per-class since a dent and a scuff of the same length are different
# risk (root CLAUDE.md) and should look different at a glance too.
CARD_COLOR = (255, 200, 0)
CLASS_COLORS = {"dent": (0, 0, 255), "surface_damage": (0, 140, 255)}
DEFAULT_CLASS_COLOR = (0, 255, 0)


def resize_long_side(img, target=RESIZE_LONG_SIDE):
    """Resize once so the long side == target; return the array both stages consume.

    Runs whether the source is bigger or smaller than target -- what matters is that
    YOLO inference and card_measure's Canny/contour pipeline see identical pixel
    coordinates, not any particular resolution.
    """
    h, w = img.shape[:2]
    scale = target / max(h, w)
    if scale == 1.0:
        return img
    new_w, new_h = max(1, round(w * scale)), max(1, round(h * scale))
    interp = cv2.INTER_AREA if scale < 1.0 else cv2.INTER_LINEAR
    return cv2.resize(img, (new_w, new_h), interpolation=interp)


def run_detection(weights_path, img, conf=0.25):
    """YOLO inference -> detections list in card_measure.measure()'s expected format.

    `img` is the already-resized array (see resize_long_side) -- the same one passed
    to card_measure.measure(), so boxes and card pixels share one coordinate system.

    Ultralytics' `boxes.xywh` is already CENTER x,y + width,height in the pixel grid
    of whatever array you hand it in (no letterbox to undo when you pass an array in
    memory) -- the same convention Roboflow's inference API returns, which is what
    card_measure.damage_cm() expects. This convenience disappears on-device with the
    raw TFLite model (scripts/CLAUDE.md); there the letterbox has to be undone by hand
    before combining with card pixels.
    """
    model = YOLO(str(weights_path))
    print(f"model classes: {model.names}")
    results = model.predict(img, conf=conf, verbose=False)
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


def _font_scale(img_w, base_scale=0.55):
    """Scale font size to the actual image width so labels read the same relative
    size regardless of whether resize_long_side() landed on a portrait or landscape
    frame (long side is fixed at 1280, but width varies with orientation)."""
    return max(0.35, base_scale * img_w / FONT_REF_WIDTH)


def _put_label(img, text, org, color, scale, alpha=0.55):
    """card_measure._put_text with a translucent dark plate behind it.

    Damage boxes land on sneaker-box cardboard, which ranges from near-white to
    photo-shadow-dark in the same frame -- the plain outlined text from
    card_measure._put_text can still wash out on busy backgrounds. A semi-transparent
    backing plate guarantees contrast without hiding the pixels under it.
    """
    thickness = 2 if scale > 0.7 else 1
    (tw, th), baseline = cv2.getTextSize(text, cv2.FONT_HERSHEY_SIMPLEX, scale, thickness)
    x, y = org
    pad = max(2, round(scale * 6))
    x1, y1 = max(x - pad, 0), max(y - th - pad, 0)
    x2, y2 = min(x + tw + pad, img.shape[1]), min(y + baseline + pad, img.shape[0])

    overlay = img.copy()
    cv2.rectangle(overlay, (x1, y1), (x2, y2), (0, 0, 0), -1)
    cv2.addWeighted(overlay, alpha, img, 1 - alpha, 0, dst=img)
    card_measure._put_text(img, text, org, color, scale=scale, thickness=thickness)


def draw_result(img, result):
    """Draw the card box and each damage box (class + size_cm label) on a copy of img.

    card_measure.py has draw_debug(), but that only visualizes card CANDIDATES for
    its own self-test (rejected contours, ground-truth comparison text) -- it has no
    notion of damage detections. So this is a new function, but it reuses what
    card_measure.py already computed (card_rect, per-detection corners, _put_text's
    glyph-drift-safe outline) rather than re-deriving any of it, so the picture can
    never disagree with measure()'s numbers.

    Always draws every detection box, measurable or not -- a card miss should not
    hide the damage the model DID find, it should just say the size is unavailable.
    """
    out = img.copy()
    scale = _font_scale(out.shape[1])

    if result["measurable"] and result.get("card_rect") is not None:
        box = cv2.boxPoints(result["card_rect"]).astype(int)
        cv2.polylines(out, [box], True, (0, 0, 0), 4, cv2.LINE_AA)
        cv2.polylines(out, [box], True, CARD_COLOR, 2, cv2.LINE_AA)
        label_x, label_y = int(box[:, 0].min()), int(box[:, 1].min())
        _put_label(out, f"CARD {result['card_px_width']:.0f}px",
                   (label_x, max(label_y - 10, 15)), CARD_COLOR, scale)

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

        size_label = f"{d['size_cm']:.2f}cm" if d.get("size_cm") is not None else "size unavailable"
        _put_label(out, f"{d['class']} {size_label}",
                   (x1, max(y1 - 10, 15)), color, scale)

    if not result["measurable"]:
        _put_label(out, f"NOT MEASURABLE: {result['reason']}", (20, 40),
                   (0, 0, 255), _font_scale(out.shape[1], base_scale=0.7))

    return out


def save_debug_steps(img, stem):
    """Card-detection step images: Canny edges, raw contours, final selected rect.

    card_measure.py itself is not touched or imported-and-monkeypatched -- this
    recomputes only the Canny/contour visualization steps, using the same
    parameters as card_measure.find_candidates() (grayscale -> blur(5,5) ->
    Canny(50,150) -> MORPH_CLOSE with a 15x15 kernel). The final-rect image reuses
    card_measure.draw_debug() unmodified, so it can never disagree with what
    measure() actually picked.
    """
    DEBUG_STEPS_DIR.mkdir(parents=True, exist_ok=True)

    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    gray = cv2.GaussianBlur(gray, (5, 5), 0)
    edges = cv2.Canny(gray, 50, 150)
    canny_path = DEBUG_STEPS_DIR / f"{stem}_01_canny.png"
    cv2.imwrite(str(canny_path), edges)

    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (15, 15))
    closed = cv2.morphologyEx(edges, cv2.MORPH_CLOSE, kernel)
    contours, _ = cv2.findContours(closed, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    contours_img = img.copy()
    cv2.drawContours(contours_img, contours, -1, (0, 255, 255), 2, cv2.LINE_AA)
    contours_path = DEBUG_STEPS_DIR / f"{stem}_02_contours.png"
    cv2.imwrite(str(contours_path), contours_img)

    selection_img = card_measure.draw_debug(img)
    selection_path = DEBUG_STEPS_DIR / f"{stem}_03_card_selection.png"
    cv2.imwrite(str(selection_path), selection_img)

    print(f"\ndebug steps saved: {canny_path.name}, {contours_path.name}, "
          f"{selection_path.name} (in {DEBUG_STEPS_DIR})")


def main(image_path=None, weights_path=None, conf=0.25, save_debug=True, show=False,
         debug_steps=False):
    image_path = Path(image_path or DEFAULT_IMAGE)
    weights_path = Path(weights_path or DEFAULT_WEIGHTS)

    img = cv2.imread(str(image_path))
    assert img is not None, f"could not read {image_path}"
    img = resize_long_side(img)

    if debug_steps:
        save_debug_steps(img, image_path.stem)

    detections = run_detection(weights_path, img, conf=conf)
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
            DEBUG_STEPS_DIR.mkdir(parents=True, exist_ok=True)
            result_path = DEBUG_STEPS_DIR / f"{image_path.stem}_result.png"
            cv2.imwrite(str(result_path), debug_img)
            print(f"\nresult image saved to {result_path}")
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
                         help="do not write debug/{image_stem}_result.png")
    parser.add_argument("--debug", action="store_true",
                         help="save step-by-step card-detection debug images "
                              "(Canny edges, raw contours, final selected rect) to debug/")
    args = parser.parse_args()

    main(args.image, args.weights, args.conf,
         save_debug=not args.no_save, show=args.show, debug_steps=args.debug)
