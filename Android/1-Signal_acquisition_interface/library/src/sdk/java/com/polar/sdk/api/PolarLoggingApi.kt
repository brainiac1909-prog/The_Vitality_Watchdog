// Copyright © 2026 Polar Electro Oy. All rights reserved.
package com.polar.sdk.api

import com.polar.sdk.api.model.LogConfig
import com.polar.sdk.api.model.PolarDeviceLog

/**
 * Polar logging API.
 *
 * Provides access to diagnostic and trace log files stored on a Polar device,
 * as well as the device sensor data log configuration.
 *
 * Requires feature [PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_FILE_TRANSFER]
 */
interface PolarLoggingApi {

    /**
     * Export all available diagnostic and trace log files from the device.
     *
     * The following files are attempted in order. Files that do not exist on the
     * device are silently omitted from the result:
     *
     * - `/ERRORLOG.BPB`   – Primary error log
     * - `/ERRORLO2.BPB`   – Secondary error log
     * - `/SYSLOG.TXT`     – System log
     * - `/TRC1.BIN`, `/TRC2.BIN`, … – Device telemetry data.
     *   Files are fetched sequentially starting from index 1 until a file is not found.
     * - `/DBGTRC1.BIN`, `/DBGTRC2.BIN`, … – Device debug trace data.
     *   Files are fetched sequentially starting from index 1 until a file is not found.
     *
     * Requires feature [PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_FILE_TRANSFER]
     *
     * @param identifier Polar device ID or BT address
     * @return List of [PolarDeviceLog] entries for all log files that exist on the device.
     * @throws Exception if a non-recoverable error occurs during the transfer
     */
    suspend fun exportDeviceLogs(identifier: String): List<PolarDeviceLog>

    /**
     * Get [LogConfig] from device. Requires feature [PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_DEVICE_CONTROL]
     *
     * @param identifier Polar device ID or BT address
     * @return [LogConfig] or error
     */
    suspend fun getLogConfig(identifier: String): LogConfig

    /**
     * Set [LogConfig] for device. Requires feature [PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_DEVICE_CONTROL]
     *
     * @param identifier Polar device ID or BT address
     * @param logConfig new [LogConfig]
     * @return Success or error
     */
    suspend fun setLogConfig(identifier: String, logConfig: LogConfig)
}
