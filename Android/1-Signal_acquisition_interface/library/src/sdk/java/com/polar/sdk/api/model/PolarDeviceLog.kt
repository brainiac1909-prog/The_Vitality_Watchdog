// Copyright © 2026 Polar Electro Oy. All rights reserved.
package com.polar.sdk.api.model

/**
 * Represents a log file fetched from a Polar device.
 *
 * @property path the file path on the device (e.g. "/ERRORLOG.BPB")
 * @property data the raw file contents as a [ByteArray]
 */
data class PolarDeviceLog(
    val path: String,
    val data: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PolarDeviceLog) return false
        return path == other.path && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = path.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

