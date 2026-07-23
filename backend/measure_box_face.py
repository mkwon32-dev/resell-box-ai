#!/usr/bin/env python3
"""Card-free damage sizing: box-face quad -> homography -> panel-relative -> cm.

Degradation ladder:
  box_face  face quad found; damage rectified, sized as fraction of panel,
            cm via nominal box length (long dim of top/front face = 35 cm,
            side face long dim = 25 cm).
  box_edge  silhouette found but no clean face split; coarse scale from
            minAreaRect long edge = 35 cm (no rectification).
  none      box edges not in frame (close-up); no cm emitted.

Standalone tool, run from sneaker-box-dataset/:
  .venv/bin/python measurement/measure_box_face.py IMAGE \
      [--bbox CX CY W H] [--debug-out out.png] [--json]
Without --bbox a probe box (20% of frame) is placed at frame centre; the
live detector supplies real bboxes in production.
"""

from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path

import cv2
import numpy as np

# Nominal box prior (cm). Sneaker boxes ~33-37 long; use the middle.
NOMINAL_LONG_CM = 35.0
NOMINAL_SIDE_LONG_CM = 25.0  # side panel's long dim = box width

# Sanity range for a rectified panel aspect (long/short). A 35x25x13 box
# gives ~1.4 (top), ~1.9 (side), ~2.7 (front); perspective foreshortening
# pulls these toward 1, so the range is wide and assignment is positional.
ASPECT_SANE = (1.0, 3.8)

WORK_LONG_SIDE = 600  # resize target for detection
GRABCUT_LONG_SIDE = 300  # GrabCut runs at half work-res for speed
MIN_HULL_FRAC = 0.12  # silhouette must cover >= this fraction of frame
MAX_HULL_SPAN = 0.96  # hull spanning >= this of BOTH dims => close-up
BG_FG_MIN_DIST = 0.25  # min Bhattacharyya distance box vs background
MIN_EDGE_SUPPORT = 0.40  # face quad sides must lie on image edges


def quad_aspect(quad: np.ndarray) -> tuple[float, float, float]:
    """(aspect long/short, w_px, h_px) from averaged opposite sides.

    quad must be in polygon order; w pairs sides 0-1/2-3, h pairs 1-2/3-0.
    """
    a, b, c, d = quad
    w = (np.linalg.norm(b - a) + np.linalg.norm(c - d)) / 2
    h = (np.linalg.norm(c - b) + np.linalg.norm(d - a)) / 2
    if min(w, h) < 1:
        return 0.0, w, h
    return max(w, h) / min(w, h), w, h


def is_convex_quad(quad: np.ndarray) -> bool:
    q = quad.astype(np.float64)
    signs = []
    for i in range(4):
        a, b, c = q[i], q[(i + 1) % 4], q[(i + 2) % 4]
        u, v = b - a, c - b
        signs.append(np.sign(u[0] * v[1] - u[1] * v[0]))
    return abs(sum(signs)) == 4


def _hull_valid(hull: np.ndarray, w: int, h: int) -> bool:
    if cv2.contourArea(hull) < MIN_HULL_FRAC * w * h:
        return False
    _, _, bw, bh = cv2.boundingRect(hull)
    # fills the frame -> close-up texture, not a box outline
    return not (bw >= MAX_HULL_SPAN * w and bh >= MAX_HULL_SPAN * h)


def _lab_hist(lab: np.ndarray, mask: np.ndarray) -> np.ndarray:
    hist = cv2.calcHist([lab], [1, 2], mask, [24, 24], [0, 256, 0, 256])
    cv2.normalize(hist, hist)
    return hist


def edge_map(bgr: np.ndarray) -> np.ndarray:
    """Median-auto Canny edges of a bilateral-filtered grey image."""
    gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
    blur = cv2.bilateralFilter(gray, 9, 60, 60)
    med = float(np.median(blur))
    return cv2.Canny(blur, int(max(10, 0.66 * med)), int(min(255, 1.33 * med + 40)))


