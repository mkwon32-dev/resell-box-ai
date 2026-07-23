import 'package:flutter_test/flutter_test.dart';
import 'package:resellbox_app/data/models/analysis_result.dart';
import 'package:resellbox_app/data/models/detection.dart';
import 'package:resellbox_app/data/models/risk_verdict.dart';

Map<String, dynamic> prediction({
  Object? x = 10,
  Object? widthCm = 1,
  Object? confidence = .9,
}) => {
  'x': x,
  'y': 10,
  'width': 5,
  'height': 4,
  'class': 'tear',
  'confidence': confidence,
  'width_cm': widthCm,
  'height_cm': 1,
};

void main() {
  group('backend model validation', () {
    test('reports malformed prediction shape as a FormatException', () {
      expect(
        () => AnalysisResult.fromJson({'detail': 'backend error'}),
        throwsA(isA<FormatException>()),
      );
      expect(
        () => AnalysisResult.fromJson({'predictions': 'not-a-list'}),
        throwsA(isA<FormatException>()),
      );
      expect(
        () => AnalysisResult.fromJson({
          'predictions': [42],
        }),
        throwsA(
          isA<FormatException>().having(
            (error) => error.message,
            'message',
            contains('index 0'),
          ),
        ),
      );
    });

    test('rejects missing, non-finite, negative, and out-of-range numbers', () {
      final missingX = prediction()..remove('x');
      expect(() => Detection.fromJson(missingX), throwsFormatException);
      expect(
        () => Detection.fromJson(prediction(x: double.nan)),
        throwsFormatException,
      );
      expect(
        () => Detection.fromJson(prediction(widthCm: -1)),
        throwsFormatException,
      );
      expect(
        () => Detection.fromJson(prediction(confidence: 2)),
        throwsFormatException,
      );
    });

    test('rejects malformed image, verdict, and scale fields', () {
      expect(
        () => AnalysisResult.fromJson({'image': []}),
        throwsFormatException,
      );
      expect(
        () => AnalysisResult.fromJson({
          'image': {'width': -1},
        }),
        throwsFormatException,
      );
      expect(
        () => AnalysisResult.fromJson({'verdict': 1}),
        throwsFormatException,
      );
      expect(
        () => AnalysisResult.fromJson({'scale_source': 1}),
        throwsFormatException,
      );
      // Legacy field still type-checked while the fallback exists.
      expect(
        () => AnalysisResult.fromJson({'card_detected': 'yes'}),
        throwsFormatException,
      );
    });

    test('local rules prevent backend from downgrading a high-risk tear', () {
      final result = AnalysisResult.fromJson({
        'predictions': [prediction(widthCm: 10)],
        'verdict': 'low',
        'scale_source': 'box_face',
      });

      expect(result.verdict, RiskVerdict.high);
    });

    test('none scale discards contradictory cm fields before scoring', () {
      final result = AnalysisResult.fromJson({
        'predictions': [prediction(widthCm: 10)],
        'verdict': 'low',
        'scale_source': 'none',
      });

      expect(result.detections.single.widthCm, isNull);
      expect(result.detections.single.heightCm, isNull);
      expect(result.verdict, RiskVerdict.caution);
      final storedPrediction =
          (result.toJson()['predictions'] as List).single
              as Map<String, dynamic>;
      expect(storedPrediction, isNot(contains('width_cm')));
    });

    test('non-finite manually constructed measurements fail safe', () {
      final detection = Detection(
        x: 1,
        y: 1,
        width: 1,
        height: 1,
        damageClass: DamageClass.scratch,
        confidence: 1,
        widthCm: double.nan,
        heightCm: 0,
      );

      expect(computeVerdict([detection]), RiskVerdict.caution);
    });
  });
}
