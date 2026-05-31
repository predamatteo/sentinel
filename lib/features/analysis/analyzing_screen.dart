import 'package:flutter/material.dart';
import '../../l10n/generated/app_localizations.dart';

import '../../services/analysis_models.dart';
import '../../services/analysis_service.dart';
import 'verdict_screen.dart';

/// Shown while the native engine is checking the URL. The analysis itself
/// is fire-and-await; this screen owns the cancellation contract.
class AnalyzingScreen extends StatefulWidget {
  const AnalyzingScreen({
    super.key,
    required this.url,
    required this.service,
  });

  final String url;
  final AnalysisService service;

  @override
  State<AnalyzingScreen> createState() => _AnalyzingScreenState();
}

class _AnalyzingScreenState extends State<AnalyzingScreen> {
  bool _cancelled = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _runAnalysis());
  }

  Future<void> _runAnalysis() async {
    try {
      final result = await widget.service.analyze(widget.url);
      if (!mounted || _cancelled) return;
      // Fast path for SAFE and UNKNOWN verdicts: forward straight to Chrome
      // without showing an intermediate screen. SAFE means "everything OK";
      // UNKNOWN means a check simply could not complete (most likely fine),
      // so interrupting the user with a screen for a verification that never
      // finished only adds friction. Only SUSPICIOUS/MALICIOUS — which carry
      // real evidence — warrant the verdict screen.
      if (result.verdict == Verdict.safe || result.verdict == Verdict.unknown) {
        final launched = await widget.service.proceedToChrome(widget.url);
        if (!mounted) return;
        if (launched) {
          // Surface any non-blocking notes (e.g. "online check could not
          // complete") via a brief SnackBar so the auto-forward stays
          // transparent without blocking the user.
          if (result.notes.isNotEmpty) {
            ScaffoldMessenger.of(context).showSnackBar(
              SnackBar(content: Text(result.notes.first)),
            );
          }
        } else {
          final l10n = AppLocalizations.of(context);
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text(l10n.errorNoBrowser)),
          );
          // Fall back to the verdict screen so the user can retry from a
          // visible state instead of being stuck on the spinner.
          Navigator.of(context).pushReplacement(
            MaterialPageRoute(
              builder: (_) => VerdictScreen(
                result: result,
                service: widget.service,
              ),
            ),
          );
        }
        return;
      }
      Navigator.of(context).pushReplacement(
        MaterialPageRoute(
          builder: (_) => VerdictScreen(
            result: result,
            service: widget.service,
          ),
        ),
      );
    } catch (error) {
      if (!mounted || _cancelled) return;
      // On unexpected failure fall back to a SUSPICIOUS verdict screen
      // so the user is warned instead of silently approving.
      final fallback = AnalysisResult(
        url: widget.url,
        verdict: Verdict.suspicious,
        reasons: ['${AppLocalizations.of(context).errorGeneric}: $error'],
        sources: const [],
        analyzedAt: DateTime.now(),
      );
      Navigator.of(context).pushReplacement(
        MaterialPageRoute(
          builder: (_) => VerdictScreen(
            result: fallback,
            service: widget.service,
          ),
        ),
      );
    }
  }

  Future<void> _cancel() async {
    setState(() => _cancelled = true);
    await widget.service.cancelNavigation();
  }

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
              Center(
                child: Icon(
                  Icons.shield_outlined,
                  size: 64,
                  color: theme.colorScheme.primary,
                ),
              ),
              const SizedBox(height: 32),
              const Center(
                child: SizedBox(
                  width: 56,
                  height: 56,
                  child: CircularProgressIndicator(strokeWidth: 4),
                ),
              ),
              const SizedBox(height: 32),
              Text(
                l10n.analyzingTitle,
                style: theme.textTheme.headlineSmall?.copyWith(
                  fontWeight: FontWeight.w600,
                ),
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 8),
              Text(
                l10n.analyzingSubtitle,
                style: theme.textTheme.bodyMedium?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 24),
              Card(
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
                        widget.url,
                        style: theme.textTheme.bodyMedium?.copyWith(
                          fontFamily: 'monospace',
                        ),
                        maxLines: 3,
                      ),
                    ],
                  ),
                ),
              ),
              const Spacer(),
              OutlinedButton(
                onPressed: _cancelled ? null : _cancel,
                child: Text(l10n.analyzingCancel),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
