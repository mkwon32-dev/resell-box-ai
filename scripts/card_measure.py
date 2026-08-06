"""
ResellBox AI - Component 1/3: reference-card detection + pixel-to-cm conversion.

Pure OpenCV, no ML. Verified against images/craft_with_card.png.
Ground truth: card = 254 x 160 px, 8.56 cm wide -> 0.0337 cm/px, 100 px = 3.37 cm.
This module measures 257 px (+1.2%) -> 0.03331 cm/px, 100 px = 3.33 cm.

THREE FAILURE MODES FOUND ON THE PROTOTYPE IMAGE (do not "simplify" these away):

1. Magnetic stripe splits the card.
   The plain recipe (Canny -> findContours -> approxPolyDP -> keep 4-vertex) does NOT
   find the card. The dark stripe is the strongest edge inside the card, so contours
   split it and the best 4-vertex candidate is the lower white panel only:
   251 x 119, ratio 2.109 -> rejected by the aspect gate. Fix: MORPH_CLOSE the edge
   map before findContours. Kernel size is not sensitive (9..31 all work).

2. approxPolyDP eats 3% of the width.
   The card has rounded corners; approxPolyDP chords across them and pulls the quad
   inward -> 246 px (-3.2%), which burns most of the +/-5% budget for nothing.
   Fix: use approxPolyDP ONLY as a "is it 4-sided?" shape test, and take the
   measurement from minAreaRect on the RAW contour -> 257 px (+1.2%).

3. The Jumpman logo passes the aspect gate.
   The printed logo forms a large contour of 380 x 249, ratio 1.529 -- inside the
   1.586 +/-10% window. Every Jordan box has one. Vertex count happens to reject it
   here, but that is luck. Fix: a fill-ratio gate. The card's contour fills 97% of its
   minAreaRect; the logo silhouette fills 21%.
   Also: do NOT pick the largest-area candidate -- pick the best aspect-ratio match.
"""

from pathlib import Path

import cv2
import numpy as np

# scripts/card_measure.py -> project root is one level up.
# Resolving from __file__ means the self-test works no matter where you run it from.
PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_IMAGE = PROJECT_ROOT / "images" / "craft_with_card.png"

# ISO/IEC 7810 ID-1
CARD_W_CM = 8.56
CARD_H_CM = 5.398
CARD_RATIO = CARD_W_CM / CARD_H_CM          # 1.586
RATIO_TOL = 0.10                            # +/-10%, finalize empirically
MIN_FILL = 0.85                             # card ~0.97, logo ~0.21


def find_candidates(img, min_area_frac=0.01):
    """Run the shared detection pipeline and return every contour past the area gate.

    Each candidate is a dict: contour, rect (minAreaRect), w, h, ratio, fill, area,
    is_quad, ratio_err, reject_reason (None if it passes every gate). This is the
    single place the three documented failure-mode fixes live; detect_reference_card()
    and draw_debug() both consume this list so the debug view can never disagree with
    the actual detector.
    """
    h_img, w_img = img.shape[:2]
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    gray = cv2.GaussianBlur(gray, (5, 5), 0)
    edges = cv2.Canny(gray, 50, 150)

    # Failure mode 1: bridge the magnetic stripe so the card is a single contour.
    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (15, 15))
    edges = cv2.morphologyEx(edges, cv2.MORPH_CLOSE, kernel)

    contours, _ = cv2.findContours(edges, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)

    candidates = []
    for c in contours:
        area = cv2.contourArea(c)
        if area < min_area_frac * h_img * w_img:
            continue

        # Failure mode 2: measure the RAW contour; approxPolyDP is only a shape test.
        rect = cv2.minAreaRect(c)            # rotation-tolerant, unlike boundingRect
        w, h = max(rect[1]), min(rect[1])
        if h == 0:
            continue
        ratio = w / h
        fill = area / (w * h)
        peri = cv2.arcLength(c, True)
        is_quad = len(cv2.approxPolyDP(c, 0.02 * peri, True)) == 4
        ratio_err = abs(ratio - CARD_RATIO) / CARD_RATIO

        reason = None
        if not is_quad:
            reason = "not 4-sided"
        elif fill < MIN_FILL:
            # Failure mode 3: a printed logo is a thin silhouette, not a filled rectangle.
            reason = f"fill {fill:.2f} < {MIN_FILL:.2f}"
        elif ratio_err > RATIO_TOL:
            # Aspect gate = the obliqueness check from component 1. Do not ship without it.
            reason = f"ratio {ratio:.3f} off by {ratio_err * 100:.0f}%"

        candidates.append({
            "contour": c, "rect": rect, "w": w, "h": h, "ratio": ratio,
            "fill": fill, "area": area, "is_quad": is_quad,
            "ratio_err": ratio_err, "reject_reason": reason,
        })
    return candidates


