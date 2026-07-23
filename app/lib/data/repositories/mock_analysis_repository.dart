import 'dart:io';
import 'dart:ui' as ui;

import '../models/analysis_result.dart';
import '../models/detection.dart';
import '../models/risk_verdict.dart';
import 'analysis_repository.dart';

/// Demo repository: no backend required. Decodes the submitted photo's real
/// dimensions and places canned detections proportionally so overlays land
/// plausibly on any image. Cycles four scenarios (Low / Caution / High /
/// close-up with no box edges in frame, so no sizes).
class MockAnalysisRepository implements AnalysisRepository {
  MockAnalysisRepository({this.delay = const Duration(milliseconds: 2600)});

  final Duration delay;
  int _call = 0;

  @override
  Future<AnalysisResult> analyze(File image) async {
    final bytes = await image.readAsBytes();
    final codec = await ui.instantiateImageCodec(bytes);
    late final double w;
    late final double h;
    try {
      final frame = await codec.getNextFrame();
      try {
        w = frame.image.width.toDouble();
        h = frame.image.height.toDouble();
      } finally {
        frame.image.dispose();
      }
    } finally {
      codec.dispose();
    }

    await Future<void>.delayed(delay);

    final scenario = _call++ % 4;
    final detections = switch (scenario) {
      // Clean-ish box: one small scuff → LOW.
      0 => [
        _d(
          w,
          h,
          cx: .62,
          cy: .38,
          bw: .10,
          bh: .06,
          cls: DamageClass.scratch,
          conf: .81,
          wCm: 1.8,
          hCm: 0.6,
        ),
      ],
      // Dent + scuff → CAUTION.
      1 => [
        _d(
          w,
          h,
          cx: .42,
          cy: .55,
          bw: .22,
          bh: .16,
          cls: DamageClass.dent,
          conf: .92,
          wCm: 5.6,
          hCm: 3.2,
        ),
        _d(
          w,
          h,
          cx: .71,
          cy: .30,
          bw: .12,
          bh: .07,
          cls: DamageClass.surfaceDamage,
          conf: .74,
          wCm: 2.9,
          hCm: 1.1,
        ),
      ],
      // Close-up: tear fills the frame, no box edges → unsized, CAUTION floor.
      2 => [
        _d(
          w,
          h,
          cx: .52,
          cy: .48,
          bw: .46,
          bh: .20,
          cls: DamageClass.tear,
          conf: .90,
        ),
      ],
      // Big tear + crushed corner → HIGH.
      _ => [
        _d(
          w,
          h,
          cx: .50,
          cy: .44,
          bw: .34,
          bh: .12,
          cls: DamageClass.tear,
          conf: .95,
          wCm: 9.2,
          hCm: 2.4,
        ),
        _d(
          w,
          h,
          cx: .24,
          cy: .68,
          bw: .18,
          bh: .15,
          cls: DamageClass.dent,
          conf: .88,
          wCm: 4.4,
          hCm: 3.8,
        ),
        _d(
          w,
          h,
          cx: .78,
          cy: .62,
          bw: .10,
          bh: .08,
          cls: DamageClass.surfaceDamage,
          conf: .69,
          wCm: 2.1,
          hCm: 1.6,
        ),
      ],
    };

    return AnalysisResult(
      imageWidth: w.round(),
      imageHeight: h.round(),
      detections: detections,
      verdict: computeVerdict(detections),
      scaleSource: scenario == 2 ? ScaleSource.none : ScaleSource.boxFace,
    );
  }

  Detection _d(
    double w,
    double h, {
    required double cx,
    required double cy,
    required double bw,
    required double bh,
    required DamageClass cls,
    required double conf,
    double? wCm,
    double? hCm,
  }) => Detection(
    x: w * cx,
    y: h * cy,
    width: w * bw,
    height: h * bh,
    damageClass: cls,
    confidence: conf,
    widthCm: wCm,
    heightCm: hCm,
  );
}
