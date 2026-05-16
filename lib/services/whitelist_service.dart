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
    final domain = _normalise(rawDomain);
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
  static bool isValidDomain(String input) => _normalise(input) != null;

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
