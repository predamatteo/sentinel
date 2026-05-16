import 'package:firebase_app_check/firebase_app_check.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

import 'app/app.dart';
import 'firebase_options.dart';
import 'services/remote_config_service.dart';

/// Entry point.
///
/// Initialization order (Sprint 2):
///   1. WidgetsFlutterBinding so async work can run before runApp().
///   2. Firebase core. Must come before App Check.
///   3. Firebase App Check. Debug provider in `kDebugMode`, Play Integrity
///      in release. Run BEFORE any other Firebase call that hits a
///      protected backend (Remote Config does, so it must be after).
///   4. Remote Config. fetchAndActivate is non-blocking; we never let it
///      delay the first frame.
///
/// Any Firebase failure is swallowed so the app still boots in airplane
/// mode (Layer 1 still works without Firebase).
Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await _bootstrapFirebase();
  runApp(const SentinelApp());
}

Future<void> _bootstrapFirebase() async {
  try {
    await Firebase.initializeApp(
      options: DefaultFirebaseOptions.currentPlatform,
    );
    await FirebaseAppCheck.instance.activate(
      androidProvider: kDebugMode
          ? AndroidProvider.debug
          : AndroidProvider.playIntegrity,
    );
    await RemoteConfigService.instance.initialize();
  } catch (error, stack) {
    // Last-resort log: stderr is the only sink we can rely on before
    // the engine is fully alive.
    debugPrint('Firebase bootstrap failed: $error\n$stack');
  }
}
