// Copyright © 2026 Polar Electro Oy. All rights reserved.
package com.polar.sdk.api.model

import com.polar.sdk.api.PolarBleApi

/**
 * Derived measurement methods supported by the device (methods 0–9).
 *
 * Any non-empty subset of the methods supported by a group may be requested simultaneously.
 * Each active method appends its output values to the derived sample in ascending id order.
 *
 * Methods 0–4 are applied component-wise (e.g. X, Y, Z for ACC).
 * Methods 5–9 reduce the D-dimensional source vector to a single unsigned scalar.
 */
enum class PolarDerivedMeasurementMethod(val id: Int, val csvLabel: String) {

    /** Method 0 — last source sample within the time window (downsampled). */
    DOWNSAMPLE(0, "DS"),

    /** Method 1 — component-wise minimum of source samples within the time window. */
    MIN(1, "MIN"),

    /** Method 2 — component-wise maximum of source samples within the time window. */
    MAX(2, "MAX"),

    /** Method 3 — component-wise average of source samples within the time window. */
    AVG(3, "AVG"),

    /**
     * Method 4 — component-wise standard deviation (unsigned-promoted type).
     * e.g. int16 source component → uint16 std output.
     */
    STD(4, "STD"),

    /**
     * Method 5 — Euclidean norm (magnitude) of downsampled source vector.
     * D→1 scalar, unsigned-promoted type.
     */
    NORM(5, "NORM"),

    /**
     * Method 6 — minimum Euclidean norm across source samples within the time window.
     * D→1 scalar, unsigned-promoted type.
     */
    MIN_OF_NORMS(6, "MIN_OF_NORMS"),

    /**
     * Method 7 — maximum Euclidean norm across source samples within the time window.
     * D→1 scalar, unsigned-promoted type.
     */
    MAX_OF_NORMS(7, "MAX_OF_NORMS"),

    /**
     * Method 8 — standard deviation of Euclidean norms within the time window.
     * D→1 scalar, unsigned-promoted type.
     */
    STD_OF_NORMS(8, "STD_OF_NORMS"),

    /**
     * Method 9 — Euclidean norm of component-wise standard deviations.
     * D→1 scalar, unsigned-promoted type.
     */
    NORM_OF_STDS(9, "NORM_OF_STDS");

    companion object {
        /** Returns the [PolarDerivedMeasurementMethod] whose wire [id] matches, or null if unknown. */
        fun fromId(id: Int): PolarDerivedMeasurementMethod? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Available settings for one Derived Measurement Settings Group returned by the device.
 *
 * Obtain an instance by calling [com.polar.sdk.api.PolarDerivedMeasurementApi.requestDerivedMeasurementSettingsGroup].
 *
 * @property groupId the Derived Measurement Settings Group ID advertised by the device
 * @property sourceTypes the source measurement data types supported by this group
 * @property sourceSampleRates the source measurement sample rates (Hz) available in this group
 * @property timeWindowOptions the derived measurement time window durations (ms) available in this group.
 *   The time window defines the output cadence: e.g. 1000 ms → 1 Hz derived output.
 * @property supportedMethods the derived measurement methods supported in this group.
 *   Any non-empty subset of [supportedMethods] may be selected when starting a recording.
 */
data class PolarDerivedMeasurementSettingsGroup(
    val groupId: Int,
    val sourceTypes: Set<PolarBleApi.PolarDeviceDataType>,
    val sourceSampleRates: Set<Int>,
    val timeWindowOptions: Set<Int>,
    val supportedMethods: Set<PolarDerivedMeasurementMethod>
)

/**
 * Selected settings for starting a derived offline recording.
 *
 * Any non-empty subset of the supported methods may be requested simultaneously.
 * Each selected method appends its result values to every derived sample.
 *
 * @property groupId the Derived Measurement Settings Group ID obtained from the device
 * @property sourceMeasurementType the source sensor data type to derive from (e.g. ACC)
 * @property sourceSampleRate the source sensor sample rate in Hz (e.g. 50)
 * @property timeWindowMs the time window / output cadence in milliseconds (e.g. 1000 for 1 Hz)
 * @property selectedMethods non-empty set of methods to apply; results are concatenated per sample
 */
data class PolarDerivedMeasurementSettings(
    val groupId: Int,
    val sourceMeasurementType: PolarBleApi.PolarDeviceDataType,
    val sourceSampleRate: Int,
    val timeWindowMs: Int,
    val selectedMethods: Set<PolarDerivedMeasurementMethod>
) {
    init {
        require(sourceSampleRate > 0) { "sourceSampleRate must be positive, got: $sourceSampleRate" }
        require(timeWindowMs > 0) { "timeWindowMs must be positive, got: $timeWindowMs" }
        require(selectedMethods.isNotEmpty()) { "selectedMethods must not be empty" }
    }
}

