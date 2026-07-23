# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

ResellBox AI — a school project to detect sneaker-box damage from close-up photos and return a resale-risk level (`Low`/`Caution`/`High`). Current state: curated image dataset + scraping/review tooling, a Flutter app (`app/`), an OpenCV measurement prototype (`sneaker-box-dataset/measurement/`), and a FastAPI backend (`backend/app.py`) bridging the trained Roboflow model (`box_ai_detection/9`, yolo11s, classes `dent`/`surface_damage`) to the app contract. App uses the mock repository unless built with `--dart-define=BACKEND_URL=...`. Backend needs `ROBOFLOW_API_KEY` in `backend/.env` (gitignored; see `.env.example`).

`README.md` is the source of truth for scope. Pipeline:
1. **Object detection** (Roboflow/YOLO) — bounding boxes classifying `scratch`/`dent`/`tear`; `normal` images are no-bbox negatives. Not yet implemented.
2. **OpenCV box-face measurement** (card-free) — box silhouette → face-quad split → homography rectification → damage as panel fraction → cm via nominal 35 cm box length. Degradation ladder `box_face`/`box_edge`/`none` (`none` = close-up, unsized). Prototype: `sneaker-box-dataset/measurement/measure_box_face.py` + `run_benchmark.py`. The old reference-card approach (8.56 × 5.398 cm px→cm ratio) is dead — don't reintroduce it.
3. **Rule-based risk scoring** — current app rules use type + longest measured side and take the worst detection (e.g. `tear + length ≥ 9cm → High`); count/area rules are planned, not implemented. Risk is never labeled during training. Unsized damage floors at `Caution` (`app/lib/data/models/risk_verdict.dart`). Wire contract: `scale_source: none|box_edge|box_face` (legacy `card_detected` bool still parsed for stored records).

### The label scheme is in flux — reconcile before any model work
Three artifacts disagree; know which you're touching:
- **`README.md`**: object detection, 3 classes `scratch/dent/tear` + `normal` negatives.
- **Active dataset** (`browser_search_damage/`): 5 classification classes `normal/scratch/dent/tear/stain` — adds `stain`, no bboxes.
- **Legacy dataset** (`raw/` + `metadata.json`): 4 StockX-condition classes `mint/minor_damage/major_damage/rejected` — superseded, badly skewed (151 mint vs single-digit damaged).

`browser_search_damage/training_ready/` is the current dataset of record. The old `raw/`-based classification scheme and its `metadata.json` are legacy.

## Three sub-projects in this repo

```
README.md                    Project plan — source of truth for scope
progress.md                  Near-empty status stub
sneaker-box-dataset/         (1) the dataset + scrapers — the actual project
browser-search/              (2) vendored web-search/browsing skill (infra dependency)
outreach/                    (3) UNRELATED cold-outreach engine — not part of ResellBox
```

### 1. `sneaker-box-dataset/` — the dataset

Two generations live side by side:

**Legacy (`raw/`)** — 4-class StockX-condition classification. `metadata.json` is *derived* from `raw/` (path/category/w/h/bytes) by a `rebuild_metadata()` function each old scraper carries — never hand-edit it, regenerate it. Label = parent folder name. Superseded.

**Active (`browser_search_damage/`)** — the current pipeline, matching the README's damage-class direction (plus `stain`). Candidate → review → verified → training-ready:
- `browser_search_damage_dataset.py` collects candidates via the local **browser-search / SearXNG** stack (not the old direct-scrape path) into `candidates/`, `downloaded.jsonl`, `cloak_*_results_*.json`. Candidates stay candidates until a human marks keep/reject.
- Humans review via the many `review_sheets_*/` (HTML contact-sheet rounds per class) → decisions in `review_decisions.csv`, `manual_keep_ids.csv`, `reviewed_indexes_*.json`. Keepers land in `verified/<class>/`.
- `prepare_verified_dataset.py` audits `verified/` (sha256 + perceptual-hash dedup, readability, split-leakage) and builds `training_ready/` — copied `train/val/test/` image trees plus `manifest.csv`, per-split CSVs, `audit_report.json`. Source `verified/` is left intact. `final_3class_100/` is a hand-culled 100-each `dent/scratch/tear` subset.
- `summary.json` tracks candidate→download→review→keep funnel counts per class.
- Duplicates removed from `verified/` are quarantined under `quarantine/`, not deleted.