def _pick_best(candidates):
    """Best passing candidate = lowest ratio error, NOT the biggest blob."""
    best = None
    for c in candidates:
        if c["reject_reason"] is not None:
            continue
        if best is None or c["ratio_err"] < best["ratio_err"]:
            best = c
    return best


def detect_reference_card(img, min_area_frac=0.01, debug=False):
    """Locate the reference card.

    Returns (card_px_width, minAreaRect) or (None, None) if not found.
    Never raises on a missing card -- the caller reports "size not measurable".
    """
    candidates = find_candidates(img, min_area_frac)
    if debug:
        for c in candidates:
            print(f"  cand {c['w']:6.1f}x{c['h']:<6.1f} ratio={c['ratio']:.3f} "
                  f"fill={c['fill']:.3f} quad={c['is_quad']}")

    best = _pick_best(candidates)
    if best is None:
        return None, None
    return best["w"], best["rect"]


def cm_per_pixel(card_px_width):
    return CARD_W_CM / card_px_width


def damage_cm(x, y, w, h, cmpp, diagonal_thresh=0.45):
    """Roboflow gives box CENTRE x,y + w,h -- not the top-left corner.

    Returns (length_cm, (x1, y1, x2, y2)) with corners ready for cv2.rectangle.
    Uses the diagonal for clearly slanted damage; the long side alone under-measures
    diagonal scratches by 20-30%.
    """
    x1, y1 = int(x - w / 2), int(y - h / 2)      # forgetting the /2 is the classic bug
    x2, y2 = int(x + w / 2), int(y + h / 2)

    if min(w, h) / max(w, h) >= diagonal_thresh:
        px_len = float(np.hypot(w, h))           # squarish -> likely diagonal
    else:
        px_len = float(max(w, h))                # elongated -> axis-aligned
    return px_len * cmpp, (x1, y1, x2, y2)


def measure(img, detections):
    """detections: iterable of dicts with x, y, width, height, class, confidence."""
    card_px, rect = detect_reference_card(img)
    if card_px is None:
        return {"measurable": False,
                "reason": "reference card not found - re-shoot with the card flat on "
                          "the same surface as the damage",
                "detections": [dict(d, size_cm=None) for d in detections]}

    cmpp = cm_per_pixel(card_px)
    out = []
    for d in detections:
        length, corners = damage_cm(d["x"], d["y"], d["width"], d["height"], cmpp)
        out.append(dict(d, size_cm=round(length, 2), corners=corners))
    return {"measurable": True, "card_px_width": round(card_px, 1),
            "cm_per_pixel": round(cmpp, 5), "card_rect": rect, "detections": out}


# --- visualization --------------------------------------------------------------
GREEN = (0, 255, 0)
RED = (0, 0, 255)
WHITE = (255, 255, 255)


def _put_text(img, text, org, color, scale=0.55, thickness=1):
    """Black outline first, color on top -- readable on both light card and dark bg.

    cv2.putText's glyph advance width depends on `thickness`, so drawing the outline
    at a heavier thickness than the fill (two calls, same origin) makes the two
    passes drift apart character by character -- fine at the start of a string,
    visibly doubled by the end. Drawing the outline as 8 same-thickness passes
    offset by 1px around the fill avoids the drift entirely.
    """
    x, y = org
    for dx in (-1, 0, 1):
        for dy in (-1, 0, 1):
            if dx == 0 and dy == 0:
                continue
            cv2.putText(img, text, (x + dx, y + dy), cv2.FONT_HERSHEY_SIMPLEX, scale,
                        (0, 0, 0), thickness, cv2.LINE_AA)
    cv2.putText(img, text, org, cv2.FONT_HERSHEY_SIMPLEX, scale, color, thickness,
                cv2.LINE_AA)


