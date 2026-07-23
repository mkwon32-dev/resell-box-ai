import 'dart:io';

import '../models/analysis_result.dart';

/// The swap point between the demo mock and the real FastAPI backend.
abstract interface class AnalysisRepository {
  Future<AnalysisResult> analyze(File image);
}
