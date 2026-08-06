import 'package:flutter/material.dart';

import '../../app/tokens.dart';
import 'detection.dart';

enum RiskVerdict {
  low,
  caution,
  high;

  static RiskVerdict fromString(String? raw) {
    switch (raw?.trim().toLowerCase()) {
      case 'low':
        return RiskVerdict.low;
      case 'caution':
      case 'medium':
        return RiskVerdict.caution;
      case 'high':
        return RiskVerdict.high;
      default:
        return RiskVerdict.caution;
    }
  }

  String get wire => name;
}

extension RiskVerdictX on RiskVerdict {
  Color get color => switch (this) {
    RiskVerdict.low => AppTokens.riskLow,
    RiskVerdict.caution => AppTokens.riskCaution,
    RiskVerdict.high => AppTokens.riskHigh,
  };

  String get label => switch (this) {
    RiskVerdict.low => 'LOW RISK',
    RiskVerdict.caution => 'CAUTION',
    RiskVerdict.high => 'HIGH RISK',
  };

  String get shortLabel => switch (this) {
    RiskVerdict.low => 'LOW',
    RiskVerdict.caution => 'CAUTION',
    RiskVerdict.high => 'HIGH',
  };
}

/// Client-side fallback implementing the README risk rules.
/// Used by the mock repository and whenever the backend omits a verdict.
///
/// Besides the per-detection type + longest-side rules, two aggregate
/// escalations cover damage no single detection captures:
///  - 3+ detections floor at caution: widespread damage stays serious
///    even when every individual spot is small.
///  - With real scale (box_edge/box_face — the frame is roughly the box),
///    detections covering ≥20% of the image mean a large share of the box
///    is damaged → high. Gated on scale because a close-up of one small
///    dent fills the frame without meaning anything about the box.
RiskVerdict computeVerdict(
  List<Detection> detections, {
  int imageWidth = 0,
  int imageHeight = 0,
  bool hasScale = false,
}) {
  if (detections.isEmpty) return RiskVerdict.low;
  var verdict = RiskVerdict.low;
  for (final d in detections) {
    final longest = d.longestSideCm;
    // Damage with no measurement (box edges not visible in frame) can't be
    // cleared as small — floor it at caution.
    final v = switch (d.damageClass) {
      _ when longest == null || !longest.isFinite || longest < 0 =>
        RiskVerdict.caution,
      DamageClass.tear when longest >= 9 => RiskVerdict.high,
      DamageClass.tear => RiskVerdict.caution,
      DamageClass.dent when longest >= 12 => RiskVerdict.high,
      DamageClass.dent when longest >= 4 => RiskVerdict.caution,
      DamageClass.dent => RiskVerdict.low,
      DamageClass.scratch when longest >= 10 => RiskVerdict.caution,
      DamageClass.scratch => RiskVerdict.low,
      DamageClass.surfaceDamage when longest >= 8 => RiskVerdict.caution,
      DamageClass.surfaceDamage => RiskVerdict.low,
      DamageClass.unknown => RiskVerdict.caution,
    };
    if (v.index > verdict.index) verdict = v;
  }

  if (detections.length >= 3 && verdict == RiskVerdict.low) {
    verdict = RiskVerdict.caution;
  }

  final imageArea = imageWidth.toDouble() * imageHeight.toDouble();
  if (hasScale && imageArea > 0) {
    var damageArea = 0.0;
    for (final d in detections) {
      damageArea += d.width * d.height;
    }
    if (damageArea >= imageArea * 0.20) verdict = RiskVerdict.high;
  }
  return verdict;
}
