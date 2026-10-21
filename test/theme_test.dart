import 'dart:ui' as ui;

import 'package:destination_alarm_search/main.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() => SharedPreferences.setMockInitialValues({}));

  group('ThemePreferenceController', () {
    test('missing and invalid saved values both default to System', () async {
      final missing = ThemePreferenceController();
      await missing.load();
      expect(missing.themeMode, ThemeMode.system);

      SharedPreferences.setMockInitialValues({
        ThemePreferenceController.preferenceKey: 'sepia',
      });
      final invalid = ThemePreferenceController();
      await invalid.load();
      expect(invalid.themeMode, ThemeMode.system);
    });

    for (final mode in ThemeMode.values) {
      test('${mode.name} is saved and restored', () async {
        final preferences = ThemePreferenceController();
        await preferences.load();
        await preferences.setThemeMode(mode);

        final stored = await SharedPreferences.getInstance();
        expect(
          stored.getString(ThemePreferenceController.preferenceKey),
          mode.name,
        );

        final restored = ThemePreferenceController();
        await restored.load();
        expect(restored.themeMode, mode);
      });
    }
  });

  testWidgets(
    'theme selection updates immediately and persists the selected state',
    (tester) async {
      final preferences = ThemePreferenceController();
      await preferences.load();
      addTearDown(preferences.dispose);

      await tester.pumpWidget(
        DestinationAlarmApp(
          themePreferences: preferences,
          home: const SettingsScreen(),
        ),
      );
      expect(_appBrightness(tester), Brightness.light);
      expect(_choice(tester, 'System').selected, isTrue);
      final semantics = tester.ensureSemantics();
      expect(
        tester
            .getSemantics(find.bySemanticsLabel('System theme'))
            .flagsCollection
            .isSelected,
        ui.Tristate.isTrue,
      );
      final lightTheme = Theme.of(
        tester.element(find.text('Settings and About')),
      );
      expect(lightTheme.scaffoldBackgroundColor, bg);
      expect(lightTheme.cardTheme.color, Colors.white);
      expect(lightTheme.colorScheme.primary, primary);
      expect(lightTheme.colorScheme.secondary, highlight);
      expect(lightTheme.textTheme.bodyMedium?.color, ink);
      expect(
        lightTheme.textButtonTheme.style?.foregroundColor?.resolve(const {}),
        ink,
      );

      await tester.tap(find.widgetWithText(ChoiceChip, 'Light'));
      await tester.pumpAndSettle();
      expect(_appBrightness(tester), Brightness.light);
      expect(_choice(tester, 'Light').selected, isTrue);
      expect(_choice(tester, 'System').selected, isFalse);

      await tester.tap(find.widgetWithText(ChoiceChip, 'Dark'));
      await tester.pumpAndSettle();

      expect(_appBrightness(tester), Brightness.dark);
      expect(_choice(tester, 'Dark').selected, isTrue);
      expect(_choice(tester, 'System').selected, isFalse);
      expect(find.text('Appearance'), findsOneWidget);
      expect(find.text('Location permission'), findsOneWidget);

      final theme = Theme.of(tester.element(find.text('Settings and About')));
      expect(theme.scaffoldBackgroundColor, const Color(0xFF0E0E0E));
      expect(theme.cardTheme.color, const Color(0xFF1A1A1A));
      expect(theme.colorScheme.primary, const Color(0xFFFEEE53));
      expect(theme.colorScheme.secondary, const Color(0xFFFEEE53));
      expect(theme.colorScheme.onSurface, Colors.white);
      expect(theme.textTheme.bodyMedium?.color, Colors.white);
      expect(
        theme.textButtonTheme.style?.foregroundColor?.resolve(const {}),
        Colors.white,
      );
      expect(theme.colorScheme.onSurfaceVariant, const Color(0xFFE1E1E1));
      expect(theme.colorScheme.outline, const Color(0xFF877B6F));

      final stored = await SharedPreferences.getInstance();
      expect(stored.getString(ThemePreferenceController.preferenceKey), 'dark');
      semantics.dispose();
    },
  );

  testWidgets('System theme responds when the device brightness changes', (
    tester,
  ) async {
    final platformDispatcher = tester.binding.platformDispatcher;
    platformDispatcher.platformBrightnessTestValue = Brightness.dark;
    addTearDown(platformDispatcher.clearPlatformBrightnessTestValue);

    final preferences = ThemePreferenceController();
    await preferences.load();
    addTearDown(preferences.dispose);
    await tester.pumpWidget(
      DestinationAlarmApp(
        themePreferences: preferences,
        home: const SettingsScreen(),
      ),
    );
    expect(_appBrightness(tester), Brightness.dark);
    expect(_choice(tester, 'System').selected, isTrue);

    platformDispatcher.platformBrightnessTestValue = Brightness.light;
    await tester.pumpAndSettle();
    expect(_appBrightness(tester), Brightness.light);
  });
}

Brightness _appBrightness(WidgetTester tester) =>
    Theme.of(tester.element(find.text('Settings and About'))).brightness;

ChoiceChip _choice(WidgetTester tester, String label) =>
    tester.widget<ChoiceChip>(find.widgetWithText(ChoiceChip, label));
