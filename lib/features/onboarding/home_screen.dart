import 'package:flutter/material.dart';
import '../../l10n/generated/app_localizations.dart';

import '../../app/theme.dart';
import '../../services/analysis_service.dart';

/// Shown when the app is launched directly (no incoming URL). It displays
/// the current protection status and lets the user (re)set Sentinel as
/// the default browser. There is intentionally no link history or
/// browsing UI — Sentinel is a gateway, not a browser.
class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key, required this.service});

  final AnalysisService service;

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> with WidgetsBindingObserver {
  bool? _isDefaultBrowser;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refresh();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _refresh();
    }
  }

  Future<void> _refresh() async {
    final isDefault = await widget.service.isDefaultBrowser();
    if (!mounted) return;
    setState(() => _isDefaultBrowser = isDefault);
  }

  Future<void> _openSettings() async {
    await widget.service.openDefaultBrowserSettings();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final isDefault = _isDefaultBrowser ?? false;
    final accent = isDefault ? SentinelColors.success : SentinelColors.warning;

    return Scaffold(
      appBar: AppBar(title: Text(l10n.appName)),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const SizedBox(height: 8),
              Center(
                child: Container(
                  width: 120,
                  height: 120,
                  decoration: BoxDecoration(
                    color: theme.colorScheme.primaryContainer,
                    shape: BoxShape.circle,
                  ),
                  child: Icon(
                    Icons.shield,
                    size: 72,
                    color: theme.colorScheme.onPrimaryContainer,
                  ),
                ),
              ),
              const SizedBox(height: 24),
              Text(
                l10n.homeTitle,
                style: theme.textTheme.headlineMedium?.copyWith(
                  fontWeight: FontWeight.w700,
                ),
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 8),
              Text(
                l10n.homeSubtitle,
                style: theme.textTheme.titleMedium?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 24),
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Row(
                    children: [
                      Icon(
                        isDefault ? Icons.check_circle : Icons.warning_amber,
                        size: 28,
                        color: accent,
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: Text(
                          isDefault
                              ? l10n.homeStatusActive
                              : l10n.homeStatusInactive,
                          style: theme.textTheme.titleSmall?.copyWith(
                            color: accent,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 16),
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Text(
                    l10n.homeDescription,
                    style: theme.textTheme.bodyMedium,
                  ),
                ),
              ),
              const Spacer(),
              FilledButton.icon(
                onPressed: _openSettings,
                icon: const Icon(Icons.settings),
                label: Text(
                  isDefault
                      ? l10n.homeCtaAlreadyDefault
                      : l10n.homeCtaSetDefault,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
