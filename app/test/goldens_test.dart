// Visual-review goldens: render key screens at phone size with real fonts.
// Regenerate: flutter test test/goldens_test.dart --update-goldens
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:resellbox_app/app/theme.dart';
import 'package:resellbox_app/data/db/app_database.dart';
import 'package:resellbox_app/data/models/analysis_result.dart';
import 'package:resellbox_app/data/models/detection.dart';
import 'package:resellbox_app/data/models/risk_verdict.dart';
import 'package:resellbox_app/features/analysis/result/result_screen.dart';
import 'package:resellbox_app/features/history/history_providers.dart';
import 'package:resellbox_app/features/home/home_screen.dart';

Future<void> loadAppFonts() async {
  for (final (family, asset) in [
    ('BigShouldersDisplay', 'assets/fonts/BigShouldersDisplay.ttf'),
    ('Archivo', 'assets/fonts/Archivo.ttf'),
    ('SplineSansMono', 'assets/fonts/SplineSansMono.ttf'),
  ]) {
    final loader = FontLoader(family)..addFont(rootBundle.load(asset));
    await loader.load();
  }
}

Future<File> materializeSample(WidgetTester tester, String asset) async {
  late File file;
  await tester.runAsync(() async {
    final data = await rootBundle.load(asset);
    file = File('${Directory.systemTemp.path}/golden_${asset.split('/').last}');
    await file.writeAsBytes(data.buffer.asUint8List());
  });
  return file;
}

Future<void> settleFileImages(WidgetTester tester) async {
  for (var i = 0; i < 5; i++) {
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(milliseconds: 120)),
    );
    await tester.pump(const Duration(milliseconds: 120));
  }
}

AnalysisResult highResult() => AnalysisResult(
  imageWidth: 720,
  imageHeight: 540,
  detections: const [
    Detection(
      x: 360,
      y: 238,
      width: 245,
      height: 65,
      damageClass: DamageClass.tear,
      confidence: .95,
      widthCm: 9.2,
      heightCm: 2.4,
    ),
    Detection(
      x: 173,
      y: 367,
      width: 130,
      height: 81,
      damageClass: DamageClass.dent,
      confidence: .88,
      widthCm: 4.4,
      heightCm: 3.8,
    ),
    Detection(
      x: 562,
      y: 335,
      width: 72,
      height: 43,
      damageClass: DamageClass.surfaceDamage,
      confidence: .69,
      widthCm: 2.1,
      heightCm: 1.6,
    ),
  ],
  verdict: RiskVerdict.high,
  scaleSource: ScaleSource.boxFace,
);

Widget app(Widget child) => MaterialApp(theme: buildAppTheme(), home: child);

void main() {
  setUpAll(loadAppFonts);

  testWidgets('golden: home with history', (tester) async {
    tester.view.physicalSize = const Size(390 * 3, 844 * 3);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);

    final photo = await materializeSample(
      tester,
      'assets/samples/sample_dent.jpg',
    );
    final records = [
      for (var i = 0; i < 3; i++)
        ScanRecordData(
          id: i + 1,
          scannedAt: DateTime(2026, 7, 16, 12, 30 + i),
          imagePath: photo.path,
          result: highResult(),
        ),
    ];

    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          scanHistoryProvider.overrideWith((ref) => Stream.value(records)),
        ],
        child: app(const HomeScreen()),
      ),
    );
    await settleFileImages(tester);
    await expectLater(
      find.byType(HomeScreen),
      matchesGoldenFile('goldens/home.png'),
    );
  }, tags: 'golden');

  testWidgets('golden: result screen (high risk, revealed)', (tester) async {
    tester.view.physicalSize = const Size(390 * 3, 844 * 3);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);

    final photo = await materializeSample(
      tester,
      'assets/samples/sample_tear.jpg',
    );
    final record = ScanRecordData(
      id: 1,
      scannedAt: DateTime(2026, 7, 16, 12, 30),
      imagePath: photo.path,
      result: highResult(),
    );

    await tester.pumpWidget(
      ProviderScope(
        overrides: [scanByIdProvider.overrideWith((ref, id) async => record)],
        child: app(const ResultScreen(scanId: 1, reveal: false)),
      ),
    );
    await settleFileImages(tester);
    await expectLater(
      find.byType(ResultScreen),
      matchesGoldenFile('goldens/result.png'),
    );
  }, tags: 'golden');
}