def edge_support(quad: np.ndarray, edges_dilated: np.ndarray) -> float:
    """Fraction of points sampled along the quad's sides that land on an
    (already dilated) edge pixel. Real box faces sit on visible edges; quads
    fitted to segmentation blobs on flat texture do not."""
    h, w = edges_dilated.shape[:2]
    hits = 0
    total = 0
    for i in range(4):
        a, b = quad[i], quad[(i + 1) % 4]
        n = max(8, int(np.linalg.norm(b - a) / 4))
        for t in np.linspace(0.05, 0.95, n):
            x = int(round(a[0] + (b[0] - a[0]) * t))
            y = int(round(a[1] + (b[1] - a[1]) * t))
            if 0 <= x < w and 0 <= y < h:
                total += 1
                if edges_dilated[y, x]:
                    hits += 1
    return hits / total if total else 0.0


def find_silhouette(bgr: np.ndarray) -> tuple[np.ndarray, str] | None:
    """Box silhouette as (convex hull, source), or None (close-up / no box).

    Two attempts: (1) strict edge-based largest contour ("edge") -- succeeds
    when the box boundary is high-contrast and unbroken; (2) GrabCut seeded
    with a central rect ("grabcut"), accepted only when the segmented
    foreground's colour differs from the border background. A "grabcut" hull
    is weak evidence -- callers must corroborate it with a successful face
    split before emitting any size.
    """
    h, w = bgr.shape[:2]
    closed = cv2.morphologyEx(
        edge_map(bgr), cv2.MORPH_CLOSE,
        cv2.getStructuringElement(cv2.MORPH_RECT, (7, 7)),
    )
    contours, _ = cv2.findContours(closed, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if contours:
        hull = cv2.convexHull(max(contours, key=cv2.contourArea))
        if _hull_valid(hull, w, h):
            return hull, "edge"

    # GrabCut attempt (at reduced resolution -- it dominates runtime).
    gc_scale = GRABCUT_LONG_SIDE / max(h, w)
    small = cv2.resize(bgr, (round(w * gc_scale), round(h * gc_scale)),
                       interpolation=cv2.INTER_AREA)
    sh, sw = small.shape[:2]
    margin_x, margin_y = round(0.06 * sw), round(0.06 * sh)
    rect = (margin_x, margin_y, sw - 2 * margin_x, sh - 2 * margin_y)
    mask = np.zeros((sh, sw), np.uint8)
    try:
        cv2.grabCut(small, mask, rect, np.zeros((1, 65), np.float64),
                    np.zeros((1, 65), np.float64), 3, cv2.GC_INIT_WITH_RECT)
    except cv2.error:
        return None
    fg = np.where((mask == cv2.GC_FGD) | (mask == cv2.GC_PR_FGD), 255, 0).astype(
        np.uint8
    )
    fg = cv2.morphologyEx(
        fg, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_RECT, (5, 5))
    )
    contours, _ = cv2.findContours(fg, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if not contours:
        return None
    hull = cv2.convexHull(
        (max(contours, key=cv2.contourArea) / gc_scale).astype(np.int32)
    )
    if not _hull_valid(hull, w, h):
        return None
    margin_x, margin_y = round(0.06 * w), round(0.06 * h)

    # Colour check: foreground must be distinguishable from the border ring,
    # otherwise this is a uniform close-up that GrabCut couldn't split.
    lab = cv2.cvtColor(bgr, cv2.COLOR_BGR2LAB)
    hull_mask = np.zeros((h, w), np.uint8)
    cv2.fillConvexPoly(hull_mask, hull.astype(np.int32), 255)
    bg_mask = cv2.bitwise_not(hull_mask)
    bg_mask[margin_y:-margin_y, margin_x:-margin_x] = 0  # border ring only
    if cv2.countNonZero(bg_mask) < 500:
        return None
    dist = cv2.compareHist(
        _lab_hist(lab, hull_mask), _lab_hist(lab, bg_mask), cv2.HISTCMP_BHATTACHARYYA
    )
    if dist < BG_FG_MIN_DIST:
        return None
    return hull, "grabcut"


def hexagon_candidates(hull: np.ndarray) -> list[np.ndarray]:
    """4/6-vertex reductions of the hull, finest first (7-gons reduced by
    dropping the vertex whose removal loses the least area)."""
    peri = cv2.arcLength(hull, True)
    seen: set[bytes] = set()
    out: list[np.ndarray] = []
    for eps in np.linspace(0.008, 0.06, 14):
        approx = cv2.approxPolyDP(hull, eps * peri, True)
        n = len(approx)
        if n == 7:
            v = approx.reshape(-1, 2).astype(np.float64)
            losses = []
            for i in range(7):
                reduced = np.delete(v, i, axis=0)
                losses.append(abs(cv2.contourArea(v.astype(np.float32))
                                  - cv2.contourArea(reduced.astype(np.float32))))
            approx = np.delete(v, int(np.argmin(losses)), axis=0).reshape(
                -1, 1, 2).astype(np.int32)
            n = 6
        if n not in (4, 6):
            continue
        key = approx.tobytes()
        if key in seen:
            continue
        seen.add(key)
        out.append(approx)
    return out


def hexagon_faces(hexa: np.ndarray) -> list[np.ndarray]:
    """Split a hexagonal box silhouette into visible face quads.

    Uses parallelogram completion: the interior 3-face junction p satisfies
    p = v[i-1] + v[i+1] - v[i] for the alternating vertex parity. Try both
    parities, keep the one whose three estimates agree and land inside.
    """
    v = hexa.reshape(6, 2).astype(np.float64)
    diam = math.hypot(*(v.max(axis=0) - v.min(axis=0)))
    best: list[np.ndarray] = []
    best_spread = None
    for k in (0, 1):
        est = np.array(
            [v[(2 * j + k) % 6] + v[(2 * j + k + 2) % 6] - v[(2 * j + k + 1) % 6]
             for j in range(3)]
        )
        spread = float(np.max(np.linalg.norm(est - np.median(est, axis=0), axis=1)))
        if spread > 0.30 * diam:
            continue
        p = np.median(est, axis=0)
        if cv2.pointPolygonTest(hexa.astype(np.float32), tuple(p), False) < 0:
            continue
        faces = []
        ok = True
        for j in range(3):
            # Already polygon-ordered: junction -> 3 consecutive hull verts.
            quad = np.array(
                [p, v[(2 * j + k) % 6], v[(2 * j + k + 1) % 6], v[(2 * j + k + 2) % 6]]
            )
            if not is_convex_quad(quad):
                ok = False
                break
            faces.append(quad)
        if ok and (best_spread is None or spread < best_spread):
            best, best_spread = faces, spread
    return best


def assign_faces(faces: list[np.ndarray]) -> list[tuple[np.ndarray, str, float]]:
    """Positional face assignment -> [(quad, name, nominal long-dim cm)].

    Topmost centroid = top face; of the remaining, larger area = front.
    Aspect bands are only a sanity gate (foreshortening makes exact bands
    unreliable). Top and front long dim = box length; side long dim = width.
    """
    sane = []
    for quad in faces:
        aspect, _, _ = quad_aspect(quad)
        if ASPECT_SANE[0] <= aspect <= ASPECT_SANE[1]:
            sane.append(quad)
    if not sane:
        return []
    if len(sane) == 1:
        return [(sane[0], "panel", NOMINAL_LONG_CM)]
    by_y = sorted(sane, key=lambda q: q[:, 1].mean())
    top, rest = by_y[0], by_y[1:]
    out = [(top, "top", NOMINAL_LONG_CM)]
    rest.sort(key=lambda q: cv2.contourArea(q.astype(np.float32)), reverse=True)
    out.append((rest[0], "front", NOMINAL_LONG_CM))
    if len(rest) > 1:
        out.append((rest[1], "side", NOMINAL_SIDE_LONG_CM))
    return out


def bbox_corners(cx: float, cy: float, w: float, h: float) -> np.ndarray:
    return np.array(
        [
            [cx - w / 2, cy - h / 2],
            [cx + w / 2, cy - h / 2],
            [cx + w / 2, cy + h / 2],
            [cx - w / 2, cy + h / 2],
        ],
        dtype=np.float64,
    )


def intersection(quad: np.ndarray, box: np.ndarray) -> tuple[float, np.ndarray | None]:
    """Area and polygon shared by a panel quad and a damage bbox."""
    area, polygon = cv2.intersectConvexConvex(
        quad.astype(np.float32), box.astype(np.float32)
    )
    if area <= 0 or polygon is None or len(polygon) < 3:
        return 0.0, None
    return float(area), polygon.reshape(-1, 2).astype(np.float32)


def split_faces(hull: np.ndarray) -> tuple[list[np.ndarray], str]:
    """Hull -> (face quads, kind). kind "hex": 3 faces from a hexagonal 3/4
    view, structurally validated by junction consistency. kind "quad": one
    4-vertex face (frontal view) -- weak evidence, callers must corroborate
    with edge support. Hexagon splits are preferred: a 4-vertex reduction of
    a 3/4 view chops the whole silhouette into a fake panel.
    """
    single: np.ndarray | None = None
    for approx in hexagon_candidates(hull):
        if len(approx) == 4:
            quad = approx.reshape(4, 2).astype(np.float64)  # polygon order
            if single is None and is_convex_quad(quad):
                single = quad
            continue
        faces = hexagon_faces(approx)
        if faces:
            return faces, "hex"
    if single is not None:
        return [single], "quad"
    return [], "none"


def _border_touches(hull: np.ndarray, w: int, h: int) -> int:
    m = 0.02 * max(w, h)
    pts = hull.reshape(-1, 2)
    return sum(
        [bool((pts[:, 0] < m).any()), bool((pts[:, 0] > w - m).any()),
         bool((pts[:, 1] < m).any()), bool((pts[:, 1] > h - m).any())]
    )


def _rect_fill(hull: np.ndarray) -> float:
    rect_area = cv2.minAreaRect(hull)[1]
    rect_area = rect_area[0] * rect_area[1]
    return cv2.contourArea(hull) / rect_area if rect_area else 0.0


def measure(img: np.ndarray, bbox: tuple[float, float, float, float]) -> dict:
    """Run the ladder on a BGR image + damage bbox (cx, cy, w, h in px)."""
    oh, ow = img.shape[:2]
    scale = WORK_LONG_SIDE / max(oh, ow)
    work = cv2.resize(img, (round(ow * scale), round(oh * scale)),
                      interpolation=cv2.INTER_CUBIC)
    cx, cy, bw, bh = (v * scale for v in bbox)
    result: dict = {"scale_source": "none", "face_quad_px": None,
                    "panel_aspect": None, "px_per_cm": None,
                    "width_cm": None, "height_cm": None, "long_frac": None,
                    "face": None}

    found = find_silhouette(work)
    if found is None:
        return result
    hull, hull_source = found

    faces, kind = split_faces(hull)
    # A cropped panel is useless for scale: its true extent is unknown. The
    # signature of cropping is a clipped edge running along a frame border --
    # two or more vertices on the same border. A single grazing vertex is
    # tolerated (boxes shot tight to the frame are still measurable).
    wh, ww = work.shape[:2]
    m = 0.006 * max(ww, wh)

    def cropped(q: np.ndarray) -> bool:
        return any(
            int(side.sum()) >= 2
            for side in (q[:, 0] < m, q[:, 0] > ww - m,
                         q[:, 1] < m, q[:, 1] > wh - m)
        )

    faces = [q for q in faces if not cropped(q)]
    if kind == "quad":
        # A lone 4-vertex quad has no structural validation (a GrabCut blob
        # on flat texture reduces to one too) -- demand visible edge support.
        edges_dilated = cv2.dilate(
            edge_map(work), cv2.getStructuringElement(cv2.MORPH_RECT, (9, 9))
        )
        faces = [q for q in faces
                 if edge_support(q, edges_dilated) >= MIN_EDGE_SUPPORT]

    box = bbox_corners(cx, cy, bw, bh)
    assigned = assign_faces(faces)
    chosen = None
    if assigned:
        scored = []
        for quad, name, long_cm in assigned:
            area, damage_on_face = intersection(quad, box)
            scored.append((area, quad, name, long_cm, damage_on_face))
        scored.sort(key=lambda t: t[0], reverse=True)
        if scored[0][0] > 0:  # damage overlaps a face
            chosen = scored[0]

    if chosen is not None:
        _, quad, face_name, long_cm, damage_on_face = chosen
        assert damage_on_face is not None
        aspect, w_px, h_px = quad_aspect(quad)
        # Rectify preserving the quad's own side proportions; the dst rect is
        # in the same polygon order as the quad, so sides map 1:1.
        f = 300.0 / max(w_px, h_px)
        rect_w, rect_h = w_px * f, h_px * f
        dst = np.array([[0, 0], [rect_w, 0], [rect_w, rect_h], [0, rect_h]],
                       dtype=np.float32)
        H = cv2.getPerspectiveTransform(quad.astype(np.float32), dst)
        # Only the part of the detector box that lies on this face has a
        # meaningful panel homography. Transforming the full bbox after a
        # tiny overlap extrapolates off-panel corners and can grossly inflate
        # the reported size.
        warped = cv2.perspectiveTransform(damage_on_face.reshape(1, -1, 2), H)
        wx, wy = warped[0, :, 0], warped[0, :, 1]
        dmg_w, dmg_h = float(wx.max() - wx.min()), float(wy.max() - wy.min())
        long_px = max(rect_w, rect_h)
        px_per_cm = long_px / long_cm
        result.update(
            scale_source="box_face",
            face=face_name,
            face_quad_px=(quad / scale).round(1).tolist(),
            panel_aspect=round(aspect, 3),
            # Report the face's observed long-edge scale in original-image
            # pixels. `px_per_cm` above belongs to the arbitrarily normalized
            # rectified plane, so it cannot be converted with resize alone.
            px_per_cm=round(max(w_px, h_px) / long_cm / scale, 3),
            width_cm=round(dmg_w / px_per_cm, 2),
            height_cm=round(dmg_h / px_per_cm, 2),
            long_frac=round(max(dmg_w, dmg_h) / long_px, 4),
        )
        return result

    # box_edge fallback: whole-silhouette coarse scale. Only from an
    # edge-based hull that looks box-shaped -- a GrabCut blob with no face
    # structure, an irregular hull, or one running off every frame border is
    # weak evidence (close-up texture) and stays "none". Boxes shot tight to
    # the frame (cropped panels, tier above unavailable) land here.
    if (hull_source != "edge" or _rect_fill(hull) < 0.70
            or _border_touches(hull, ww, wh) >= 4):
        return result
    rect = cv2.minAreaRect(hull)
    long_edge = max(rect[1])
    if long_edge < 10:
        return result
    px_per_cm = long_edge / NOMINAL_LONG_CM
    result.update(
        scale_source="box_edge",
        face_quad_px=(cv2.boxPoints(rect) / scale).round(1).tolist(),
        px_per_cm=round(px_per_cm / scale, 3),
        width_cm=round(bw / px_per_cm, 2),
        height_cm=round(bh / px_per_cm, 2),
        long_frac=round(max(bw, bh) / long_edge, 4),
    )
    return result


def draw_debug(img: np.ndarray, bbox, result: dict) -> np.ndarray:
    out = img.copy()
    cx, cy, w, h = bbox
    if result.get("face_quad_px"):
        quad = np.array(result["face_quad_px"], dtype=np.int32)
        color = (255, 255, 0) if result["scale_source"] == "box_face" else (0, 165, 255)
        cv2.polylines(out, [quad], True, color, 2)
    cv2.rectangle(out, (int(cx - w / 2), int(cy - h / 2)),
                  (int(cx + w / 2), int(cy + h / 2)), (0, 0, 255), 2)
    label = result["scale_source"]
    if result["width_cm"] is not None:
        label += f" {result['width_cm']}x{result['height_cm']}cm"
    if result.get("face"):
        label += f" [{result['face']} {result['panel_aspect']}]"
    cv2.putText(out, label, (8, 24), cv2.FONT_HERSHEY_SIMPLEX, 0.6,
                (0, 255, 0), 2, cv2.LINE_AA)
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("image", type=Path)
    ap.add_argument("--bbox", nargs=4, type=float, metavar=("CX", "CY", "W", "H"),
                    help="damage bbox centre+size in px; default probe at centre")
    ap.add_argument("--debug-out", type=Path)
    ap.add_argument("--json", action="store_true", help="print JSON only")
    args = ap.parse_args()

    img = cv2.imread(str(args.image))
    if img is None:
        print(f"cannot read {args.image}", file=sys.stderr)
        return 1
    h, w = img.shape[:2]
    bbox = tuple(args.bbox) if args.bbox else (w / 2, h / 2, w * 0.2, h * 0.2)

    result = measure(img, bbox)
    if args.debug_out:
        args.debug_out.parent.mkdir(parents=True, exist_ok=True)
        cv2.imwrite(str(args.debug_out), draw_debug(img, bbox, result))
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
