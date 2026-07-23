import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:resellbox_app/app/theme.dart';
import 'package:resellbox_app/app/tokens.dart';
import 'package:resellbox_app/data/models/analysis_result.dart';
import 'package:resellbox_app/data/models/detection.dart';
import 'package:resellbox_app/data/models/risk_verdict.dart';
import 'package:resellbox_app/features/analysis/result/verdict_badge.dart';

AnalysisResult result(
  RiskVerdict verdict,
  List<Detection> detections, {
  ScaleSource scale = ScaleSource.boxFace,
}) => AnalysisResult(
  imageWidth: 640,
  imageHeight: 480,
  detections: detections,
  verdict: verdict,
  scaleSource: scale,
);

Widget host(Widget child) => MaterialApp(
  theme: buildAppTheme(),
  home: Scaffold(body: child),
);

void main() {
  testWidgets('high verdict shows label, fact, and red banner', (tester) async {
    await tester.pumpWidget(
      host(
        VerdictBanner(
          result: result(RiskVerdict.high, [
            const Detection(
              x: 1,
              y: 1,
              width: 1,
              height: 1,
              damageClass: DamageClass.tear,
              confidence: .95,
              widthCm: 9.2,
              heightCm: 2.0,
            ),
          ]),
        ),
      ),
    );

    expect(find.text('HIGH RISK'), findsOneWidget);
    expect(find.textContaining('tear ~9.2 cm'), findsOneWidget);
    final container = tester.widget<Container>(
      find
          .ancestor(
            of: find.text('HIGH RISK'),
            matching: find.byType(Container),
          )
          .first,
    );
    expect(container.color, AppTokens.riskHigh);
  });

  testWidgets('clean box shows LOW RISK · clean box', (tester) async {
    await tester.pumpWidget(
      host(VerdictBanner(result: result(RiskVerdict.low, []))),
    );
    expect(find.text('LOW RISK'), findsOneWidget);
    expect(find.textContaining('clean box'), findsOneWidget);
  });

  testWidgets('unsized damage cites class without cm', (tester) async {
    await tester.pumpWidget(
      host(
        VerdictBanner(
          result: result(RiskVerdict.caution, [
            const Detection(
              x: 1,
              y: 1,
              width: 1,
              height: 1,
              damageClass: DamageClass.dent,
              confidence: .8,
            ),
          ], scale: ScaleSource.none),
        ),
      ),
    );
    expect(find.text('CAUTION'), findsOneWidget);
    expect(find.textContaining('dent'), findsOneWidget);
    expect(find.textContaining('cm'), findsNothing);
  });
}
