import 'dart:io';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'data/db/app_database.dart';
import 'data/models/analysis_result.dart';
import 'data/repositories/analysis_repository.dart';
import 'data/repositories/qnn_analysis_repository.dart';

/// Real backend (backend/app.py) when built with
/// `--dart-define=BACKEND_URL=http://host:8000`, mock otherwise.
final analysisRepositoryProvider = Provider<AnalysisRepository>(
  (ref) => QnnAnalysisRepository(),
);

final databaseProvider = Provider<AppDatabase>((ref) {
  final db = AppDatabase();
  db.cleanupOrphanedFiles().ignore();
  ref.onDispose(() => db.close().ignore());
  return db;
});

/// Outcome of [AnalysisController.analyze]: either the scan is saved, or a
/// detected reference card needs the user's confirmation first.
sealed class AnalyzeStep {
  const AnalyzeStep();
}

class AnalyzeSaved extends AnalyzeStep {
  const AnalyzeSaved(this.scanId);

  final int scanId;
}

class AnalyzeNeedsCardConfirm extends AnalyzeStep {
  const AnalyzeNeedsCardConfirm(this.result);

  /// Card-based result; `result.cardConfirmation` carries the outline and
  /// the fallback. Resolve with
  /// [AnalysisController.resolveCardConfirmation].
  final AnalysisResult result;
}

/// The in-flight/last analysis for the current scan flow.
class AnalysisController extends AsyncNotifier<AnalysisResult?> {
  bool _isAnalyzing = false;
  File? _pendingPhoto;
  AnalysisResult? _pendingResult;

  @override
  Future<AnalysisResult?> build() async => null;

  Future<AnalyzeStep> analyze(File photo) async {
    if (_isAnalyzing) {
      throw StateError('An analysis is already in progress');
    }
    _isAnalyzing = true;
    _pendingPhoto = null;
    _pendingResult = null;
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
      if (result.cardConfirmation != null) {
        // Hold the save until the user accepts or rejects the card.
        _pendingPhoto = photo;
        _pendingResult = result;
        if (ref.mounted) state = AsyncData(result);
        return AnalyzeNeedsCardConfirm(result);
      }
      final id = await db.saveScan(photo, result);
      if (ref.mounted) state = AsyncData(result);
      return AnalyzeSaved(id);
    } catch (e, st) {
      if (ref.mounted) state = AsyncError(e, st);
      rethrow;
    } finally {
      _isAnalyzing = false;
    }
  }

  /// Save the pending analysis with the card scale ([useCard] true) or the
  /// card-free fallback measurements ([useCard] false).
  Future<int> resolveCardConfirmation({required bool useCard}) async {
    final photo = _pendingPhoto;
    final pending = _pendingResult;
    final confirmation = pending?.cardConfirmation;
    if (photo == null || pending == null || confirmation == null) {
      throw StateError('No card confirmation is pending');
    }
    _pendingPhoto = null;
    _pendingResult = null;
    final chosen = useCard ? pending : confirmation.fallback;
    final db = ref.read(databaseProvider);
    final id = await db.saveScan(photo, chosen);
    if (ref.mounted) state = AsyncData(chosen);
    return id;
  }
}

final analysisControllerProvider =
    AsyncNotifierProvider<AnalysisController, AnalysisResult?>(
      AnalysisController.new,
    );
