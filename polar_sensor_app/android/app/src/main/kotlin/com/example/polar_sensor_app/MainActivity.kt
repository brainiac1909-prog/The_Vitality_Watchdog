package com.example.polar_sensor_app

import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleApiDefaultImpl
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainActivity : FlutterActivity() {
    companion object {
        private const val TAG = "PolarNative"
        private const val COMMAND_CHANNEL = "com.example.polar/commands"
        private const val HR_STREAM_CHANNEL = "com.example.polar/hr_stream"
    }

    private lateinit var polarApi: PolarBleApi
    private var lastDeviceId: String? = null
    private var hrEventSink: EventChannel.EventSink? = null
    private var hrStreamJob: Job? = null

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
            override fun deviceConnected(polarDeviceInfo: com.polar.sdk.api.model.PolarDeviceInfo) {
                Log.d(TAG, "Connected to ${polarDeviceInfo.deviceId}")
            }

            override fun bleSdkFeatureReady(
                identifier: String,
                feature: PolarBleApi.PolarBleSdkFeature
            ) {
                Log.d(TAG, "Feature $feature ready for $identifier")
            }
        })

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, COMMAND_CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "connect" -> {
                        val deviceId = call.argument<String>("deviceId")
                        if (deviceId.isNullOrBlank()) {
                            result.error("INVALID_ID", "Device ID cannot be empty", null)
                            return@setMethodCallHandler
                        }

                        lastDeviceId = deviceId
                        polarApi.connectToDevice(deviceId)
                        result.success(true)
                    }

                    "startHrStream" -> {
                        val deviceId = lastDeviceId
                        if (deviceId.isNullOrBlank()) {
                            result.error("NO_DEVICE", "No connected device", null)
                            return@setMethodCallHandler
                        }

                        val isReady = polarApi.isFeatureReady(
                            deviceId,
                            PolarBleApi.PolarBleSdkFeature.FEATURE_HR
                        )

                        if (!isReady) {
                            result.error(
                                "FEATURE_NOT_READY",
                                "HR feature is not ready on this device",
                                null
                            )
                            return@setMethodCallHandler
                        }

                        hrStreamJob?.cancel()
                        hrStreamJob = lifecycleScope.launch {
                            polarApi.startHrStreaming(deviceId)
                                .catch { error ->
                                    Log.e(TAG, "HR stream failed", error)
                                    hrEventSink?.error("HR_STREAM_ERROR", error.message, null)
                                }
                                .collect { hrData ->
                                    val hrValue = hrData.samples.lastOrNull()?.hr ?: 0
                                    hrEventSink?.success(hrValue)
                                }
                        }
                        result.success(true)
                    }

                    "disconnect" -> {
                        val deviceId = lastDeviceId
                        if (deviceId != null) {
                            hrStreamJob?.cancel()
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
    }
}