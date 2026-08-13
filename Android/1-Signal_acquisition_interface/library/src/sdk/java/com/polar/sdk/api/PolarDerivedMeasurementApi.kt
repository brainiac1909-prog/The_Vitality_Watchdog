// Copyright © 2026 Polar Electro Oy. All rights reserved.
package com.polar.sdk.api

import com.polar.sdk.api.PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_OFFLINE_RECORDING
import com.polar.sdk.api.PolarBleApi.PolarDeviceDataType
import com.polar.sdk.api.model.PolarDerivedMeasurementSettings
import com.polar.sdk.api.model.PolarDerivedMeasurementSettingsGroup
import com.polar.sdk.api.model.PolarRecordingSecret

/**
 * Derived measurement API.
 *
 * Derived measurements compute statistical summaries (e.g. min, max, std) over a
 * configurable time window of raw sensor data and store one result per window.
 */
interface PolarDerivedMeasurementApi {

    /**
     * Query which Derived Measurement Settings Group IDs are supported for the given source type.
     *
     * Call this before [requestDerivedMeasurementSettingsGroup] to discover which group IDs
     * the device advertises for a source type (e.g. ACC).
     *
     * @param identifier Polar device id found printed on the sensor/device or bt address
     * @param sourceType the source measurement type to query (typically [PolarDeviceDataType.ACC])
     * @return set of Derived Measurement Settings Group IDs advertised by the device for this source type
     */
    suspend fun requestDerivedMeasurementGroupIds(
        identifier: String,
        sourceType: PolarDeviceDataType
    ): Set<Int>

    /**
     * Request the full contents of one Derived Measurement Settings Group from the device.
     *
     * Use this before starting a derived recording to discover which source types, sample rates,
     * time windows, and computation methods are supported by a group.
     *
     * Typical flow:
     * 1. Call [requestDerivedMeasurementGroupIds] for the source type to get available group IDs.
     * 2. Call this function for each group ID of interest.
     * 3. Present [PolarDerivedMeasurementSettingsGroup.supportedMethods] to the user for selection.
     * 4. Build [PolarDerivedMeasurementSettings] from the user's choices.
     * 5. Call [startDerivedOfflineRecording].
     *
     * @param identifier Polar device id found printed on the sensor/device or bt address
     * @param groupId the Derived Measurement Settings Group ID to query
     * @return [PolarDerivedMeasurementSettingsGroup] describing the available options for this group
     */
    suspend fun requestDerivedMeasurementSettingsGroup(
        identifier: String,
        groupId: Int
    ): PolarDerivedMeasurementSettingsGroup

    /**
     * Start a derived offline recording on the device.
     *
     * The device computes the selected derived methods over each
     * [PolarDerivedMeasurementSettings.timeWindowMs] time window of source data and stores
     * one derived sample per window. Fetch results with [PolarOfflineRecordingApi.getOfflineRecord].
     *
     * @param identifier Polar device id found printed on the sensor/device or bt address
     * @param settings the derived measurement settings including the chosen preset mode
     * @param secret optional encryption secret; if provided, the recording is encrypted on device
     */
    suspend fun startDerivedOfflineRecording(
        identifier: String,
        settings: PolarDerivedMeasurementSettings,
        secret: PolarRecordingSecret? = null
    )

    /**
     * Stop the active derived offline recording on the device.
     *
     * @param identifier Polar device id found printed on the sensor/device or bt address
     */
    suspend fun stopDerivedOfflineRecording(identifier: String)
}

