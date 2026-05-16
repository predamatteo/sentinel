import 'dart:async';

import 'package:flutter/material.dart';
import '../../l10n/generated/app_localizations.dart';

import '../../app/theme.dart';
import '../../services/analysis_models.dart';
import '../../services/analysis_service.dart';

/// Single verdict screen that renders one of three visual states based on
/// [AnalysisResult.verdict]:
///   - SAFE: green, auto-forwards to Chrome after a short countdown
///   - SUSPICIOUS: amber, requires explicit user choice, no auto-forward
///   - MALICIOUS: red, "Procedi comunque" guarded by a confirm dialog
class VerdictScreen extends StatefulWidget {
  const VerdictScreen({
    super.key,
    required this.result,
    required this.service,
    this.safeAutoRedirectSeconds = 2,
  });

  final AnalysisResult result;
  final AnalysisService service;
  final int safeAutoRedirectSeconds;

  @override
  State<VerdictScreen> createState() => _VerdictScreenState();
}

class _VerdictScreenState extends State<VerdictScreen> {
  Timer? _countdownTimer;
  int _remaining = 0;
  bool _proceeding = false;

  @override
  void initState() {
    super.initState();
    if (widget.result.verdict == Verdict.safe) {
      _remaining = widget.safeAutoRedirectSeconds;
      _countdownTimer = Timer.periodic(const Duration(seconds: 1), (timer) {
        if (!mounted) return;
        if (_remaining <= 1) {
          timer.cancel();
          _proceed(askConfirm: false);
        } else {
          setState(() => _remaining -= 1);
        }
      });
    }
  }

  @override
  void dispose() {
    _countdownTimer?.cancel();
    super.dispose();
  }

  Future<void> _proceed({required bool askConfirm}) async {
    if (_proceeding) return;
    if (askConfirm) {
      final confirmed = await _showConfirmDialog();
      if (confirmed != true) return;
    }
    setState(() => _proceeding = true);
    _countdownTimer?.cancel();
    final launched = await widget.service.proceedToChrome(widget.result.url);
    if (!launched && mounted) {
      final l10n = AppLocalizations.of(context);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(l10n.errorNoBrowser)),
      );
      setState(() => _proceeding = false);
    }
  }

  Future<bool?> _showConfirmDialog() {
    final l10n = AppLocalizations.of(context);
    return showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(l10n.verdictDangerConfirmTitle),
        content: Text(l10n.verdictDangerConfirmBody),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: Text(l10n.verdictDangerConfirmCancel),
          ),
          FilledButton(
            style: FilledButton.styleFrom(
              backgroundColor: SentinelColors.danger,
            ),
            onPressed: () => Navigator.of(ctx).pop(true),
            child: Text(l10n.verdictDangerConfirmProceed),
          ),
        ],
      ),
    );
  }

  Future<void> _cancel() async {
    _countdownTimer?.cancel();
    await widget.service.cancelNavigation();
  }

  @override
  Widget build(BuildContext context) {
    switch (widget.result.verdict) {
      case Verdict.safe:
        return _SafeVerdictBody(
          result: widget.result,
          remainingSeconds: _remaining,
          proceeding: _proceeding,
          onProceed: () => _proceed(askConfirm: false),
          onCancel: _cancel,
        );
      case Verdict.malicious:
        return _DangerVerdictBody(
          result: widget.result,
          proceeding: _proceeding,
          onProceed: () => _proceed(askConfirm: true),
          onCancel: _cancel,
          isCritical: true,
        );
      case Verdict.suspicious:
      case Verdict.unknown:
        return _DangerVerdictBody(
          result: widget.result,
          proceeding: _proceeding,
          onProceed: () => _proceed(askConfirm: true),
          onCancel: _cancel,
          isCritical: false,
        );
    }
  }
}

class _SafeVerdictBody extends StatelessWidget {
  const _SafeVerdictBody({
    required this.result,
    required this.remainingSeconds,
    required this.proceeding,
    required this.onProceed,
    required this.onCancel,
  });