def draw_debug(img, min_area_frac=0.01):
    """Render every find_candidates() outcome onto a copy of img.

    Shares find_candidates()/_pick_best() with detect_reference_card() so the debug
    view can never show a different card than the one actually measured.
    """
    out = img.copy()
    h_img = out.shape[0]
    candidates = find_candidates(img, min_area_frac)
    best = _pick_best(candidates)

    for c in candidates:
        box = cv2.boxPoints(c["rect"]).astype(int)
        is_best = c is best
        color = GREEN if is_best else RED

        cv2.polylines(out, [box], True, (0, 0, 0), 4, cv2.LINE_AA)
        cv2.polylines(out, [box], True, color, 2, cv2.LINE_AA)

        box_top = int(box[:, 1].min())
        label_x = int(box[:, 0].min())
        if is_best:
            for (px, py) in box:
                cv2.circle(out, (int(px), int(py)), 6, (0, 0, 0), -1, cv2.LINE_AA)
                cv2.circle(out, (int(px), int(py)), 4, color, -1, cv2.LINE_AA)
            _put_text(out, f"CARD {c['w']:.0f}x{c['h']:.0f}px",
                      (label_x, max(box_top - 38, 15)), color)
            _put_text(out, f"ratio {c['ratio']:.3f} fill {c['fill']:.3f}",
                      (label_x, max(box_top - 16, 33)), color)
        else:
            _put_text(out, f"rejected: {c['reject_reason']}",
                      (label_x, max(box_top - 14, 15)), color)

    if best is None:
        _put_text(out, "CARD NOT FOUND - size not measurable", (20, 40), RED,
                   scale=0.8, thickness=2)
        return out

    cmpp = cm_per_pixel(best["w"])
    summary = [
        f"card width: {best['w']:.1f}px (truth {GT_PX_WIDTH}px)",
        f"cm_per_pixel: {cmpp:.5f} (truth {GT_CM_PER_PX})",
        f"100px = {100 * cmpp:.2f}cm (truth 3.37cm)",
    ]
    for i, line in enumerate(summary):
        _put_text(out, line, (20, 30 + i * 22), WHITE)

    # Scale bar: 100px white line with end ticks, bottom-left.
    bar_x0, bar_y = 30, h_img - 30
    bar_x1 = bar_x0 + 100
    cv2.line(out, (bar_x0, bar_y), (bar_x1, bar_y), (0, 0, 0), 5, cv2.LINE_AA)
    cv2.line(out, (bar_x0, bar_y), (bar_x1, bar_y), WHITE, 3, cv2.LINE_AA)
    for x in (bar_x0, bar_x1):
        cv2.line(out, (x, bar_y - 6), (x, bar_y + 6), (0, 0, 0), 5, cv2.LINE_AA)
        cv2.line(out, (x, bar_y - 6), (x, bar_y + 6), WHITE, 3, cv2.LINE_AA)
    _put_text(out, f"100 px = {100 * cmpp:.2f} cm", (bar_x0, bar_y - 14), WHITE)

    return out


# --- verification against the documented ground truth -------------------------
GT_PX_WIDTH = 254
GT_CM_PER_PX = 0.0337
TOL = 0.05
DEBUG_IMAGE_PATH = PROJECT_ROOT / "debug" / "card_detection.png"


def self_test(path=None, debug=True, save_debug=True, show=False):
    path = str(path or DEFAULT_IMAGE)
    img = cv2.imread(path)
    assert img is not None, f"could not read {path}"
    print(f"image: {img.shape[1]}x{img.shape[0]}")

    card_px, _ = detect_reference_card(img, debug=debug)

    # Save/show the debug image before the assert below -- a missing card is
    # exactly when the picture is most needed.
    if save_debug or show:
        debug_img = draw_debug(img)
        if save_debug:
            DEBUG_IMAGE_PATH.parent.mkdir(parents=True, exist_ok=True)
            cv2.imwrite(str(DEBUG_IMAGE_PATH), debug_img)
            print(f"debug image saved to {DEBUG_IMAGE_PATH}")
        if show:
            cv2.imshow("card_detection", debug_img)
            cv2.waitKey(0)
            cv2.destroyAllWindows()

    assert card_px is not None, "FAIL: card not detected"

    cmpp = cm_per_pixel(card_px)
    w_err = abs(card_px - GT_PX_WIDTH) / GT_PX_WIDTH
    c_err = abs(cmpp - GT_CM_PER_PX) / GT_CM_PER_PX

    print(f"\ncard width   = {card_px:.1f} px   (truth {GT_PX_WIDTH})   err {w_err*100:+.1f}%")
    print(f"cm_per_pixel = {cmpp:.5f}      (truth {GT_CM_PER_PX})  err {c_err*100:+.1f}%")
    print(f"100 px       = {100*cmpp:.2f} cm     (truth 3.37)")

    ok = w_err <= TOL and c_err <= TOL
    print(f"\n{'PASS' if ok else 'FAIL'} (tolerance +/-{TOL*100:.0f}%)")
    return ok


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="Reference-card detection self-test")
    parser.add_argument("image", nargs="?", default=None,
                         help="path to test image (default: images/craft_with_card.png)")
    parser.add_argument("--show", action="store_true",
                         help="display the debug image in a window")
    parser.add_argument("--no-save", action="store_true",
                         help="do not write debug/card_detection.png")
    args = parser.parse_args()

    ok = self_test(args.image, save_debug=not args.no_save, show=args.show)
    raise SystemExit(0 if ok else 1)
