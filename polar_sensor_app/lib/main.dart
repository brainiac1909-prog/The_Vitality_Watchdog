import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

class PolarService {
  static const MethodChannel _commandChannel =
      MethodChannel('com.example.polar/commands');
  static const EventChannel _hrStreamChannel =
      EventChannel('com.example.polar/hr_stream');

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

  Stream<int> get heartRateStream {
    return _hrStreamChannel.receiveBroadcastStream().map((event) {
      if (event is int) return event;
      if (event is num) return event.toInt();
      return 0;
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
      title: 'Polar Sensor App',
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
  final PolarService _polarService = PolarService();
  final TextEditingController _deviceIdController = TextEditingController();
  bool _connected = false;
  bool _streaming = false;
  int _heartRate = 0;

  @override
  void dispose() {
    _deviceIdController.dispose();
    super.dispose();
  }

  Future<void> _connect() async {
    final deviceId = _deviceIdController.text.trim();
    if (deviceId.isEmpty) return;

    final success = await _polarService.connectToSensor(deviceId);
    if (!mounted) return;

    setState(() {
      _connected = success;
    });

    if (success) {
      final streamStarted = await _polarService.startHeartRateStream();
      if (!mounted) return;
      setState(() {
        _streaming = streamStarted;
      });

      _polarService.heartRateStream.listen((value) {
        if (!mounted) return;
        setState(() {
          _heartRate = value;
        });
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Polar Sensor'),
      ),
      body: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            TextField(
              controller: _deviceIdController,
              decoration: const InputDecoration(
                labelText: 'Device ID',
                border: OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 16),
            ElevatedButton(
              onPressed: _connect,
              child: const Text('Connect and stream HR'),
            ),
            const SizedBox(height: 24),
            Text(
              _connected ? 'Connected' : 'Not connected',
              style: Theme.of(context).textTheme.titleMedium,
            ),
            Text(
              _streaming ? 'Streaming HR' : 'Not streaming',
              style: Theme.of(context).textTheme.titleMedium,
            ),
            const SizedBox(height: 16),
            Text(
              'Heart Rate: $_heartRate bpm',
              style: Theme.of(context).textTheme.headlineMedium,
            ),
          ],
        ),
      ),
    );
  }
}
