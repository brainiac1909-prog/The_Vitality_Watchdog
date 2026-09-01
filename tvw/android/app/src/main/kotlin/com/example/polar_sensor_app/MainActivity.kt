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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

class MainActivity : FlutterActivity() {
    companion object {
        private const val TAG = "PolarNative"
        private const val COMMAND_CHANNEL = "com.example.polar/commands"
        private const val HR_STREAM_CHANNEL = "com.example.polar/hr_stream"
        private const val PPI_STREAM_CHANNEL = "com.example.polar/ppi_stream"
        private const val DEVICE_SCAN_CHANNEL = "com.example.polar/device_scan"
        private const val CONNECTION_CHANNEL = "com.example.polar/connection"
        private const val BLUETOOTH_PERMISSION_REQUEST = 1001

        // How long the PPI stream is willing to wait for the HR stream's PMD
        // handshake to complete before starting its own, if a HR stream is
        // currently being (re)established on the same device.
        private const val HR_HANDSHAKE_TIMEOUT_MS = 5_000L

        // Polar's SDK-wide sample timestamps (PolarPpiSample.timeStamp, and the
        // equivalent fields on ACC/ECG/etc.) are nanoseconds since 2000-01-01T00:00:00Z,
        // not the Unix epoch. Confirm this against documentation/TimeSystemExplained.md
        // in your SDK checkout - this constant assumes the SDK-wide convention holds
        // for PPI too, which wasn't explicitly stated in PolarPpiData.kt's doc comment.
        private const val POLAR_EPOCH_OFFSET_MS = 946_684_800_000L // 2000-01-01T00:00:00Z in Unix ms
    }

    private lateinit var polarApi: PolarBleApi
    private var lastDeviceId: String? = null

    private var hrEventSink: EventChannel.EventSink? = null
    private var hrStreamJob: Job? = null
    private var hrStreamRequested = false

    // Completes once the HR flow has actually started delivering data (i.e.
    // the PMD/HR-service handshake succeeded). Used to serialise the PPI
    // stream's own handshake so the two never race each other on the BLE link.
    private var hrStreamStarted: CompletableDeferred<Unit>? = null

    private var ppiEventSink: EventChannel.EventSink? = null
    private var ppiStreamJob: Job? = null
    private var ppiStreamRequested = false
    private var onlineStreamingReady = false

