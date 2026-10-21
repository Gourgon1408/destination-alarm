import 'dart:async';
import 'dart:convert';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:geolocator/geolocator.dart';
import 'package:http/http.dart' as http;
import 'package:latlong2/latlong.dart';
import 'package:shared_preferences/shared_preferences.dart';

const bg = Color(0xFFF6F5FA);
const border = Color(0xFFDBDFE9);
const primary = Color(0xFFCFDECA);
const highlight = Color(0xFFEFF0A3);
const ink = Color(0xFF212121);
const _darkBackground = Color(0xFF0E0E0E);
const _darkSurface = Color(0xFF1A1A1A);
const _darkAccent = Color(0xFFFEEE53);
const _darkSupporting = Color(0xFFE1E1E1);
const _darkMuted = Color(0xFF877B6F);
const serviceChannel = MethodChannel('destination_alarm/service');
const eventChannel = EventChannel('destination_alarm/service_events');

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final preferences = ThemePreferenceController();
  await preferences.load();
  runApp(DestinationAlarmApp(themePreferences: preferences));
}

@immutable
class AppThemeColors extends ThemeExtension<AppThemeColors> {
  const AppThemeColors({
    required this.primaryAction,
    required this.highlight,
    required this.infoSurface,
  });

  final Color primaryAction;
  final Color highlight;
  final Color infoSurface;

  @override
  AppThemeColors copyWith({
    Color? primaryAction,
    Color? highlight,
    Color? infoSurface,
  }) => AppThemeColors(
    primaryAction: primaryAction ?? this.primaryAction,
    highlight: highlight ?? this.highlight,
    infoSurface: infoSurface ?? this.infoSurface,
  );

  @override
  AppThemeColors lerp(covariant AppThemeColors? other, double t) {
    if (other == null) return this;
    return AppThemeColors(
      primaryAction: Color.lerp(primaryAction, other.primaryAction, t)!,
      highlight: Color.lerp(highlight, other.highlight, t)!,
      infoSurface: Color.lerp(infoSurface, other.infoSurface, t)!,
    );
  }
}

class AppThemes {
  static final ThemeData light = _build(
    brightness: Brightness.light,
    background: bg,
    surface: Colors.white,
    foreground: ink,
    supporting: const Color(0xFF555555),
    outline: border,
    action: primary,
    accent: highlight,
    infoSurface: const Color(0xFFF0F1EB),
    error: const Color(0xFF8B2F2F),
  );

  static final ThemeData dark = _build(
    brightness: Brightness.dark,
    background: _darkBackground,
    surface: _darkSurface,
    foreground: Colors.white,
    supporting: _darkSupporting,
    outline: _darkMuted,
    action: _darkAccent,
    accent: _darkAccent,
    infoSurface: _darkSurface,
    error: const Color(0xFFFFB4AB),
  );

  static ThemeData _build({
    required Brightness brightness,
    required Color background,
    required Color surface,
    required Color foreground,
    required Color supporting,
    required Color outline,
    required Color action,
    required Color accent,
    required Color infoSurface,
    required Color error,
  }) {
    final onAction = brightness == Brightness.dark ? _darkBackground : ink;
    final colorScheme =
        ColorScheme.fromSeed(
          seedColor: action,
          brightness: brightness,
          surface: surface,
        ).copyWith(
          primary: action,
          onPrimary: onAction,
          secondary: accent,
          onSecondary: onAction,
          surface: surface,
          onSurface: foreground,
          onSurfaceVariant: supporting,
          outline: outline,
          outlineVariant: outline,
          error: error,
          onError: brightness == Brightness.dark
              ? _darkBackground
              : Colors.white,
          surfaceTint: Colors.transparent,
        );
    final textTheme = ThemeData(brightness: brightness).textTheme
        .apply(bodyColor: foreground, displayColor: foreground);
    final buttonShape = RoundedRectangleBorder(
      borderRadius: BorderRadius.circular(16),
    );
    final fieldBorder = OutlineInputBorder(
      borderRadius: BorderRadius.circular(16),
      borderSide: BorderSide(color: outline),
    );

    return ThemeData(
      useMaterial3: true,
      brightness: brightness,
      colorScheme: colorScheme,
      scaffoldBackgroundColor: background,
      canvasColor: background,
      cardColor: surface,
      dividerColor: outline,
      iconTheme: IconThemeData(color: foreground),
      textTheme: textTheme,
      primaryTextTheme: textTheme,
      extensions: [
        AppThemeColors(
          primaryAction: action,
          highlight: accent,
          infoSurface: infoSurface,
        ),
      ],
      appBarTheme: AppBarTheme(
        backgroundColor: background,
        foregroundColor: foreground,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        centerTitle: false,
      ),
      cardTheme: CardThemeData(
        color: surface,
        elevation: 0,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(20),
          side: BorderSide(color: outline),
        ),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: surface,
        border: fieldBorder,
        enabledBorder: fieldBorder,
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(16),
          borderSide: BorderSide(color: action, width: 1.5),
        ),
      ),
      elevatedButtonTheme: ElevatedButtonThemeData(
        style: ElevatedButton.styleFrom(
          backgroundColor: action,
          foregroundColor: onAction,
          minimumSize: const Size.fromHeight(54),
          shape: buttonShape,
          textStyle: const TextStyle(fontWeight: FontWeight.w700),
        ),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          backgroundColor: action,
          foregroundColor: onAction,
          shape: buttonShape,
          textStyle: const TextStyle(fontWeight: FontWeight.w700),
        ),
      ),
      textButtonTheme: TextButtonThemeData(
        style: TextButton.styleFrom(foregroundColor: foreground),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          foregroundColor: foreground,
          side: BorderSide(color: outline),
          shape: buttonShape,
        ),
      ),
    );
  }
}

