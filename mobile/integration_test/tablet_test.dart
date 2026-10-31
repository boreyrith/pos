import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:pos_mobile/main.dart' as app;

/// Runs the real app on a tablet against a dev server: signs in, adds items and checks the cart sits
/// beside the catalog. A layout overflow fails it. It waits on each screen so screenshots can be taken.
/// Run with `flutter test integration_test/tablet_test.dart -d DEVICE --dart-define=TEST_USER=... --dart-define=TEST_PASSWORD=...`.
const _user = String.fromEnvironment('TEST_USER');
const _password = String.fromEnvironment('TEST_PASSWORD');
const _hold = Duration(seconds: int.fromEnvironment('HOLD_SECONDS', defaultValue: 0));

Future<void> _wait(WidgetTester tester, [int seconds = 4]) async {
  for (var i = 0; i < seconds * 10; i++) {
    await tester.pump(const Duration(milliseconds: 100));
  }
}

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('tablet layout', (tester) async {
    app.main();
    await _wait(tester, 6);

    if (find.text('Sign in').evaluate().isNotEmpty) {
      await tester.enterText(find.byType(TextField).at(0), _user);
      await tester.enterText(find.byType(TextField).at(1), _password);
      await tester.tap(find.text('Sign in').last);
      await _wait(tester, 6);
    }

    await tester.tap(find.text('Baby Powder 200g'));
    await _wait(tester, 2);
    await tester.tap(find.text('Baby Wipes 80 Sheets'));
    await _wait(tester, 3);

    expect(find.byType(NavigationRail), findsOneWidget, reason: 'tablets get the side rail');
    expect(find.textContaining('Charge'), findsOneWidget, reason: 'the cart sits beside the catalog');

    // ignore: avoid_print
    print('SCREEN sell');
    await _wait(tester, _hold.inSeconds);

    await tester.tap(find.textContaining('Charge'));
    await _wait(tester, 4);
    // ignore: avoid_print
    print('SCREEN payment');
    await _wait(tester, _hold.inSeconds);
  });
}
