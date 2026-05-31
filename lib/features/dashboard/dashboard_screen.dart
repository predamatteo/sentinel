import 'dart:async';

import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../../app/theme.dart';
import '../../l10n/generated/app_localizations.dart';
import '../../services/accessibility_service.dart';
import '../../services/analysis_service.dart';
import '../../services/vpn_service.dart';
import '../settings/settings_screen.dart';

/// Sentinel home dashboard. Sits alongside (and effectively replaces)
/// the Sprint 1 [home_screen.HomeScreen] as the post-onboarding landing.
///
/// Sprint Quality:
///  - reads counters from [VpnService.statsStream] (EventChannel push)
///    instead of a 2-second polling timer;
///  - separates "Pubblicità bloccate" from "Minacce bloccate";
///  - tags every recent block entry with a category chip;
///  - shows a "Aggiornato {time}" footer using the intl package.
class DashboardScreen extends StatefulWidget {
  const DashboardScreen({
    super.key,
    required this.analysisService,
    required this.vpnService,
    this.accessibilityService,
  });

  final AnalysisService analysisService;
  final VpnService vpnService;
  final AccessibilityService? accessibilityService;

  @override
  State<DashboardScreen> createState() => _DashboardScreenState();
}

class _DashboardScreenState extends State<DashboardScreen>
    with WidgetsBindingObserver {
  late final AccessibilityService _accessibility =
      widget.accessibilityService ?? AccessibilityService();

  bool _vpnRunning = false;
  bool _vpnBusy = false;
  bool _hasError = false;
  bool? _isDefaultBrowser;
  bool _accessibilityEnabled = false;
  bool _overlayAllowed = false;
  bool _hotspotActive = false;
  VpnEnvironmentStatus _env = VpnEnvironmentStatus.clear;
  VpnStats _stats = VpnStats.empty;
  DateTime _lastUpdated = DateTime.now();

  StreamSubscription<VpnStats>? _statsSub;
  StreamSubscription<bool>? _consentSub;
  Timer? _runningPollTimer;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _consentSub = widget.vpnService.consentResults.listen(_onConsentResult);
    _subscribeToStats();
    _startRunningPoll();
    _refreshAll();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _statsSub?.cancel();
    _statsSub = null;
    _consentSub?.cancel();
    _consentSub = null;
    _runningPollTimer?.cancel();
    _runningPollTimer = null;
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _refreshAll();
      _subscribeToStats();
      _startRunningPoll();
    } else if (state == AppLifecycleState.paused) {
      _statsSub?.cancel();
      _statsSub = null;
      _runningPollTimer?.cancel();
      _runningPollTimer = null;
    }
  }

  void _subscribeToStats() {
    _statsSub?.cancel();
    _statsSub = widget.vpnService.statsStream().listen(
      (stats) {
        if (!mounted) return;
        setState(() {
          _stats = stats;
          _lastUpdated = DateTime.now();
        });
      },
      onError: (Object _) {
        // The platform channel can transiently error when the engine is
        // recreated on configuration changes; we silently swallow the
        // error and the next periodic emit will reconnect. Falling back
        // to a one-shot read keeps the dashboard responsive.
        _refreshStatsOnce();
      },
    );
  }

  void _startRunningPoll() {
    _runningPollTimer?.cancel();
    _runningPollTimer = Timer.periodic(const Duration(seconds: 2), (_) {
      _refreshRunning();
      _refreshHotspot();
      _refreshEnvironment();
    });
  }

  Future<void> _refreshAll() async {
    await Future.wait<void>([
      _refreshRunning(),
      _refreshStatsOnce(),
      _refreshDefaultBrowser(),
      _refreshAccessibility(),
      _refreshHotspot(),
      _refreshEnvironment(),
    ]);
  }

  Future<void> _refreshAccessibility() async {
    final axEnabled = await _accessibility.isAccessibilityEnabled();
    final overlay = await _accessibility.canDrawOverlays();
    if (!mounted) return;
    setState(() {
      _accessibilityEnabled = axEnabled;
      _overlayAllowed = overlay;
    });
  }

  Future<void> _refreshRunning() async {
    final running = await widget.vpnService.isRunning();
    if (!mounted) return;
    setState(() => _vpnRunning = running);
  }

  Future<void> _refreshHotspot() async {
    final active = await widget.vpnService.isHotspotActive();
    if (!mounted || active == _hotspotActive) return;
    setState(() => _hotspotActive = active);
  }

  Future<void> _refreshEnvironment() async {
    final env = await widget.vpnService.getEnvironmentStatus();
    if (!mounted) return;
    setState(() => _env = env);
  }

  Future<void> _refreshStatsOnce() async {
    final stats = await widget.vpnService.getStats();
    if (!mounted) return;
    setState(() {
      _stats = stats;
      _lastUpdated = DateTime.now();
    });
  }

  Future<void> _refreshDefaultBrowser() async {
    final isDefault = await widget.analysisService.isDefaultBrowser();
    if (!mounted) return;
    setState(() => _isDefaultBrowser = isDefault);
  }

  Future<void> _toggleVpn() async {
    if (_vpnBusy) return;
    final l10n = AppLocalizations.of(context);
    setState(() {
      _vpnBusy = true;
      _hasError = false;
    });
    try {
      if (_vpnRunning) {
        await widget.vpnService.stop();
        if (!mounted) return;
        _showSnack(l10n.vpnStoppedSnack);
      } else {
        final outcome = await widget.vpnService.requestStart();
        switch (outcome) {
          case VpnStartOutcome.ready:
            final ok = await widget.vpnService.confirmStart();
            if (!ok && mounted) {
              setState(() => _hasError = true);
              _showSnack(l10n.vpnStartError);
            } else if (mounted) {
              _showSnack(l10n.vpnStartedSnack);
            }
            break;
          case VpnStartOutcome.consentRequired:
            // Wait for the user to dismiss the system VPN consent
            // dialog. The result will arrive via consentResults stream.
            break;
          case VpnStartOutcome.error:
          case VpnStartOutcome.consentDenied:
            if (mounted) {
              setState(() => _hasError = true);
              _showSnack(l10n.vpnStartError);
            }
            break;
        }
      }
    } finally {
      if (mounted) {
        setState(() => _vpnBusy = false);
        unawaited(_refreshRunning());
      }
    }
  }

  Future<void> _onConsentResult(bool granted) async {
    if (!mounted) return;
    final l10n = AppLocalizations.of(context);
    if (granted) {
      final ok = await widget.vpnService.confirmStart();
      if (!mounted) return;
      if (ok) {
        _showSnack(l10n.vpnStartedSnack);
      } else {
        setState(() => _hasError = true);
        _showSnack(l10n.vpnStartError);
      }
    } else {
      _showSnack(l10n.vpnConsentDenied);
    }
    unawaited(_refreshRunning());
  }

  void _showSnack(String message) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message)),
    );
  }

  Future<void> _openSettings() async {
    await Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => SettingsScreen(
          analysisService: widget.analysisService,
          vpnService: widget.vpnService,
          accessibilityService: _accessibility,
        ),
      ),
    );
    await _refreshAll();
  }

  Future<void> _setAsDefaultBrowser() async {
    await widget.analysisService.openDefaultBrowserSettings();
    // The user will return via lifecycle; refresh runs in didChange.
  }

  Future<void> _openAccessibility() async {
    await _accessibility.openAccessibilitySettings();
  }

  Future<void> _openOverlaySettings() async {
    await _accessibility.openOverlaySettings();
  }

  Future<void> _openHotspotSettings() async {
    await widget.vpnService.openHotspotSettings();
  }

  Future<void> _openPrivateDnsSettings() async {
    await widget.vpnService.openPrivateDnsSettings();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);

    final state = _hasError
        ? SentinelProtectionState.error
        : (_vpnRunning
            ? SentinelProtectionState.active
            : SentinelProtectionState.inactive);

    return Scaffold(
      appBar: AppBar(
        title: Text(l10n.dashboardTitle),
        actions: [
          IconButton(
            tooltip: l10n.dashboardOpenSettings,
            onPressed: _openSettings,
            icon: const Icon(Icons.settings_outlined),
          ),
        ],
      ),
      body: SafeArea(
        child: RefreshIndicator(
          onRefresh: _refreshAll,
          child: ListView(
            padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
            children: [
              _StatusCard(
                state: state,
                running: _vpnRunning,
                busy: _vpnBusy,
                onToggle: _toggleVpn,
              ),
              const SizedBox(height: 16),
              if (_hotspotActive)
                Padding(
                  padding: const EdgeInsets.only(bottom: 16),
                  child: _HotspotAdvisoryCard(
                    onOpenSettings: _openHotspotSettings,
                  ),
                ),
              // Encrypted DNS (Private DNS / browser Secure DNS) bypasses our
              // UDP/53 filter; strict Private DNS can also break resolution.
              // Only nag while the VPN is on.
              if (_vpnRunning && _env.encryptedDnsActive)
                Padding(
                  padding: const EdgeInsets.only(bottom: 16),
                  child: _DnsEnvironmentAdvisoryCard(
                    strict: _env.strictPrivateDns,
                    onOpenSettings: _openPrivateDnsSettings,
                  ),
                ),
              if (_isDefaultBrowser == false)
                Padding(
                  padding: const EdgeInsets.only(bottom: 16),
                  child: _DefaultBrowserCta(
                    onSet: _setAsDefaultBrowser,
                    label: l10n.dashboardSetDefaultBrowser,
                  ),
                ),
              // Sprint 3 advisory chips. We only nag when the VPN is
              // actually on (otherwise the user is mid-onboarding and
              // already sees stronger CTAs) and we never show both
              // chips simultaneously: the overlay chip presupposes the
              // accessibility service is enabled, so it implicitly
              // replaces the accessibility chip in the granted-by-half
              // state.
              if (_vpnRunning && !_accessibilityEnabled)
                Padding(
                  padding: const EdgeInsets.only(bottom: 16),
                  child: _AdvisoryChip(
                    icon: Icons.accessibility_new,
                    label: l10n.dashboardAdvancedProtectionMissing,
                    cta: l10n.dashboardAdvancedProtectionMissingCta,
                    onPressed: _openAccessibility,
                  ),
                ),
              if (_vpnRunning && _accessibilityEnabled && !_overlayAllowed)
                Padding(
                  padding: const EdgeInsets.only(bottom: 16),
                  child: _AdvisoryChip(
                    icon: Icons.layers,
                    label: l10n.dashboardOverlayPermissionMissing,
                    cta: l10n.dashboardOverlayPermissionMissingCta,
                    onPressed: _openOverlaySettings,
                  ),
                ),
              _StatsSection(stats: _stats),
              const SizedBox(height: 16),
              Text(
                l10n.dashboardRecentTitle,
                style: theme.textTheme.titleMedium?.copyWith(
                  fontWeight: FontWeight.w700,
                ),
              ),
              const SizedBox(height: 8),
              _RecentBlocksList(events: _stats.recentBlocks),
              const SizedBox(height: 12),
              _UpdatedAtFooter(timestamp: _lastUpdated),
              const SizedBox(height: 24),
            ],
          ),
        ),
      ),
    );
  }
}

