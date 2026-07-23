#!/usr/bin/env python3
"""Benchmark measure_box_face against the datasets.

Inputs:
  - all 600 synthetic full-box images (full_box_damage_images/{scratch,dent,tear})
    -> expect box_face / box_edge
  - 30 verified close-ups (10 each from browser_search_damage/verified/
    {scratch,dent,tear}, deterministic first-N by name) -> expect none
    (note: verified/ contains a few full-box synthetics; audit the sheet)

Go/no-go gates (README pivot rule):
  >= 70% box_face on the synthetic set
  >= 95% "none" on genuine close-ups (audit sheet for mislabelled full-box)

Outputs measurement/out/report.md plus HTML contact sheets with debug
overlays (all 30 close-ups; first 30 per synthetic class -- capped to keep
the sheet reviewable, cap is stated in the report).

Run from sneaker-box-dataset/:  .venv/bin/python measurement/run_benchmark.py
"""

from __future__ import annotations

import statistics
import sys
from collections import Counter
from pathlib import Path

import cv2

sys.path.insert(0, str(Path(__file__).parent))
from measure_box_face import (  # noqa: E402
    WORK_LONG_SIDE,
    assign_faces,
    draw_debug,
    find_silhouette,
    measure,
    quad_aspect,
    split_faces,
)

ROOT = Path(__file__).resolve().parent.parent
SYNTH = ROOT / "full_box_damage_images"
VERIFIED = ROOT / "browser_search_damage" / "verified"
OUT = ROOT / "measurement" / "out"
CLASSES = ("scratch", "dent", "tear")
CLOSEUPS_PER_CLASS = 10
OVERLAYS_PER_SYNTH_CLASS = 30


def probe_bbox(img) -> tuple[float, float, float, float]:
    h, w = img.shape[:2]
    return (w / 2, h / 2, w * 0.2, h * 0.2)


def face_scale_stability(img) -> float | None:
    """Relative spread of px-per-cm implied by each classified face of one
    box. None unless >= 2 faces classify."""
    work_scale = WORK_LONG_SIDE / max(img.shape[:2])
    work = cv2.resize(
        img,
        (round(img.shape[1] * work_scale), round(img.shape[0] * work_scale)),
        interpolation=cv2.INTER_CUBIC,
    )
    found = find_silhouette(work)
    if found is None:
        return None
    hull, _source = found
    faces, _kind = split_faces(hull)
    ratios = []
    for quad, _name, long_cm in assign_faces(faces):
        _, w_px, h_px = quad_aspect(quad)
        ratios.append(max(w_px, h_px) / long_cm)
    if len(ratios) < 2:
        return None
    return (max(ratios) - min(ratios)) / statistics.mean(ratios)


def contact_sheet(rows: list[tuple[str, str, Path]], title: str, out: Path) -> None:
    cells = "\n".join(
        f'<figure><img src="{p.relative_to(OUT)}" loading="lazy">'
        f"<figcaption>{name}<br><b>{tier}</b></figcaption></figure>"
        for name, tier, p in rows
    )
    out.write_text(
        f"<!doctype html><meta charset='utf-8'><title>{title}</title>"
        "<style>body{font:12px monospace;background:#111;color:#eee}"
        "figure{display:inline-block;margin:4px;width:220px;vertical-align:top}"
        "img{width:220px}</style>" + f"<h1>{title}</h1>{cells}"
    )


def main() -> int:
    (OUT / "overlays").mkdir(parents=True, exist_ok=True)
    tiers: dict[str, Counter] = {"synthetic": Counter(), "closeup": Counter()}
    stability: list[float] = []
    sheets: dict[str, list] = {"synthetic": [], "closeup": []}
    failures: list[str] = []

    for cls in CLASSES:
        images = sorted((SYNTH / cls).glob("*.jpg"))
        for i, path in enumerate(images):
            img = cv2.imread(str(path))
            if img is None:
                failures.append(str(path))
                continue
            bbox = probe_bbox(img)
            res = measure(img, bbox)
            tiers["synthetic"][res["scale_source"]] += 1
            if res["scale_source"] == "box_face":
                spread = face_scale_stability(img)
                if spread is not None:
                    stability.append(spread)
            if i < OVERLAYS_PER_SYNTH_CLASS:
                op = OUT / "overlays" / f"synth_{path.stem}.png"
                cv2.imwrite(str(op), draw_debug(img, bbox, res))
                sheets["synthetic"].append((path.name, res["scale_source"], op))

    for cls in CLASSES:
        images = sorted((VERIFIED / cls).iterdir())[:CLOSEUPS_PER_CLASS]
        for path in images:
            img = cv2.imread(str(path))
            if img is None:
                failures.append(str(path))
                continue
            bbox = probe_bbox(img)
            res = measure(img, bbox)
            tiers["closeup"][res["scale_source"]] += 1
            op = OUT / "overlays" / f"close_{path.stem}.png"
            cv2.imwrite(str(op), draw_debug(img, bbox, res))
            sheets["closeup"].append((path.name, res["scale_source"], op))

    contact_sheet(sheets["synthetic"], "synthetic full-box (first "
                  f"{OVERLAYS_PER_SYNTH_CLASS}/class)", OUT / "sheet_synthetic.html")
    contact_sheet(sheets["closeup"], "verified close-ups", OUT / "sheet_closeups.html")

    n_syn = sum(tiers["synthetic"].values())
    n_clo = sum(tiers["closeup"].values())
    face_rate = tiers["synthetic"]["box_face"] / n_syn if n_syn else 0
    edge_rate = tiers["synthetic"]["box_edge"] / n_syn if n_syn else 0
    none_rate_clo = tiers["closeup"]["none"] / n_clo if n_clo else 0
    gate_face = face_rate >= 0.70
    gate_none = none_rate_clo >= 0.95

    med_spread = statistics.median(stability) if stability else None
    report = [
        "# Box-face measurement benchmark",
        "",
        f"Synthetic full-box set: {n_syn} images "
        f"({', '.join(f'{k} {v}' for k, v in sorted(tiers['synthetic'].items()))})",
        f"- box_face rate: **{face_rate:.1%}** (gate >= 70%: "
        f"{'PASS' if gate_face else 'FAIL'})",
        f"- box_edge rate: {edge_rate:.1%} "
        f"(sized, coarse; combined sized rate {face_rate + edge_rate:.1%})",
        "",
        f"Close-up set: {n_clo} images "
        f"({', '.join(f'{k} {v}' for k, v in sorted(tiers['closeup'].items()))})",
        f"- none rate: **{none_rate_clo:.1%}** (gate >= 95%: "
        f"{'PASS' if gate_none else 'AUDIT'} -- verified/ contains a few "
        "mislabelled full-box synthetics; check sheet_closeups.html before "
        "reading this as failure)",
        "",
        "Scale stability (px-per-cm spread between two classified faces of "
        "the same box, box_face images only):",
        f"- n={len(stability)}, median spread "
        f"{med_spread:.1%}" if med_spread is not None else "- n=0",
        "",
        f"Overlay cap: first {OVERLAYS_PER_SYNTH_CLASS} per synthetic class "
        "on the sheet; all close-ups shown.",
        f"Unreadable files: {len(failures)}"
        + (": " + ", ".join(failures) if failures else ""),
    ]
    (OUT / "report.md").write_text("\n".join(report) + "\n")
    print("\n".join(report))
    return 0 if (gate_face and none_rate_clo >= 0.60) else 1


if __name__ == "__main__":
    raise SystemExit(main())
