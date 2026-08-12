import 'dart:async';
import 'dart:io';

import 'package:drift/native.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:resellbox_app/data/db/app_database.dart';
import 'package:resellbox_app/data/models/analysis_result.dart';
import 'package:resellbox_app/data/models/risk_verdict.dart';
import 'package:resellbox_app/data/repositories/analysis_repository.dart';
import 'package:resellbox_app/providers.dart';

class _ControlledRepository implements AnalysisRepository {
  final completer = Completer<AnalysisResult>();

  @override
  Future<AnalysisResult> analyze(File image) => completer.future;
}

const result = AnalysisResult(
  imageWidth: 1,
  imageHeight: 1,
  detections: [],
  verdict: RiskVerdict.low,
  scaleSource: ScaleSource.none,
);

void main() {
  test('a second analysis cannot race the in-flight scan', () async {
    final temp = await Directory.systemTemp.createTemp(
      'resell_controller_test_',
    );
    final photo = await File('${temp.path}/photo.jpg').writeAsBytes([1]);
    final db = AppDatabase.forTesting(
      NativeDatabase.memory(),
      documentsDirectory: temp,
    );
    final repository = _ControlledRepository();
    final container = ProviderContainer(
      overrides: [
        databaseProvider.overrideWithValue(db),
        analysisRepositoryProvider.overrideWithValue(repository),
      ],
    );
    addTearDown(() async {
      container.dispose();
      await db.close();
      await temp.delete(recursive: true);
    });
    await container.read(analysisControllerProvider.future);
    final controller = container.read(analysisControllerProvider.notifier);

    final first = controller.analyze(photo);
    await expectLater(controller.analyze(photo), throwsA(isA<StateError>()));
    repository.completer.complete(result);

    final step = await first;
    final id = (step as AnalyzeSaved).scanId;
    expect(await db.getScan(id), isNotNull);
    expect(container.read(analysisControllerProvider).value, result);
  });

  test('card confirmation defers the save and honors the choice', () async {
    final temp = await Directory.systemTemp.createTemp(
      'resell_controller_test_',
    );
    final photo = await File('${temp.path}/photo.jpg').writeAsBytes([1]);
    final db = AppDatabase.forTesting(
      NativeDatabase.memory(),
      documentsDirectory: temp,
    );
    final repository = _ControlledRepository();
    final container = ProviderContainer(
      overrides: [
        databaseProvider.overrideWithValue(db),
        analysisRepositoryProvider.overrideWithValue(repository),
      ],
    );
    addTearDown(() async {
      container.dispose();
      await db.close();
      await temp.delete(recursive: true);
    });
    await container.read(analysisControllerProvider.future);
    final controller = container.read(analysisControllerProvider.notifier);

    final cardResult = AnalysisResult.fromJson({
      'image': {'width': 640, 'height': 480},
      'scale_source': 'card',
      'card': {
        'corners': [
          {'x': 10, 'y': 10},
          {'x': 110, 'y': 10},
          {'x': 110, 'y': 73},
          {'x': 10, 'y': 73},
        ],
        'fallback_scale_source': 'box_face',
      },
      'predictions': [
        {
          'x': 320,
          'y': 240,
          'width': 60,
          'height': 40,
          'class': 'dent',
          'confidence': 0.9,
          'width_cm': 3.0,
          'height_cm': 2.0,
          'fallback_width_cm': 5.0,
          'fallback_height_cm': 4.0,
        },
      ],
    });
    final confirmation = cardResult.cardConfirmation;
    expect(confirmation, isNotNull);
    expect(confirmation!.corners, hasLength(4));
    expect(cardResult.scaleSource, ScaleSource.card);
    expect(cardResult.detections.single.widthCm, 3.0);
    expect(confirmation.fallback.scaleSource, ScaleSource.boxFace);
    expect(confirmation.fallback.detections.single.widthCm, 5.0);
    expect(confirmation.fallback.cardConfirmation, isNull);

    final future = controller.analyze(photo);
    repository.completer.complete(cardResult);
    final step = await future;
    expect(step, isA<AnalyzeNeedsCardConfirm>());
    expect(
      await db.watchAllScans().first,
      isEmpty,
      reason: 'save must be deferred',
    );

    final id = await controller.resolveCardConfirmation(useCard: false);
    final record = await db.getScan(id);
    expect(record!.result.scaleSource, ScaleSource.boxFace);
    expect(record.result.detections.single.widthCm, 5.0);

    await expectLater(
      controller.resolveCardConfirmation(useCard: true),
      throwsA(isA<StateError>()),
      reason: 'pending confirmation is single-use',
    );
  });
}