class _StatusCard extends StatelessWidget {
  const _StatusCard({
    required this.state,
    required this.running,
    required this.busy,
    required this.onToggle,
  });

  final SentinelProtectionState state;
  final bool running;
  final bool busy;
  final VoidCallback onToggle;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final fg = SentinelStatusColor.foregroundFor(state);
    final bg = SentinelStatusColor.containerFor(state);

    final title = switch (state) {
      SentinelProtectionState.active => l10n.dashboardStatusOnTitle,
      SentinelProtectionState.inactive => l10n.dashboardStatusOffTitle,
      SentinelProtectionState.error => l10n.dashboardStatusErrorTitle,
    };
    final subtitle = switch (state) {
      SentinelProtectionState.active => l10n.dashboardStatusOnSubtitle,
      SentinelProtectionState.inactive => l10n.dashboardStatusOffSubtitle,
      SentinelProtectionState.error => l10n.dashboardStatusErrorSubtitle,
    };
    final icon = switch (state) {
      SentinelProtectionState.active => Icons.verified_user,
      SentinelProtectionState.inactive => Icons.shield_outlined,
      SentinelProtectionState.error => Icons.error_outline,
    };

    return Card(
      color: bg,
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Icon(icon, color: fg, size: 32),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    title,
                    style: theme.textTheme.titleLarge?.copyWith(
                      color: fg,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            Text(
              subtitle,
              style: theme.textTheme.bodyMedium?.copyWith(
                color: theme.colorScheme.onSurface,
              ),
            ),
            const SizedBox(height: 16),
            FilledButton.icon(
              onPressed: busy ? null : onToggle,
              icon: Icon(running ? Icons.power_settings_new : Icons.shield),
              label: Text(
                running
                    ? l10n.dashboardToggleDisable
                    : l10n.dashboardToggleEnable,
              ),
              style: FilledButton.styleFrom(
                backgroundColor: running
                    ? theme.colorScheme.onSurface.withValues(alpha: 0.85)
                    : fg,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _StatsSection extends StatelessWidget {
  const _StatsSection({required this.stats});

  final VpnStats stats;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(
          l10n.dashboardStatsTitle,
          style: theme.textTheme.titleMedium?.copyWith(
            fontWeight: FontWeight.w700,
          ),
        ),
        const SizedBox(height: 8),
        Row(
          children: [
            Expanded(
              child: _StatTile(
                value: stats.threatsBlocked,
                label: l10n.dashboardStatThreatsBlocked,
                color: SentinelColors.danger,
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: _StatTile(
                value: stats.adsBlocked,
                label: l10n.dashboardStatAdsBlocked,
                color: SentinelColors.warning,
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: _StatTile(
                value: stats.linksChecked,
                label: l10n.dashboardStatLinksChecked,
                color: SentinelColors.success,
              ),
            ),
          ],
        ),
      ],
    );
  }
}

class _StatTile extends StatelessWidget {
  const _StatTile({
    required this.value,
    required this.label,
    required this.color,
  });

  final int value;
  final String label;
  final Color color;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              _format(value),
              style: theme.textTheme.headlineSmall?.copyWith(
                color: color,
                fontWeight: FontWeight.w800,
                fontFeatures: const [FontFeature.tabularFigures()],
              ),
            ),
            const SizedBox(height: 4),
            Text(
              label,
              style: theme.textTheme.bodySmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
          ],
        ),
      ),
    );
  }

  String _format(int value) {
    if (value < 1000) return value.toString();
    if (value < 1000000) return '${(value / 1000).toStringAsFixed(1)}k';
    return '${(value / 1000000).toStringAsFixed(1)}M';
  }
}

