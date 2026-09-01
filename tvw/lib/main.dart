import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'dart:async';

import 'datamodels/sensor_data.dart';
import 'processing/sig_processor.dart';

class PolarDevice {
  const PolarDevice({
    required this.deviceId,
    required this.name,
    required this.address,
    required this.rssi,
    required this.isConnectable,
  });

  final String deviceId;
  final String name;
  final String address;
  final int rssi;
  final bool isConnectable;

  factory PolarDevice.fromMap(Map<Object?, Object?> map) {
    return PolarDevice(
      deviceId: map['deviceId'] as String,
      name: (map['name'] as String?)?.trim().isNotEmpty == true
          ? map['name'] as String
          : 'Unnamed Polar device',
      address: map['address'] as String,
      rssi: (map['rssi'] as num).toInt(),
      isConnectable: map['isConnectable'] as bool? ?? true,
    );
  }
}

class PolarService {
  static const MethodChannel _commandChannel =
      MethodChannel('com.example.tvw/commands');
  static const EventChannel _hrStreamChannel =
      EventChannel('com.example.tvw/hr_stream');
  static const EventChannel _ppiStreamChannel =
      EventChannel('com.example.tvw/ppi_stream');
  static const EventChannel _deviceScanChannel =
      EventChannel('com.example.tvw/device_scan');
  static const EventChannel _connectionChannel =
      EventChannel('com.example.tvw/connection');

  Stream<PolarDevice> get discoveredDevices {
    return _deviceScanChannel.receiveBroadcastStream().map((event) {
      return PolarDevice.fromMap(Map<Object?, Object?>.from(event as Map));
    });
  }

  Stream<Map<Object?, Object?>> get connectionEvents {
    return _connectionChannel.receiveBroadcastStream().map((event) {
      return Map<Object?, Object?>.from(event as Map);
    });
  }

  Future<void> scanForDevices() async {
    await _commandChannel.invokeMethod('scan');
  }

  Future<void> stopScan() async {
    await _commandChannel.invokeMethod('stopScan');
  }

  Future<bool> connectToSensor(String deviceId) async {
    try {
      final bool success =
          await _commandChannel.invokeMethod('connect', {'deviceId': deviceId});
      return success;
    } on PlatformException catch (e) {
      debugPrint('Failed to connect: ${e.message}');
      return false;
    }
  }

  Future<bool> startHeartRateStream() async {
    try {
      final bool success = await _commandChannel.invokeMethod('startHrStream');
      return success;
    } on PlatformException catch (e) {
      debugPrint('Failed to start HR stream: ${e.message}');
      return false;
    }
  }

  /// Starts the PPI (pulse-to-pulse interval) stream. Safe to call whether
  /// or not an HR stream is already running/starting - the native side
  /// sequences the two PMD handshakes so they don't race each other.
  Future<bool> startPpiStream() async {
    try {
      final bool success = await _commandChannel.invokeMethod('startPpiStream');
      return success;
    } on PlatformException catch (e) {
      debugPrint('Failed to start PPI stream: ${e.message}');
      return false;
    }
  }

  Future<void> stopPpiStream() async {
    await _commandChannel.invokeMethod('stopPpiStream');
  }

  Future<void> disconnectFromSensor() async {
    await _commandChannel.invokeMethod('disconnect');
  }

  Stream<int> get heartRateStream {
    return _hrStreamChannel.receiveBroadcastStream().map((event) {
      if (event is int) return event;
      if (event is num) return event.toInt();
      throw FormatException('Unexpected heart-rate event: $event');
    });
  }

  /// Each event is a full PPI packet, delivered as a list of samples in
  /// chronological order (already reconstructed with per-sample timestamps
  /// on the native side).
  Stream<List<PpiSample>> get ppiStream {
    return _ppiStreamChannel.receiveBroadcastStream().map((event) {
      final rawList = event as List<Object?>;
      return rawList
          .map((raw) => PpiSample.fromMap(Map<Object?, Object?>.from(raw as Map)))
          .toList(growable: false);
    });
  }
}

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'The Vitality Watchdog',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.deepPurple),
      ),
      home: const PolarHomePage(),
    );
  }
}

class PolarHomePage extends StatefulWidget {
  const PolarHomePage({super.key});

  @override
  State<PolarHomePage> createState() => _PolarHomePageState();
}

class _PolarHomePageState extends State<PolarHomePage> {
  static const int _prvWindowSize = 60; // ~60 pulses ≈ a workable PRV window

