import 'package:flutter_test/flutter_test.dart';
import 'package:mycrmsync/core/config.dart';

void main() {
  test('API base URL is configured', () {
    expect(AppConfig.apiBaseUrl, contains('mycrmsync'));
  });
}
