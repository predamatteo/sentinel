import 'dart:async';

import 'package:flutter/services.dart';

/// Category for a recent block entry, mirrored from the Kotlin
/// `BlocklistCategory` enum sent over the platform channel.
enum BlockCategory { ads, threat, unknown }

BlockCategory _parseCategory(Object? raw) {
  switch (raw?.toString()) {
    case 'ADS':
      return BlockCategory.ads;
    case 'THREATS':
      return BlockCategory.threat;
    default:
      return BlockCategory.unknown;
  }
}

/// In-memory snapshot of the dashboard counters sent over the platform
/// channel. Sprint Quality: ads and threats are separated, the analyzer
/// counter (`linksChecked`) is part of the same payload so the UI can
/// rebuild every tile from a single rebuild.
class VpnStats {
  const VpnStats({
    required this.adsBlocked,
    required this.threatsBlocked,
    required this.linksChecked,
    required this.totalQueries,
    required this.totalForwards,
    required this.totalErrors,
    required this.recentBlocks,
  });

  /// Number of ad/tracker domains blocked today.
  final int adsBlocked;

  /// Number of threat domains (malware/phishing) blocked today.
  final int threatsBlocked;

  /// Number of links the analyzer has verified today.
  final int linksChecked;

  /// Total DNS queries observed by the tunnel (forwarded + blocked).
  final int totalQueries;

  /// Queries forwarded upstream (not blocked).
  final int totalForwards;

  /// Counter of unexpected I/O errors while running the tunnel.
  final int totalErrors;

  /// Last N block events, most-recent first.
  final List<VpnBlockEvent> recentBlocks;

  /// Sum of ads + threats. Kept as a convenience accessor so widgets
  /// that need a single number do not have to add.
  int get totalBlocks => adsBlocked + threatsBlocked;

  static const VpnStats empty = VpnStats(
    adsBlocked: 0,
    threatsBlocked: 0,
    linksChecked: 0,
    totalQueries: 0,
    totalForwards: 0,
    totalErrors: 0,
    recentBlocks: <VpnBlockEvent>[],
  );

  factory VpnStats.fromMap(Map<dynamic, dynamic> raw) {
    final List<dynamic> recent = (raw['recentBlocks'] as List?) ?? const [];
    // Sprint Quality payload uses adsBlocked/threatsBlocked; older
    // builds may still send the legacy `totalBlocks` only — degrade
    // gracefully by routing legacy totals into threats.
    final ads = _asInt(raw['adsBlocked']);
    final threats = _asInt(raw['threatsBlocked']);
    final hasCategorised = ads + threats > 0;
    final legacyTotal = _asInt(raw['totalBlocks']);
    return VpnStats(
      adsBlocked: ads,
      threatsBlocked: hasCategorised ? threats : legacyTotal,
      linksChecked: _asInt(raw['linksChecked']),
      totalQueries: _asInt(raw['totalQueries']),
      totalForwards: _asInt(raw['totalForwards']),
      totalErrors: _asInt(raw['totalErrors']),
      recentBlocks: recent
          .whereType<Map<dynamic, dynamic>>()
          .map(VpnBlockEvent.fromMap)
          .toList(),
    );
  }

  static int _asInt(Object? value) {
    if (value is int) return value;
    if (value is num) return value.toInt();
    if (value is String) return int.tryParse(value) ?? 0;
    return 0;
  }
}

class VpnBlockEvent {
  const VpnBlockEvent({
    required this.domain,
    required this.reason,
    required this.category,
    required this.timestamp,
  });

  final String domain;
  final String reason;
  final BlockCategory category;
  final DateTime timestamp;

  factory VpnBlockEvent.fromMap(Map<dynamic, dynamic> raw) {
    final ts = raw['timestamp'];
    final millis = ts is num
        ? ts.toInt()
        : (ts is String ? int.tryParse(ts) ?? 0 : 0);
    return VpnBlockEvent(
      domain: raw['domain']?.toString() ?? '',
      reason: raw['reason']?.toString() ?? '',
      category: _parseCategory(raw['category']),
      timestamp: DateTime.fromMillisecondsSinceEpoch(millis),
    );
  }
}

/// Outcome of a `start` attempt as returned by the platform channel.
enum VpnStartOutcome { ready, consentRequired, consentDenied, error }

