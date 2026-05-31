import 'dart:async';

import 'package:flutter/material.dart';

import '../../app/theme.dart';
import '../../l10n/generated/app_localizations.dart';
import '../../services/accessibility_service.dart';
import '../../services/analysis_service.dart';
import '../../services/remote_config_service.dart';
import '../../services/vpn_service.dart';
import '../../services/whitelist_service.dart';

/// Settings hub. Lets the user toggle the VPN, pick an upstream DNS,
/// manage the per-domain whitelist, force-refresh remote blocklists,
/// and (Sprint 3) inspect / open the system pages that gate the
/// Layer-3 accessibility-service protection.
class SettingsScreen extends StatefulWidget {
  const SettingsScreen({
    super.key,
    required this.analysisService,
    required this.vpnService,
    this.whitelistService,
    this.remoteConfigService,
    this.accessibilityService,
  });

  final AnalysisService analysisService;
  final VpnService vpnService;
  final WhitelistService? whitelistService;
  final RemoteConfigService? remoteConfigService;
  final AccessibilityService? accessibilityService;

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

enum _DnsPreset { cloudflare, quad9, google, custom }

class _SettingsScreenState extends State<SettingsScreen>
    with WidgetsBindingObserver {
  late final WhitelistService _whitelist =
      widget.whitelistService ?? WhitelistService();
  late final RemoteConfigService _remoteConfig =
      widget.remoteConfigService ?? RemoteConfigService.instance;
  late final AccessibilityService _accessibility =
      widget.accessibilityService ?? AccessibilityService();

  bool _vpnRunning = false;
  bool _vpnBusy = false;
  bool _refreshing = false;
  bool _isDefaultBrowser = false;
  bool _accessibilityEnabled = false;
  bool _overlayAllowed = false;
  _DnsPreset _dnsPreset = _DnsPreset.cloudflare;
  String _customPrimary = '';
  String _customSecondary = '';
  List<String> _whitelistDomains = const [];

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
    // The system Accessibility / Display-over-other-apps pages live in
    // a separate process; the only way to know the user came back is
    // the lifecycle-resumed event. Refresh the toggles every time.
    if (state == AppLifecycleState.resumed) {
      _refresh();
    }
  }

  Future<void> _refresh() async {
    final running = await widget.vpnService.isRunning();
    final isDefault = await widget.analysisService.isDefaultBrowser();
    final domains = await _whitelist.getAll();
    final axEnabled = await _accessibility.isAccessibilityEnabled();
    final overlayOk = await _accessibility.canDrawOverlays();
    if (!mounted) return;
    setState(() {
      _vpnRunning = running;
      _isDefaultBrowser = isDefault;
      _whitelistDomains = domains;
      _accessibilityEnabled = axEnabled;
      _overlayAllowed = overlayOk;
    });
  }

  Future<void> _toggleVpn(bool enable) async {
    if (_vpnBusy) return;
    setState(() => _vpnBusy = true);
    try {
      if (!enable) {
        await widget.vpnService.stop();
      } else {
        final outcome = await widget.vpnService.requestStart();
        if (outcome == VpnStartOutcome.ready) {
          await widget.vpnService.confirmStart();
        }
      }
    } finally {
      if (mounted) {
        setState(() => _vpnBusy = false);
        unawaited(_refresh());
      }
    }
  }

  Future<void> _selectDnsPreset(_DnsPreset? choice) async {
    if (choice == null) return;
    if (choice == _DnsPreset.custom) {
      final result = await _showCustomDnsDialog();
      if (result == null) return;
      _customPrimary = result.$1;
      _customSecondary = result.$2;
      await widget.vpnService.setUpstream(
        primary: _customPrimary,
        secondary: _customSecondary,
        dotHostname: 'cloudflare-dns.com',
      );
    } else {
      final (primary, secondary, dot) = _presetServers(choice);
      await widget.vpnService.setUpstream(
        primary: primary,
        secondary: secondary,
        dotHostname: dot,
      );
    }
    if (mounted) setState(() => _dnsPreset = choice);
  }

  (String, String, String) _presetServers(_DnsPreset preset) {
    switch (preset) {
      case _DnsPreset.cloudflare:
        return ('1.1.1.1', '1.0.0.1', 'cloudflare-dns.com');
      case _DnsPreset.quad9:
        return ('9.9.9.9', '149.112.112.112', 'dns.quad9.net');
      case _DnsPreset.google:
        return ('8.8.8.8', '8.8.4.4', 'dns.google');
      case _DnsPreset.custom:
        return (_customPrimary, _customSecondary, 'cloudflare-dns.com');
    }
  }

