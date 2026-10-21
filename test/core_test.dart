import 'package:destination_alarm_search/main.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('Photon GeoJSON coordinates are longitude then latitude', () {
    final results = parsePhotonResponse('''{
      "features": [{"geometry":{"coordinates":[77.5946,12.9716],"type":"Point"},
      "properties":{"name":"Bengaluru","city":"Bengaluru","state":"Karnataka","country":"India"},"type":"Feature"}]
    }''');
    expect(results, hasLength(1));
    expect(results.single.latitude, 12.9716);
    expect(results.single.longitude, 77.5946);
    expect(results.single.address, contains('India'));
  });

  test('Photon parsing returns no more than six results', () {
    final features = List.generate(8, (i) => '{"geometry":{"coordinates":[$i,$i]},"properties":{"name":"Place $i"}}').join(',');
    expect(parsePhotonResponse('{"features":[$features]}'), hasLength(6));
  });

  test('distance calculation is near zero for identical coordinates', () {
    expect(distanceMetres(12.9716, 77.5946, 12.9716, 77.5946), closeTo(0, 0.001));
  });

  test('distance calculation handles a known one degree arc', () {
    expect(distanceMetres(0, 0, 0, 1), closeTo(111195, 100));
  });

  test('arrival requires two accurate fixes inside the selected radius', () {
    final gate = ArrivalRadiusGate(500);
    expect(gate.update(distance: 400, accuracy: 30), isFalse);
    expect(gate.update(distance: 400, accuracy: 30), isTrue);
    expect(gate.update(distance: 10, accuracy: 5), isFalse);
  });

  test('arrival accepts realistic Samsung GPS accuracy within a 1 km radius', () {
    final gate = ArrivalRadiusGate(1000);
    expect(gate.update(distance: 28.7, accuracy: 98.4), isFalse);
    expect(gate.update(distance: 26.5, accuracy: 148.6), isTrue);
  });

  test('one temporarily inaccurate fix preserves arrival confirmation', () {
    final gate = ArrivalRadiusGate(500);
    expect(gate.update(distance: 400, accuracy: 20), isFalse);
    expect(gate.update(distance: 300, accuracy: 600), isFalse);
    expect(gate.update(distance: 400, accuracy: 20), isTrue);
  });

  test('fresh accurate fix outside the selected radius resets confirmation', () {
    final gate = ArrivalRadiusGate(500);
    expect(gate.update(distance: 400, accuracy: 20), isFalse);
    expect(gate.update(distance: 510, accuracy: 20), isFalse);
    expect(gate.update(distance: 400, accuracy: 20), isFalse);
    expect(gate.update(distance: 400, accuracy: 20), isTrue);
  });

  test('invalid distance resets arrival confirmation safely', () {
    final gate = ArrivalRadiusGate(500);
    expect(gate.update(distance: 400, accuracy: 20), isFalse);
    expect(gate.update(distance: double.nan, accuracy: 20), isFalse);
    expect(gate.update(distance: 400, accuracy: 20), isFalse);
    expect(gate.update(distance: 400, accuracy: 20), isTrue);
  });
}
