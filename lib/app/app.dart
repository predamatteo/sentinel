import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';

import '../features/analysis/analyzing_screen.dart';
import '../features/dashboard/dashboard_screen.dart';
import '../features/onboarding/welcome_screen.dart';
import '../l10n/generated/app_localizations.dart';
import '../services/accessibility_service.dart';
import '../services/analysis_service.dart';
import '../services/vpn_service.dart';
import 'theme.dart';

/// Root widget. The UI surface remains small enough that we route via
/// imperative `Navigator.push` rather than pulling in a routing package.
///
/// Sprint Quality: the Dart-side `_linksChecked` counter has been
/// removed. Link analyses are now counted on the native side
/// (`AnalysisStats`) and broadcast back via the EventChannel powering
/// [VpnService.statsStream], so the dashboard rebuilds on every change.
class SentinelApp extends StatefulWidget {
  const SentinelApp({
    super.key,
    AnalysisService? service,
    VpnService? vpnService,
    AccessibilityService? accessibilityService,
  })  : _service = service,
        _vpnService = vpnService,
        _accessibilityService = accessibilityService;

  final AnalysisService? _service;
  final VpnService? _vpnService;
  final AccessibilityService? _accessibilityService;

  @override
  State<SentinelApp> createState() => _SentinelAppState();
}

class _SentinelAppState extends State<SentinelApp> {
  late final AnalysisService _service;
  late final VpnService _vpnService;
  late final AccessibilityService _accessibilityService;
  final GlobalKey<NavigatorState> _navigatorKey = GlobalKey<NavigatorState>();

  @override
  void initState() {
    super.initState();
    _service = widget._service ?? AnalysisService();
    _vpnService = widget._vpnService ?? VpnService();
    _accessibilityService =
        widget._accessibilityService ?? AccessibilityService();
    _service.setNewUrlListener(_handleNewUrl);
  }

  void _handleNewUrl(String url) {
    final navigator = _navigatorKey.currentState;
    if (navigator == null) return;
    navigator.pushAndRemoveUntil(
      MaterialPageRoute(
        builder: (_) => AnalyzingScreen(url: url, service: _service),
      ),
      (_) => false,
    );
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      navigatorKey: _navigatorKey,
      title: 'Sentinel',
      debugShowCheckedModeBanner: false,
      theme: SentinelTheme.light(),
      darkTheme: SentinelTheme.dark(),
      themeMode: ThemeMode.system,
      localizationsDelegates: const [
        AppLocalizations.delegate,
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      supportedLocales: const [Locale('it'), Locale('en')],
      home: _RootRouter(
        analysisService: _service,
        vpnService: _vpnService,
        accessibilityService: _accessibilityService,
      ),
    );
  }
}

/// Decides whether the app boots into the analysis flow (incoming URL)
/// or into the onboarding/dashboard flow. The decision is taken once
/// when the activity starts; further URLs are handled by the navigator
/// listener installed in [_SentinelAppState].
class _RootRouter extends StatefulWidget {
  const _RootRouter({
    required this.analysisService,
    required this.vpnService,
    required this.accessibilityService,
  });

  final AnalysisService analysisService;
  final VpnService vpnService;
  final AccessibilityService accessibilityService;

  @override
  State<_RootRouter> createState() => _RootRouterState();
}

class _RootRouterState extends State<_RootRouter> {
  String? _initialUrl;
  bool _loaded = false;

  @override
  void initState() {
    super.initState();
    _resolveInitialRoute();
  }

  Future<void> _resolveInitialRoute() async {
    final url = await widget.analysisService.currentUrl();
    if (!mounted) return;
    setState(() {
      _initialUrl = url;
      _loaded = true;
    });
  }

  @override
  Widget build(BuildContext context) {
    if (!_loaded) {
      return const Scaffold(
        body: Center(child: CircularProgressIndicator()),
      );
    }
    final url = _initialUrl;
    if (url != null && url.isNotEmpty) {
      return AnalyzingScreen(url: url, service: widget.analysisService);
    }
    return _LauncherHome(
      analysisService: widget.analysisService,
      vpnService: widget.vpnService,
      accessibilityService: widget.accessibilityService,
    );
  }
}

/// Launcher view. Shows onboarding on first run (still gated by default
/// browser status as a simple persistence proxy in Sprint 2) and the
/// dashboard once the user is set up.
class _LauncherHome extends StatefulWidget {
  const _LauncherHome({
    required this.analysisService,
    required this.vpnService,
    required this.accessibilityService,
  });

  final AnalysisService analysisService;
  final VpnService vpnService;
  final AccessibilityService accessibilityService;

  @override
  State<_LauncherHome> createState() => _LauncherHomeState();
}

class _LauncherHomeState extends State<_LauncherHome>
    with WidgetsBindingObserver {
  bool _showOnboarding = true;
  bool _checked = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _decide();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Re-evaluate when the user returns from system Settings (e.g. after
    // making Sentinel the default browser) so the onboarding screen can
    // transition to the dashboard without a manual reload.
    if (state == AppLifecycleState.resumed) {
      _decide();
    }
  }

  Future<void> _decide() async {
    final isDefault = await widget.analysisService.isDefaultBrowser();
    if (!mounted) return;
    setState(() {
      _showOnboarding = !isDefault;
      _checked = true;
    });
  }

  @override
  Widget build(BuildContext context) {
    if (!_checked) {
      return const Scaffold(
        body: Center(child: CircularProgressIndicator()),
      );
    }
    if (_showOnboarding) {
      return WelcomeScreen(
        service: widget.analysisService,
        accessibilityService: widget.accessibilityService,
      );
    }
    return DashboardScreen(
      analysisService: widget.analysisService,
      vpnService: widget.vpnService,
      accessibilityService: widget.accessibilityService,
    );
  }
}
