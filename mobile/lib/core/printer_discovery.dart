import 'dart:io';

/// What a search looked at and found.
class PrinterSearch {
  const PrinterSearch(this.networks, this.found);

  /// The networks searched, e.g. 192.168.0.x; empty when the device isn't on Wi-Fi or LAN.
  final List<String> networks;
  final List<String> found;
}

/// Finds network receipt printers (Rongta and other ESC/POS ones): every address in this device's
/// Wi-Fi/LAN networks is tried on the printer port. Many such printers don't announce themselves
/// (Bonjour), so asking each address is what finds them.
Future<PrinterSearch> findPrinters({int port = 9100}) async {
  final interfaces = await NetworkInterface.list(type: InternetAddressType.IPv4);
  final local = [
    for (final i in interfaces)
      for (final a in i.addresses) (i.name, a.address),
  ];
  final hosts = subnetHosts(local);
  final networks = {for (final h in hosts) '${h.substring(0, h.lastIndexOf('.'))}.x'}.toList();
  return PrinterSearch(networks, await probe(hosts, port));
}

/// The other addresses (x.x.x.1 to 254) in the private /24 networks of the given (interface, address)
/// pairs. Mobile data, VPN and loopback interfaces are skipped: printers aren't there.
List<String> subnetHosts(List<(String, String)> local) {
  final own = {for (final (_, address) in local) address};
  final hosts = <String>{};
  for (final (name, address) in local) {
    if (RegExp(r'^(lo|pdp_ip|rmnet|utun|ipsec|tun|ppp)').hasMatch(name) || !_private(address)) continue;
    final prefix = address.substring(0, address.lastIndexOf('.'));
    for (var n = 1; n <= 254; n++) {
      if (!own.contains('$prefix.$n')) hosts.add('$prefix.$n');
    }
  }
  return hosts.toList();
}

bool _private(String address) {
  final o = address.split('.').map(int.parse).toList();
  return o[0] == 10 || (o[0] == 172 && o[1] >= 16 && o[1] <= 31) || (o[0] == 192 && o[1] == 168);
}

/// The [hosts] that accept a connection on [port], in address order. Many are tried at once, each for
/// [timeout] (Wi-Fi printers can be slow to answer), so a whole network takes about five seconds.
Future<List<String>> probe(Iterable<String> hosts, int port,
    {Duration timeout = const Duration(milliseconds: 1500), int parallel = 64}) async {
  final queue = hosts.toList();
  final found = <String>[];
  Future<void> worker() async {
    while (queue.isNotEmpty) {
      final host = queue.removeLast();
      try {
        final socket = await Socket.connect(host, port, timeout: timeout);
        socket.destroy();
        found.add(host);
      } on Object {
        // Nothing there, or not a printer.
      }
    }
  }

  await Future.wait([for (var i = 0; i < parallel; i++) worker()]);
  int last(String h) => int.parse(h.substring(h.lastIndexOf('.') + 1));
  return found..sort((a, b) => a.substring(0, a.lastIndexOf('.')) == b.substring(0, b.lastIndexOf('.')) ? last(a) - last(b) : a.compareTo(b));
}
