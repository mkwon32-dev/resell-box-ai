#!/usr/bin/env python3
"""Focused regression tests for measurement coordinate handling."""

from __future__ import annotations

import unittest
import sys
from pathlib import Path
from unittest.mock import patch

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
import measure_box_face as measurement


class MeasureBoxFaceTest(unittest.TestCase):
    def _measure_with_face(
        self,
        image: np.ndarray,
        bbox: tuple[float, float, float, float],
        face: np.ndarray,
    ) -> dict:
        hull = face.astype(np.int32).reshape(-1, 1, 2)
        with (
            patch.object(measurement, "find_silhouette", return_value=(hull, "edge")),
            patch.object(measurement, "split_faces", return_value=([face], "hex")),
        ):
            return measurement.measure(image, bbox)

    def test_reports_face_scale_in_original_image_pixels(self) -> None:
        # 1200px is resized to the 600px work size. The observed 350px face
        # edge is therefore 700px in the original, or 20px/cm at 35cm.
        image = np.zeros((800, 1200, 3), dtype=np.uint8)
        face = np.array(
            [[100, 100], [450, 100], [450, 350], [100, 350]],
            dtype=np.float64,
        )

        result = self._measure_with_face(image, (400, 400, 100, 100), face)

        self.assertEqual(result["scale_source"], "box_face")
        self.assertAlmostEqual(result["px_per_cm"], 20.0, places=3)

    def test_clips_damage_bbox_to_the_selected_face(self) -> None:
        image = np.zeros((400, 600, 3), dtype=np.uint8)
        face = np.array(
            [[100, 100], [450, 100], [450, 350], [100, 350]],
            dtype=np.float64,
        )
        # The 200px-wide bbox overlaps only the final 100px of the face.
        result = self._measure_with_face(image, (450, 200, 200, 50), face)

        self.assertEqual(result["scale_source"], "box_face")
        self.assertAlmostEqual(result["width_cm"], 10.0, places=2)
        self.assertAlmostEqual(result["long_frac"], 100 / 350, places=4)


if __name__ == "__main__":
    unittest.main()
