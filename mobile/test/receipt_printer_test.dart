import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:pos_mobile/core/receipt_printer.dart';

void main() {
  test('sends every byte to a network printer', () async {
    final printer = await ServerSocket.bind(InternetAddress.loopbackIPv4, 0);
    final received = <int>[];
    final done = printer.first.then((client) => client.listen(received.addAll).asFuture<void>());
    final bytes = Uint8List.fromList(List.generate(100000, (i) => i % 251));
    await sendToPrinter('127.0.0.1', printer.port, bytes);
    await done;
    await printer.close();
    expect(received, bytes);
  });

  test('says which printer it could not reach', () async {
    final unused = await ServerSocket.bind(InternetAddress.loopbackIPv4, 0);
    final port = unused.port;
    await unused.close();
    expect(
      () => sendToPrinter('127.0.0.1', port, Uint8List(1)),
      throwsA(predicate((e) => '$e'.contains("Couldn't reach the printer at 127.0.0.1:$port"))),
    );
  });
}