  final AnalysisResult result;
  final int remainingSeconds;
  final bool proceeding;
  final VoidCallback onProceed;
  final VoidCallback onCancel;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);

    return Scaffold(
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 32),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const SizedBox(height: 24),
              const _VerdictBadge(
                icon: Icons.verified_user,
                color: SentinelColors.success,
                containerColor: SentinelColors.successContainer,
              ),
              const SizedBox(height: 24),
              Text(
                l10n.verdictSafeTitle,
                style: theme.textTheme.headlineMedium?.copyWith(
                  fontWeight: FontWeight.w700,
                  color: SentinelColors.success,
                ),
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 8),
              Text(
                l10n.verdictSafeSubtitle,
                style: theme.textTheme.bodyLarge?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 24),
              _UrlCard(url: result.url),
              const SizedBox(height: 16),
              if (result.sources.isNotEmpty)
                _SourcesCard(sources: result.sources),
              const Spacer(),
              if (remainingSeconds > 0)
                Padding(
                  padding: const EdgeInsets.only(bottom: 12),
                  child: Text(
                    l10n.verdictSafeAutoRedirect(remainingSeconds),
                    textAlign: TextAlign.center,
                    style: theme.textTheme.bodySmall?.copyWith(
                      color: theme.colorScheme.onSurfaceVariant,
                    ),
                  ),
                ),
              FilledButton.icon(
                onPressed: proceeding ? null : onProceed,
                icon: const Icon(Icons.open_in_new),
                label: Text(l10n.verdictSafeAction),
                style: FilledButton.styleFrom(
                  backgroundColor: SentinelColors.success,
                ),
              ),
              const SizedBox(height: 12),
              OutlinedButton(
                onPressed: proceeding ? null : onCancel,
                child: Text(l10n.verdictSafeCancel),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _DangerVerdictBody extends StatelessWidget {
  const _DangerVerdictBody({
    required this.result,
    required this.proceeding,
    required this.onProceed,
    required this.onCancel,
    required this.isCritical,
  });

  final AnalysisResult result;
  final bool proceeding;
  final VoidCallback onProceed;
  final VoidCallback onCancel;
  final bool isCritical;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final mainColor = isCritical ? SentinelColors.danger : SentinelColors.warning;
    final containerColor = isCritical
        ? SentinelColors.dangerContainer
        : SentinelColors.warningContainer;
    final icon = isCritical ? Icons.gpp_bad : Icons.shield_moon;
    final title = isCritical
        ? l10n.verdictDangerTitle
        : l10n.verdictWarningTitle;
    final subtitle = isCritical
        ? l10n.verdictDangerSubtitle
        : l10n.verdictWarningSubtitle;

    return Scaffold(
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const SizedBox(height: 12),
              _VerdictBadge(
                icon: icon,
                color: mainColor,
                containerColor: containerColor,
              ),
              const SizedBox(height: 20),
              Text(
                title,
                style: theme.textTheme.headlineMedium?.copyWith(
                  fontWeight: FontWeight.w700,
                  color: mainColor,
                ),
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 8),
              Text(
                subtitle,
                style: theme.textTheme.bodyLarge?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 20),
              _UrlCard(url: result.url),
              const SizedBox(height: 16),
              Expanded(
                child: SingleChildScrollView(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      if (result.reasons.isNotEmpty)
                        _ReasonsCard(
                          title: l10n.verdictDangerReasonsTitle,
                          reasons: result.reasons,
                          accentColor: mainColor,
                        ),
                      if (result.sources.isNotEmpty) ...[
                        const SizedBox(height: 12),
                        _SourcesCard(sources: result.sources),
                      ],
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 12),
              FilledButton.icon(
                onPressed: proceeding ? null : onCancel,
                icon: const Icon(Icons.arrow_back),
                label: Text(l10n.verdictDangerBack),
              ),
              const SizedBox(height: 12),
              OutlinedButton(
                onPressed: proceeding ? null : onProceed,
                style: OutlinedButton.styleFrom(foregroundColor: mainColor),
                child: Text(l10n.verdictDangerProceed),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _VerdictBadge extends StatelessWidget {
  const _VerdictBadge({
    required this.icon,
    required this.color,
    required this.containerColor,
  });

  final IconData icon;
  final Color color;
  final Color containerColor;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Container(
        width: 120,
        height: 120,
        decoration: BoxDecoration(
          color: containerColor,
          shape: BoxShape.circle,
        ),
        child: Icon(icon, size: 72, color: color),
      ),
    );
  }
}

class _UrlCard extends StatelessWidget {
  const _UrlCard({required this.url});

  final String url;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              l10n.labelUrl,
              style: theme.textTheme.labelMedium?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
            const SizedBox(height: 6),
            SelectableText(
              url,
              style: theme.textTheme.bodyMedium?.copyWith(
                fontFamily: 'monospace',
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _ReasonsCard extends StatelessWidget {
  const _ReasonsCard({
    required this.title,
    required this.reasons,
    required this.accentColor,
  });

  final String title;
  final List<String> reasons;
  final Color accentColor;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              title,
              style: theme.textTheme.titleSmall?.copyWith(
                fontWeight: FontWeight.w600,
              ),
            ),
            const SizedBox(height: 8),
            for (final reason in reasons)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 4),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Icon(Icons.error_outline, size: 18, color: accentColor),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        reason,
                        style: theme.textTheme.bodyMedium,
                      ),
                    ),
                  ],
                ),
              ),
          ],
        ),
      ),
    );
  }
}

class _SourcesCard extends StatelessWidget {
  const _SourcesCard({required this.sources});

  final List<String> sources;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              l10n.verdictDangerSourcesTitle,
              style: theme.textTheme.titleSmall?.copyWith(
                fontWeight: FontWeight.w600,
              ),
            ),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                for (final source in sources)
                  Chip(
                    label: Text(source),
                    visualDensity: VisualDensity.compact,
                  ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
