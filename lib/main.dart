import 'dart:async';

import 'package:firebase_app_check/firebase_app_check.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_crashlytics/firebase_crashlytics.dart';
import 'package:firebase_performance/firebase_performance.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

import 'app/app.dart';
import 'firebase_options.dart';
import 'services/remote_config_service.dart';

/// Entry point.
///
/// Initialization order:
///   1. WidgetsFlutterBinding so async work can run before runApp().
///   2. Firebase core. Must come before App Check / Crashlytics / Performance.
///   3. Crashlytics handlers wired (collection disabled in debug to keep
///      the dashboard clean, but the handlers themselves are always wired
///      so we can exercise them locally).
///   4. Performance: lazy-init the singleton early so first-frame traces
///      are captured.
///   5. App Check. Debug provider in `kDebugMode`, Play Integrity in
///      release. Run BEFORE any other Firebase call that hits a protected
///      backend (Remote Config does, so it must be after).
///   6. Remote Config. fetchAndActivate is non-blocking; we never let it
///      delay the first frame.
///
/// Any Firebase failure is swallowed so the app still boots in airplane
/// mode (Layer 1 still works without Firebase).
Future<void> main() async {
  await runZonedGuarded<Future<void>>(() async {
    WidgetsFlutterBinding.ensureInitialized();
    await _bootstrapFirebase();
    runApp(const SentinelApp());
  }, (error, stack) {
    FirebaseCrashlytics.instance.recordError(error, stack, fatal: true);
  });
}

Future<void> _bootstrapFirebase() async {
  try {
    await Firebase.initializeApp(
      options: DefaultFirebaseOptions.currentPlatform,
    );

    await FirebaseCrashlytics.instance
        .setCrashlyticsCollectionEnabled(!kDebugMode);
    FlutterError.onError =
        FirebaseCrashlytics.instance.recordFlutterFatalError;
    PlatformDispatcher.instance.onError = (error, stack) {
      FirebaseCrashlytics.instance.recordError(error, stack, fatal: true);
      return true;
    };

    await FirebasePerformance.instance
        .setPerformanceCollectionEnabled(true);

    await FirebaseAppCheck.instance.activate(
      androidProvider: kDebugMode
          ? AndroidProvider.debug
          : AndroidProvider.playIntegrity,
    );
    await RemoteConfigService.instance.initialize();
  } catch (error, stack) {
    debugPrint('Firebase bootstrap failed: $error\n$stack');
  }
}
