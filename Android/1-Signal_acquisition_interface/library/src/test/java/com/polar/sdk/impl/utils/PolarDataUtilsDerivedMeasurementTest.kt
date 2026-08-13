package com.polar.sdk.impl.utils

import com.polar.androidcommunications.api.ble.model.gatt.client.pmd.PmdSetting
import com.polar.sdk.impl.utils.PolarDataUtils.mapPmdSettingsToPolarDerivedMeasurementSettingsGroup
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [PolarDataUtils.mapPmdSettingsToPolarDerivedMeasurementSettingsGroup].
 */
class PolarDataUtilsDerivedMeasurementTest {

    /**
     * Device response that DOES include the GROUP_ID field.
     * groupId should be read from the payload (1 in this case).
     */
    @Test
    fun `group id is taken from response payload when present`() {
        val bytes = byteArrayOf(
            // GROUP_ID = 1
            0x0C, 0x01, 0x01,
            // TIME_WINDOW = 1000 ms
            0x0B, 0x01, 0xE8.toByte(), 0x03, 0x00, 0x00,
            // METHOD, count=1, mode 2 (DETAILED_STATS)
            0x07, 0x01, 0x02,
            // SOURCE_TYPE = 2 (ACC)
            0x08, 0x01, 0x02,
            // SOURCE_RATE = 50 Hz
            0x09, 0x01, 0x32, 0x00
        )
        val pmdSetting = PmdSetting(bytes)
        val group = mapPmdSettingsToPolarDerivedMeasurementSettingsGroup(pmdSetting, requestedGroupId = 99)

        // Payload wins over requestedGroupId
        assertEquals(1, group.groupId)
    }

    /**
     * Device response that does NOT include the GROUP_ID field.
     * groupId must fall back to the requestedGroupId parameter (not 0).
     *
     * This is the exact scenario that caused ERROR_INVALID_DERIVED_MEASUREMENT_SETTINGS_GROUP:
     * the mapper was defaulting to 0 even when the actual valid group ID was e.g. 1.
     */
    @Test
    fun `group id falls back to requested id when not in response payload`() {
        val bytes = byteArrayOf(
            // No GROUP_ID field
            // TIME_WINDOW = 1000 ms
            0x0B, 0x01, 0xE8.toByte(), 0x03, 0x00, 0x00,
            // METHOD, count=3, modes 0 1 2
            0x07, 0x03, 0x00, 0x01, 0x02,
            // SOURCE_TYPE = 2 (ACC)
            0x08, 0x01, 0x02,
            // SOURCE_RATE = 50 Hz
            0x09, 0x01, 0x32, 0x00
        )
        val pmdSetting = PmdSetting(bytes)

        val group = mapPmdSettingsToPolarDerivedMeasurementSettingsGroup(pmdSetting, requestedGroupId = 1)

        // Must use requestedGroupId, NOT the old hardcoded fallback of 0
        assertEquals(
            "groupId must equal the requestedGroupId when not present in BLE response payload",
            1,
            group.groupId
        )
    }

    /**
     * Backward-compatibility: requestedGroupId defaults to 0 when caller doesn't pass it.
     * This covers the case where the GROUP_ID really is 0.
     */
    @Test
    fun `default requestedGroupId is 0 for backward compatibility`() {
        val bytes = byteArrayOf(
            // GROUP_ID = 0
            0x0C, 0x01, 0x00,
            0x0B, 0x01, 0xE8.toByte(), 0x03, 0x00, 0x00,
            0x07, 0x01, 0x00,
            0x08, 0x01, 0x02,
            0x09, 0x01, 0x32, 0x00
        )
        val pmdSetting = PmdSetting(bytes)
        val group = mapPmdSettingsToPolarDerivedMeasurementSettingsGroup(pmdSetting)
        assertEquals(0, group.groupId)
    }
}

