import 'dart:math';

import '../datamodels/sensor_data.dart';

/// Time-domain PRV (pulse rate variability) features computed from a window
/// of [PpiSample]s. These are the kind of features a decision-tree stress
/// classifier typically consumes; this file intentionally stops at feature
/// extraction and does not implement the classifier itself.
///
/// Per documentation/PPIData.md, PPI "cannot be measured accurately during
/// activity - it should only be used at complete rest". Features computed
/// over a window with movement (blocker-bit) contamination are noise, not
/// signal - check [PrvFeatures.discardedForMovement] before trusting a
/// result.
class PrvFeatures {
  const PrvFeatures({
    required this.sampleCount,
    required this.discardedForMovement,
    required this.meanPpiMs,
    required this.sdnnMs,
    required this.rmssdMs,
    required this.pnn50Percent,
    required this.meanHr,
  });

  /// Number of samples the features below were actually computed from
  /// (blocker-bit samples excluded).
  final int sampleCount;

  /// Number of samples in the input window that were dropped because
  /// blockerBit was set (movement detected). A high count relative to
  /// [sampleCount] means the person was moving during this window - per
  /// documentation/PPIData.md, PPI isn't accurate under those conditions, so
  /// treat the features as unreliable rather than feeding them to the stress
  /// model as-is.
  final int discardedForMovement;

  final double meanPpiMs;

  /// Standard deviation of NN (pp) intervals - overall variability.
  final double sdnnMs;

  /// Root mean square of successive differences - short-term / vagally
  /// mediated variability. Typically the feature most sensitive to acute
  /// stress.
  final double rmssdMs;

  /// Percentage of successive NN interval differences greater than 50ms.
  final double pnn50Percent;

  final double meanHr;

  /// Coarse reliability check: true when at least 80% of the window survived
  /// the movement-blocker filter. Tune the threshold to what your model was
  /// trained on.
  bool get isReliable =>
      discardedForMovement / (sampleCount + discardedForMovement) < 0.2;

  @override
  String toString() =>
      'PrvFeatures(n=$sampleCount, discarded=$discardedForMovement, '
      'meanPPI=${meanPpiMs.toStringAsFixed(1)}ms, '
      'SDNN=${sdnnMs.toStringAsFixed(1)}ms, RMSSD=${rmssdMs.toStringAsFixed(1)}ms, '
      'pNN50=${pnn50Percent.toStringAsFixed(1)}%, meanHR=${meanHr.toStringAsFixed(1)})';
}

class SigProcessor {
  /// Drops samples flagged by the sensor as unreliable (movement blocker).
  /// Skin-contact flags are deliberately not used as a filter here since
  /// they're known to be unreliable on optical sensors like Verity Sense.
  static List<PpiSample> filterValid(List<PpiSample> samples) {
    return samples.where((s) => s.isLikelyValid).toList(growable: false);
  }

  /// Computes standard time-domain PRV features over [samples], which should
  /// already be time-ordered (the caller's rolling buffer should append in
  /// arrival order). Returns null if there are fewer than 2 usable samples,
  /// since successive differences require at least that many.
  static PrvFeatures? computeTimeDomainFeatures(List<PpiSample> samples) {
    final valid = filterValid(samples);
    if (valid.length < 2) return null;

    final discarded = samples.length - valid.length;

    final ppis = valid.map((s) => s.ppiMs.toDouble()).toList();
    final meanPpi = ppis.reduce((a, b) => a + b) / ppis.length;

    final variance =
        ppis.map((v) => pow(v - meanPpi, 2)).reduce((a, b) => a + b) /
            (ppis.length - 1);
    final sdnn = sqrt(variance);

    final diffs = <double>[];
    for (var i = 1; i < ppis.length; i++) {
      diffs.add(ppis[i] - ppis[i - 1]);
    }
    final squaredDiffSum = diffs.map((d) => d * d).reduce((a, b) => a + b);
    final rmssd = sqrt(squaredDiffSum / diffs.length);

    final nn50 = diffs.where((d) => d.abs() > 50).length;
    final pnn50 = (nn50 / diffs.length) * 100;

    final meanHr = valid.map((s) => s.hr).reduce((a, b) => a + b) / valid.length;

    return PrvFeatures(
      sampleCount: valid.length,
      discardedForMovement: discarded,
      meanPpiMs: meanPpi,
      sdnnMs: sdnn,
      rmssdMs: rmssd,
      pnn50Percent: pnn50,
      meanHr: meanHr,
    );
  }
}
