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

    final id = await first;
    expect(await db.getScan(id), isNotNull);
    expect(container.read(analysisControllerProvider).value, result);
  });
}
