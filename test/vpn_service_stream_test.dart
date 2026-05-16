// Verifies that VpnService.statsStream() correctly bridges the
// EventChannel into a Dart Stream<VpnStats>.
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sentinel/services/vpn_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('VpnService.statsStream', () {
    const eventChannelName = 'com.sentinel.app/stats_events';
    final binding = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

    tearDown(() {
      binding.setMockMessageHandler(eventChannelName, null);
    });

    test('parses platform payloads into VpnStats objects', () async {
      const codec = StandardMethodCodec();
      final emitted = <Map<dynamic, dynamic>>[
        {
          'adsBlocked': 5,
          'threatsBlocked': 2,
          'linksChecked': 1,
          'totalQueries': 10,
          'totalForwards': 3,
          'totalErrors': 0,
          'recentBlocks': const <Object>[],
        },
        {
          'adsBlocked': 6,
          'threatsBlocked': 2,
          'linksChecked': 1,
          'totalQueries': 11,
          'totalForwards': 3,
          'totalErrors': 0,
          'recentBlocks': const <Object>[],
        },
      ];

      // Mock the platform side: when Dart calls "listen" we synthesise
      // two stream events, then EndOfStream.
      binding.setMockMessageHandler(eventChannelName, (message) async {
        final call = codec.decodeMethodCall(message!);
        if (call.method == 'listen') {
          for (final payload in emitted) {
            await binding.handlePlatformMessage(
              eventChannelName,
              codec.encodeSuccessEnvelope(payload),
              (_) {},
            );
          }
          return codec.encodeSuccessEnvelope(null);
        }
        if (call.method == 'cancel') {
          return codec.encodeSuccessEnvelope(null);
        }
        return null;
      });

      final service = VpnService();
      final received = <VpnStats>[];
      final sub = service.statsStream().listen(received.add);
      await Future<void>.delayed(const Duration(milliseconds: 50));
      await sub.cancel();
      service.dispose();

      expect(received, hasLength(2));
      expect(received[0].adsBlocked, 5);
      expect(received[1].adsBlocked, 6);
      expect(received[0].linksChecked, 1);
    });

    test('errors on the platform stream do not crash the subscriber',
        () async {
      const codec = StandardMethodCodec();
      binding.setMockMessageHandler(eventChannelName, (message) async {
        final call = codec.decodeMethodCall(message!);
        if (call.method == 'listen') {
          await binding.handlePlatformMessage(
            eventChannelName,
            codec.encodeErrorEnvelope(code: 'BOOM', message: 'simulated'),
            (_) {},
          );
          return codec.encodeSuccessEnvelope(null);
        }
        if (call.method == 'cancel') return codec.encodeSuccessEnvelope(null);
        return null;
      });

      final service = VpnService();
      Object? caught;
      final sub = service.statsStream().listen(
            (_) {},
            onError: (Object error) {
              caught = error;
            },
          );
      await Future<void>.delayed(const Duration(milliseconds: 50));
      await sub.cancel();
      service.dispose();
      expect(caught, isNotNull);
    });
  });
}