class ThemePreferenceController extends ChangeNotifier {
  static const preferenceKey = 'theme_mode';

  ThemeMode _themeMode = ThemeMode.system;
  ThemeMode get themeMode => _themeMode;

  Future<void> load() async {
    try {
      final preferences = await SharedPreferences.getInstance();
      _themeMode = switch (preferences.getString(preferenceKey)) {
        'light' => ThemeMode.light,
        'dark' => ThemeMode.dark,
        'system' => ThemeMode.system,
        _ => ThemeMode.system,
      };
      notifyListeners();
    } catch (error) {
      _themeMode = ThemeMode.system;
      debugPrint('Theme preference load failed; using System: $error');
    }
  }

  Future<void> setThemeMode(ThemeMode mode) async {
    if (_themeMode != mode) {
      _themeMode = mode;
      notifyListeners();
    }
    final preferences = await SharedPreferences.getInstance();
    final saved = await preferences.setString(preferenceKey, mode.name);
    if (!saved) throw StateError('Theme preference could not be saved');
  }
}

class ThemePreferenceScope
    extends InheritedNotifier<ThemePreferenceController> {
  const ThemePreferenceScope({
    super.key,
    required this.controller,
    required super.child,
  }) : super(notifier: controller);

  final ThemePreferenceController controller;

  static ThemePreferenceController of(BuildContext context) {
    final scope = context
        .dependOnInheritedWidgetOfExactType<ThemePreferenceScope>();
    assert(scope != null, 'ThemePreferenceScope is missing above this context');
    return scope!.controller;
  }
}

class DestinationAlarmApp extends StatelessWidget {
  const DestinationAlarmApp({
    super.key,
    required this.themePreferences,
    this.home = const HomeScreen(),
  });
  final ThemePreferenceController themePreferences;
  final Widget home;

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: themePreferences,
    builder: (context, _) => MaterialApp(
      title: 'Destination Alarm Search',
      debugShowCheckedModeBanner: false,
      theme: AppThemes.light,
      darkTheme: AppThemes.dark,
      themeMode: themePreferences.themeMode,
      builder: (context, child) => ThemePreferenceScope(
        controller: themePreferences,
        child: child ?? const SizedBox.shrink(),
      ),
      home: home,
    ),
  );
}

@immutable
class Place {
  const Place({
    required this.name,
    required this.address,
    required this.latitude,
    required this.longitude,
  });
  final String name, address;
  final double latitude, longitude;
  Map<String, Object> toJson() => {
    'name': name,
    'address': address,
    'latitude': latitude,
    'longitude': longitude,
  };
  factory Place.fromJson(Map<String, dynamic> json) => Place(
    name: json['name'] as String,
    address: json['address'] as String,
    latitude: (json['latitude'] as num).toDouble(),
    longitude: (json['longitude'] as num).toDouble(),
  );
  factory Place.fromPhoton(Map<String, dynamic> feature) {
    final properties =
        feature['properties'] as Map<String, dynamic>? ?? const {};
    final coordinates =
        (feature['geometry'] as Map<String, dynamic>)['coordinates']
            as List<dynamic>;
    if (coordinates.length < 2) {
      throw const FormatException('Photon result has no coordinate pair');
    }
    final longitude = (coordinates[0] as num).toDouble();
    final latitude = (coordinates[1] as num).toDouble();
    if (!latitude.isFinite ||
        !longitude.isFinite ||
        latitude < -90 ||
        latitude > 90 ||
        longitude < -180 ||
        longitude > 180) {
      throw const FormatException('Photon returned invalid coordinates');
    }
    final name =
        (properties['name'] ??
                properties['city'] ??
                properties['street'] ??
                'Unnamed place')
            .toString();
    final address =
        [
              properties['housenumber'],
              properties['street'],
              properties['district'],
              properties['city'],
              properties['state'],
              properties['country'],
            ]
            .where(
              (part) =>
                  part != null &&
                  part.toString().isNotEmpty &&
                  part.toString() != name,
            )
            .map((part) => part.toString())
            .toSet()
            .join(', ');
    return Place(
      name: name,
      address: address.isEmpty ? 'OpenStreetMap place' : address,
      latitude: latitude,
      longitude: longitude,
    );
  }
  LatLng get point => LatLng(latitude, longitude);
}

List<Place> parsePhotonResponse(String body, {int limit = 6}) {
  final data = jsonDecode(body) as Map<String, dynamic>;
  final features = data['features'] as List<dynamic>? ?? const [];
  return features
      .take(limit)
      .map((feature) => Place.fromPhoton(feature as Map<String, dynamic>))
      .toList();
}

double distanceMetres(double lat1, double lon1, double lat2, double lon2) {
  const earthRadius = 6371000.0;
  final p1 = lat1 * math.pi / 180, p2 = lat2 * math.pi / 180;
  final dp = (lat2 - lat1) * math.pi / 180, dl = (lon2 - lon1) * math.pi / 180;
  final a =
      (math.pow(math.sin(dp / 2), 2) +
              math.cos(p1) * math.cos(p2) * math.pow(math.sin(dl / 2), 2))
          .clamp(0.0, 1.0);
  return 2 * earthRadius * math.atan2(math.sqrt(a), math.sqrt(1 - a));
}

