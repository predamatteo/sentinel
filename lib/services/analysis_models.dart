/// Dart-side mirror of the Kotlin `AnalysisResult`. The MethodChannel sends
/// us a plain `Map` so we parse it defensively: any missing field falls back
/// to a non-blocking value, never throws.
enum Verdict { safe, suspicious, malicious, unknown }

extension VerdictParsing on Verdict {
  static Verdict parse(String? raw) {
    switch (raw) {
      case 'SAFE':
        return Verdict.safe;
      case 'SUSPICIOUS':
        return Verdict.suspicious;
      case 'MALICIOUS':
        return Verdict.malicious;
      case 'UNAVAILABLE':
        // A check that could not run/complete. Non-blocking: rendered as a
        // neutral "couldn't verify" state, not a threat.
        return Verdict.unknown;
      default:
        return Verdict.unknown;
    }
  }
}

class AnalysisResult {
  AnalysisResult({
    required this.url,
    required this.verdict,
    required this.reasons,
    required this.sources,
    required this.analyzedAt,
    this.notes = const [],
  });

  final String url;
  final Verdict verdict;
  final List<String> reasons;
  final List<String> sources;
  final DateTime analyzedAt;

  /// Non-blocking informational notes (e.g. "online check could not
  /// complete"). Distinct from [reasons], which carry real threat evidence.
  final List<String> notes;

  factory AnalysisResult.fromMap(Map<dynamic, dynamic> raw) {
    final reasons = (raw['reasons'] as List?)?.cast<String>() ?? const [];
    final sources = (raw['sources'] as List?)?.cast<String>() ?? const [];
    final notes = (raw['notes'] as List?)?.cast<String>() ?? const [];
    final analyzedAtMs =
        (raw['analyzedAt'] as num?)?.toInt() ?? DateTime.now().millisecondsSinceEpoch;
    return AnalysisResult(
      url: raw['url']?.toString() ?? '',
      verdict: VerdictParsing.parse(raw['verdict']?.toString()),
      reasons: reasons,
      sources: sources,
      analyzedAt: DateTime.fromMillisecondsSinceEpoch(analyzedAtMs),
      notes: notes,
    );
  }
}
