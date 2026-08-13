import 'dart:io';

import 'package:flutter/foundation.dart';

import '../../services/qnn_service.dart';
import '../models/analysis_result.dart';
import 'analysis_repository.dart';

class QnnAnalysisRepository implements AnalysisRepository {
  QnnAnalysisRepository({QnnService? service})
      : _service = service ?? QnnService();

  final QnnService _service;

  @override
  Future<AnalysisResult> analyze(File image) async {
    try {
      final rawResult = await _service.analyze(image);
      final result = _normalizeMap(rawResult);
      // Photo paths and full detection payloads are user data; keep the
      // trace out of release logs. debugPrint still prints in release builds.
      if (kDebugMode) {
        debugPrint('QNN analyze: ${image.path}');
        debugPrint('QNN normalized result: $result');
      }
      return AnalysisResult.fromJson(result);
    } catch (error, stackTrace) {
      debugPrint('QNN ERROR: $error');
      if (kDebugMode) debugPrintStack(stackTrace: stackTrace);
      rethrow;
    }
  }

  Map<String, dynamic> _normalizeMap(Map<dynamic, dynamic> source) {
    return source.map(
      (key, value) => MapEntry(
        key.toString(),
        _normalizeValue(value),
      ),
    );
  }

  dynamic _normalizeValue(dynamic value) {
    if (value is Map) {
      return _normalizeMap(value);
    }

    if (value is List) {
      return value.map(_normalizeValue).toList();
    }

    return value;
  }
}
