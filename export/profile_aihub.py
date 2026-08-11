"""
ResellBox AI - Phase D, step 2: compile weights.onnx for the target device via Qualcomm AI
Hub, then profile the compiled model on a real device in the cloud.

Two AI Hub jobs, both against DEVICE_NAME:
  1. Compile job: export/models/weights.onnx -> export/models/weights_float.tflite
     (no explicit --target_runtime; relies on AI Hub's default for this device/model)
  2. Profile job: runs the compiled model on a real device and reports per-layer compute
     unit (NPU/GPU/CPU) placement and inference latency.

Per export/CLAUDE.md: this script lives in export/, artifacts go to export/models/,
scripts/ is untouched. Verification of the exported model happens separately via
`python scripts/detect_and_measure.py --weights <tflite>` - not here.

Auth: qai-hub reads the token from ~/.qai_hub/client.ini, set up once via `qai-hub configure`.
This script never reads or references the token itself.

Run this from the .venv-export environment (qai-hub does not coexist with the main
ultralytics env - see export/CLAUDE.md).
"""

import time
from pathlib import Path

import qai_hub as hub

PROJECT_ROOT = Path(__file__).resolve().parent.parent
ONNX_PATH = PROJECT_ROOT / "export" / "models" / "weights.onnx"
TFLITE_PATH = PROJECT_ROOT / "export" / "models" / "weights_float.tflite"
EXPERIMENTS_LOG = PROJECT_ROOT / "docs" / "experiments.md"

DEVICE_NAME = "Samsung Galaxy S25"
DEVICE_OS = "15"


def wait_for_job(job, label: str):
    """Block until an AI Hub job finishes, printing progress before/after.

    job.wait() already prints a live progress bar itself (AI Hub client default),
    this wrapper adds the job id/dashboard url and a final elapsed-time line around it.
    """
    print(f"\n[{label}] submitted: job_id={job.job_id}")
    print(f"[{label}] dashboard: {job.url}")
    start = time.monotonic()
    status = job.wait()
    elapsed = time.monotonic() - start
    print(f"[{label}] finished in {elapsed:.0f}s: state={status.code}")
    if not status.success:
        raise RuntimeError(
            f"[{label}] job did not succeed: state={status.code} message={status.message}"
        )
    return status


def summarize_profile(profile: dict) -> str:
    summary = profile.get("execution_summary", {})
    details = profile.get("execution_detail", [])

    inference_us = summary.get("estimated_inference_time")
    inference_ms = (
        inference_us / 1000 if isinstance(inference_us, (int, float)) else None
    )

    layer_count_by_unit: dict[str, int] = {}
    time_us_by_unit: dict[str, int] = {}
    for layer in details:
        unit = layer.get("compute_unit", "UNKNOWN")
        layer_count_by_unit[unit] = layer_count_by_unit.get(unit, 0) + 1
        if "execution_time" in layer:
            time_us_by_unit[unit] = time_us_by_unit.get(unit, 0) + layer["execution_time"]

    total_layers = sum(layer_count_by_unit.values())
    total_time_us = sum(time_us_by_unit.values())

    lines = []
    lines.append(
        f"Estimated inference time: {inference_ms:.2f} ms"
        if inference_ms is not None
        else "Estimated inference time: n/a"
    )
    lines.append(f"Layers: {total_layers}")

    lines.append("Compute unit breakdown (by layer count):")
    for unit, count in sorted(layer_count_by_unit.items(), key=lambda kv: -kv[1]):
        pct = 100 * count / total_layers if total_layers else 0
        lines.append(f"  {unit:>10}: {count:>4} layers ({pct:5.1f}%)")

    if total_time_us:
        lines.append("Compute unit breakdown (by execution time):")
        for unit, us in sorted(time_us_by_unit.items(), key=lambda kv: -kv[1]):
            pct = 100 * us / total_time_us
            lines.append(f"  {unit:>10}: {us / 1000:8.3f} ms ({pct:5.1f}%)")

    return "\n".join(lines)


def append_experiment_log(compile_job, profile_job, profile: dict, console_summary: str):
    summary = profile.get("execution_summary", {})
    inference_us = summary.get("estimated_inference_time")
    inference_ms = (
        inference_us / 1000 if isinstance(inference_us, (int, float)) else None
    )
    inference_str = f"{inference_ms:.2f} ms" if inference_ms is not None else "n/a"
    date_str = time.strftime("%Y-%m-%d")

    entry = f"""
## Edge conversion — AI Hub compile + profile (Samsung Galaxy S25)

- **Date:** {date_str}
- **Script:** `export/profile_aihub.py`
- **Device:** {DEVICE_NAME}
- **Compile job:** {compile_job.url} (no explicit target_runtime)
- **Profile job:** {profile_job.url}
- **Output:** `export/models/weights_float.tflite`
- **Estimated inference time:** {inference_str}

```
{console_summary}
```
"""

    with open(EXPERIMENTS_LOG, "a", encoding="utf-8") as f:
        f.write(entry)

    print(f"\nAppended summary to {EXPERIMENTS_LOG}")


def main():
    if not ONNX_PATH.exists():
        raise FileNotFoundError(f"{ONNX_PATH} not found - run export/to_onnx.py first")

    device = hub.Device(DEVICE_NAME, os=DEVICE_OS)

    print(f"Submitting compile job for {ONNX_PATH.name} on {DEVICE_NAME} ...")
    compile_job = hub.submit_compile_job(
        model=str(ONNX_PATH),
        device=device,
        name="resell-box-ai yolo11s (onnx -> tflite)",
    )
    wait_for_job(compile_job, "compile")

    target_model = compile_job.get_target_model()
    if target_model is None:
        raise RuntimeError("compile job succeeded but produced no target model")

    TFLITE_PATH.parent.mkdir(parents=True, exist_ok=True)
    downloaded_path = target_model.download(str(TFLITE_PATH))
    print(f"Compiled tflite downloaded to: {downloaded_path}")

    print(f"\nSubmitting profile job on {DEVICE_NAME} ...")
    profile_job = hub.submit_profile_job(
        model=target_model,
        device=device,
        name="resell-box-ai yolo11s (profile)",
    )
    wait_for_job(profile_job, "profile")

    profile = profile_job.download_profile()
    assert isinstance(profile, dict)

    console_summary = summarize_profile(profile)
    print("\n=== Profile summary ===")
    print(console_summary)

    append_experiment_log(compile_job, profile_job, profile, console_summary)


if __name__ == "__main__":
    main()
