import 'dart:async';

import 'package:shared_preferences/shared_preferences.dart';

/// Persistent whitelist of user-allowed domains.
///
/// Backed by [SharedPreferences] for Sprint 2 (Room migration is
/// scoped out). Reads cache the list in memory once loaded so the UI
/// stays responsive.
class WhitelistService {
  WhitelistService();

  static const String _prefsKey = 'vpn_whitelist_v1';

  List<String>? _cache;

  Future<List<String>> getAll() async {
    final cached = _cache;
    if (cached != null) return List.unmodifiable(cached);
    final prefs = await SharedPreferences.getInstance();
    final list = prefs.getStringList(_prefsKey) ?? const [];
    final normalised = list.map(_normalise).whereType<String>().toSet().toList()
      ..sort();
    _cache = normalised;
    return List.unmodifiable(normalised);
  }

  Future<bool> add(String rawDomain) async {
    final domain = normaliseInput(rawDomain);
    if (domain == null) return false;
    final current = (await getAll()).toList();
    if (current.contains(domain)) return false;
    current.add(domain);
    current.sort();
    await _persist(current);
    return true;
  }

  Future<bool> remove(String domain) async {
    final normalised = _normalise(domain);
    if (normalised == null) return false;
    final current = (await getAll()).toList();
    if (!current.remove(normalised)) return false;
    await _persist(current);
    return true;
  }

  Future<bool> isWhitelisted(String domain) async {
    final all = await getAll();
    return all.contains(_normalise(domain));
  }

  Future<void> _persist(List<String> list) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setStringList(_prefsKey, list);
    _cache = List.unmodifiable(list);
  }

  /// Public hook used by tests + the settings UI to validate user input.
  ///
  /// Strict: only accepts a bare host (no scheme/path/port). Kept unchanged
  /// so callers that want strict validation — and the existing unit tests —
  /// keep their contract (e.g. 'http://example.com' is invalid here).
  static bool isValidDomain(String input) => _normalise(input) != null;

  /// URL-tolerant normalisation used by the *add* flow. Accepts a full URL
  /// (or a bare host) and reduces it to the bare registrable host by
  /// stripping the scheme, any path/query/fragment, the port and a leading
  /// "www.". Returns the normalised host, or `null` if nothing valid is left.
  ///
  /// Examples:
  ///   'https://www.shop.com/cart?id=1' -> 'shop.com'
  ///   'http://example.com:8080'        -> 'example.com'
  ///   'EXAMPLE.COM'                    -> 'example.com'
  static String? normaliseInput(String raw) {
    var candidate = raw.trim();
    if (candidate.isEmpty) return null;

    // Strip the scheme (anything up to "://") if present.
    final schemeMatch = RegExp(r'^[a-zA-Z][a-zA-Z0-9+.-]*://').firstMatch(candidate);
    if (schemeMatch != null) {
      candidate = candidate.substring(schemeMatch.end);
    }

    // Drop any path / query / fragment: keep only the authority part.
    candidate = candidate.split(RegExp(r'[/?#]')).first;

    // Drop userinfo (user:pass@host) if present.
    final atIndex = candidate.lastIndexOf('@');
    if (atIndex != -1) {
      candidate = candidate.substring(atIndex + 1);
    }

    // Drop the port (host:port). IPv6 literals are out of scope (the strict
    // validator below rejects them anyway), so a plain rsplit on ':' is safe.
    final colonIndex = candidate.indexOf(':');
    if (colonIndex != -1) {
      candidate = candidate.substring(0, colonIndex);
    }

    // Strip a single leading "www." so 'www.shop.com' and 'shop.com' collapse
    // to the same registrable host.
    final lower = candidate.toLowerCase();
    if (lower.startsWith('www.')) {
      candidate = candidate.substring(4);
    }

    return _normalise(candidate);
  }

  static String? _normalise(String raw) {
    final trimmed = raw.trim().toLowerCase();
    if (trimmed.isEmpty || trimmed.length > 253) return null;
    if (!trimmed.contains('.')) return null;
    // Allow ASCII letters/digits/hyphens and dots; reject everything else
    // including unicode (which would require punycode encoding to round-trip
    // safely; out of scope for Sprint 2).
    final valid = RegExp(r'^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$');
    if (!valid.hasMatch(trimmed)) return null;
    return trimmed;
  }
}
