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

  group('WhitelistService.normaliseInput', () {
    test('reduces a full URL to the bare registrable host', () {
      expect(WhitelistService.normaliseInput('https://www.shop.com/cart'),
          'shop.com');
      expect(WhitelistService.normaliseInput('http://example.com'),
          'example.com');
      expect(
          WhitelistService.normaliseInput(
              'https://www.shop.com/cart?id=1#section'),
          'shop.com');
    });

    test('strips scheme, port, userinfo and leading www', () {
      expect(WhitelistService.normaliseInput('http://example.com:8080'),
          'example.com');
      expect(WhitelistService.normaliseInput('https://user:pass@example.com/x'),
          'example.com');
      expect(WhitelistService.normaliseInput('www.example.com'), 'example.com');
    });

    test('accepts a bare host unchanged (besides case/whitespace)', () {
      expect(WhitelistService.normaliseInput(' EXAMPLE.COM '), 'example.com');
      expect(WhitelistService.normaliseInput('sub.example.com'),
          'sub.example.com');
    });

    test('does not over-strip a www in the middle of a host', () {
      expect(WhitelistService.normaliseInput('mywww.example.com'),
          'mywww.example.com');
    });

    test('returns null for input with no usable host', () {
      expect(WhitelistService.normaliseInput(''), isNull);
      expect(WhitelistService.normaliseInput('https://'), isNull);
      expect(WhitelistService.normaliseInput('localhost'), isNull);
    });
  });
}
