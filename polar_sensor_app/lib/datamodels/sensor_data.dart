/// Data models for samples streamed from the connected Polar sensor.
///
/// These are the "usable variables" the rest of the app (PRV feature
/// extraction, the stress-classification model, on-device analyses) should
/// consume, rather than talking to platform channels directly.

class HrSample {
  const HrSample({
    required this.hr,
    required this.timestampMs,
  });

  /// Heart rate in beats per minute.
  final int hr;

  /// Wall-clock time (ms since epoch) the sample was received on-device.
  final int timestampMs;

  factory HrSample.fromValue(int hr) {
    return HrSample(
      hr: hr,
      timestampMs: DateTime.now().millisecondsSinceEpoch,
    );
  }
}

/// A single pulse-to-pulse interval (PPI) sample.
///
/// PPI is the optical-sensor analogue of an RR interval from an ECG strap.
/// A sequence of these is the raw material for PRV (pulse rate variability)
/// features such as SDNN / RMSSD / pNN50, which in turn feed the stress
/// classifier.
class PpiSample {
  const PpiSample({
    required this.timestampMs,
    required this.hr,
    required this.ppiMs,
    required this.errorEstimateMs,
    required this.blockerBit,
    required this.skinContactStatus,
    required this.skinContactSupported,
  });

  /// Wall-clock time (ms since epoch) of the pulse, as reported by the
  /// sensor itself (converted from the SDK's native timestamp on the native
  /// side).
  final int timestampMs;

  /// Heart rate in bpm associated with this sample.
  final int hr;

  /// Pulse-to-pulse interval in milliseconds (i.e. the "RR interval").
  final int ppiMs;

  /// SDK-reported error estimate for [ppiMs], in milliseconds.
  final int errorEstimateMs;

  /// True if this PPI measurement is invalid due to acceleration/movement
  /// (or other reason). Per documentation/PPIData.md, samples with the
  /// blocker bit set should be discarded before computing PRV features.
  final bool blockerBit;

  /// False if the device detects poor or no skin contact (only meaningful
  /// when [skinContactSupported] is true). documentation/PPIData.md warns
  /// this is unreliable specifically on Verity Sense / OH1, so it's not used
  /// to filter samples here - see [isLikelyValid].
  final bool skinContactStatus;

  /// True if the sensor-contact feature is supported at all on this device.
  final bool skinContactSupported;

  /// Whether this sample looks usable for PRV feature extraction.
  bool get isLikelyValid => !blockerBit;

  factory PpiSample.fromMap(Map<Object?, Object?> map) {
    return PpiSample(
      timestampMs: (map['timestampMs'] as num).toInt(),
      hr: (map['hr'] as num).toInt(),
      ppiMs: (map['ppiMs'] as num).toInt(),
      errorEstimateMs: (map['errorEstimateMs'] as num).toInt(),
      blockerBit: map['blockerBit'] as bool,
      skinContactStatus: map['skinContactStatus'] as bool,
      skinContactSupported: map['skinContactSupported'] as bool,
    );
  }
}
