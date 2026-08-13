// Copyright © 2022 Polar Electro Oy. All rights reserved.
package com.polar.sdk.api.model

import java.time.LocalDateTime

/**
 * Polar Offline recording data
 *
 * @property startTime the time recording was started in UTC time
 */
sealed class PolarOfflineRecordingData(val startTime: LocalDateTime, val settings: PolarSensorSetting?) {
    /**
     * Accelerometer offline recording data
     *
     * @property data acc data
     * @property startTime the time recording was started in UTC time
     * @property settings the settings used while recording
     */
    class AccOfflineRecording(
        val data: PolarAccelerometerData,
        startTime: LocalDateTime,
        settings: PolarSensorSetting
    ) : PolarOfflineRecordingData(startTime, settings) {
        internal fun appendAccData(
            existingRecording: AccOfflineRecording,
            newData: PolarAccelerometerData,
            settings: PolarSensorSetting
        ): AccOfflineRecording {
            val mergedSamples = mutableListOf<PolarAccelerometerData.PolarAccelerometerDataSample>()
            mergedSamples.addAll(existingRecording.data.samples)
            mergedSamples.addAll(newData.samples)
            return AccOfflineRecording(
                PolarAccelerometerData(
                    mergedSamples
                ),
                startTime,
                settings
            )
        }
    }

    /**
     * Gyroscope Offline recording data
     *
     * @property data gyro data
     * @property startTime the time recording was started in UTC time
     * @property settings the settings used while recording
     */
    class GyroOfflineRecording(
        val data: PolarGyroData,
        startTime: LocalDateTime,
        settings: PolarSensorSetting
    ) : PolarOfflineRecordingData(startTime, settings) {
        internal fun appendGyroData(
            existingRecording: GyroOfflineRecording,
            newData: PolarGyroData,
            settings: PolarSensorSetting
        ): GyroOfflineRecording {
            val mergedSamples = mutableListOf<PolarGyroData.PolarGyroDataSample>()
            mergedSamples.addAll(existingRecording.data.samples)
            mergedSamples.addAll(newData.samples)
            return GyroOfflineRecording(
                PolarGyroData(
                    mergedSamples
                ),
                startTime,
                settings
            )
        }
    }

    /**
     * Magnetometer offline recording data
     *
     * @property data magnetometer data
     * @property startTime the time recording was started in UTC time
     * @property settings the settings used while recording
     */
    class MagOfflineRecording(
        val data: PolarMagnetometerData,
        startTime: LocalDateTime,
        settings: PolarSensorSetting?
    ) : PolarOfflineRecordingData(startTime, settings) {
        internal fun appendMagData(
            existingRecording: MagOfflineRecording,
            newData: PolarMagnetometerData
        ): MagOfflineRecording {
            val mergedSamples = mutableListOf<PolarMagnetometerData.PolarMagnetometerDataSample>()
            mergedSamples.addAll(existingRecording.data.samples)
            mergedSamples.addAll(newData.samples)
            return MagOfflineRecording(
                PolarMagnetometerData(
                    mergedSamples
                ),
                startTime,
                settings
            )
        }
    }

    /**
     * PPG (Photoplethysmography) offline recording data
     *
     * @property data ppg data
     * @property startTime the time recording was started in UTC time
     * @property settings the settings used while recording
     */
    class PpgOfflineRecording(
        val data: PolarPpgData,
        startTime: LocalDateTime,
        settings: PolarSensorSetting?
    ) : PolarOfflineRecordingData(startTime, settings) {
        internal fun appendPpgData(
            existingRecording: PpgOfflineRecording,
            newData: PolarPpgData
        ): PpgOfflineRecording {
            val mergedSamples = mutableListOf<PolarPpgData.PolarPpgSample>()
            mergedSamples.addAll(existingRecording.data.samples)
            mergedSamples.addAll(newData.samples)
            return PpgOfflineRecording(
                PolarPpgData(
                    mergedSamples,
                    newData.type
                ),
                startTime,
                settings
            )
        }
    }

    /**
     * PPI (Peak-to-peak interval) offline recording data
     *
     * @property data ppi data
     * @property startTime the time recording was started in UTC time
     */
    class PpiOfflineRecording(val data: PolarPpiData, startTime: LocalDateTime) :
        PolarOfflineRecordingData(startTime, null) {
        internal fun appendPpiData(
            existingRecording: PpiOfflineRecording,
            newData: PolarPpiData
        ): PpiOfflineRecording {
            val mergedSamples = mutableListOf<PolarPpiData.PolarPpiSample>()
            mergedSamples.addAll(existingRecording.data.samples)
            mergedSamples.addAll(newData.samples)
            return PpiOfflineRecording(
                PolarPpiData(mergedSamples),
                startTime
            )
        }
    }

