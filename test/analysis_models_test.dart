// Tests the defensive Map parsing of AnalysisResult, including the new
// UNAVAILABLE verdict (fail-open) and the notes field.
import 'package:flutter_test/flutter_test.dart';
import 'package:sentinel/services/analysis_models.dart';

void main() {
  group('AnalysisResult.fromMap', () {
    test('maps known verdict strings', () {
      expect(AnalysisResult.fromMap({'verdict': 'SAFE'}).verdict, Verdict.safe);
      expect(
        AnalysisResult.fromMap({'verdict': 'SUSPICIOUS'}).verdict,
        Verdict.suspicious,
      );
      expect(
        AnalysisResult.fromMap({'verdict': 'MALICIOUS'}).verdict,
        Verdict.malicious,
      );
    });

    test('maps UNAVAILABLE to the neutral unknown verdict (fail-open)', () {
      final result = AnalysisResult.fromMap({
        'url': 'http://example.com',
        'verdict': 'UNAVAILABLE',
      });
      expect(result.verdict, Verdict.unknown);
    });

    test('parses notes, defaulting to empty when absent', () {
      final withNotes = AnalysisResult.fromMap({
        'verdict': 'SAFE',
        'notes': <String>['Verifica online non completata'],
      });
      expect(withNotes.notes, ['Verifica online non completata']);

      final withoutNotes = AnalysisResult.fromMap({'verdict': 'SAFE'});
      expect(withoutNotes.notes, isEmpty);
    });

    test('unknown / missing verdict falls back to unknown', () {
      expect(AnalysisResult.fromMap({}).verdict, Verdict.unknown);
      expect(
        AnalysisResult.fromMap({'verdict': 'GIBBERISH'}).verdict,
        Verdict.unknown,
      );
    });
  });
}