class ArrivalRadiusGate {
  ArrivalRadiusGate(this.radiusMetres);
  final double radiusMetres;
  int _insideFixes = 0;
  bool arrived = false;
  bool update({required double distance, required double accuracy}) {
    if (arrived) return false;
    final maximumAccuracy = math.min(250.0, radiusMetres * 0.5);
    if (!distance.isFinite || distance < 0 || accuracy < 0) {
      _insideFixes = 0;
      return false;
    }
    if (!accuracy.isFinite || accuracy > maximumAccuracy) return false;
    if (distance > radiusMetres) {
      _insideFixes = 0;
      return false;
    }
    _insideFixes++;
    if (_insideFixes < 2) return false;
    arrived = true;
    return true;
  }
}

class PhotonSearch {
  PhotonSearch({http.Client? client}) : _client = client ?? http.Client();
  final http.Client _client;
  static const endpoint = 'https://photon.komoot.io/api/';
  Future<List<Place>> search(String query) async {
    final uri = Uri.parse(endpoint)
        .replace(queryParameters: {'q': query, 'limit': '6', 'lang': 'en'});
    final response = await _client
        .get(
          uri,
          headers: const {
            'User-Agent':
                'DestinationAlarmSearch/1.0 (OpenStreetMap Photon client)',
          },
        )
        .timeout(const Duration(seconds: 10));
    if (response.statusCode != 200) {
      throw Exception(
        'Place search is temporarily unavailable (${response.statusCode}).',
      );
    }
    return parsePhotonResponse(response.body);
  }
}

