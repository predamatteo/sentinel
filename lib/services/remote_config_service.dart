import 'dart:async';

import 'package:firebase_remote_config/firebase_remote_config.dart';
import 'package:flutter/foundation.dart';

/// Typed view over the Remote Config keys we ship in Sprint 2. The
/// fields below define defaults used as fallback on first launch (or
/// when fetching fails). The values stay in sync with the keys
/// registered in the Firebase Console; any missing key transparently
/// falls back to the default declared here.
class BlocklistConfig {
  const BlocklistConfig({
    required this.blocklistVersion,
    required this.phishingUrl,
    required this.adsUrl,
    required this.vpnUpstreamPrimary,
    required this.vpnUpstreamSecondary,
    required this.vpnUpstreamDotHostname,
  });

  final int blocklistVersion;
  final String phishingUrl;
  final String adsUrl;
  final String vpnUpstreamPrimary;
  final String vpnUpstreamSecondary;
  final String vpnUpstreamDotHostname;

  static const BlocklistConfig defaults = BlocklistConfig(
    blocklistVersion: 1,
    phishingUrl: '',
    adsUrl: '',
    vpnUpstreamPrimary: '1.1.1.1',
    vpnUpstreamSecondary: '1.0.0.1',
    vpnUpstreamDotHostname: 'cloudflare-dns.com',
  );

  Map<String, String> remoteUrlsByTag() => {
        if (phishingUrl.isNotEmpty) 'phishing': phishingUrl,
        if (adsUrl.isNotEmpty) 'ads': adsUrl,
      };
}

/// Thin singleton around [FirebaseRemoteConfig].
///
/// Boot order:
///   1. Set in-memory defaults so reads work offline.
///   2. Configure fetch intervals (long in release, short in debug).
///   3. fetchAndActivate() in the background, swallowing failures —
///      the cached values from the previous run are still served.
///
/// The fetch is intentionally fire-and-forget: we do not block app
/// startup waiting for the network. Callers can `await ensureFresh()`
/// when they need an up-to-date value (e.g. a manual "refresh" button).
class RemoteConfigService {
  RemoteConfigService._();
  static final RemoteConfigService instance = RemoteConfigService._();

  static const Duration _releaseFetchInterval = Duration(hours: 12);
  static const Duration _debugFetchInterval = Duration(minutes: 1);

  FirebaseRemoteConfig? _rc;
  bool _initialized = false;
  Completer<void>? _firstFetch;

  Future<void> initialize() async {
    if (_initialized) return;
    final rc = FirebaseRemoteConfig.instance;
    await rc.setConfigSettings(
      RemoteConfigSettings(
        fetchTimeout: const Duration(seconds: 10),
        minimumFetchInterval:
            kDebugMode ? _debugFetchInterval : _releaseFetchInterval,
      ),
    );
    await rc.setDefaults(<String, Object>{
      'blocklist_version': BlocklistConfig.defaults.blocklistVersion,
      'blocklist_url_phishing': BlocklistConfig.defaults.phishingUrl,
      'blocklist_url_ads': BlocklistConfig.defaults.adsUrl,
      'vpn_upstream_dns_primary': BlocklistConfig.defaults.vpnUpstreamPrimary,
      'vpn_upstream_dns_secondary':
          BlocklistConfig.defaults.vpnUpstreamSecondary,
      'vpn_upstream_dns_dot_hostname':
          BlocklistConfig.defaults.vpnUpstreamDotHostname,
    });
    _rc = rc;
    _initialized = true;
    // Kick off a non-blocking fetch and remember it so a manual refresh
    // can re-use the same in-flight call.
    unawaited(_firstFetchAsync());
  }

  Future<void> _firstFetchAsync() async {
    final completer = Completer<void>();
    _firstFetch = completer;
    try {
      await _rc?.fetchAndActivate();
    } catch (_) {
      // Network or quota errors leave the cached values in place.
    } finally {
      if (!completer.isCompleted) completer.complete();
    }
  }

  /// Wait for the very first fetch attempt to complete. Returns
  /// immediately on subsequent calls.
  Future<void> ensureFreshOnce() async {
    if (!_initialized) return;
    final pending = _firstFetch;
    if (pending == null || pending.isCompleted) return;
    await pending.future;
  }

  /// Force a fresh fetch (used by the "refresh now" UI). Returns true
  /// if the call succeeded.
  Future<bool> forceRefresh() async {
    final rc = _rc;
    if (rc == null) return false;
    try {
      return await rc.fetchAndActivate();
    } catch (_) {
      return false;
    }
  }

  BlocklistConfig getBlocklistConfig() {
    final rc = _rc;
    if (rc == null) return BlocklistConfig.defaults;
    return BlocklistConfig(
      blocklistVersion: rc.getInt('blocklist_version'),
      phishingUrl: rc.getString('blocklist_url_phishing'),
      adsUrl: rc.getString('blocklist_url_ads'),
      vpnUpstreamPrimary: rc.getString('vpn_upstream_dns_primary'),
      vpnUpstreamSecondary: rc.getString('vpn_upstream_dns_secondary'),
      vpnUpstreamDotHostname: rc.getString('vpn_upstream_dns_dot_hostname'),
    );
  }
}
