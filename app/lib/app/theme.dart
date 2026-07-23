import 'package:flutter/material.dart';

import 'tokens.dart';

/// Dark-first theme. Display: Big Shoulders Display (condensed,
/// athletic-industrial). Body: Archivo (quiet grotesk). Numerals:
/// Spline Sans Mono via [AppText.mono]. All bundled — no network fonts.
const _display = 'BigShouldersDisplay';
const _body = 'Archivo';
const _mono = 'SplineSansMono';

ThemeData buildAppTheme() {
  final base = ThemeData.dark().textTheme.apply(fontFamily: _body);

  TextStyle display(double size, {FontWeight weight = FontWeight.w800}) =>
      TextStyle(
        fontFamily: _display,
        fontSize: size,
        fontWeight: weight,
        color: AppTokens.textPrimary,
        height: 0.95,
        letterSpacing: 0.5,
      );

  final scheme = const ColorScheme.dark(
    surface: AppTokens.bg,
    surfaceContainer: AppTokens.surface,
    surfaceContainerHigh: AppTokens.surfaceRaised,
    primary: AppTokens.accent,
    onPrimary: AppTokens.bg,
    secondary: AppTokens.textSecondary,
    onSurface: AppTokens.textPrimary,
    onSurfaceVariant: AppTokens.textSecondary,
    error: AppTokens.riskHigh,
  );

  return ThemeData(
    useMaterial3: true,
    colorScheme: scheme,
    scaffoldBackgroundColor: AppTokens.bg,
    fontFamily: _body,
    splashFactory: InkSparkle.splashFactory,
    textTheme: base.copyWith(
      displayLarge: display(96),
      displayMedium: display(64),
      displaySmall: display(44),
      headlineMedium: display(32, weight: FontWeight.w700),
      titleLarge: base.titleLarge?.copyWith(
        fontWeight: FontWeight.w600,
        color: AppTokens.textPrimary,
      ),
      bodyLarge: base.bodyLarge?.copyWith(
        color: AppTokens.textPrimary,
        height: 1.55,
      ),
      bodyMedium: base.bodyMedium?.copyWith(
        color: AppTokens.textSecondary,
        height: 1.55,
      ),
      labelSmall: base.labelSmall?.copyWith(
        color: AppTokens.textFaint,
        letterSpacing: 1.6,
        fontWeight: FontWeight.w600,
      ),
    ),
    appBarTheme: const AppBarTheme(
      backgroundColor: Colors.transparent,
      elevation: 0,
      foregroundColor: AppTokens.textPrimary,
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        backgroundColor: AppTokens.accent,
        foregroundColor: AppTokens.bg,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(AppTokens.rMd),
        ),
        textStyle: const TextStyle(
          fontFamily: _body,
          fontWeight: FontWeight.w700,
          letterSpacing: 0.4,
        ),
        minimumSize: const Size(64, 56),
      ),
    ),
    textButtonTheme: TextButtonThemeData(
      style: TextButton.styleFrom(foregroundColor: AppTokens.textSecondary),
    ),
  );
}

/// Measurement/spec text — mono for cm, %, timestamps.
abstract final class AppText {
  static TextStyle mono({
    double size = 14,
    Color color = AppTokens.textPrimary,
    FontWeight weight = FontWeight.w500,
  }) => TextStyle(
    fontFamily: _mono,
    fontSize: size,
    color: color,
    fontWeight: weight,
    letterSpacing: 0.2,
  );
}
