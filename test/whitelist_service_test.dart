// Tests for the user-whitelist validator. We do not exercise the
// SharedPreferences-backed I/O here because that requires the
// shared_preferences MethodChannel to be mocked — covered by an
// integration test in a later sprint.
import 'package:flutter_test/flutter_test.dart';
import 'package:sentinel/services/whitelist_service.dart';

void main() {
  group('WhitelistService.isValidDomain', () {
    test('accepts normal domains', () {
      expect(WhitelistService.isValidDomain('example.com'), isTrue);
      expect(WhitelistService.isValidDomain('sub.example.com'), isTrue);
      expect(WhitelistService.isValidDomain('a.b.c.d.example.it'), isTrue);
      expect(WhitelistService.isValidDomain('xn--mnchen-3ya.de'), isTrue);
    });

    test('rejects empty and malformed input', () {
      expect(WhitelistService.isValidDomain(''), isFalse);
      expect(WhitelistService.isValidDomain('localhost'), isFalse);
      expect(WhitelistService.isValidDomain('no_underscore.com'), isFalse);
      expect(WhitelistService.isValidDomain('-leading.dash.com'), isFalse);
      expect(WhitelistService.isValidDomain('trailing-.com'), isFalse);
      expect(WhitelistService.isValidDomain('http://example.com'), isFalse);
    });

    test('normalises whitespace and case', () {
      expect(WhitelistService.isValidDomain(' EXAMPLE.COM '), isTrue);
    });
  });
}
