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
RiskVerdict computeVerdict(List<Detection> detections) {
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
  return verdict;
}