    /**
     * Heart rate offline recording data
     *
     * @property data heart rate data
     * @property startTime the time recording was started in UTC time
     */
    class HrOfflineRecording(val data: PolarHrData, startTime: LocalDateTime) :
        PolarOfflineRecordingData(startTime, null) {
        internal fun appendHrData(
            existingRecording: HrOfflineRecording,
            newData: PolarHrData
        ): HrOfflineRecording {
            val mergedSamples = mutableListOf<PolarHrData.PolarHrSample>()
            mergedSamples.addAll(existingRecording.data.samples)
            mergedSamples.addAll(newData.samples)
            return HrOfflineRecording(
                PolarHrData(mergedSamples),
                startTime
            )
        }
    }

    /**
     * Temperature offline recording data
     *
     * @property data temperature data
     * @property startTime the time recording was started in UTC time
     */
    class TemperatureOfflineRecording(val data: PolarTemperatureData, startTime: LocalDateTime) :
        PolarOfflineRecordingData(startTime, null) {
        internal fun appendTemperatureData(
            existingTemperatureData: TemperatureOfflineRecording,
            newData: PolarTemperatureData
        ): TemperatureOfflineRecording {
            val mergedSamples = mutableListOf<PolarTemperatureData.PolarTemperatureDataSample>()
            mergedSamples.addAll(existingTemperatureData.data.samples)
            mergedSamples.addAll(newData.samples)
            return TemperatureOfflineRecording(
                PolarTemperatureData(mergedSamples),
                startTime
            )
        }
    }

    /**
     * Skin temperature offline recording data
     *
     * @property data skin temperature data
     * @property startTime the time recording was started in UTC time
     */
    class SkinTemperatureOfflineRecording(val data: PolarTemperatureData, startTime: LocalDateTime) :
        PolarOfflineRecordingData(startTime, null) {
        internal fun appendSkinTemperatureData(
            existingSkinTemperatureData: SkinTemperatureOfflineRecording,
            newData: PolarTemperatureData
        ): SkinTemperatureOfflineRecording {
            val mergedSamples = mutableListOf<PolarTemperatureData. PolarTemperatureDataSample>()
            mergedSamples.addAll(existingSkinTemperatureData. data.samples)
            mergedSamples.addAll(newData.samples)
            return SkinTemperatureOfflineRecording(
                PolarTemperatureData(mergedSamples),
                startTime
            )
        }
    }

    /**
     * Derived ACC offline recording data
     *
     * @property data derived ACC data
     * @property startTime the time recording was started in UTC time
     * @property settings the settings used while recording
     */
    class DerivedAccOfflineRecording(
        val data: PolarDerivedAccData,
        startTime: LocalDateTime,
        settings: PolarSensorSetting?
    ) : PolarOfflineRecordingData(startTime, settings) {
        internal fun appendDerivedAccData(
            existingRecording: DerivedAccOfflineRecording,
            newData: PolarDerivedAccData,
            settings: PolarSensorSetting?
        ): DerivedAccOfflineRecording {
            val mergedSamples = mutableListOf<PolarDerivedSample>()
            mergedSamples.addAll(existingRecording.data.samples)
            mergedSamples.addAll(newData.samples)
            return DerivedAccOfflineRecording(
                PolarDerivedAccData(
                    mergedSamples
                ),
                startTime,
                settings
            )
        }
    }
}

/**
 * One derived-measurement sample produced per time window (e.g. 1 sample/s with 1000 ms window).
 *
 * [methodValues] maps each active [PolarDerivedMeasurementMethod] to its output values:
 * - Component methods (e.g. DOWNSAMPLE, MIN, MAX, AVG, STD for a 3-axis source) → `[x, y, z]`
 * - Scalar methods (NORM, MIN_OF_NORMS, MAX_OF_NORMS, STD_OF_NORMS, NORM_OF_STDS) → `[v]`
 *
 * All raw integer values must be multiplied by the recording's conversion factor to obtain
 * physical values (same factor as the source recording).
 *
 * @property timeStamp sample timestamp in nanoseconds, corresponds to end of the time window
 * @property activeMethods the set of methods active for this recording
 * @property methodValues output values per active method; absent key means method was not active
 */
data class PolarDerivedSample(
    val timeStamp: Long,
    val activeMethods: Set<PolarDerivedMeasurementMethod>,
    val methodValues: Map<PolarDerivedMeasurementMethod, List<Int>>
)

data class PolarDerivedAccData(val samples: List<PolarDerivedSample>)

/**
 * Result wrapper for offline recording fetch operations with progress.
 */
sealed class PolarOfflineRecordingResult {
    /**
     * Progress update during download.
     *
     * @property bytesDownloaded Number of bytes downloaded so far
     * @property totalBytes Total size of the recording in bytes
     * @property progressPercent Progress as percentage (0-100)
     */
    data class Progress(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val progressPercent: Int
    ) : PolarOfflineRecordingResult()

    /**
     * Download completed successfully.
     *
     * @property data The downloaded offline recording data
     */
    data class Complete(
        val data: PolarOfflineRecordingData
    ) : PolarOfflineRecordingResult()
}
