import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:pos_mobile/main.dart' as app;
import 'package:shared_preferences/shared_preferences.dart';

/// Runs the whole selling flow in the real app against a dev server, on a phone or a tablet. It makes
/// real orders, so never point it at a live store. Needs a login that may discount items and a store
/// with KHQR in test mode. Run with
/// `flutter test integration_test/sale_flow_test.dart -d DEVICE --dart-define=TEST_USER=... --dart-define=TEST_PASSWORD=...`.
const _user = String.fromEnvironment('TEST_USER');
const _password = String.fromEnvironment('TEST_PASSWORD');

Future<void> _wait(WidgetTester tester, [int seconds = 3]) async {
  for (var i = 0; i < seconds * 10; i++) {
    await tester.pump(const Duration(milliseconds: 100));
  }
}

Future<void> _tap(WidgetTester tester, Finder finder, [int seconds = 3]) async {
  await tester.ensureVisible(finder);
  await tester.pump();
  await tester.tap(finder);
  await _wait(tester, seconds);
}

/// Phones keep the cart behind a bar; tablets show it all the time.
Future<void> _openCart(WidgetTester tester) async {
  final bar = find.text('View cart');
  if (bar.evaluate().isNotEmpty) await _tap(tester, bar);
}

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('sell, hold, KHQR, orders, Khmer, sign out', (tester) async {
    // Starts with no saved cart.
    await (await SharedPreferences.getInstance()).clear();
    app.main();
    await _wait(tester, 6);

    if (find.text('More').evaluate().isNotEmpty) {
      await _tap(tester, find.text('More').first);
      await _tap(tester, find.text('Sign out'), 4);
    }
    await tester.enterText(find.byType(TextField).at(0), _user);
    await tester.enterText(find.byType(TextField).at(1), _password);
    await _tap(tester, find.text('Sign in').last, 6);

    // An item discount of 10%, then a cash sale.
    await _tap(tester, find.text('Baby Wipes 80 Sheets').first);
    await _openCart(tester);
    await _tap(tester, find.text('Baby Wipes 80 Sheets').last);
    await tester.enterText(find.widgetWithText(TextField, '0'), '10');
    await _tap(tester, find.text('Done'));
    expect(find.text('Charge \$2.25'), findsOneWidget, reason: '10% off \$2.50');
    await _tap(tester, find.text('Charge \$2.25'), 4);
    await _tap(tester, find.text('Complete sale'), 5);
    expect(find.text('Sale complete'), findsOneWidget);
    expect(find.text('TOTAL'), findsOneWidget);
    expect(find.text('Print receipt'), findsOneWidget);
    await _tap(tester, find.text('New sale'));

    // The sale is in today's orders.
    await _tap(tester, find.text('Orders').first, 4);
    expect(find.text('\$2.25'), findsWidgets);
    await _tap(tester, find.text('Sell').first);

    // Hold a sale, resume it, and pay by KHQR.
    await tester.enterText(find.byType(TextField).first, 'Baby Powder');
    await _wait(tester);
    await _tap(tester, find.text('Baby Powder 200g').first);
    await _openCart(tester);
    await _tap(tester, find.text('Hold'));
    await tester.enterText(find.byType(TextField).last, 'Flow test');
    await _tap(tester, find.text('Hold').last, 4);
    await _tap(tester, find.text('Held sales').first, 4);
    expect(find.textContaining('Flow test'), findsOneWidget);
    await _tap(tester, find.textContaining('Flow test'), 5);
    await _openCart(tester);
    await _tap(tester, find.text('Charge \$4.50'), 4);
    await _tap(tester, find.text('KHQR'));
    await _tap(tester, find.text('Show QR code'), 5);
    expect(find.text('Waiting for payment…'), findsOneWidget);
    await _tap(tester, find.text('Mark as paid (test)'), 7);
    expect(find.text('Sale complete'), findsOneWidget);
    await _tap(tester, find.text('New sale'));

    // Khmer and back, then sign out.
    await _tap(tester, find.text('More').first);
    await _tap(tester, find.text('ខ្មែរ'), 4);
    expect(find.text('ភាសា'), findsOneWidget);
    await _tap(tester, find.text('EN'), 4);
    expect(find.text('Language'), findsOneWidget);
    await _tap(tester, find.text('Sign out'), 4);
    expect(find.text('Sign in'), findsWidgets);
  });
}
