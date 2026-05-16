import 'package:flutter/services.dart';

/// Dart-side wrapper around `com.sentinel.app/accessibility`. Mirrors
/// the Kotlin [AccessibilityChannel] one-to-one.
///
/// Methods are intentionally tiny so the channel can be mocked from
/// tests by injecting a stub [MethodChannel].
class AccessibilityService {
  AccessibilityService({MethodChannel? channel})
      : _channel = channel ?? const MethodChannel(_channelName);

  static const String _channelName = 'com.sentinel.app/accessibility';

  final MethodChannel _channel;

  /// True when [SentinelAccessibilityService] is enabled in the
  /// system-wide ENABLED_ACCESSIBILITY_SERVICES setting.
  Future<bool> isAccessibilityEnabled() async {
    try {
      final value =
          await _channel.invokeMethod<bool>('isAccessibilityEnabled');
      return value ?? false;
    } on PlatformException {
      return false;
    }
  }

  /// True when SYSTEM_ALERT_WINDOW has been granted (or always-true on
  /// pre-23 devices where the permission is install-time).
  Future<bool> canDrawOverlays() async {
    try {
      final value = await _channel.invokeMethod<bool>('canDrawOverlays');
      return value ?? false;
    } on PlatformException {
      return false;
    }
  }

  /// Convenience: both above are granted.
  Future<bool> isFullyConfigured() async {
    try {
      final value = await _channel.invokeMethod<bool>('isFullyConfigured');
      return value ?? false;
    } on PlatformException {
      return false;
    }
  }

  /// Opens Settings > Accessibility. The UI surface is responsible
  /// for re-querying [isAccessibilityEnabled] on lifecycle resume.
  Future<void> openAccessibilitySettings() async {
    await _channel.invokeMethod<bool>('openAccessibilitySettings');
  }

  /// Opens Settings > Display over other apps for the Sentinel package.
  Future<void> openOverlaySettings() async {
    await _channel.invokeMethod<bool>('openOverlaySettings');
  }
}