    private var scanJob: Job? = null
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
                    ppiStreamJob?.cancel()
                    hrStreamStarted = null
                    onlineStreamingReady = false
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
                when (feature) {
                    PolarBleSdkFeature.FEATURE_HR -> {
                        if (identifier == lastDeviceId && hrStreamRequested) {
                            startHrStreaming(identifier)
                        }
                    }

                    PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING -> {
                        // PPI is delivered via the PMD online-streaming service, not the
                        // standard BLE HR service, so it waits on this flag rather than
                        // FEATURE_HR.
                        onlineStreamingReady = true
                        if (identifier == lastDeviceId && ppiStreamRequested) {
                            startPpiStreaming(identifier)
                        }
                    }

                    else -> Unit
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
                        ppiStreamRequested = false
                        onlineStreamingReady = false
                        hrStreamStarted = null
                        hrStreamJob?.cancel()
                        ppiStreamJob?.cancel()
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

                    "startPpiStream" -> {
                        val deviceId = lastDeviceId
                        if (deviceId.isNullOrBlank()) {
                            result.error("NO_DEVICE", "No connected device", null)
                            return@setMethodCallHandler
                        }

                        ppiStreamRequested = true
                        if (onlineStreamingReady &&
                            polarApi.isFeatureReady(
                                deviceId,
                                PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING
                            )
                        ) {
                            Log.d(TAG, "Online streaming feature is ready; starting PPI stream")
                            startPpiStreaming(deviceId)
                        } else {
                            Log.d(TAG, "Waiting for online-streaming readiness before starting PPI stream")
                        }
                        result.success(true)
                    }

                    "stopPpiStream" -> {
                        ppiStreamJob?.cancel()
                        ppiStreamRequested = false
                        result.success(true)
                    }

                    "disconnect" -> {
                        val deviceId = lastDeviceId
                        if (deviceId != null) {
                            hrStreamJob?.cancel()
                            ppiStreamJob?.cancel()
                            hrStreamRequested = false
                            ppiStreamRequested = false
                            onlineStreamingReady = false
                            hrStreamStarted = null
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

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, PPI_STREAM_CHANNEL)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    ppiEventSink = events
                }

                override fun onCancel(arguments: Any?) {
                    ppiStreamJob?.cancel()
                    ppiEventSink = null
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

    /**
     * Starts the HR stream and records when the first sample actually arrives,
     * via [hrStreamStarted]. [startPpiStreaming] waits on this before doing its
     * own PMD handshake so the two setups are never issued concurrently
     * (see AGENTS.md: "Do not issue multiple asynchronous SDK calls to the same
     * device in parallel... Serialise device operations.").
     */
    private fun startHrStreaming(deviceId: String) {
        hrStreamJob?.cancel()

        val started = CompletableDeferred<Unit>()
        hrStreamStarted = started

        hrStreamJob = lifecycleScope.launch {
            polarApi.startHrStreaming(deviceId)
                .catch { error ->
                    Log.e(TAG, "HR stream failed", error)
                    hrEventSink?.error("HR_STREAM_ERROR", error.message, null)
                    // Don't leave PPI waiting forever on a HR stream that failed to start.
                    if (!started.isCompleted) started.complete(Unit)
                }
                .collect { hrData ->
                    if (!started.isCompleted) started.complete(Unit)

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

    /**
     * Converts a Polar SDK sample timestamp (nanoseconds since the SDK's
     * 2000-01-01T00:00:00Z epoch, per documentation/TimeSystemExplained.md) to
     * Unix epoch milliseconds.
     */
    private fun polarTimestampToUnixMs(polarTimestampNs: ULong): Long {
        return POLAR_EPOCH_OFFSET_MS + (polarTimestampNs / 1_000_000uL).toLong()
    }

    /**
     * Starts the PPI (pulse-to-pulse interval) stream. This is the raw
     * material for PRV metrics (SDNN/RMSSD/pNN50 etc.) that feed the stress
     * model.
     *
     * Per documentation/TimeSystemExplained.md, PPI is one of the data types
     * where a precise per-sample device time "cannot be defined" - the SDK
     * reports it as zero or missing rather than a real timestamp. So: use the
     * device timestamp when it's non-zero, otherwise fall back to
     * reconstructing an estimate by walking backwards from the packet's
     * arrival time through its cumulative ppi (ms) durations.
     */
    private fun startPpiStreaming(deviceId: String) {
        ppiStreamJob?.cancel()
        ppiStreamJob = lifecycleScope.launch {
            // Serialise with any in-flight HR stream setup on the same device.
            val hrStarting = hrStreamJob?.isActive == true
            val started = hrStreamStarted
            if (hrStarting && started != null && !started.isCompleted) {
                Log.d(TAG, "Waiting for HR stream handshake before starting PPI stream")
                withTimeoutOrNull(HR_HANDSHAKE_TIMEOUT_MS) { started.await() }
            }

            Log.d(TAG, "Starting PPI stream for $deviceId")
            polarApi.startPpiStreaming(deviceId)
                .catch { error ->
                    Log.e(TAG, "PPI stream failed", error)
                    ppiEventSink?.error("PPI_STREAM_ERROR", error.message, null)
                }
                .collect { ppiData ->
                    val arrivalMs = System.currentTimeMillis()

                    var cumulativeMs = 0
                    val samplesForFlutter = ppiData.samples.asReversed().map { sample ->
                        cumulativeMs += sample.ppi
                        val timestampMs = if (sample.timeStamp != 0uL) {
                            polarTimestampToUnixMs(sample.timeStamp)
                        } else {
                            arrivalMs - cumulativeMs
                        }
                        mapOf(
                            "timestampMs" to timestampMs,
                            "hr" to sample.hr,
                            "ppiMs" to sample.ppi,
                            "errorEstimateMs" to sample.errorEstimate,
                            // Per documentation/PPIData.md, the general rule is to
                            // discard when skinContactStatus is false OR blockerBit
                            // is true - but the same doc explicitly warns that skin
                            // contact reporting "cannot be trusted" on Verity Sense
                            // / OH1, so these are forwarded as-is and only
                            // blockerBit is used to filter on the Dart side
                            // (see SigProcessor.filterValid).
                            "blockerBit" to sample.blockerBit,
                            "skinContactStatus" to sample.skinContactStatus,
                            "skinContactSupported" to sample.skinContactSupported,
                        )
                    }.asReversed()

                    Log.d(TAG, "PPI packet: ${samplesForFlutter.size} samples")
                    ppiEventSink?.success(samplesForFlutter)
                }
        }
    }

    override fun onDestroy() {
        scanJob?.cancel()
        hrStreamJob?.cancel()
        ppiStreamJob?.cancel()
        polarApi.shutDown()
        super.onDestroy()
    }
}