class _RecentBlocksList extends StatelessWidget {
  const _RecentBlocksList({required this.events});

  final List<VpnBlockEvent> events;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    if (events.isEmpty) {
      return Card(
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Text(
            l10n.dashboardRecentEmptyToday,
            style: theme.textTheme.bodyMedium?.copyWith(
              color: theme.colorScheme.onSurfaceVariant,
            ),
          ),
        ),
      );
    }
    final shown = events.take(10).toList(growable: false);
    return Card(
      child: Column(
        children: [
          for (final (i, e) in shown.indexed) ...[
            _BlockEventTile(event: e),
            if (i < shown.length - 1)
              const Divider(height: 1, indent: 16, endIndent: 16),
          ],
        ],
      ),
    );
  }
}

class _BlockEventTile extends StatelessWidget {
  const _BlockEventTile({required this.event});

  final VpnBlockEvent event;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final isThreat = event.category == BlockCategory.threat;
    final chipColor =
        isThreat ? SentinelColors.danger : SentinelColors.warning;
    final chipLabel =
        isThreat ? l10n.blockCategoryThreat : l10n.blockCategoryAds;
    return ListTile(
      dense: true,
      leading: Icon(
        isThreat ? Icons.gpp_bad : Icons.block,
        color: chipColor,
      ),
      title: Text(
        event.domain,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: const TextStyle(fontFamily: 'monospace'),
      ),
      subtitle: Row(
        children: [
          _CategoryChip(label: chipLabel, color: chipColor),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              _formatTime(event.timestamp),
              style: theme.textTheme.bodySmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
          ),
        ],
      ),
    );
  }

  String _formatTime(DateTime t) {
    final hh = t.hour.toString().padLeft(2, '0');
    final mm = t.minute.toString().padLeft(2, '0');
    return '$hh:$mm';
  }
}

