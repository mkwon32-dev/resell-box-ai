import 'package:flutter/material.dart';

/// Design tokens — single source of visual truth.
///
/// Discipline (from design brief): the app is near-monochrome dark.
/// Verdict colors appear ONLY on the result banner and history tags.
abstract final class AppTokens {
  // Surfaces — near-black, tinted slightly warm (never pure #000).
  static const Color bg = Color(0xFF121110);
  static const Color surface = Color(0xFF1A1918);
  static const Color surfaceRaised = Color(0xFF232120);

  // Text — warm off-whites, never pure #fff.
  static const Color textPrimary = Color(0xFFF2EFEB);
  static const Color textSecondary = Color(0xFFA8A29B);
  static const Color textFaint = Color(0xFF6B6660);

  // The one non-verdict accent: used for interactive emphasis only.
  static const Color accent = Color(0xFFE8E4DD);

  // Verdict colors — result banner + history tags ONLY.
  static const Color riskLow = Color(0xFF3FB950);
  static const Color riskCaution = Color(0xFFD4A72C);
  static const Color riskHigh = Color(0xFFE5484D);

  // On-verdict text (dark, readable on all three verdict fills).
  static const Color onVerdict = Color(0xFF14130F);

  // Per-class bbox stroke colors — desaturated, evidence not decoration.
  static const Map<String, Color> classColors = {
    'dent': Color(0xFFD9A066),
    'surface_damage': Color(0xFF7FB4C9),
    'scratch': Color(0xFF9AB87A),
    'tear': Color(0xFFC98181),
    'unknown': Color(0xFF999288),
  };

  // Spacing scale (4pt base).
  static const double s1 = 4;
  static const double s2 = 8;
  static const double s3 = 12;
  static const double s4 = 16;
  static const double s5 = 24;
  static const double s6 = 32;
  static const double s7 = 48;
  static const double s8 = 64;

  // Radii — sharp-ish; posters aren't pill-shaped.
  static const double rSm = 4;
  static const double rMd = 8;
  static const double rLg = 12;

  // Motion.
  static const Duration tFast = Duration(milliseconds: 160);
  static const Duration tMed = Duration(milliseconds: 300);
  static const Duration tReveal = Duration(milliseconds: 420);
  static const Curve easeOutQuint = Cubic(0.22, 1, 0.36, 1);
  static const Curve easeOutExpo = Cubic(0.16, 1, 0.3, 1);
}
