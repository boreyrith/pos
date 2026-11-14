import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'session.dart';

/// This device's receipt printer (More > Receipt printer). Each till has its own, so it is kept on
/// the device, not on the server. Off: the system print dialog (AirPrint, Android print service).
class PrinterSettings {
  const PrinterSettings({this.enabled = false, this.host = '', this.port = 9100, this.autoPrint = false});

  /// Print straight to a Rongta (ESC/POS) printer over Wi-Fi or LAN.
  final bool enabled;
  final String host;
  final int port;

  /// Print the receipt as soon as a sale is paid.
  final bool autoPrint;

  bool get ready => enabled && host.isNotEmpty;

  Map<String, dynamic> toJson() => {'enabled': enabled, 'host': host, 'port': port, 'autoPrint': autoPrint};

  factory PrinterSettings.fromJson(Map<String, dynamic> j) => PrinterSettings(
        enabled: j['enabled'] as bool? ?? false,
        host: j['host'] as String? ?? '',
        port: j['port'] as int? ?? 9100,
        autoPrint: j['autoPrint'] as bool? ?? false,
      );
}

class PrinterNotifier extends Notifier<PrinterSettings> {
  static const _key = 'receiptPrinter';

  @override
  PrinterSettings build() {
    final raw = ref.watch(prefsProvider).getString(_key);
    return raw == null ? const PrinterSettings() : PrinterSettings.fromJson(jsonDecode(raw) as Map<String, dynamic>);
  }

  Future<void> save(PrinterSettings settings) async {
    await ref.read(prefsProvider).setString(_key, jsonEncode(settings.toJson()));
    state = settings;
  }
}

final printerProvider = NotifierProvider<PrinterNotifier, PrinterSettings>(PrinterNotifier.new);
