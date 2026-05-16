import 'package:flutter/material.dart';
import '../../l10n/generated/app_localizations.dart';

import '../../services/accessibility_service.dart';
import '../../services/analysis_service.dart';

/// Multi-page onboarding shown when the user opens Sentinel from the
/// launcher rather than through a link. The final page requests the
/// default-browser role; the new page 4 (Sprint 3) introduces the
/// Layer-3 accessibility-based protection and routes the user to the
/// system Accessibility settings.
class WelcomeScreen extends StatefulWidget {
  const WelcomeScreen({
    super.key,
    required this.service,
    AccessibilityService? accessibilityService,
  }) : _accessibilityService = accessibilityService;

  final AnalysisService service;
  final AccessibilityService? _accessibilityService;

  @override
  State<WelcomeScreen> createState() => _WelcomeScreenState();
}

class _WelcomeScreenState extends State<WelcomeScreen> {
  final PageController _controller = PageController();
  late final AccessibilityService _accessibility =
      widget._accessibilityService ?? AccessibilityService();
  int _index = 0;
  bool? _isDefaultBrowser;

  @override
  void initState() {
    super.initState();
    _refreshDefaultBrowserStatus();
  }

  Future<void> _refreshDefaultBrowserStatus() async {
    final isDefault = await widget.service.isDefaultBrowser();
    if (!mounted) return;
    setState(() => _isDefaultBrowser = isDefault);
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _next() {
    if (_index < _pageCount - 1) {
      _controller.nextPage(
        duration: const Duration(milliseconds: 240),
        curve: Curves.easeOut,
      );
    } else {
      _requestDefaultBrowser();
    }
  }

  // Single source of truth for the page count, used by the navigation
  // logic and by the dots indicator. Kept here so the welcome screen
  // can grow without re-wiring index arithmetic.
  static const int _pageCount = 5;

  Future<void> _requestDefaultBrowser() async {
    await widget.service.openDefaultBrowserSettings();
    // Status is refreshed when the user returns to the app via the
    // route's didChangeAppLifecycleState mechanism, but we also re-query
    // after a short delay to cover quick returns.
    Future<void>.delayed(const Duration(milliseconds: 500), _refreshDefaultBrowserStatus);
  }

  Future<void> _openAccessibilitySettings() async {
    await _accessibility.openAccessibilitySettings();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);

    final pages = <_OnboardingPageData>[
      _OnboardingPageData(
        icon: Icons.shield_outlined,
        title: l10n.onboardingTitle1,
        body: l10n.onboardingBody1,
      ),
      _OnboardingPageData(
        icon: Icons.search,
        title: l10n.onboardingTitle2,
        body: l10n.onboardingBody2,
      ),
      _OnboardingPageData(
        icon: Icons.dns_outlined,
        title: l10n.onboardingTitle4,
        body: l10n.onboardingBody4,
      ),
      _OnboardingPageData(
        icon: Icons.accessibility_new,
        title: l10n.onboardingTitle5,
        body: l10n.onboardingBody5,
        actionLabel: l10n.onboardingCtaAccessibility,
        onAction: _openAccessibilitySettings,
      ),
      _OnboardingPageData(
        icon: Icons.settings_suggest,
        title: l10n.onboardingTitle3,
        body: l10n.onboardingBody3,
      ),
    ];

    return Scaffold(
      body: SafeArea(
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Row(
                    children: [
                      Icon(Icons.shield, color: theme.colorScheme.primary),
                      const SizedBox(width: 8),
                      Text(
                        l10n.appName,
                        style: theme.textTheme.titleMedium?.copyWith(
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                    ],
                  ),
                  if (_index < _pageCount - 1)
                    TextButton(
                      onPressed: () => _controller.jumpToPage(_pageCount - 1),
                      child: Text(l10n.onboardingSkip),
                    ),
                ],
              ),
            ),
            Expanded(
              child: PageView.builder(
                controller: _controller,
                itemCount: pages.length,
                onPageChanged: (i) => setState(() => _index = i),
                itemBuilder: (_, i) => _OnboardingPage(data: pages[i]),
              ),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 24),
              child: Column(
                children: [
                  _DotsIndicator(count: pages.length, index: _index),
                  const SizedBox(height: 24),
                  if (_index == pages.length - 1 && _isDefaultBrowser == true)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 12),
                      child: Row(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          Icon(
                            Icons.check_circle,
                            size: 18,
                            color: theme.colorScheme.primary,
                          ),
                          const SizedBox(width: 8),
                          Text(
                            l10n.homeCtaAlreadyDefault,
                            style: theme.textTheme.bodyMedium,
                          ),
                        ],
                      ),
                    ),
                  SizedBox(
                    width: double.infinity,
                    child: FilledButton(
                      onPressed: _next,
                      child: Text(
                        _index < pages.length - 1
                            ? l10n.onboardingNext
                            : l10n.onboardingDone,
                      ),
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

class _OnboardingPageData {
  const _OnboardingPageData({
    required this.icon,
    required this.title,
    required this.body,
    this.actionLabel,
    this.onAction,
  });

  final IconData icon;
  final String title;
  final String body;

  /// Optional inline CTA shown below the body copy. Used by page 4 to
  /// offer a one-tap shortcut to the Accessibility system settings,
  /// without forcing the user out of the onboarding flow.
  final String? actionLabel;
  final Future<void> Function()? onAction;
}

class _OnboardingPage extends StatelessWidget {
  const _OnboardingPage({required this.data});

  final _OnboardingPageData data;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 32),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Container(
            width: 140,
            height: 140,
            decoration: BoxDecoration(
              color: theme.colorScheme.primaryContainer,
              shape: BoxShape.circle,
            ),
            child: Icon(
              data.icon,
              size: 80,
              color: theme.colorScheme.onPrimaryContainer,
            ),
          ),
          const SizedBox(height: 32),
          Text(
            data.title,
            textAlign: TextAlign.center,
            style: theme.textTheme.headlineSmall?.copyWith(
              fontWeight: FontWeight.w700,
            ),
          ),
          const SizedBox(height: 16),
          Text(
            data.body,
            textAlign: TextAlign.center,
            style: theme.textTheme.bodyLarge?.copyWith(
              color: theme.colorScheme.onSurfaceVariant,
            ),
          ),
          if (data.actionLabel != null && data.onAction != null) ...[
            const SizedBox(height: 24),
            OutlinedButton.icon(
              icon: const Icon(Icons.settings),
              label: Text(data.actionLabel!),
              onPressed: () => data.onAction!(),
            ),
          ],
        ],
      ),
    );
  }
}

class _DotsIndicator extends StatelessWidget {
  const _DotsIndicator({required this.count, required this.index});

  final int count;
  final int index;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Row(
      mainAxisAlignment: MainAxisAlignment.center,
      children: List.generate(count, (i) {
        final selected = i == index;
        return AnimatedContainer(
          duration: const Duration(milliseconds: 200),
          width: selected ? 24 : 8,
          height: 8,
          margin: const EdgeInsets.symmetric(horizontal: 4),
          decoration: BoxDecoration(
            color: selected
                ? theme.colorScheme.primary
                : theme.colorScheme.outlineVariant,
            borderRadius: BorderRadius.circular(4),
          ),
        );
      }),
    );
  }
}