  final PolarService _polarService = PolarService();
  final Map<String, PolarDevice> _devices = {};
  bool _connected = false;
  bool _streamingHr = false;
  bool _streamingPpi = false;
  int _heartRate = 0;

  /// Rolling buffer of recent PPI samples - the "usable variable" your
  /// stress model should read from. Trimmed to [_prvWindowSize] so this
  /// stays cheap to keep in memory; swap for persistent storage if you need
  /// full-session history for offline analysis.
  final List<PpiSample> _ppiBuffer = [];
  PrvFeatures? _latestPrvFeatures;

  /// Tracks consecutive blocker-flagged samples so the UI can nudge the user
  /// to stay still, per documentation/PPIData.md's own suggested pattern:
  /// "If the SDK application sees several samples with blocker = 1 in a row,
  /// it could use that to inform the user that they should try to be more
  /// still."
  static const int _consecutiveBlockedThreshold = 5;
  int _consecutiveBlockedSamples = 0;

  StreamSubscription<int>? _heartRateSubscription;
  StreamSubscription<List<PpiSample>>? _ppiSubscription;
  StreamSubscription<PolarDevice>? _deviceSubscription;
  StreamSubscription<Map<Object?, Object?>>? _connectionSubscription;
  bool _connecting = false;
  String? _errorMessage;

  @override
  void dispose() {
    _heartRateSubscription?.cancel();
    _ppiSubscription?.cancel();
    _deviceSubscription?.cancel();
    _connectionSubscription?.cancel();
    _polarService.stopScan();
    super.dispose();
  }

  @override
  void initState() {
    super.initState();
    _deviceSubscription = _polarService.discoveredDevices.listen(
      (device) => setState(() => _devices[device.deviceId] = device),
      onError: (Object error) => setState(() => _errorMessage = 'Scan failed: $error'),
    );
    _connectionSubscription = _polarService.connectionEvents.listen(_handleConnectionEvent);
    _scan();
  }

  Future<void> _scan() async {
    setState(() {
      _errorMessage = null;
      _devices.clear();
    });
    try {
      await _polarService.scanForDevices();
    } on PlatformException catch (error) {
      if (mounted) setState(() => _errorMessage = error.message ?? 'Unable to scan for devices');
    }
  }

  void _handleConnectionEvent(Map<Object?, Object?> event) {
    if (!mounted) return;
    final state = event['state'];
    setState(() {
      _connecting = state == 'connecting';
      _connected = state == 'connected';
      if (state == 'disconnected') {
        _connected = false;
        _streamingHr = false;
        _streamingPpi = false;
        _heartRate = 0;
      }
    });
    if (state == 'connected') {
      // Request both streams. The native side serialises the underlying PMD
      // handshakes so this is safe even though PPI depends on HR having
      // started first.
      _startHeartRateStream();
      _startPpiStream();
    }
  }

  Future<void> _connect(PolarDevice device) async {
    setState(() {
      _connecting = true;
      _errorMessage = null;
      _ppiBuffer.clear();
      _latestPrvFeatures = null;
      _consecutiveBlockedSamples = 0;
    });

    final accepted = await _polarService.connectToSensor(device.deviceId);
    if (!accepted && mounted) {
      setState(() {
        _connecting = false;
        _errorMessage = 'The sensor could not be found or connection was rejected.';
      });
    }
  }

  Future<void> _startHeartRateStream() async {
    await _heartRateSubscription?.cancel();
    _heartRateSubscription = _polarService.heartRateStream.listen(
        (value) {
          if (!mounted) return;
          setState(() => _heartRate = value);
        },
        onError: (Object error) {
          if (!mounted) return;
          setState(() => _errorMessage = 'Heart-rate stream failed: $error');
        },
      );
    final streamStarted = await _polarService.startHeartRateStream();
    if (mounted) setState(() => _streamingHr = streamStarted);
  }

  Future<void> _startPpiStream() async {
    await _ppiSubscription?.cancel();
    _ppiSubscription = _polarService.ppiStream.listen(
      (packet) {
        if (!mounted) return;
        setState(() {
          for (final sample in packet) {
            _consecutiveBlockedSamples =
                sample.blockerBit ? _consecutiveBlockedSamples + 1 : 0;
          }
          _ppiBuffer.addAll(packet);
          if (_ppiBuffer.length > _prvWindowSize) {
            _ppiBuffer.removeRange(0, _ppiBuffer.length - _prvWindowSize);
          }
          _latestPrvFeatures = SigProcessor.computeTimeDomainFeatures(_ppiBuffer);
        });
      },
      onError: (Object error) {
        if (!mounted) return;
        setState(() => _errorMessage = 'PPI stream failed: $error');
      },
    );
    final streamStarted = await _polarService.startPpiStream();
    if (mounted) setState(() => _streamingPpi = streamStarted);
  }

