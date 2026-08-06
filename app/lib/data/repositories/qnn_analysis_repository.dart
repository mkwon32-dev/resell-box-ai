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
    debugPrint('QNN STEP 1: Repository called');
    debugPrint('QNN image path: ${image.path}');

    try {
      final rawResult = await _service.analyze(image);
      debugPrint('QNN STEP 2: Native result received');
      debugPrint('QNN raw result: $rawResult');

      final result = _normalizeMap(rawResult);
      debugPrint('QNN STEP 3: Result normalized');
      debugPrint('QNN normalized result: $result');

      final parsed = AnalysisResult.fromJson(result);
      debugPrint('QNN STEP 4: Result parsed successfully');

      return parsed;
    } catch (error, stackTrace) {
      debugPrint('QNN ERROR: $error');
      debugPrintStack(stackTrace: stackTrace);
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
