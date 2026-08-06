import 'package:flutter_test/flutter_test.dart';
import 'package:resellbox_app/data/models/analysis_result.dart';
import 'package:resellbox_app/data/models/detection.dart';
import 'package:resellbox_app/data/models/risk_verdict.dart';

Detection d(DamageClass cls, {double? cm}) => Detection(
  x: 10,
  y: 10,
  width: 5,
  height: 5,
  damageClass: cls,
  confidence: .9,
  widthCm: cm,
  heightCm: cm == null ? null : cm / 2,
);

void main() {
  group('computeVerdict rules', () {
    test('no detections → low', () {
      expect(computeVerdict([]), RiskVerdict.low);
    });

    test('tear ≥ 9cm → high', () {
      expect(computeVerdict([d(DamageClass.tear, cm: 9.2)]), RiskVerdict.high);
    });

    test('tear below 9cm → caution', () {
      expect(computeVerdict([d(DamageClass.tear, cm: 3)]), RiskVerdict.caution);
    });

    test('small scratch → low', () {
      expect(computeVerdict([d(DamageClass.scratch, cm: 2)]), RiskVerdict.low);
    });

    test('medium dent → caution', () {
      expect(computeVerdict([d(DamageClass.dent, cm: 5)]), RiskVerdict.caution);
    });

    test('worst detection wins', () {
      expect(
        computeVerdict([
          d(DamageClass.scratch, cm: 1),
          d(DamageClass.tear, cm: 12),
          d(DamageClass.dent, cm: 5),
        ]),
        RiskVerdict.high,
      );
    });

    test('unsized tear still caution (unknown size ≠ clean)', () {
      expect(computeVerdict([d(DamageClass.tear)]), RiskVerdict.caution);
    });

    test('unsized dent/scratch floor at caution (no box edges ≠ low risk)', () {
      expect(computeVerdict([d(DamageClass.dent)]), RiskVerdict.caution);
      expect(computeVerdict([d(DamageClass.scratch)]), RiskVerdict.caution);
      expect(
        computeVerdict([d(DamageClass.surfaceDamage)]),
        RiskVerdict.caution,
      );
    });
  });

  group('aggregate escalations', () {
    test('3+ small detections floor at caution even if each is low', () {
      final small = [
        d(DamageClass.dent, cm: 1),
        d(DamageClass.scratch, cm: 1),
        d(DamageClass.surfaceDamage, cm: 1),
      ];
      expect(computeVerdict(small), RiskVerdict.caution);
      expect(computeVerdict(small.sublist(0, 2)), RiskVerdict.low);
    });

    test('sized damage covering ≥20% of the frame → high', () {
      final wide = Detection(
        x: 320,
        y: 240,
        width: 400,
        height: 200,
        damageClass: DamageClass.dent,
        confidence: .9,
        widthCm: 3, // per-detection rule alone would say low
        heightCm: 1.5,
      );
      expect(
        computeVerdict(
          [wide],
          imageWidth: 640,
          imageHeight: 480,
          hasScale: true,
        ),
        RiskVerdict.high,
      );
    });

    test('area rule is inert without scale (close-up fills the frame)', () {
      final closeUp = Detection(
        x: 320,
        y: 240,
        width: 600,
        height: 400,
        damageClass: DamageClass.dent,
        confidence: .9,
      );
      expect(
        computeVerdict([closeUp], imageWidth: 640, imageHeight: 480),
        RiskVerdict.caution, // unsized floor, not area-escalated high
      );
    });
  });

  group('string parsing', () {
    test('RiskVerdict.fromString case-insensitive', () {
      expect(RiskVerdict.fromString('HIGH'), RiskVerdict.high);
      expect(RiskVerdict.fromString('Low'), RiskVerdict.low);
      expect(RiskVerdict.fromString('garbage'), RiskVerdict.caution);
    });

    test('DamageClass.fromString handles wire names + unknowns', () {
      expect(
        DamageClass.fromString('surface_damage'),
        DamageClass.surfaceDamage,
      );
      expect(
        DamageClass.fromString('surface damage'),
        DamageClass.surfaceDamage,
      );
      expect(DamageClass.fromString('tear'), DamageClass.tear);
      expect(DamageClass.fromString('stain'), DamageClass.unknown);
    });
  });

  group('AnalysisResult JSON round-trip', () {
    test('fromJson/toJson preserves fields, computes verdict when absent', () {
      final json = {
        'image': {'width': 640, 'height': 480},
        'predictions': [
          {
            'x': 320.0,
            'y': 240.0,
            'width': 100.0,
            'height': 50.0,
            'class': 'tear',
            'confidence': 0.95,
            'width_cm': 9.5,
            'height_cm': 2.0,
          },
        ],
        'scale_source': 'box_face',
      };
      final result = AnalysisResult.fromJson(json);
      expect(result.imageWidth, 640);
      expect(result.verdict, RiskVerdict.high); // computed, ≥9cm tear
      expect(result.detections.single.damageClass, DamageClass.tear);
      expect(result.headline?.longestSideCm, 9.5);

      final round = AnalysisResult.fromJson(result.toJson());
      expect(round.verdict, RiskVerdict.high);
      expect(round.detections.single.widthCm, 9.5);
      expect(round.scaleSource, ScaleSource.boxFace);
      expect(round.toJson()['scale_source'], 'box_face');
    });

    test('legacy card_detected records still parse', () {
      // Old drift-stored results predate scale_source.
      expect(
        AnalysisResult.fromJson({
          'predictions': <Object?>[],
          'card_detected': true,
        }).scaleSource,
        ScaleSource.boxFace,
      );
      expect(
        AnalysisResult.fromJson({
          'predictions': <Object?>[],
          'card_detected': false,
        }).scaleSource,
        ScaleSource.none,
      );
      expect(
        AnalysisResult.fromJson({'predictions': <Object?>[]}).scaleSource,
        ScaleSource.none,
      );
    });

    test('unknown scale_source degrades to none, scale_source wins over '
        'legacy flag', () {
      expect(
        AnalysisResult.fromJson({
          'predictions': <Object?>[],
          'scale_source': 'satellite',
        }).scaleSource,
        ScaleSource.none,
      );
      expect(
        AnalysisResult.fromJson({
          'predictions': <Object?>[],
          'scale_source': 'box_edge',
          'card_detected': false,
        }).scaleSource,
        ScaleSource.boxEdge,
      );
    });
  });
}