  Future<void> _disconnect() async {
    await _heartRateSubscription?.cancel();
    await _ppiSubscription?.cancel();
    await _polarService.stopPpiStream();
    await _polarService.disconnectFromSensor();
    if (!mounted) return;
    setState(() {
      _connected = false;
      _streamingHr = false;
      _streamingPpi = false;
      _heartRate = 0;
      _ppiBuffer.clear();
      _latestPrvFeatures = null;
      _consecutiveBlockedSamples = 0;
    });
  }

  @override
  Widget build(BuildContext context) {
    final prv = _latestPrvFeatures;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Polar Sensor'),
      ),
      body: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text('Nearby Polar devices', style: Theme.of(context).textTheme.titleMedium),
                IconButton(
                  onPressed: _connecting || _connected ? null : _scan,
                  icon: const Icon(Icons.refresh),
                  tooltip: 'Scan again',
                ),
              ],
            ),
            Expanded(
              child: _devices.isEmpty
                  ? const Center(child: Text('Searching for nearby devices...'))
                  : ListView(
                      children: _devices.values.map((device) {
                        return ListTile(
                          leading: const Icon(Icons.watch),
                          title: Text(device.name),
                          subtitle: Text('${device.address}  |  RSSI ${device.rssi} dBm'),
                          trailing: const Icon(Icons.chevron_right),
                          enabled: !_connecting && !_connected && device.isConnectable,
                          onTap: () => _connect(device),
                        );
                      }).toList(),
                    ),
            ),
            const SizedBox(height: 16),
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                if (_connecting)
                  const Row(
                    children: [
                      SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2)),
                      SizedBox(width: 8),
                      Text('Connecting...'),
                    ],
                  ),
                if (!_connecting && !_connected) const Text('Tap a device to connect'),
                if (_connected) const Text('Connected'),
                const SizedBox(width: 12),
                OutlinedButton.icon(
                  onPressed: _connected ? _disconnect : null,
                  icon: const Icon(Icons.bluetooth_disabled),
                  label: const Text('Disconnect'),
                ),
              ],
            ),
            const SizedBox(height: 24),
            Text(
              _streamingHr ? 'Streaming HR' : 'Not streaming HR',
              style: Theme.of(context).textTheme.titleMedium,
            ),
            Text(
              _streamingPpi ? 'Streaming PPI' : 'Not streaming PPI',
              style: Theme.of(context).textTheme.titleMedium,
            ),
            const SizedBox(height: 16),
            Text(
              'Heart Rate: $_heartRate bpm',
              style: Theme.of(context).textTheme.headlineMedium,
            ),
            const SizedBox(height: 8),
            Text(
              'PPI buffer: ${_ppiBuffer.length} samples',
              style: Theme.of(context).textTheme.bodyMedium,
            ),
            if (prv != null) ...[
              const SizedBox(height: 8),
              Text(
                'SDNN ${prv.sdnnMs.toStringAsFixed(1)}ms  '
                'RMSSD ${prv.rmssdMs.toStringAsFixed(1)}ms  '
                'pNN50 ${prv.pnn50Percent.toStringAsFixed(1)}%',
                style: Theme.of(context).textTheme.bodyMedium,
                textAlign: TextAlign.center,
              ),
              if (!prv.isReliable)
                Padding(
                  padding: const EdgeInsets.only(top: 4),
                  child: Text(
                    'Low confidence: too much movement in this window',
                    style: TextStyle(color: Theme.of(context).colorScheme.error),
                    textAlign: TextAlign.center,
                  ),
                ),
            ],
            if (_consecutiveBlockedSamples >= _consecutiveBlockedThreshold)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Text(
                  'Hold still for a more accurate PPI reading',
                  style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                        fontWeight: FontWeight.bold,
                      ),
                  textAlign: TextAlign.center,
                ),
              ),
            if (_errorMessage != null) ...[
              const SizedBox(height: 12),
              Text(
                _errorMessage!,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
                textAlign: TextAlign.center,
              ),
            ],
          ],
        ),
      ),
    );
  }
}
