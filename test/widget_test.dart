// Smoke test for the Sentinel app. The full UI relies on the native
// AnalysisChannel which is not available in widget tests, so we only
// verify that the analysis_models layer parses sensible inputs.

import 'package:flutter_test/flutter_test.dart';
import 'package:sentinel/services/analysis_models.dart';

void main() {
  test('AnalysisResult.fromMap parses native payload', () {
    final result = AnalysisResult.fromMap({
      'url': 'https://example.com',
      'verdict': 'SAFE',
      'reasons': <String>[],
      'sources': <String>['Blacklist locale', 'Google Safe Browsing'],
      'analyzedAt': 1700000000000,
    });

    expect(result.url, 'https://example.com');
    expect(result.verdict, Verdict.safe);
    expect(result.sources, hasLength(2));
  });

  test('AnalysisResult.fromMap falls back gracefully on missing fields', () {
    final result = AnalysisResult.fromMap({});
    expect(result.url, isEmpty);
    expect(result.verdict, Verdict.unknown);
    expect(result.reasons, isEmpty);
  });
}
