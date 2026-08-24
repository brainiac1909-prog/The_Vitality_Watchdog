package com.example.polar_sensor_app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApi.PolarBleSdkFeature
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleApiDefaultImpl
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.androidcommunications.api.ble.model.DisInfo
import com.polar.sdk.api.model.PolarHealthThermometerData
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : FlutterActivity() {
    companion object {
        private const val TAG = "PolarNative"
        private const val COMMAND_CHANNEL = "com.example.polar/commands"
        private const val HR_STREAM_CHANNEL = "com.example.polar/hr_stream"
        private const val DEVICE_SCAN_CHANNEL = "com.example.polar/device_scan"
        private const val CONNECTION_CHANNEL = "com.example.polar/connection"
        private const val BLUETOOTH_PERMISSION_REQUEST = 1001
    }

    private lateinit var polarApi: PolarBleApi
    private var lastDeviceId: String? = null
    private var hrEventSink: EventChannel.EventSink? = null
    private var hrStreamJob: Job? = null
    private var scanJob: Job? = null
    private var hrStreamRequested = false
    private var deviceEventSink: EventChannel.EventSink? = null
    private var connectionEventSink: EventChannel.EventSink? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        polarApi = PolarBleApiDefaultImpl.defaultImplementation(
            applicationContext,
            setOf(
                PolarBleApi.PolarBleSdkFeature.FEATURE_HR,
                PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING
            )
        )

        polarApi.setApiCallback(object : PolarBleApiCallback() {
            override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "Connected to ${polarDeviceInfo.deviceId}")
                lastDeviceId = polarDeviceInfo.deviceId
                connectionEventSink?.success(mapOf(
                    "state" to "connected",
                    "deviceId" to polarDeviceInfo.deviceId,
                ))
            }

           override fun disInformationReceived(identifier: String, uuid: UUID,  value: String) {
                 Log.d(TAG, "Device information received: $identifier")
            }

            override fun disInformationReceived(identifier: String,disInfo:DisInfo) {
                 Log.d(TAG, "Device information received: $identifier")
            }

            override fun htsNotificationReceived(identifier:String, data:PolarHealthThermometerData){
                Log.d(TAG, "Health thermometer data received: $data")
            }


            override fun deviceConnecting(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "Connecting to ${polarDeviceInfo.deviceId}")
                connectionEventSink?.success(mapOf(
                    "state" to "connecting",
                    "deviceId" to polarDeviceInfo.deviceId,
                ))
            }

            override fun deviceDisconnected(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "Disconnected from ${polarDeviceInfo.deviceId}")
                if (polarDeviceInfo.deviceId == lastDeviceId) {
                    hrStreamJob?.cancel()
                    connectionEventSink?.success(mapOf(
                        "state" to "disconnected",
                        "deviceId" to polarDeviceInfo.deviceId,
                    ))
                }
            }

            override fun bleSdkFeatureReady(
                identifier: String,
                feature: PolarBleApi.PolarBleSdkFeature
            ) {
                Log.d(TAG, "Feature $feature ready for $identifier")
                if (feature == PolarBleSdkFeature.FEATURE_HR &&
                    identifier == lastDeviceId &&
                    hrStreamRequested
                ) {
                    startHrStreaming(identifier)
                }
            }
        })

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, COMMAND_CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "scan" -> {
                        if (!hasBluetoothPermissions()) {
                            requestBluetoothPermissions()
                        } else {
                            startDeviceScan()
                        }
                        result.success(true)
                    }
                    "stopScan" -> {
                        scanJob?.cancel()
                        result.success(true)
                    }
                    "connect" -> {
                        
                        val deviceId = call.argument<String>("deviceId")
                        if (deviceId.isNullOrBlank()) {
                            result.error("INVALID_ID", "Device ID cannot be empty", null)
                            return@setMethodCallHandler
                        }

                        lastDeviceId = deviceId
                        hrStreamRequested = false
                        Log.d(TAG, "Requesting connection to $deviceId")
                        polarApi.connectToDevice(deviceId)
                        result.success(true)
                    }

                    "startHrStream" -> {
                        val deviceId = lastDeviceId
                        if (deviceId.isNullOrBlank()) {
                            result.error("NO_DEVICE", "No connected device", null)
                            return@setMethodCallHandler
                        }

                        hrStreamRequested = true
                        if (polarApi.isFeatureReady(
                                deviceId,
                                PolarBleSdkFeature.FEATURE_HR
                            )
                        ) {
                            Log.d(TAG, "HR feature is ready; starting Verity Sense HR stream")
                            startHrStreaming(deviceId)
                        } else {
                            Log.d(
                                TAG,
                                "Waiting for HR readiness. If this is SDK mode, " +
                                    "Verity Sense HR streaming is unavailable"
                            )
                        }
                        result.success(true)
                    }

                    "disconnect" -> {
                        val deviceId = lastDeviceId
                        if (deviceId != null) {
                            hrStreamJob?.cancel()
                            hrStreamRequested = false
                            polarApi.disconnectFromDevice(deviceId)
                            lastDeviceId = null
                        }
                        result.success(true)
                    }

                    else -> result.notImplemented()
                }
            }

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, HR_STREAM_CHANNEL)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    hrEventSink = events
                }

                override fun onCancel(arguments: Any?) {
                    hrStreamJob?.cancel()
                    hrEventSink = null
                }
            })

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, DEVICE_SCAN_CHANNEL)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    deviceEventSink = events
                }

                override fun onCancel(arguments: Any?) {
                    scanJob?.cancel()
                    deviceEventSink = null
                }
            })

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, CONNECTION_CHANNEL)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    connectionEventSink = events
                }

                override fun onCancel(arguments: Any?) {
                    connectionEventSink = null
                }
            })
    }

    private fun hasBluetoothPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestBluetoothPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        requestPermissions(permissions, BLUETOOTH_PERMISSION_REQUEST)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == BLUETOOTH_PERMISSION_REQUEST && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startDeviceScan()
        } else if (requestCode == BLUETOOTH_PERMISSION_REQUEST) {
            deviceEventSink?.error("PERMISSION_DENIED", "Bluetooth permission is required to find sensors", null)
        }
    }

    private fun startDeviceScan() {
        scanJob?.cancel()
        scanJob = lifecycleScope.launch {
            polarApi.searchForDevice("Polar")
                .catch { error ->
                    Log.e(TAG, "Device scan failed", error)
                    deviceEventSink?.error("SCAN_ERROR", error.message, null)
                }
                .collect { device ->
                    deviceEventSink?.success(mapOf(
                        "deviceId" to device.deviceId,
                        "name" to device.name,
                        "address" to device.address,
                        "rssi" to device.rssi,
                        "isConnectable" to device.isConnectable,
                    ))
                }
        }
    }

    private fun startHrStreaming(deviceId: String) {
        hrStreamJob?.cancel()
        hrStreamJob = lifecycleScope.launch {
            polarApi.startHrStreaming(deviceId)
                .catch { error ->
                    Log.e(TAG, "HR stream failed", error)
                    hrEventSink?.error("HR_STREAM_ERROR", error.message, null)
                }
                .collect { hrData ->
                    Log.d(TAG, "HR packet: samples=${hrData.samples}")

                    val sample = hrData.samples.lastOrNull()
                    if (sample == null) {
                        Log.d(TAG, "HR packet contained no samples; no BPM sent to Flutter")
                        return@collect
                    }

                    Log.d(
                        TAG,
                        "HR=${sample.hr}, corrected=${sample.correctedHr}, " +
                            "contact=${sample.contactStatus}"
                    )

                    hrEventSink?.success(sample.hr)
                }
        }
    }

    override fun onDestroy() {
        scanJob?.cancel()
        hrStreamJob?.cancel()
        polarApi.shutDown()
        super.onDestroy()
    }
}