  Future<(String, String)?> _showCustomDnsDialog() async {
    final l10n = AppLocalizations.of(context);
    final primaryCtrl = TextEditingController(text: _customPrimary);
    final secondaryCtrl = TextEditingController(text: _customSecondary);
    final result = await showDialog<(String, String)>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(l10n.settingsDnsCustomDialogTitle),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: primaryCtrl,
              decoration: InputDecoration(
                labelText: l10n.settingsDnsCustomPrimary,
              ),
              keyboardType: TextInputType.numberWithOptions(decimal: true),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: secondaryCtrl,
              decoration: InputDecoration(
                labelText: l10n.settingsDnsCustomSecondary,
              ),
              keyboardType: TextInputType.numberWithOptions(decimal: true),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: Text(l10n.settingsDnsCustomCancel),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(
              ctx,
              (primaryCtrl.text.trim(), secondaryCtrl.text.trim()),
            ),
            child: Text(l10n.settingsDnsCustomSave),
          ),
        ],
      ),
    );
    primaryCtrl.dispose();
    secondaryCtrl.dispose();
    return result;
  }

  Future<void> _refreshLists() async {
    if (_refreshing) return;
    setState(() => _refreshing = true);
    final l10n = AppLocalizations.of(context);
    final ok = await _remoteConfig.forceRefresh();
    if (ok) {
      final cfg = _remoteConfig.getBlocklistConfig();
      await widget.vpnService.refreshRemoteLists(cfg.remoteUrlsByTag());
      if (mounted) _showSnack(l10n.settingsRefreshDone);
    } else {
      if (mounted) _showSnack(l10n.settingsRefreshError);
    }
    if (mounted) setState(() => _refreshing = false);
  }

  Future<void> _addWhitelist() async {
    final l10n = AppLocalizations.of(context);
    final controller = TextEditingController();
    String? error;
    final added = await showDialog<String>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setStateDialog) => AlertDialog(
          title: Text(l10n.settingsWhitelistDialogTitle),
          content: TextField(
            controller: controller,
            autofocus: true,
            decoration: InputDecoration(
              hintText: l10n.settingsWhitelistHint,
              errorText: error,
            ),
            onSubmitted: (value) => Navigator.pop(ctx, value),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx),
              child: Text(l10n.settingsWhitelistCancel),
            ),
            FilledButton(
              onPressed: () {
                final raw = controller.text.trim();
                // URL-tolerant: accept a pasted full URL by normalising it to
                // the bare host. Only reject input that has no usable host.
                if (WhitelistService.normaliseInput(raw) == null) {
                  setStateDialog(() => error = l10n.settingsWhitelistInvalid);
                  return;
                }
                Navigator.pop(ctx, raw);
              },
              child: Text(l10n.settingsWhitelistSave),
            ),
          ],
        ),
      ),
    );
    controller.dispose();
    if (added == null) return;
    final domain = WhitelistService.normaliseInput(added) ?? added;
    final didAdd = await _whitelist.add(added);
    if (!mounted) return;
    if (!didAdd) {
      // add() returns false only when the (normalised) domain is already
      // present, since the input was validated before reaching here.
      _showSnack(l10n.settingsWhitelistAlready(domain));
      return;
    }
    final all = await _whitelist.getAll();
    if (!mounted) return;
    setState(() => _whitelistDomains = all);
    await widget.vpnService.setWhitelist(_whitelistDomains);
    if (!mounted) return;
    _showSnack(l10n.settingsWhitelistAdded(domain));
  }

  Future<void> _removeWhitelist(String domain) async {
    final didRemove = await _whitelist.remove(domain);
    if (!didRemove) return;
    final all = await _whitelist.getAll();
    if (!mounted) return;
    setState(() => _whitelistDomains = all);
    await widget.vpnService.setWhitelist(_whitelistDomains);
  }

  Future<void> _openDefaultBrowserSettings() async {
    await widget.analysisService.openDefaultBrowserSettings();
  }

  Future<void> _openAccessibilitySettings() async {
    await _accessibility.openAccessibilitySettings();
  }

  Future<void> _openOverlaySettings() async {
    await _accessibility.openOverlaySettings();
  }

  void _showSnack(String message) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message)),
    );
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(l10n.settingsTitle)),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 16, 20, 32),
          children: [
            // Section: Protection
            _SectionHeader(label: l10n.settingsSectionProtection),
            Card(
              child: Column(
                children: [
                  SwitchListTile(
                    value: _vpnRunning,
                    onChanged: _vpnBusy ? null : (v) => _toggleVpn(v),
                    title: Text(l10n.settingsProtectionToggle),
                    subtitle: Text(l10n.settingsProtectionToggleSubtitle),
                    secondary: const Icon(Icons.shield),
                  ),
                  const Divider(height: 1),
                  Padding(
                    padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
                    child: Align(
                      alignment: Alignment.centerLeft,
                      child: Text(
                        l10n.settingsDnsUpstream,
                        style: theme.textTheme.titleSmall?.copyWith(
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                    ),
                  ),
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 16),
                    child: Align(
                      alignment: Alignment.centerLeft,
                      child: Text(
                        l10n.settingsDnsUpstreamSubtitle,
                        style: theme.textTheme.bodySmall?.copyWith(
                          color: theme.colorScheme.onSurfaceVariant,
                        ),
                      ),
                    ),
                  ),
                  RadioGroup<_DnsPreset>(
                    groupValue: _dnsPreset,
                    onChanged: _selectDnsPreset,
                    child: Column(
                      children: [
                        RadioListTile<_DnsPreset>(
                          value: _DnsPreset.cloudflare,
                          title: Text(l10n.settingsDnsCloudflare),
                        ),
                        RadioListTile<_DnsPreset>(
                          value: _DnsPreset.quad9,
                          title: Text(l10n.settingsDnsQuad9),
                        ),
                        RadioListTile<_DnsPreset>(
                          value: _DnsPreset.google,
                          title: Text(l10n.settingsDnsGoogle),
                        ),
                        RadioListTile<_DnsPreset>(
                          value: _DnsPreset.custom,
                          title: Text(l10n.settingsDnsCustom),
                          subtitle: _dnsPreset == _DnsPreset.custom &&
                                  _customPrimary.isNotEmpty
                              ? Text('$_customPrimary, $_customSecondary')
                              : null,
                        ),
                      ],
                    ),
                  ),
                  const Divider(height: 1),
                  ListTile(
                    leading: _refreshing
                        ? const SizedBox(
                            width: 24,
                            height: 24,
                            child:
                                CircularProgressIndicator(strokeWidth: 2.4))
                        : const Icon(Icons.refresh),
                    title: Text(l10n.settingsRefreshLists),
                    subtitle: Text(l10n.settingsRefreshListsSubtitle),
                    onTap: _refreshing ? null : _refreshLists,
                  ),
                ],
              ),
            ),
            const SizedBox(height: 16),

            // Section: Default browser
            _SectionHeader(label: l10n.settingsDefaultBrowser),
            Card(
              child: ListTile(
                leading: Icon(
                  _isDefaultBrowser ? Icons.check_circle : Icons.warning_amber,
                  color: _isDefaultBrowser
                      ? SentinelColors.success
                      : SentinelColors.warning,
                ),
                title: Text(
                  _isDefaultBrowser
                      ? l10n.homeCtaAlreadyDefault
                      : l10n.homeCtaSetDefault,
                ),
                trailing: const Icon(Icons.chevron_right),
                onTap: _openDefaultBrowserSettings,
              ),
            ),
            const SizedBox(height: 16),

            // Section: Whitelist
            _SectionHeader(label: l10n.settingsSectionWhitelist),
            Card(
              child: Column(
                children: [
                  if (_whitelistDomains.isEmpty)
                    Padding(
                      padding: const EdgeInsets.all(16),
                      child: Text(
                        l10n.settingsWhitelistEmpty,
                        style: theme.textTheme.bodyMedium?.copyWith(
                          color: theme.colorScheme.onSurfaceVariant,
                        ),
                      ),
                    )
                  else
                    for (final (i, domain) in _whitelistDomains.indexed) ...[
                      ListTile(
                        leading: const Icon(Icons.check, color: SentinelColors.success),
                        title: Text(
                          domain,
                          style: const TextStyle(fontFamily: 'monospace'),
                        ),
                        trailing: IconButton(
                          tooltip: l10n.settingsWhitelistRemove,
                          icon: const Icon(Icons.close),
                          onPressed: () => _removeWhitelist(domain),
                        ),
                      ),
                      if (i < _whitelistDomains.length - 1)
                        const Divider(height: 1, indent: 16, endIndent: 16),
                    ],
                  const Divider(height: 1),
                  ListTile(
                    leading: const Icon(Icons.add),
                    title: Text(l10n.settingsWhitelistAdd),
                    onTap: _addWhitelist,
                  ),
                ],
              ),
            ),
            const SizedBox(height: 16),

            // Section: Advanced protection (Sprint 3 — Layer 3).
            _SectionHeader(label: l10n.accessibilitySettingsSectionTitle),
            Card(
              child: Column(
                children: [
                  _AccessibilityToggleTile(
                    enabled: _accessibilityEnabled,
                    onTap: _openAccessibilitySettings,
                  ),
                  const Divider(height: 1),
                  _OverlayPermissionTile(
                    granted: _overlayAllowed,
                    onTap: _openOverlaySettings,
                  ),
                ],
              ),
            ),
            const SizedBox(height: 16),

            // Section: About
            _SectionHeader(label: l10n.settingsSectionAbout),
            Card(
              child: FutureBuilder<_AppMeta>(
                future: _AppMeta.load(),
                builder: (ctx, snapshot) {
                  final meta = snapshot.data;
                  return Column(
                    children: [
                      ListTile(
                        leading: const Icon(Icons.info_outline),
                        title: Text(l10n.settingsVersion),
                        trailing: Text(meta?.version ?? '-'),
                      ),
                      ListTile(
                        leading: const Icon(Icons.tag),
                        title: Text(l10n.settingsBuildNumber),
                        trailing: Text(meta?.build ?? '-'),
                      ),
                    ],
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _SectionHeader extends StatelessWidget {
  const _SectionHeader({required this.label});

  final String label;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.fromLTRB(4, 4, 4, 8),
      child: Text(
        label,
        style: theme.textTheme.titleSmall?.copyWith(
          color: theme.colorScheme.primary,
          fontWeight: FontWeight.w700,
          letterSpacing: 0.6,
        ),
      ),
    );
  }
}

/// Card row that shows the Layer-3 accessibility status and routes the
/// user to the system Accessibility menu. The "Maggiori informazioni"
/// expansion makes the privacy contract explicit so the user knows
/// what Sentinel sees and what it deliberately ignores.
class _AccessibilityToggleTile extends StatelessWidget {
  const _AccessibilityToggleTile({
    required this.enabled,
    required this.onTap,
  });

  final bool enabled;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final statusColor = enabled ? SentinelColors.success : SentinelColors.warning;
    final statusText = enabled
        ? l10n.accessibilityToggleEnabled
        : l10n.accessibilityToggleDisabled;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        ListTile(
          leading: Icon(Icons.accessibility_new, color: statusColor),
          title: Text(l10n.accessibilityToggleLabel),
          subtitle: Padding(
            padding: const EdgeInsets.only(top: 4),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(statusText, style: TextStyle(color: statusColor, fontWeight: FontWeight.w600)),
                const SizedBox(height: 4),
                Text(
                  l10n.accessibilityToggleDescription,
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: theme.colorScheme.onSurfaceVariant,
                  ),
                ),
              ],
            ),
          ),
          trailing: const Icon(Icons.chevron_right),
          isThreeLine: true,
          onTap: onTap,
        ),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 8),
          child: ExpansionTile(
            tilePadding: const EdgeInsets.symmetric(horizontal: 8),
            childrenPadding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
            title: Text(
              l10n.accessibilityPrivacyDetailsTitle,
              style: theme.textTheme.bodyMedium?.copyWith(
                fontWeight: FontWeight.w600,
              ),
            ),
            children: [
              Align(
                alignment: Alignment.centerLeft,
                child: Text(
                  l10n.accessibilityPrivacyDetailsBody,
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: theme.colorScheme.onSurfaceVariant,
                  ),
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _OverlayPermissionTile extends StatelessWidget {
  const _OverlayPermissionTile({
    required this.granted,
    required this.onTap,
  });

  final bool granted;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final color = granted ? SentinelColors.success : SentinelColors.warning;
    return ListTile(
      leading: Icon(Icons.layers, color: color),
      title: Text(l10n.overlayPermissionRequired),
      subtitle: Padding(
        padding: const EdgeInsets.only(top: 4),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              granted
                  ? l10n.overlayPermissionGranted
                  : l10n.overlayPermissionMissing,
              style: TextStyle(color: color, fontWeight: FontWeight.w600),
            ),
            const SizedBox(height: 4),
            Text(
              l10n.overlayPermissionRequiredSubtitle,
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
            ),
          ],
        ),
      ),
      isThreeLine: true,
      trailing: const Icon(Icons.chevron_right),
      onTap: onTap,
    );
  }
}

/// Lightweight value object for app version/build display. We do not
/// depend on package_info_plus to keep the dependency surface tiny;
/// the version comes from the pubspec at compile time via a const.
class _AppMeta {
  const _AppMeta({required this.version, required this.build});

  final String version;
  final String build;

  // Sprint 2: hard-coded to keep the dependency footprint small. A future
  // sprint may wire package_info_plus for a runtime lookup that survives
  // pubspec bumps automatically.
  static const _AppMeta _fallback = _AppMeta(version: '1.0.0', build: '1');

  static Future<_AppMeta> load() async => _fallback;
}
