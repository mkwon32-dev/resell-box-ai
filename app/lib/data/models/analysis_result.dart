import 'detection.dart';
import 'risk_verdict.dart';

/// How the backend established real-world scale for the cm estimates.
enum ScaleSource {
  /// No box edges in frame (close-up); sizes unavailable.
  none('none'),

  /// Coarse scale from the box silhouette's long edge (nominal box length).
  boxEdge('box_edge'),

  /// Box face rectified via homography; sizes are panel-relative estimates.
  boxFace('box_face');

  const ScaleSource(this.wire);

  final String wire;

  bool get hasScale => this != ScaleSource.none;

  static ScaleSource fromWire(String value) => switch (value) {
    'box_edge' => ScaleSource.boxEdge,
    'box_face' => ScaleSource.boxFace,
    _ => ScaleSource.none,
  };
}

class AnalysisResult {
  const AnalysisResult({
    required this.imageWidth,
    required this.imageHeight,
    required this.detections,
    required this.verdict,
    required this.scaleSource,
  });

  final int imageWidth;
  final int imageHeight;
  final List<Detection> detections;
  final RiskVerdict verdict;
  final ScaleSource scaleSource;

  /// The single damage fact cited next to the verdict word:
  /// largest measured damage, e.g. "tear 9.2 cm", or the first unsized
  /// detection when measurements are unavailable. Null only when clean.
  Detection? get headline {
    Detection? best;
    for (final d in detections) {
      final len = d.longestSideCm;
      if (len == null) continue;
      if (best == null || len > (best.longestSideCm ?? 0)) best = d;
    }
    return best ?? (detections.isEmpty ? null : detections.first);
  }

  factory AnalysisResult.fromJson(Map<String, dynamic> json) {
    final rawPredictions = json['predictions'];
    if (rawPredictions is! List) {
      throw const FormatException('"predictions" must be a list');
    }
    final detections = <Detection>[];
    for (final (index, raw) in rawPredictions.indexed) {
      if (raw is! Map<String, dynamic>) {
        throw FormatException('Prediction at index $index must be an object');
      }
      try {
        detections.add(Detection.fromJson(raw));
      } on FormatException catch (error) {
        throw FormatException(
          'Invalid prediction at index $index: ${error.message}',
        );
      }
    }

    final rawImage = json['image'];
    if (rawImage != null && rawImage is! Map<String, dynamic>) {
      throw const FormatException('"image" must be an object');
    }
    final image = rawImage as Map<String, dynamic>? ?? const {};
    final imageWidth = _imageDimension(image, 'width');
    final imageHeight = _imageDimension(image, 'height');

    final rawVerdict = json['verdict'];
    if (rawVerdict != null && rawVerdict is! String) {
      throw const FormatException('"verdict" must be a string');
    }
    final rawScaleSource = json['scale_source'];
    if (rawScaleSource != null && rawScaleSource is! String) {
      throw const FormatException('"scale_source" must be a string');
    }
    // Legacy records (pre box-face sizing) carried a boolean "card_detected".
    final rawCardDetected = json['card_detected'];
    if (rawCardDetected != null && rawCardDetected is! bool) {
      throw const FormatException('"card_detected" must be a boolean');
    }
    final scaleSource = rawScaleSource != null
        ? ScaleSource.fromWire(rawScaleSource as String)
        : (rawCardDetected as bool? ?? false)
        ? ScaleSource.boxFace
        : ScaleSource.none;

    // `none` means there is no defensible pixel-to-cm conversion. Discard
    // stale/contradictory measurement fields so they cannot silently drive
    // risk rules while the UI correctly says sizes are unavailable.
    final usableDetections = scaleSource.hasScale
        ? detections
        : detections.map((d) => d.withoutMeasurements()).toList();

    // Never allow a stale or compromised backend verdict to downgrade damage
    // that the documented local rules classify more severely.
    final computedVerdict = computeVerdict(usableDetections);
    final backendVerdict = rawVerdict == null
        ? computedVerdict
        : RiskVerdict.fromString(rawVerdict as String);
    return AnalysisResult(
      imageWidth: imageWidth,
      imageHeight: imageHeight,
      detections: List.unmodifiable(usableDetections),
      verdict: backendVerdict.index >= computedVerdict.index
          ? backendVerdict
          : computedVerdict,
      scaleSource: scaleSource,
    );
  }

  Map<String, dynamic> toJson() => {
    'image': {'width': imageWidth, 'height': imageHeight},
    'predictions': detections.map((d) => d.toJson()).toList(),
    'verdict': verdict.wire,
    'scale_source': scaleSource.wire,
  };
}

int _imageDimension(Map<String, dynamic> image, String key) {
  final raw = image[key];
  if (raw == null) return 0;
  if (raw is! num || !raw.toDouble().isFinite || raw < 0) {
    throw FormatException('Image "$key" must be a non-negative number');
  }
  return raw.toInt();
}