/// Dart-side wrapper for the `com.sentinel.app/vpn` MethodChannel plus
/// the Sprint Quality `com.sentinel.app/stats_events` EventChannel.
///
/// The EventChannel is the steady-state push path for the dashboard:
/// subscribers receive a fresh [VpnStats] payload on every block event
/// and at a 1.5s heartbeat. The MethodChannel `getStats()` is kept as
/// the one-shot read used at first paint.
class VpnService {
  VpnService({
    MethodChannel? channel,
    EventChannel? statsChannel,
  })  : _channel = channel ?? const MethodChannel(_channelName),
        _statsChannel =
            statsChannel ?? const EventChannel(_statsChannelName) {
    _channel.setMethodCallHandler(_onCall);
  }

  static const String _channelName = 'com.sentinel.app/vpn';
  static const String _statsChannelName = 'com.sentinel.app/stats_events';

  final MethodChannel _channel;
  final EventChannel _statsChannel;
  final StreamController<bool> _consentEvents =
      StreamController<bool>.broadcast();

  Stream<bool> get consentResults => _consentEvents.stream;

  /// Push stream of dashboard stats. The Kotlin EventChannel handler
  /// emits a fresh payload immediately on subscription, on every Room
  /// insert into `block_events`, and on a 1.5s heartbeat to surface
  /// in-memory counter updates that bypass the DAO.
  ///
  /// The stream is broadcast: multiple subscribers share the same
  /// platform-side stream. The first subscriber attaches the handler,
  /// the last one detaching cancels it on the native side.
  Stream<VpnStats> statsStream() {
    return _statsChannel.receiveBroadcastStream().map((event) {
      if (event is Map) {
        return VpnStats.fromMap(event);
      }
      return VpnStats.empty;
    });
  }

  Future<dynamic> _onCall(MethodCall call) async {
    switch (call.method) {
      case 'onConsentResult':
        final granted = call.arguments == true;
        _consentEvents.add(granted);
        break;
    }
    return null;
  }

  /// Ask the native side to start. The result tells the UI whether the
  /// activity will pop a consent dialog. When [VpnStartOutcome.ready],
  /// the caller still needs to call [confirmStart] to actually launch
  /// the foreground service.
  Future<VpnStartOutcome> requestStart() async {
    try {
      final reply = await _channel.invokeMethod<String>('requestStart');
      switch (reply) {
        case 'ready':
          return VpnStartOutcome.ready;
        case 'consent_required':
          return VpnStartOutcome.consentRequired;
        default:
          return VpnStartOutcome.error;
      }
    } catch (_) {
      return VpnStartOutcome.error;
    }
  }

  Future<bool> confirmStart() async {
    final ok = await _channel.invokeMethod<bool>('confirmStart');
    return ok ?? false;
  }

  Future<void> stop() async {
    await _channel.invokeMethod<bool>('stop');
  }

  Future<bool> isRunning() async {
    final v = await _channel.invokeMethod<bool>('isRunning');
    return v ?? false;
  }

  /// Best-effort check for an active Wi-Fi/USB/Bluetooth tethering
  /// interface. When true, devices connected to the phone's hotspot are
  /// not protected by Sentinel and may lose DNS resolution until the
  /// hotspot is recycled. Returns false on any platform error.
  Future<bool> isHotspotActive() async {
    try {
      final v = await _channel.invokeMethod<bool>('isHotspotActive');
      return v ?? false;
    } on PlatformException {
      return false;
    }
  }

  /// Opens the system tethering / wireless settings so the user can toggle
  /// the hotspot off and on, which restores the tethered DNS forwarder
  /// after Sentinel has been stopped.
  Future<void> openHotspotSettings() async {
    await _channel.invokeMethod<bool>('openHotspotSettings');
  }

  Future<VpnStats> getStats() async {
    try {
      final raw =
          await _channel.invokeMethod<Map<dynamic, dynamic>>('getStats');
      if (raw == null) return VpnStats.empty;
      return VpnStats.fromMap(raw);
    } catch (_) {
      return VpnStats.empty;
    }
  }

  Future<void> setWhitelist(List<String> domains) async {
    await _channel.invokeMethod<bool>('setWhitelist', {'domains': domains});
  }

  Future<void> refreshRemoteLists(Map<String, String> targets) async {
    await _channel
        .invokeMethod<bool>('refreshRemoteLists', {'targets': targets});
  }

  Future<void> setUpstream({
    required String primary,
    required String secondary,
    required String dotHostname,
  }) async {
    await _channel.invokeMethod<bool>('setUpstream', {
      'primary': primary,
      'secondary': secondary,
      'dotHostname': dotHostname,
    });
  }

  void dispose() {
    _channel.setMethodCallHandler(null);
    _consentEvents.close();
  }
}