String formatDistance(double? metres) => metres == null
    ? '—'
    : metres >= 1000
    ? '${(metres / 1000).toStringAsFixed(1)} km'
    : '${metres.round()} m';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key, this.search, this.persistRecent});
  @visibleForTesting
  final PhotonSearch? search;
  @visibleForTesting
  final Future<void> Function(Place place)? persistRecent;
  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  late final PhotonSearch _search;
  final _controller = TextEditingController();
  Timer? _debounce;
  int _requestId = 0;
  List<Place> _results = [], _recent = [];
  bool _loading = false;
  String? _error;
  Place? _destination;
  int _radius = 1000;
  Map<Object?, Object?> _status = {};
  StreamSubscription<Object?>? _events;
  bool _isSelecting = false;

  @override
  void initState() {
    super.initState();
    _search = widget.search ?? PhotonSearch();
    _loadRecents();
    _refreshStatus();
    _events = eventChannel.receiveBroadcastStream().listen((event) {
      if (event is Map && mounted) {
        setState(() => _status = Map<Object?, Object?>.from(event));
      }
    });
    _controller.addListener(_onQueryChanged);
  }

  Future<void> _loadRecents() async {
    final prefs = await SharedPreferences.getInstance();
    final saved = prefs.getStringList('recent_places') ?? [];
    if (mounted) {
      setState(
        () => _recent = saved
            .map((s) => Place.fromJson(jsonDecode(s) as Map<String, dynamic>))
            .toList(),
      );
    }
  }

  Future<void> _saveRecent(Place place) async {
    final prefs = await SharedPreferences.getInstance();
    final next = [
      place,
      ..._recent.where((p) => p.name != place.name),
    ].take(6).toList();
    await prefs.setStringList(
      'recent_places',
      next.map((p) => jsonEncode(p.toJson())).toList(),
    );
    if (mounted) setState(() => _recent = next);
  }

  Future<void> _refreshStatus() async {
    try {
      final result = await serviceChannel.invokeMapMethod<Object?, Object?>(
        'getStatus',
      );
      if (mounted && result != null) setState(() => _status = result);
    } on PlatformException {
      /* The journey service may not be available yet. */
    }
  }

  void _onQueryChanged() {
    _requestId++;
    _debounce?.cancel();
    final query = _controller.text.trim();
    if (query.length < 3) {
      setState(() {
        _results = [];
        _loading = false;
        _error = null;
      });
      return;
    }
    _debounce = Timer(
      const Duration(milliseconds: 600),
      () => _runSearch(query),
    );
  }

  Future<void> _runSearch(String query) async {
    final id = ++_requestId;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final found = await _search.search(query);
      if (!mounted || id != _requestId) return;
      setState(() {
        _results = found;
        _loading = false;
      });
    } on TimeoutException {
      if (mounted && id == _requestId) {
        setState(() {
          _loading = false;
          _error = 'Search timed out. Check your connection and retry.';
        });
      }
    } catch (_) {
      if (mounted && id == _requestId) {
        setState(() {
          _loading = false;
          _error =
              'Could not reach place search. Check your connection and retry.';
        });
      }
    }
  }

  Future<void> _choose(Place place) async {
    if (_isSelecting) return;
    _isSelecting = true;
    _debounce?.cancel();
    _requestId++;
    try {
      _controller.clear();
      if (!mounted) return;
      setState(() {
        _destination = place;
        _results = [];
        _loading = false;
      });
      final navigation = Navigator.of(context).push<void>(
        MaterialPageRoute<void>(
          builder: (_) => DestinationScreen(
            place: place,
            initialRadius: _radius,
            onRadius: (value) => setState(() => _radius = value),
            onStart: _startJourney,
          ),
        ),
      );
      // Recent-history persistence is best effort. It must never hold the
      // destination route hostage if Android storage/plugin initialization fails.
      unawaited(_persistSelectedPlace(place));
      await navigation;
      if (mounted) await _refreshStatus();
    } catch (error) {
      if (mounted) _message('Could not open ${place.name}. Please try again.');
    } finally {
      _isSelecting = false;
    }
  }

  Future<void> _persistSelectedPlace(Place place) async {
    try {
      final customPersister = widget.persistRecent;
      if (customPersister == null) {
        await _saveRecent(place);
      } else {
        await customPersister(place);
      }
    } catch (_) {
      if (mounted) {
        _message(
          'Opened ${place.name}, but it could not be saved to recent destinations.',
        );
      }
    }
  }

  Future<void> _manualCoordinates() async {
    final lat = TextEditingController(), lon = TextEditingController();
    final place = await showDialog<Place>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Enter coordinates'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: lat,
              keyboardType: const TextInputType.numberWithOptions(
                decimal: true,
                signed: true,
              ),
              decoration: const InputDecoration(
                labelText: 'Latitude',
                hintText: '12.9716',
              ),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: lon,
              keyboardType: const TextInputType.numberWithOptions(
                decimal: true,
                signed: true,
              ),
              decoration: const InputDecoration(
                labelText: 'Longitude',
                hintText: '77.5946',
              ),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () {
              final a = double.tryParse(lat.text),
                  b = double.tryParse(lon.text);
              if (a == null ||
                  b == null ||
                  a < -90 ||
                  a > 90 ||
                  b < -180 ||
                  b > 180) {
                ScaffoldMessenger.of(context).showSnackBar(
                  const SnackBar(
                    content: Text('Enter a valid latitude and longitude.'),
                  ),
                );
                return;
              }
              Navigator.pop(
                context,
                Place(
                  name: 'Pinned destination',
                  address: 'Manual coordinates',
                  latitude: a,
                  longitude: b,
                ),
              );
            },
            child: const Text('Use location'),
          ),
        ],
      ),
    );
    if (place != null) _choose(place);
  }

  Future<void> _startJourney(Place place, int radius) async {
    try {
      final enabled = await Geolocator.isLocationServiceEnabled();
      if (!enabled) {
        _message('Turn on location services, then try again.');
        return;
      }
      var permission = await Geolocator.checkPermission();
      if (permission == LocationPermission.denied) {
        permission = await Geolocator.requestPermission();
      }
      if (permission == LocationPermission.denied ||
          permission == LocationPermission.deniedForever) {
        _message('Allow location while using the app to start a journey.');
        return;
      }
      final notices =
          await serviceChannel.invokeMethod<bool>(
            'requestNotificationPermission',
          ) ??
          false;
      if (!notices) {
        _message(
          'Allow notifications for ongoing journey status and arrival alerts.',
        );
        return;
      }
      await serviceChannel.invokeMethod('startJourney', {
        'latitude': place.latitude,
        'longitude': place.longitude,
        'radiusMetres': radius.toDouble(),
      });
      if (mounted) {
        Navigator.push(
          context,
          MaterialPageRoute(
            builder: (_) => JourneyScreen(
              place: place,
              radius: radius,
              onStop: _stopJourney,
              onDismiss: _dismissAlarm,
            ),
          ),
        );
      }
    } on PlatformException catch (e) {
      _message(e.message ?? 'Could not start location monitoring.');
    }
  }

  Future<void> _stopJourney() async {
    await serviceChannel.invokeMethod('stopJourney');
    if (mounted) Navigator.of(context).popUntil((route) => route.isFirst);
  }

  Future<void> _dismissAlarm() async {
    await serviceChannel.invokeMethod('dismissAlarm');
    if (mounted) Navigator.of(context).popUntil((route) => route.isFirst);
  }

  void _message(String message) {
    if (mounted) {
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(message)));
    }
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _controller.dispose();
    _events?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final tracking = _status['tracking'] == true,
        arrived = _status['arrived'] == true;
    final colors = Theme.of(context).extension<AppThemeColors>()!;
    final colorScheme = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(
        title: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            DecoratedBox(
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(9),
                border: Border.all(
                  color: colorScheme.outline.withValues(alpha: 0.45),
                ),
              ),
              child: ClipRRect(
                borderRadius: BorderRadius.circular(8),
                child: Image.asset(
                  'assets/branding/destination_alarm_logo.png',
                  width: 34,
                  height: 34,
                  fit: BoxFit.cover,
                  excludeFromSemantics: true,
                ),
              ),
            ),
            const SizedBox(width: 10),
            const Text(
              'Destination Alarm',
              style: TextStyle(fontWeight: FontWeight.w800),
            ),
          ],
        ),
        actions: [
          IconButton(
            tooltip: 'Settings and about',
            onPressed: () => Navigator.push(
              context,
              MaterialPageRoute(builder: (_) => const SettingsScreen()),
            ),
            icon: const Icon(Icons.tune_rounded),
          ),
        ],
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 32),
          children: [
            Text(
              'Go where you want.\nWe’ll let you know when you’re close.',
              style: Theme.of(context).textTheme.headlineSmall
                  ?.copyWith(fontWeight: FontWeight.w800, height: 1.2),
            ),
            const SizedBox(height: 20),
            TextField(
              controller: _controller,
              textInputAction: TextInputAction.search,
              decoration: InputDecoration(
                hintText: 'Search a place or address',
                prefixIcon: const Icon(Icons.search_rounded),
                suffixIcon: _controller.text.isEmpty
                    ? null
                    : IconButton(
                        icon: const Icon(Icons.close),
                        onPressed: _controller.clear,
                      ),
              ),
            ),
            if (_controller.text.trim().length >= 3) ...[
              const SizedBox(height: 10),
              if (_loading)
                const Center(
                  child: Padding(
                    padding: EdgeInsets.all(22),
                    child: CircularProgressIndicator(),
                  ),
                ),
              if (_error != null)
                Card(
                  child: ListTile(
                    leading: const Icon(Icons.wifi_off_rounded),
                    title: Text(_error!),
                    trailing: TextButton(
                      onPressed: () => _runSearch(_controller.text.trim()),
                      child: const Text('Retry'),
                    ),
                  ),
                ),
              if (!_loading && _error == null && _results.isEmpty)
                const Card(
                  child: ListTile(
                    leading: Icon(Icons.search_off_rounded),
                    title: Text('No places found'),
                    subtitle: Text('Try another name or address.'),
                  ),
                ),
              ..._results.map(
                (place) => _PlaceTile(
                  place: place,
                  onTap: () => unawaited(_choose(place)),
                ),
              ),
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Text(
                  'Search by Photon · © OpenStreetMap contributors',
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    color: colorScheme.onSurfaceVariant,
                    fontSize: 11,
                  ),
                ),
              ),
            ] else ...[
              const SizedBox(height: 20),
              if (arrived)
                _ActiveBanner(
                  text: 'You’ve arrived',
                  icon: Icons.notifications_active_rounded,
                  color: colors.highlight,
                  foreground: colorScheme.onSecondary,
                  onTap: () => Navigator.push(
                    context,
                    MaterialPageRoute(
                      builder: (_) => JourneyScreen(
                        place:
                            _destination ??
                            Place(
                              name: 'Your destination',
                              address: '',
                              latitude:
                                  (_status['destinationLatitude'] as num?)
                                      ?.toDouble() ??
                                  0,
                              longitude:
                                  (_status['destinationLongitude'] as num?)
                                      ?.toDouble() ??
                                  0,
                            ),
                        radius: _radius,
                        onStop: _stopJourney,
                        onDismiss: _dismissAlarm,
                      ),
                    ),
                  ),
                )
              else if (tracking)
                _ActiveBanner(
                  text: 'Journey in progress',
                  icon: Icons.navigation_rounded,
                  color: colors.primaryAction,
                  foreground: colorScheme.onPrimary,
                  onTap: () => Navigator.push(
                    context,
                    MaterialPageRoute(
                      builder: (_) => JourneyScreen(
                        place:
                            _destination ??
                            Place(
                              name: 'Your destination',
                              address: '',
                              latitude:
                                  (_status['destinationLatitude'] as num?)
                                      ?.toDouble() ??
                                  0,
                              longitude:
                                  (_status['destinationLongitude'] as num?)
                                      ?.toDouble() ??
                                  0,
                            ),
                        radius: _radius,
                        onStop: _stopJourney,
                        onDismiss: _dismissAlarm,
                      ),
                    ),
                  ),
                ),
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text(
                    'Recent destinations',
                    style: Theme.of(context).textTheme.titleMedium
                        ?.copyWith(fontWeight: FontWeight.w800),
                  ),
                  TextButton.icon(
                    onPressed: _manualCoordinates,
                    icon: const Icon(Icons.add_location_alt_outlined),
                    label: const Text('Coordinates'),
                  ),
                ],
              ),
              if (_recent.isEmpty)
                const Card(
                  child: Padding(
                    padding: EdgeInsets.all(18),
                    child: Text(
                      'Your recent places will appear here. Search above to choose a destination.',
                    ),
                  ),
                ),
              ..._recent.map(
                (place) =>
                    _PlaceTile(place: place, onTap: () => _choose(place)),
              ),
            ],
            const SizedBox(height: 20),
            Card(
              color: colors.infoSurface,
              child: const Padding(
                padding: EdgeInsets.all(16),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Icon(Icons.shield_outlined, size: 20),
                    SizedBox(width: 12),
                    Expanded(
                      child: Text(
                        'Location is used for your active journey. Background tracking depends on Android settings and your device.',
                        style: TextStyle(fontSize: 13, height: 1.4),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _PlaceTile extends StatelessWidget {
  const _PlaceTile({required this.place, required this.onTap});
  final Place place;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).extension<AppThemeColors>()!;
    final colorScheme = Theme.of(context).colorScheme;
    return Card(
      child: ListTile(
        onTap: onTap,
        leading: Container(
          width: 40,
          height: 40,
          decoration: BoxDecoration(
            color: colors.highlight.withValues(alpha: .55),
            borderRadius: BorderRadius.circular(13),
          ),
          child: Icon(Icons.place_outlined, color: colorScheme.onSecondary),
        ),
        title: Text(
          place.name,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(fontWeight: FontWeight.w700),
        ),
        subtitle: Text(
          place.address,
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
        ),
        trailing: const Icon(Icons.chevron_right_rounded),
      ),
    );
  }
}

class _ActiveBanner extends StatelessWidget {
  const _ActiveBanner({
    required this.text,
    required this.icon,
    required this.color,
    required this.foreground,
    required this.onTap,
  });
  final String text;
  final IconData icon;
  final Color color, foreground;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) => Card(
    color: color,
    child: ListTile(
      onTap: onTap,
      iconColor: foreground,
      textColor: foreground,
      leading: Icon(icon),
      title: Text(text, style: const TextStyle(fontWeight: FontWeight.w800)),
      subtitle: const Text('Tap to view journey'),
      trailing: const Icon(Icons.arrow_forward_rounded),
    ),
  );
}

class DestinationScreen extends StatefulWidget {
  const DestinationScreen({
    super.key,
    required this.place,
    required this.initialRadius,
    required this.onRadius,
    required this.onStart,
  });
  final Place place;
  final int initialRadius;
  final ValueChanged<int> onRadius;
  final Future<void> Function(Place, int) onStart;
  @override
  State<DestinationScreen> createState() => _DestinationScreenState();
}

class _DestinationScreenState extends State<DestinationScreen> {
  late int radius = widget.initialRadius;
  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).extension<AppThemeColors>()!;
    final colorScheme = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(title: const Text('Destination details')),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(20),
          children: [
            _MiniMap(place: widget.place),
            const SizedBox(height: 20),
            Text(
              widget.place.name,
              style: Theme.of(context).textTheme.headlineSmall
                  ?.copyWith(fontWeight: FontWeight.w800),
            ),
            const SizedBox(height: 6),
            Text(
              widget.place.address,
              style: TextStyle(color: colorScheme.onSurfaceVariant),
            ),
            Card(
              margin: const EdgeInsets.symmetric(vertical: 18),
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text(
                      'Destination coordinates',
                      style: TextStyle(fontWeight: FontWeight.w800),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      '${widget.place.latitude.toStringAsFixed(6)}, ${widget.place.longitude.toStringAsFixed(6)}',
                    ),
                  ],
                ),
              ),
            ),
            Text(
              'Arrival radius',
              style: Theme.of(context).textTheme.titleMedium
                  ?.copyWith(fontWeight: FontWeight.w800),
            ),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              children: [
                for (final m in [500, 1000, 2000, 5000])
                  ChoiceChip(
                    label: Text(
                      m >= 1000 ? '${m ~/ 1000} km' : '$m m',
                      style: TextStyle(
                        color: radius == m
                            ? colorScheme.onSecondary
                            : colorScheme.onSurface,
                      ),
                    ),
                    selected: radius == m,
                    selectedColor: colors.highlight,
                    onSelected: (_) {
                      setState(() => radius = m);
                      widget.onRadius(m);
                    },
                  ),
              ],
            ),
            const SizedBox(height: 22),
            ElevatedButton.icon(
              onPressed: () => widget.onStart(widget.place, radius),
              icon: const Icon(Icons.navigation_rounded),
              label: const Text('Start Journey'),
            ),
            const SizedBox(height: 12),
            Text(
              'Keep location and notifications enabled. Android may limit background tracking based on device and battery settings.',
              textAlign: TextAlign.center,
              style: TextStyle(
                fontSize: 12,
                color: colorScheme.onSurfaceVariant,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class JourneyScreen extends StatefulWidget {
  const JourneyScreen({
    super.key,
    required this.place,
    required this.radius,
    required this.onStop,
    required this.onDismiss,
  });
  final Place place;
  final int radius;
  final Future<void> Function() onStop, onDismiss;
  @override
  State<JourneyScreen> createState() => _JourneyScreenState();
}

class _JourneyScreenState extends State<JourneyScreen> {
  Map<Object?, Object?> status = {};
  StreamSubscription<Object?>? events;
  @override
  void initState() {
    super.initState();
    _read();
    events = eventChannel.receiveBroadcastStream().listen((event) {
      if (event is Map && mounted) {
        setState(() => status = Map<Object?, Object?>.from(event));
      }
    });
  }

  Future<void> _read() async {
    try {
      final s = await serviceChannel.invokeMapMethod<Object?, Object?>(
        'getStatus',
      );
      if (mounted && s != null) setState(() => status = s);
    } catch (_) {}
  }

  @override
  void dispose() {
    events?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).extension<AppThemeColors>()!;
    final colorScheme = Theme.of(context).colorScheme;
    final currentLat = (status['currentLatitude'] as num?)?.toDouble(),
        currentLon = (status['currentLongitude'] as num?)?.toDouble();
    final current = currentLat != null && currentLon != null
        ? LatLng(currentLat, currentLon)
        : null;
    final arrived = status['arrived'] == true;
    return Scaffold(
      appBar: AppBar(title: Text(arrived ? 'Arrival alarm' : 'Active journey')),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(20),
          children: [
            SizedBox(
              height: 320,
              child: ClipRRect(
                borderRadius: BorderRadius.circular(22),
                child: FlutterMap(
                  options: MapOptions(
                    initialCenter: widget.place.point,
                    initialZoom: 13,
                  ),
                  children: [
                    TileLayer(
                      urlTemplate:
                          'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
                      userAgentPackageName: 'com.destinationalarm.placesearch',
                    ),
                    MarkerLayer(
                      markers: [
                        Marker(
                          point: widget.place.point,
                          width: 44,
                          height: 44,
                          child: const Icon(
                            Icons.location_pin,
                            color: Color(0xFFB54836),
                            size: 42,
                          ),
                        ),
                        if (current != null)
                          Marker(
                            point: current,
                            width: 42,
                            height: 42,
                            child: const Icon(
                              Icons.my_location_rounded,
                              color: Color(0xFF456B85),
                              size: 28,
                            ),
                          ),
                      ],
                    ),
                    const RichAttributionWidget(
                      attributions: [
                        TextSourceAttribution('© OpenStreetMap contributors'),
                      ],
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 18),
            Card(
              color: arrived ? colors.highlight : colors.primaryAction,
              child: Padding(
                padding: const EdgeInsets.all(18),
                child: Row(
                  children: [
                    Icon(
                      arrived
                          ? Icons.notifications_active_rounded
                          : Icons.navigation_rounded,
                      size: 26,
                      color: arrived
                          ? colorScheme.onSecondary
                          : colorScheme.onPrimary,
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            arrived
                                ? 'You’ve arrived!'
                                : (status['tracking'] == true
                                      ? 'Journey in progress'
                                      : 'Waiting for location'),
                            style: TextStyle(
                              color: arrived
                                  ? colorScheme.onSecondary
                                  : colorScheme.onPrimary,
                            ),
                          ),
                          Text(
                            widget.place.name,
                            style: TextStyle(
                              fontWeight: FontWeight.w800,
                              color: arrived
                                  ? colorScheme.onSecondary
                                  : colorScheme.onPrimary,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            ),
            Card(
              child: Padding(
                padding: const EdgeInsets.all(18),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceAround,
                  children: [
                    Column(
                      children: [
                        Text(
                          'REMAINING',
                          style: TextStyle(
                            fontSize: 11,
                            color: colorScheme.onSurfaceVariant,
                          ),
                        ),
                        const SizedBox(height: 5),
                        Text(
                          formatDistance(
                            (status['remainingMetres'] as num?)?.toDouble(),
                          ),
                          style: const TextStyle(
                            fontSize: 22,
                            fontWeight: FontWeight.w800,
                          ),
                        ),
                      ],
                    ),
                    Column(
                      children: [
                        Text(
                          'ALERT RADIUS',
                          style: TextStyle(
                            fontSize: 11,
                            color: colorScheme.onSurfaceVariant,
                          ),
                        ),
                        const SizedBox(height: 5),
                        Text(
                          formatDistance(widget.radius.toDouble()),
                          style: const TextStyle(
                            fontSize: 22,
                            fontWeight: FontWeight.w800,
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
            if (status['error'] is String)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 10),
                child: Text(
                  status['error'] as String,
                  style: TextStyle(color: colorScheme.error),
                ),
              ),
            const SizedBox(height: 10),
            if (arrived) ...[
              ElevatedButton.icon(
                onPressed: widget.onDismiss,
                icon: const Icon(Icons.volume_off_rounded),
                label: const Text('Dismiss Alarm'),
              ),
              const SizedBox(height: 10),
            ],
            OutlinedButton.icon(
              onPressed: widget.onStop,
              icon: const Icon(Icons.stop_circle_outlined),
              label: const Text('Stop Journey'),
              style: OutlinedButton.styleFrom(
                minimumSize: const Size.fromHeight(54),
                foregroundColor: colorScheme.onSurface,
                side: BorderSide(color: colorScheme.outline),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(16),
                ),
              ),
            ),
            const SizedBox(height: 12),
            Text(
              'Map data © OpenStreetMap contributors · Updates depend on GPS signal and device settings.',
              textAlign: TextAlign.center,
              style: TextStyle(
                fontSize: 11,
                color: colorScheme.onSurfaceVariant,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _MiniMap extends StatelessWidget {
  const _MiniMap({required this.place});
  final Place place;
  @override
  Widget build(BuildContext context) => SizedBox(
    height: 230,
    child: ClipRRect(
      borderRadius: BorderRadius.circular(22),
      child: FlutterMap(
        options: MapOptions(initialCenter: place.point, initialZoom: 14),
        children: [
          TileLayer(
            urlTemplate: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
            userAgentPackageName: 'com.destinationalarm.placesearch',
          ),
          MarkerLayer(
            markers: [
              Marker(
                point: place.point,
                width: 44,
                height: 44,
                child: const Icon(
                  Icons.location_pin,
                  color: Color(0xFFB54836),
                  size: 42,
                ),
              ),
            ],
          ),
          const RichAttributionWidget(
            attributions: [
              TextSourceAttribution('© OpenStreetMap contributors'),
            ],
          ),
        ],
      ),
    ),
  );
}

class _ThemeSelector extends StatelessWidget {
  const _ThemeSelector();

  static const _choices = [
    (ThemeMode.light, 'Light'),
    (ThemeMode.dark, 'Dark'),
    (ThemeMode.system, 'System'),
  ];

  Future<void> _select(
    BuildContext context,
    ThemePreferenceController controller,
    ThemeMode mode,
  ) async {
    try {
      await controller.setThemeMode(mode);
    } catch (_) {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text(
            'Theme changed, but the choice could not be saved for next time.',
          ),
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final controller = ThemePreferenceScope.of(context);
    final selectedMode = controller.themeMode;
    final colors = Theme.of(context).extension<AppThemeColors>()!;
    final colorScheme = Theme.of(context).colorScheme;

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Appearance',
              style: TextStyle(fontWeight: FontWeight.w800, fontSize: 16),
            ),
            const SizedBox(height: 6),
            Text(
              'Choose Light, Dark, or System. System follows your device appearance.',
              style: TextStyle(
                color: colorScheme.onSurfaceVariant,
                height: 1.4,
              ),
            ),
            const SizedBox(height: 12),
            Wrap(
              spacing: 8,
              children: [
                for (final (mode, label) in _choices)
                  Semantics(
                    label: '$label theme',
                    selected: selectedMode == mode,
                    inMutuallyExclusiveGroup: true,
                    child: ChoiceChip(
                      label: Text(
                        label,
                        style: TextStyle(
                          color: selectedMode == mode
                              ? colorScheme.onSecondary
                              : colorScheme.onSurface,
                        ),
                      ),
                      selected: selectedMode == mode,
                      selectedColor: colors.highlight,
                      onSelected: (_) =>
                          unawaited(_select(context, controller, mode)),
                    ),
                  ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});
  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  bool _testRequested = false;
  bool _testPlaying = false;
  bool _busy = false;
  String? _alarmError;
  Timer? _statusTimer;

  Future<void> _startTestAlarm() async {
    if (_busy || _testRequested) return;
    setState(() {
      _busy = true;
      _alarmError = null;
    });
    try {
      await serviceChannel.invokeMethod<bool>('testAlarm');
      if (!mounted) return;
      setState(() {
        _testRequested = true;
        _busy = false;
      });
      _statusTimer?.cancel();
      _statusTimer = Timer.periodic(
        const Duration(milliseconds: 500),
        (_) => _readAlarmStatus(),
      );
      unawaited(_readAlarmStatus());
    } on PlatformException catch (error) {
      if (mounted) {
        setState(() {
          _busy = false;
          _alarmError =
              error.message ?? 'Android could not start the alarm test.';
        });
      }
    } catch (error) {
      if (mounted) {
        setState(() {
          _busy = false;
          _alarmError = 'Could not start the alarm test: $error';
        });
      }
    }
  }

  Future<void> _readAlarmStatus() async {
    try {
      final state = await serviceChannel.invokeMapMethod<Object?, Object?>(
        'getStatus',
      );
      if (!mounted || state == null) return;
      final error = state['error'] as String?;
      setState(() {
        _testPlaying =
            state['testAlarmActive'] == true && state['alarmSounding'] == true;
        if (error != null && error.isNotEmpty) _alarmError = error;
      });
      if (error != null && error.isNotEmpty) _statusTimer?.cancel();
    } on PlatformException catch (error) {
      if (mounted) {
        setState(
          () => _alarmError =
              error.message ?? 'Could not read alarm playback status.',
        );
      }
      _statusTimer?.cancel();
    }
  }

  Future<void> _stopTestAlarm() async {
    _statusTimer?.cancel();
    try {
      await serviceChannel.invokeMethod<void>('stopTestAlarm');
      if (mounted) {
        setState(() {
          _testRequested = false;
          _testPlaying = false;
        });
      }
    } on PlatformException catch (error) {
      if (mounted) {
        setState(
          () => _alarmError = error.message ?? 'Could not stop alarm playback.',
        );
      }
    } catch (error) {
      if (mounted) {
        setState(() => _alarmError = 'Could not stop alarm playback: $error');
      }
    }
  }

  @override
  void dispose() {
    _statusTimer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(title: const Text('Settings and About')),
      body: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          const _ThemeSelector(),
          const Card(
            child: ListTile(
              leading: Icon(Icons.location_on_outlined),
              title: Text('Location permission'),
              subtitle: Text(
                'Allow location while using the app before a journey. Android’s foreground service uses this access while your screen is off.',
              ),
            ),
          ),
          const Card(
            child: ListTile(
              leading: Icon(Icons.notifications_outlined),
              title: Text('Notifications'),
              subtitle: Text(
                'Notifications must be enabled for the persistent journey status and arrival alarm controls.',
              ),
            ),
          ),
          const Card(
            child: ListTile(
              leading: Icon(Icons.battery_saver_outlined),
              title: Text('Background tracking'),
              subtitle: Text(
                'Some Android devices restrict background location to save battery. If updates pause, allow unrestricted battery use for this app in system settings. Background tracking is subject to Android and device restrictions.',
              ),
            ),
          ),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    'Test Alarm',
                    style: TextStyle(fontWeight: FontWeight.w800, fontSize: 16),
                  ),
                  const SizedBox(height: 6),
                  Text(
                    _testPlaying
                        ? 'Alarm audio is playing. It will loop until stopped.'
                        : (_testRequested
                              ? 'Starting the alarm audio…'
                              : 'Play the bundled alarm sound without starting or changing a journey. Device alarm volume controls loudness.'),
                    style: const TextStyle(height: 1.4),
                  ),
                  const SizedBox(height: 12),
                  Row(
                    children: [
                      Expanded(
                        child: ElevatedButton.icon(
                          onPressed: _busy || _testRequested
                              ? null
                              : _startTestAlarm,
                          icon: _busy
                              ? const SizedBox(
                                  width: 18,
                                  height: 18,
                                  child: CircularProgressIndicator(
                                    strokeWidth: 2,
                                  ),
                                )
                              : const Icon(Icons.volume_up_rounded),
                          label: const Text('Test Alarm'),
                        ),
                      ),
                      if (_testRequested) ...[
                        const SizedBox(width: 10),
                        OutlinedButton(
                          onPressed: _stopTestAlarm,
                          child: const Text('Stop Test'),
                        ),
                      ],
                    ],
                  ),
                  if (_alarmError != null) ...[
                    const SizedBox(height: 8),
                    Text(
                      _alarmError!,
                      style: TextStyle(color: colorScheme.error),
                    ),
                  ],
                ],
              ),
            ),
          ),
          const Card(
            child: ListTile(
              leading: Icon(Icons.search_rounded),
              title: Text('Place search'),
              subtitle: Text(
                'Photon search service, powered by OpenStreetMap. Searches begin after three characters and are shared with the public Photon service.',
              ),
            ),
          ),
          const Card(
            child: ListTile(
              leading: Icon(Icons.map_outlined),
              title: Text('Map data'),
              subtitle: Text(
                '© OpenStreetMap contributors. Map tiles are loaded on demand from the OpenStreetMap tile service.',
              ),
            ),
          ),
          const Card(
            child: ListTile(
              leading: Icon(Icons.tune_rounded),
              title: Text('Arrival radius'),
              subtitle: Text(
                'Choose 500 m, 1 km, 2 km, or 5 km for each destination.',
              ),
            ),
          ),
          const Card(
            child: ListTile(
              leading: Icon(Icons.info_outline_rounded),
              title: Text('Destination Alarm Search'),
              subtitle: Text(
                'A standalone destination alarm. Background operation is not guaranteed on every Android device.',
              ),
            ),
          ),
        ],
      ),
    );
  }
}
