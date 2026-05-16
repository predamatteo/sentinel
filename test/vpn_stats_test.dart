// Defensive parsing of the VpnStats map sent over the platform channel.
import 'package:flutter_test/flutter_test.dart';
import 'package:sentinel/services/vpn_service.dart';

void main() {
  group('VpnStats.fromMap', () {
    test('parses a fully populated Sprint Quality payload', () {
      final stats = VpnStats.fromMap({
        'adsBlocked': 12,
        'threatsBlocked': 4,
        'linksChecked': 3,
        'totalQueries': 200,
        'totalForwards': 180,
        'totalErrors': 5,
        'recentBlocks': [
          {
            'domain': 'ads.example.com',
            'reason': 'ads',
            'category': 'ADS',
            'timestamp': 1714000000000,
          },
          {
            'domain': 'phish.test',
            'reason': 'threats',
            'category': 'THREATS',
            'timestamp': 1714000010000,
          }
        ],
      });
      expect(stats.adsBlocked, 12);
      expect(stats.threatsBlocked, 4);
      expect(stats.totalBlocks, 16); // sum convenience
      expect(stats.linksChecked, 3);
      expect(stats.totalQueries, 200);
      expect(stats.recentBlocks, hasLength(2));
      expect(stats.recentBlocks.first.domain, 'ads.example.com');
      expect(stats.recentBlocks.first.category, BlockCategory.ads);
      expect(stats.recentBlocks.last.category, BlockCategory.threat);
    });

    test('falls back to zero on missing fields', () {
      final stats = VpnStats.fromMap({});
      expect(stats.adsBlocked, 0);
      expect(stats.threatsBlocked, 0);
      expect(stats.linksChecked, 0);
      expect(stats.totalQueries, 0);
      expect(stats.recentBlocks, isEmpty);
    });

    test('tolerates string values for counters', () {
      final stats = VpnStats.fromMap({'adsBlocked': '7', 'threatsBlocked': '3'});
      expect(stats.adsBlocked, 7);
      expect(stats.threatsBlocked, 3);
      expect(stats.totalBlocks, 10);
    });

    test('legacy payload (totalBlocks only) is routed to threats', () {
      // Older builds may emit a single totalBlocks counter without
      // ads/threats categorisation. The parser must still surface those
      // numbers so the dashboard does not show zeros after an upgrade.
      final stats = VpnStats.fromMap({
        'totalBlocks': 42,
        'totalQueries': 100,
      });
      expect(stats.adsBlocked, 0);
      expect(stats.threatsBlocked, 42);
      expect(stats.totalBlocks, 42);
    });

    test('block event without category becomes BlockCategory.unknown', () {
      final stats = VpnStats.fromMap({
        'recentBlocks': [
          {
            'domain': 'something.test',
            'reason': 'blocklist',
            'timestamp': 1714000000000,
          }
        ],
      });
      expect(stats.recentBlocks.single.category, BlockCategory.unknown);
    });
  });
}
