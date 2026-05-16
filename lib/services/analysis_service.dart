import 'package:flutter/services.dart';

import 'analysis_models.dart';

/// Thin wrapper around the `com.sentinel.app/analysis` MethodChannel.
///
/// All calls return Futures; on the native side they are backed by Kotlin
/// coroutines so the UI never blocks. The service is intentionally
/// stateless — the screens themselves hold the analysis result so it
/// survives rebuilds.
class AnalysisService {
  AnalysisService({MethodChannel? channel})
      : _channel = channel ?? const MethodChannel(_channelName);

  static const String _channelName = 'com.sentinel.app/analysis';

  final MethodChannel _channel;

  /// Set a handler invoked when the native side reports a brand-new
  /// incoming URL (e.g. user tapped another link while Sentinel was
  /// already in the foreground).
  void setNewUrlListener(void Function(String url) onNewUrl) {
    _channel.setMethodCallHandler((call) async {
      if (call.method == 'onNewUrl') {
        final url = call.arguments;
        if (url is String && url.isNotEmpty) {
          onNewUrl(url);
        }
      }
      return null;
    });
  }

  Future<String?> currentUrl() async {
    return _channel.invokeMethod<String>('currentUrl');
  }

  Future<AnalysisResult> analyze(String url) async {
    final raw = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'analyze',
      {'url': url},
    );
    if (raw == null) {
      throw PlatformException(
        code: 'EMPTY_RESULT',
        message: 'Native analyzer returned no result',
      );
    }
    return AnalysisResult.fromMap(raw);
  }

  Future<bool> proceedToChrome(String url) async {
    final ok = await _channel.invokeMethod<bool>(
      'proceedToChrome',
      {'url': url},
    );
    return ok ?? false;
  }

  Future<void> cancelNavigation() async {
    await _channel.invokeMethod<void>('cancelNavigation');
  }

  Future<bool> isDefaultBrowser() async {
    final ok = await _channel.invokeMethod<bool>('isDefaultBrowser');
    return ok ?? false;
  }

  Future<bool> openDefaultBrowserSettings() async {
    final ok = await _channel.invokeMethod<bool>('openDefaultBrowserSettings');
    return ok ?? false;
  }
}
