import 'dart:io';

import '../../services/qnn_service.dart';
import '../models/analysis_result.dart';
import 'analysis_repository.dart';

class QnnAnalysisRepository implements AnalysisRepository {
  QnnAnalysisRepository({QnnService? service})
      : _service = service ?? QnnService();

  final QnnService _service;

  @override
  Future<AnalysisResult> analyze(File image) async {
    final result = await _service.analyze(image);
    return AnalysisResult.fromJson(result);
  }
}
