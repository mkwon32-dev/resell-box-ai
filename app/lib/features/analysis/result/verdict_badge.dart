import 'package:flutter/material.dart';

import '../../../app/theme.dart';
import '../../../app/tokens.dart';
import '../../../data/models/analysis_result.dart';
import '../../../data/models/detection.dart';
import '../../../data/models/risk_verdict.dart';

/// Full-width verdict banner: the only loud element in the app.
/// "HIGH RISK · tear 9.2 cm" — verdict word + damage fact, nothing else.
class VerdictBanner extends StatelessWidget {
  const VerdictBanner({super.key, required this.result});

  final AnalysisResult result;

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    final verdict = result.verdict;
    final headline = result.headline;

    final fact = switch ((headline, result.scaleSource.hasScale)) {
      (null, _) => 'clean box',
      (final d?, true) when d.longestSideCm != null =>
        '${d.damageClass.label.toLowerCase()} '
            '~${d.longestSideCm!.toStringAsFixed(1)} cm',
      (final d?, _) => d.damageClass.label.toLowerCase(),
    };

    return Container(
      width: double.infinity,
      color: verdict.color,
      padding: const EdgeInsets.symmetric(
        horizontal: AppTokens.s5,
        vertical: AppTokens.s4,
      ),
      child: Wrap(
        spacing: AppTokens.s3,
        runSpacing: AppTokens.s1,
        crossAxisAlignment: WrapCrossAlignment.end,
        children: [
          Text(
            verdict.label,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: text.displaySmall?.copyWith(color: AppTokens.onVerdict),
          ),
          Padding(
            padding: const EdgeInsets.only(bottom: 6),
            child: Text(
              '· $fact',
              style: AppText.mono(
                size: 14,
                color: AppTokens.onVerdict,
                weight: FontWeight.w600,
              ),
            ),
          ),
        ],
      ),
    );
  }
}
