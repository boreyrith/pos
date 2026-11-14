import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:pos_mobile/core/printer_discovery.dart';

void main() {
  test('tries the rest of each Wi-Fi/LAN network, not mobile data, VPN or public addresses', () {
    final hosts = subnetHosts([
      ('en0', '192.168.0.106'),
      ('pdp_ip0', '10.20.30.40'),
      ('utun3', '10.8.0.2'),
      ('lo0', '127.0.0.1'),
      ('en5', '8.8.4.4'),
    ]);
    expect(hosts, hasLength(253));
    expect(hosts, containsAll(['192.168.0.1', '192.168.0.100', '192.168.0.254']));
    expect(hosts, isNot(contains('192.168.0.106'))); // this device
    expect(hosts.where((h) => !h.startsWith('192.168.0.')), isEmpty);
  });

  test('two networks at once (Wi-Fi and Ethernet)', () {
    final hosts = subnetHosts([('en0', '192.168.1.20'), ('en1', '10.0.5.7'), ('eth0', '172.16.3.9')]);
    expect(hosts, hasLength(3 * 253));
  });

  test('finds what answers on the printer port, in address order', () async {
    final printer = await ServerSocket.bind(InternetAddress.loopbackIPv4, 0);
    printer.listen((c) => c.destroy());
    final found = await probe(['127.0.0.3', '127.0.0.1', '127.0.0.2'], printer.port);
    await printer.close();
    // Only 127.0.0.1 is listening (the others are unanswered loopback addresses).
    expect(found, ['127.0.0.1']);
  });
}