class _CategoryChip extends StatelessWidget {
  const _CategoryChip({required this.label, required this.color});

  final String label;
  final Color color;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.15),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        label,
        style: theme.textTheme.labelSmall?.copyWith(
          color: color,
          fontWeight: FontWeight.w600,
        ),
      ),
    );
  }
}

class _UpdatedAtFooter extends StatelessWidget {
  const _UpdatedAtFooter({required this.timestamp});

  final DateTime timestamp;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final locale = Localizations.localeOf(context);
    final formatted = DateFormat.Hms(locale.toString()).format(timestamp);
    return Align(
      alignment: Alignment.centerRight,
      child: Text(
        l10n.relativeUpdatedAt(formatted),
        style: theme.textTheme.labelSmall?.copyWith(
          color: theme.colorScheme.onSurfaceVariant,
        ),
      ),
    );
  }
}

/// Sprint 3: compact advisory chip surfaced when Layer-3 protection
/// is missing or partially configured. Stays out of the way visually
/// while remaining tappable to drop the user straight into the
/// relevant system settings page.
class _AdvisoryChip extends StatelessWidget {
  const _AdvisoryChip({
    required this.icon,
    required this.label,
    required this.cta,
    required this.onPressed,
  });

  final IconData icon;
  final String label;
  final String cta;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      color: theme.colorScheme.secondaryContainer,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
        child: Row(
          children: [
            Icon(icon, color: theme.colorScheme.onSecondaryContainer),
            const SizedBox(width: 12),
            Expanded(
              child: Text(
                label,
                style: theme.textTheme.bodyMedium?.copyWith(
                  color: theme.colorScheme.onSecondaryContainer,
                ),
              ),
            ),
            TextButton(
              onPressed: onPressed,
              child: Text(cta),
            ),
          ],
        ),
      ),
    );
  }
}