Skewed classes are the active bottleneck: `verified/` is currently `normal 109 / scratch 100 / dent 100 / tear 147 / stain 100`; collecting/curating more damage examples is the ongoing need before training is viable.

### 2. `browser-search/` — vendored browsing skill (infra dependency)

Multi-engine search + scraping used by the active dataset scraper (and by `outreach/`). It supersedes the old direct-`cloakbrowser` approach. Three tools, lightest→heaviest (see `browser-search/SKILL.md`):
- **SearXNG** (Docker, `localhost:8080`) — search/image-search JSON API, first choice.
- **Camofox** (Docker, `localhost:9377`) — JS-heavy pages, REST API.
- **CloakBrowser** (npm, `scripts/cloak/cloak-fetch.mjs`) — escalation when Camofox is blocked.

Docker bring-up: `browser-search/docker/setup.md`. Scrapers assume these services are already running locally.

### 3. `outreach/` — unrelated

A separate B2B cold-outreach engine for "LEAR" (a finance-calculator SaaS), not part of ResellBox AI. It only shares the `browser-search`/SearXNG infra. `outreach-runner.mjs` is the pipeline; sending is gated (never emails without human approval). Treat as out of scope for sneaker work unless asked.

## Running things

There is no build, lint, test, or CI — running a script *is* the workflow. Everything is standalone `python3.12`; there is no package or shared module, and scrapers are near-copies (iterations, not a library). Use the dataset venv, which carries the deps (`requests`, `pillow`, `numpy`, `bdfr`):

```bash
cd sneaker-box-dataset
.venv/bin/python scripts/<name>.py            # run a scraper/tool
.venv/bin/python scripts/browser_search_damage_dataset.py --help   # active candidate collector (SearXNG must be up)
.venv/bin/python scripts/prepare_verified_dataset.py --help        # build training_ready/ from verified/
.venv/bin/python scripts/reddit_collect.py --dry-run               # BDFR reddit image pull into review_batch/reddit/
```

`browser_search_damage_dataset.py` and `prepare_verified_dataset.py` are the current tools. `reddit_collect.py` wraps BDFR (no Reddit creds needed for public search) and dumps UNLABELED images for manual sorting.

## Conventions / gotchas

- **Prefer the active pipeline.** For new dataset work use `browser_search_damage_dataset.py` + `prepare_verified_dataset.py`. The `test_ebay*.py`, `cloak_*.py`, `*_recollect.py`, `clean_and_rescrape.py`, `full_scrape.py` scripts are superseded eBay-scrape experiments — ignore/delete rather than extend.
- **Paths are absolute and machine-specific** (`/home/chimn/projects/resell/...` in older scripts); they break if the repo moves. Newer tools default to repo-relative paths and expect to be run from `sneaker-box-dataset/`.
- **Verified images are precious, candidates are cheap.** Never delete from `verified/` — dedup moves to `quarantine/`. Regenerate `training_ready/` from `verified/`; don't hand-edit it.
- **Label = parent folder name** throughout.
- Filenames are `<class>_<hash>.jpg`; the source-URL hash is the de-dup key. Human review state lives in the many CSV/JSON sidecars — don't discard them, they encode which rounds an image survived.
- The `*.md` review reports (`review_report.md`, `clean_*.md`, `review_batch*.md`) are human notes from legacy `raw/` culling passes.
- `metadata.json` describes only legacy `raw/`; it does **not** cover `browser_search_damage/`.
