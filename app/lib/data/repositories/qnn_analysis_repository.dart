import 'dart:io';

import '../../services/qnn_service.dart';
import '../models/analysis_result.dart';
import 'analysis_repository.dart';

/// On-device analysis via the native TFLite bridge (MainActivity.kt,
/// channel `com.resellbox.ai/qnn`). Android only.
class QnnAnalysisRepository implements AnalysisRepository {
  QnnAnalysisRepository({QnnService? service})
      : _service = service ?? QnnService();

  final QnnService _service;

  @override
  Future<AnalysisResult> analyze(File image) async {
    final raw = await _service.analyze(image);
    return AnalysisResult.fromJson(_normalizeMap(raw));
  }

  /// Platform channels deliver `Map<Object?, Object?>`; the parser wants
  /// string-keyed maps all the way down.
  Map<String, dynamic> _normalizeMap(Map<dynamic, dynamic> source) {
    return source.map(
      (key, value) => MapEntry(key.toString(), _normalizeValue(value)),
    );
  }

  dynamic _normalizeValue(dynamic value) {
    if (value is Map) return _normalizeMap(value);
    if (value is List) return value.map(_normalizeValue).toList();
    return value;
  }
}