class _DefaultBrowserCta extends StatelessWidget {
  const _DefaultBrowserCta({required this.onSet, required this.label});

  final VoidCallback onSet;
  final String label;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      color: theme.colorScheme.primaryContainer,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: [
            Icon(Icons.open_in_browser,
                color: theme.colorScheme.onPrimaryContainer),
            const SizedBox(width: 12),
            Expanded(
              child: Text(
                label,
                style: theme.textTheme.bodyMedium?.copyWith(
                  color: theme.colorScheme.onPrimaryContainer,
                ),
              ),
            ),
            TextButton(
              onPressed: onSet,
              child: Text(
                AppLocalizations.of(context).homeCtaSetDefault,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// Advisory shown when a Wi-Fi/USB hotspot is active. Tethered clients are
/// not protected by Sentinel and — because Android's tethering DNS
/// forwarder snapshots our sinkhole as its upstream — may stay offline
/// until the hotspot is recycled, even after the VPN is stopped. Uses
/// tertiary-container colours so it adapts to light/dark, unlike the fixed
/// [SentinelColors.warningContainer] used by the status card.
class _HotspotAdvisoryCard extends StatelessWidget {
  const _HotspotAdvisoryCard({required this.onOpenSettings});

  final VoidCallback onOpenSettings;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final fg = theme.colorScheme.onTertiaryContainer;
    return Card(
      color: theme.colorScheme.tertiaryContainer,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.wifi_tethering, color: fg),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    l10n.dashboardHotspotWarningTitle,
                    style: theme.textTheme.titleMedium?.copyWith(
                      color: fg,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              l10n.dashboardHotspotWarningBody,
              style: theme.textTheme.bodyMedium?.copyWith(color: fg),
            ),
            const SizedBox(height: 4),
            Align(
              alignment: Alignment.centerRight,
              child: TextButton(
                onPressed: onOpenSettings,
                style: TextButton.styleFrom(foregroundColor: fg),
                child: Text(l10n.dashboardHotspotWarningCta),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// Advisory shown when the device uses encrypted DNS (system Private DNS or
/// a browser's Secure DNS), which bypasses Sentinel's UDP/53 filter. In
/// strict Private DNS mode it can also break resolution, so we show a
/// stronger, actionable variant with a shortcut to the Private DNS settings.
class _DnsEnvironmentAdvisoryCard extends StatelessWidget {
  const _DnsEnvironmentAdvisoryCard({
    required this.strict,
    required this.onOpenSettings,
  });

  final bool strict;
  final VoidCallback onOpenSettings;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final fg = theme.colorScheme.onTertiaryContainer;
    return Card(
      color: theme.colorScheme.tertiaryContainer,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(strict ? Icons.dns_outlined : Icons.lock_outline, color: fg),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    strict
                        ? l10n.dashboardStrictPrivateDnsTitle
                        : l10n.dashboardEncryptedDnsTitle,
                    style: theme.textTheme.titleMedium?.copyWith(
                      color: fg,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              strict
                  ? l10n.dashboardStrictPrivateDnsBody
                  : l10n.dashboardEncryptedDnsBody,
              style: theme.textTheme.bodyMedium?.copyWith(color: fg),
            ),
            if (strict) ...[
              const SizedBox(height: 4),
              Align(
                alignment: Alignment.centerRight,
                child: TextButton(
                  onPressed: onOpenSettings,
                  style: TextButton.styleFrom(foregroundColor: fg),
                  child: Text(l10n.dashboardStrictPrivateDnsCta),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}
