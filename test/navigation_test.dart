import 'dart:convert';

import 'package:destination_alarm_search/main.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

class _PhotonClient extends http.BaseClient {
  @override
  Future<http.StreamedResponse> send(http.BaseRequest request) async {
    final body = jsonEncode({
      'features': [
        {
          'type': 'Feature',
          'geometry': {
            'type': 'Point',
            'coordinates': [77.5946, 12.9716],
          },
          'properties': {
            'name': 'Bengaluru Palace',
            'street': 'Palace Road',
            'city': 'Bengaluru',
            'state': 'Karnataka',
            'country': 'India',
          },
        },
      ],
    });
    return http.StreamedResponse(Stream.value(utf8.encode(body)), 200);
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    SharedPreferences.setMockInitialValues({});
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(serviceChannel, (call) async => {});
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockStreamHandler(
          eventChannel,
          MockStreamHandler.inline(onListen: (arguments, events) {}),
        );
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(serviceChannel, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockStreamHandler(eventChannel, null);
  });

  testWidgets(
    'tapping a Photon suggestion opens details with the selected place even if recent saving fails',
    (tester) async {
      final themePreferences = ThemePreferenceController();
      await themePreferences.load();
      addTearDown(themePreferences.dispose);
      await tester.pumpWidget(
        DestinationAlarmApp(
          themePreferences: themePreferences,
          home: HomeScreen(
            search: PhotonSearch(client: _PhotonClient()),
            persistRecent: (_) async => throw StateError('storage unavailable'),
          ),
        ),
      );
      await tester.pump();

      await tester.enterText(find.byType(TextField).first, 'Bengaluru');
      await tester.pump(const Duration(milliseconds: 600));
      await tester.pump();
      await tester.pump();
      expect(find.text('Bengaluru Palace'), findsOneWidget);

      await tester.tap(find.text('Bengaluru Palace'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 400));

      expect(find.text('Destination details'), findsOneWidget);
      expect(find.text('Bengaluru Palace'), findsOneWidget);
      expect(find.textContaining('Palace Road'), findsOneWidget);
      expect(find.text('12.971600, 77.594600'), findsOneWidget);
      expect(
        find.textContaining('could not be saved to recent destinations'),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'Settings Test Alarm starts and stops through the native playback channel',
    (tester) async {
      final calls = <String>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(serviceChannel, (call) async {
            calls.add(call.method);
            if (call.method == 'getStatus') {
              return {
                'testAlarmActive': true,
                'alarmSounding': true,
                'error': null,
              };
            }
            return true;
          });

      final themePreferences = ThemePreferenceController();
      await themePreferences.load();
      addTearDown(themePreferences.dispose);
      await tester.pumpWidget(
        DestinationAlarmApp(
          themePreferences: themePreferences,
          home: const SettingsScreen(),
        ),
      );
      await tester.drag(find.byType(ListView), const Offset(0, -450));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(ElevatedButton, 'Test Alarm'));
      await tester.pump();
      await tester.pump();

      expect(calls, contains('testAlarm'));
      expect(
        find.text('Alarm audio is playing. It will loop until stopped.'),
        findsOneWidget,
      );

      await tester.tap(find.text('Stop Test'));
      await tester.pump();
      expect(calls, contains('stopTestAlarm'));
    },
  );
}
