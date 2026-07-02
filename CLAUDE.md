# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

ResellBox AI is a mobile computer vision app that analyzes close-up photos of sneaker box damage and returns a risk assessment for resellers. It does **not** inspect full boxes — only close-up damage areas.

**Damage classes:** normal, scratch, dent, tear, stain  
**Risk levels:** Low, Caution, High  
**Target device:** Galaxy S25 (on-device inference via TensorFlow Lite)

## Planned Architecture

The system is a 3-stage pipeline:

1. **Image quality gate (OpenCV)** — blur detection, brightness check, damage visibility check. Rejects unusable input before inference.
2. **Damage classifier (MobileNetV2 or MobileNetV3)** — pretrained backbone with a 5-class head (normal / scratch / dent / tear / stain). Trained on ~400–500 close-up labeled images.
3. **Rule-based risk scorer** — combines model prediction with OpenCV-estimated damage area to produce Low / Caution / High. Example: scratch + small area → Low; dent + medium area → Caution; tear + large area → High.

The final model is converted to TensorFlow Lite for on-device inference.

## Dataset

- Labels per image: `damage_type` + `risk_label`
- Labeling tool: Label Studio or Google Sheets with folder structure
- Split: train / validation / test
- Judging approach: numeric criteria mode **or** visual severity mode (decided per label)

## Project Status

The repository is in the early data collection and setup phase (Weeks 1–2 of an 8-week schedule). No training code or app code exists yet.
