import 'package:flutter/material.dart';

import '../../../app/theme.dart';
import '../../../app/tokens.dart';
import '../../../data/models/detection.dart';

/// Spec-sheet row: no card, no container — typography does the work.
/// `1  DENT   3.2 × 1.1 cm   92%`
class DamageListTile extends StatelessWidget {
  const DamageListTile({
    super.key,
    required this.index,
    required this.detection,
    required this.sizesAvailable,
    required this.highlighted,
    required this.onTap,
  });

  final int index;
  final Detection detection;
  final bool sizesAvailable;
  final bool highlighted;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final d = detection;
    final sizeText = switch (d.damageClass) {
      DamageClass.dent => d.hasSize
          ? 'Size: ${d.widthCm!.toStringAsFixed(1)} × ${d.heightCm!.toStringAsFixed(1)} cm'
          : 'Measurement unavailable',
      DamageClass.surfaceDamage => d.longestSideCm != null && d.longestSideCm!.isFinite
          ? 'Length: ${d.longestSideCm!.toStringAsFixed(1)} cm'
          : 'Measurement unavailable',
      _ => d.hasSize
          ? 'Size: ${d.widthCm!.toStringAsFixed(1)} × ${d.heightCm!.toStringAsFixed(1)} cm'
          : 'Measurement unavailable',
    };
    final displayText = sizesAvailable ? sizeText : 'Measurement unavailable';

    return InkWell(
      onTap: onTap,
      child: AnimatedContainer(
        duration: AppTokens.tFast,
        color: highlighted ? AppTokens.surfaceRaised : Colors.transparent,
        padding: const EdgeInsets.symmetric(
          horizontal: AppTokens.s5,
          vertical: AppTokens.s3,
        ),
        child: Row(
          children: [
            SizedBox(
              width: 28,
              child: Container(
                width: 20,
                height: 20,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  color: d.damageClass.color,
                  shape: BoxShape.circle,
                ),
                child: Text(
                  '${index + 1}',
                  style: const TextStyle(
                    color: AppTokens.onVerdict,
                    fontSize: 11,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
            ),
            const SizedBox(width: AppTokens.s3),
            SizedBox(
              width: 88,
              child: Text(
                d.damageClass.label,
                style: Theme.of(context).textTheme.labelSmall?.copyWith(
                  color: AppTokens.textPrimary,
                  letterSpacing: 1.2,
                ),
              ),
            ),
            Expanded(child: Text(displayText, style: AppText.mono(size: 14))),
            Text(
              '${(d.confidence * 100).round()}%',
              style: AppText.mono(size: 13, color: AppTokens.textSecondary),
            ),
          ],
        ),
      ),
    );
  }
}
