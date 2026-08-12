import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:resellbox_app/app/router.dart';
import 'package:resellbox_app/app/theme.dart';
import 'package:resellbox_app/data/db/app_database.dart';
import 'package:resellbox_app/data/models/analysis_result.dart';
import 'package:resellbox_app/data/models/detection.dart';
import 'package:resellbox_app/data/models/risk_verdict.dart';
import 'package:resellbox_app/features/analysis/result/result_screen.dart';
import 'package:resellbox_app/features/capture/box_guide_overlay.dart';
import 'package:resellbox_app/features/history/history_providers.dart';
import 'package:resellbox_app/features/home/home_screen.dart';
import 'package:resellbox_app/features/settings/settings_screen.dart';

Widget host(Widget child, {bool reduceMotion = false}) {
  return MaterialApp(
    theme: buildAppTheme(),
    home: MediaQuery(
      data: MediaQueryData(
        disableAnimations: reduceMotion,
        textScaler: const TextScaler.linear(2),
      ),
      child: child,
    ),
  );
}

void main() {
  testWidgets('malformed and incomplete deep links show a safe recovery page', (
    tester,
  ) async {
    appRouter.go('/result/not-a-number', extra: Object());
    await tester.pumpWidget(
      ProviderScope(
        child: MaterialApp.router(
          theme: buildAppTheme(),
          routerConfig: appRouter,
        ),
      ),
    );
    await tester.pump();

    expect(find.text('Invalid scan link.'), findsOneWidget);
    expect(find.text('Back to home'), findsOneWidget);

    appRouter.go('/analyzing');
    await tester.pumpAndSettle();
    expect(
      find.text('Choose a photo before starting analysis.'),
      findsOneWidget,
    );
    appRouter.go('/');
  });

  testWidgets('home distinguishes history failure from an empty history', (
    tester,
  ) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          scanHistoryProvider.overrideWith(
            (ref) => Stream<List<ScanRecordData>>.error('database unavailable'),
          ),
        ],
        child: host(const HomeScreen()),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Could not load scans'), findsOneWidget);
    expect(find.text('No scans yet'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('settings remains scrollable on a short large-text display', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 360);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(host(const SettingsScreen()));

    expect(find.byType(SingleChildScrollView), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('result handles many rows and a missing photo without overflow', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 480);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);

    final detections = List.generate(
      8,
      (index) => Detection(
        x: 100 + index.toDouble(),
        y: 100,
        width: 40,
        height: 20,
        damageClass: DamageClass.surfaceDamage,
        confidence: .85,
        widthCm: 2.5,
        heightCm: 1.5,
      ),
    );
    final record = ScanRecordData(
      id: 7,
      scannedAt: DateTime(2026),
      imagePath: '/file/that/does/not/exist.jpg',
      result: AnalysisResult(
        imageWidth: 640,
        imageHeight: 480,
        detections: detections,
        verdict: RiskVerdict.caution,
        scaleSource: ScaleSource.none,
      ),
    );

    await tester.pumpWidget(
      ProviderScope(
        overrides: [scanByIdProvider.overrideWith((ref, id) async => record)],
        child: host(const ResultScreen(scanId: 7, reveal: false)),
      ),
    );
    await tester.pumpAndSettle();
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(milliseconds: 50)),
    );
    await tester.pump();

    expect(tester.widget<Image>(find.byType(Image)).errorBuilder, isNotNull);
    expect(find.text('No box edges — sizes unavailable'), findsOneWidget);
    expect(find.text('Size: 2.5 × 1.5 cm'), findsNothing);
    expect(
      find.text('Measurement unavailable'),
      findsNWidgets(detections.length),
    );
    expect(find.byType(SingleChildScrollView), findsWidgets);
    expect(tester.takeException(), isNull);
  });

  testWidgets('box guide settles when reduced motion is enabled', (
    tester,
  ) async {
    await tester.pumpWidget(
      host(const Scaffold(body: BoxGuideOverlay()), reduceMotion: true),
    );

    await tester.pumpAndSettle();
    expect(find.text('whole box in frame'), findsOneWidget);
    expect(tester.binding.transientCallbackCount, 0);
  });
}
