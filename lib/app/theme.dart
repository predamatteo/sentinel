import 'package:flutter/material.dart';

/// Centralised colour palette and Material 3 themes for Sentinel.
///
/// Three semantic colours are exposed explicitly because the
/// `ColorScheme.fromSeed` output does not give us a stable hook for
/// success / warning / danger states that we want to address by name from
/// any widget.
class SentinelColors {
  SentinelColors._();

  static const Color seed = Color(0xFF1A237E); // deep indigo
  static const Color success = Color(0xFF1E8E3E); // M3-friendly green
  static const Color warning = Color(0xFFF9AB00); // amber
  static const Color danger = Color(0xFFD93025); // red
  static const Color successContainer = Color(0xFFCDEACF);
  static const Color dangerContainer = Color(0xFFFADBD8);
  static const Color warningContainer = Color(0xFFFCE7B4);
}

/// Semantic state used by the dashboard status card. Maps cleanly to
/// the [SentinelColors] palette without each widget having to know the
/// hex values.
enum SentinelProtectionState { active, inactive, error }

/// Convenience accessor for state-driven colours.
class SentinelStatusColor {
  SentinelStatusColor._();

  static Color foregroundFor(SentinelProtectionState state) {
    switch (state) {
      case SentinelProtectionState.active:
        return SentinelColors.success;
      case SentinelProtectionState.inactive:
        return SentinelColors.warning;
      case SentinelProtectionState.error:
        return SentinelColors.danger;
    }
  }

  static Color containerFor(SentinelProtectionState state) {
    switch (state) {
      case SentinelProtectionState.active:
        return SentinelColors.successContainer;
      case SentinelProtectionState.inactive:
        return SentinelColors.warningContainer;
      case SentinelProtectionState.error:
        return SentinelColors.dangerContainer;
    }
  }
}

class SentinelTheme {
  SentinelTheme._();

  static ThemeData light() {
    final scheme = ColorScheme.fromSeed(
      seedColor: SentinelColors.seed,
      brightness: Brightness.light,
    );
    return _baseTheme(scheme);
  }

  static ThemeData dark() {
    final scheme = ColorScheme.fromSeed(
      seedColor: SentinelColors.seed,
      brightness: Brightness.dark,
    );
    return _baseTheme(scheme);
  }

  static ThemeData _baseTheme(ColorScheme scheme) {
    final base = ThemeData(useMaterial3: true, colorScheme: scheme);
    return base.copyWith(
      scaffoldBackgroundColor: scheme.surface,
      appBarTheme: AppBarTheme(
        backgroundColor: scheme.surface,
        foregroundColor: scheme.onSurface,
        elevation: 0,
        centerTitle: false,
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 14),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
          ),
          textStyle: const TextStyle(
            fontSize: 16,
            fontWeight: FontWeight.w600,
          ),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 14),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
          ),
        ),
      ),
      cardTheme: CardThemeData(
        elevation: 0,
        color: scheme.surfaceContainerHighest,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        margin: EdgeInsets.zero,
      ),
    );
  }
}
