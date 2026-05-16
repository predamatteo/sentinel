// Sprint 3: verifies that AccessibilityService correctly wraps the
// com.sentinel.app/accessibility MethodChannel and degrades gracefully
// on PlatformException.

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sentinel/services/accessibility_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channelName = 'com.sentinel.app/accessibility';
  final binding =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  tearDown(() {
    binding.setMockMethodCallHandler(
      const MethodChannel(channelName),
      null,
    );
  });

  group('AccessibilityService', () {
    test('isAccessibilityEnabled returns the platform boolean', () async {
      binding.setMockMethodCallHandler(
        const MethodChannel(channelName),
        (call) async {
          expect(call.method, 'isAccessibilityEnabled');
          return true;
        },
      );
      final service = AccessibilityService();
      expect(await service.isAccessibilityEnabled(), isTrue);
    });

    test('canDrawOverlays returns the platform boolean', () async {
      binding.setMockMethodCallHandler(
        const MethodChannel(channelName),
        (call) async {
          expect(call.method, 'canDrawOverlays');
          return false;
        },
      );
      final service = AccessibilityService();
      expect(await service.canDrawOverlays(), isFalse);
    });

    test('isFullyConfigured returns the platform boolean', () async {
      binding.setMockMethodCallHandler(
        const MethodChannel(channelName),
        (call) async {
          expect(call.method, 'isFullyConfigured');
          return true;
        },
      );
      final service = AccessibilityService();
      expect(await service.isFullyConfigured(), isTrue);
    });

    test('openAccessibilitySettings invokes the platform method', () async {
      var called = false;
      binding.setMockMethodCallHandler(
        const MethodChannel(channelName),
        (call) async {
          if (call.method == 'openAccessibilitySettings') {
            called = true;
            return true;
          }
          return null;
        },
      );
      final service = AccessibilityService();
      await service.openAccessibilitySettings();
      expect(called, isTrue);
    });

    test('openOverlaySettings invokes the platform method', () async {
      var called = false;
      binding.setMockMethodCallHandler(
        const MethodChannel(channelName),
        (call) async {
          if (call.method == 'openOverlaySettings') {
            called = true;
            return true;
          }
          return null;
        },
      );
      final service = AccessibilityService();
      await service.openOverlaySettings();
      expect(called, isTrue);
    });

    test('platform exceptions degrade to false instead of throwing',
        () async {
      binding.setMockMethodCallHandler(
        const MethodChannel(channelName),
        (call) async {
          throw PlatformException(code: 'BOOM', message: 'simulated');
        },
      );
      final service = AccessibilityService();
      expect(await service.isAccessibilityEnabled(), isFalse);
      expect(await service.canDrawOverlays(), isFalse);
      expect(await service.isFullyConfigured(), isFalse);
    });

    test('null platform replies degrade to false', () async {
      binding.setMockMethodCallHandler(
        const MethodChannel(channelName),
        (call) async => null,
      );
      final service = AccessibilityService();
      expect(await service.isAccessibilityEnabled(), isFalse);
      expect(await service.canDrawOverlays(), isFalse);
      expect(await service.isFullyConfigured(), isFalse);
    });
  });
}
