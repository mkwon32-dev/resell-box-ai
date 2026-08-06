import 'dart:io';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'data/db/app_database.dart';
import 'data/models/analysis_result.dart';
import 'data/repositories/analysis_repository.dart';
import 'data/repositories/http_analysis_repository.dart';
import 'data/repositories/mock_analysis_repository.dart';
import 'data/repositories/qnn_analysis_repository.dart';

/// Backend (backend/app.py, box_face-quality sizing) when built with
/// `--dart-define=BACKEND_URL=http://host:8000`; otherwise on-device
/// TFLite inference on Android and the mock everywhere else.
const _backendUrl = String.fromEnvironment('BACKEND_URL');

final analysisRepositoryProvider = Provider<AnalysisRepository>((ref) {
  if (_backendUrl.isNotEmpty) {
    return HttpAnalysisRepository(baseUrl: _backendUrl);
  }
  return Platform.isAndroid ? QnnAnalysisRepository() : MockAnalysisRepository();
});

final databaseProvider = Provider<AppDatabase>((ref) {
  final db = AppDatabase();
  db.cleanupOrphanedFiles().ignore();
  ref.onDispose(() => db.close().ignore());
  return db;
});

/// The in-flight/last analysis for the current scan flow.
class AnalysisController extends AsyncNotifier<AnalysisResult?> {
  bool _isAnalyzing = false;

  @override
  Future<AnalysisResult?> build() async => null;

  Future<int> analyze(File photo) async {
    if (_isAnalyzing) {
      throw StateError('An analysis is already in progress');
    }
    _isAnalyzing = true;
    state = const AsyncLoading();
    final repo = ref.read(analysisRepositoryProvider);
    final db = ref.read(databaseProvider);
    try {
      // Timeout only the inference; never interrupt the local save,
      // or an abandoned request could still write a ghost scan.
      final result = await repo
          .analyze(photo)
          .timeout(const Duration(seconds: 18));
      if (!ref.mounted) throw StateError('Analysis was cancelled');
      final id = await db.saveScan(photo, result);
      if (ref.mounted) state = AsyncData(result);
      return id;
    } catch (e, st) {
      if (ref.mounted) state = AsyncError(e, st);
      rethrow;
    } finally {
      _isAnalyzing = false;
    }
  }
}

final analysisControllerProvider =
    AsyncNotifierProvider<AnalysisController, AnalysisResult?>(
      AnalysisController.new,
    );
