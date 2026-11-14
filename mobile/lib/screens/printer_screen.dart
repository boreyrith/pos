import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../core/escpos.dart';
import '../core/i18n.dart';
import '../core/printer_discovery.dart';
import '../core/receipt_printer.dart';
import '../state/data.dart';
import '../state/printer.dart';
import '../widgets/common.dart';

/// This device's receipt printer: a Rongta (ESC/POS) printer on Wi-Fi or LAN, found by searching the
/// network or entered by address, or the system dialog.
class PrinterScreen extends ConsumerStatefulWidget {
  const PrinterScreen({super.key});

  @override
  ConsumerState<PrinterScreen> createState() => _PrinterScreenState();
}

class _PrinterScreenState extends ConsumerState<PrinterScreen> {
  late final PrinterSettings _saved = ref.read(printerProvider);
  late bool _enabled = _saved.enabled;
  late bool _autoPrint = _saved.autoPrint;
  late final _host = TextEditingController(text: _saved.host);
  late final _port = TextEditingController(text: '${_saved.port}');
  bool _busy = false;
  Object? _error;

  /// The last search; null before any.
  PrinterSearch? _search;
  bool _searching = false;

  @override
  void dispose() {
    _host.dispose();
    _port.dispose();
    super.dispose();
  }

  /// The settings as entered, or null (with the error shown) when they can't be used.
  PrinterSettings? _read() {
    final host = _host.text.trim();
    final port = int.tryParse(_port.text.trim());
    String? error;
    if (_enabled && host.isEmpty) error = t("Enter the printer's IP address.");
    if (_enabled && (port == null || port < 1 || port > 65535)) error = t('Enter a port between 1 and 65535.');
    setState(() => _error = error);
    if (error != null) return null;
    return PrinterSettings(enabled: _enabled, host: host, port: port ?? 9100, autoPrint: _autoPrint);
  }

  Future<void> _save() async {
    final settings = _read();
    if (settings == null) return;
    await ref.read(printerProvider.notifier).save(settings);
    if (mounted) {
      showSnack(context, t('Printer settings saved'));
      Navigator.pop(context);
    }
  }

  Future<void> _find() async {
    final port = int.tryParse(_port.text.trim()) ?? 9100;
    setState(() {
      _searching = true;
      _error = null;
    });
    try {
      final search = await findPrinters(port: port);
      if (mounted) setState(() => _search = search);
    } catch (e) {
      if (mounted) setState(() => _error = e);
    } finally {
      if (mounted) setState(() => _searching = false);
    }
  }

  /// Uses a printer from the search and saves it, so the next receipt goes there.
  Future<void> _connect(String host) async {
    _host.text = host;
    final settings = _read();
    if (settings == null) return;
    await ref.read(printerProvider.notifier).save(settings);
    if (mounted) showSnack(context, t('Connected to {address}', {'address': '$host:${settings.port}'}));
  }

  Future<void> _test() async {
    final settings = _read();
    if (settings == null) return;
    final store = ref.read(storeProvider).value;
    setState(() => _busy = true);
    try {
      await sendToPrinter(settings.host, settings.port, testPage([
        if (store != null) store.name,
        'Test print',
        if (store != null) 'Paper ${store.receiptWidth}mm',
      ]));
      if (mounted) showSnack(context, t('Sent to the printer.'));
    } catch (e) {
      if (mounted) setState(() => _error = e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final width = ref.watch(storeProvider).value?.receiptWidth ?? 80;
    final search = _search;
    return Scaffold(
      appBar: AppBar(title: Text(t('Receipt printer'))),
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 480),
              child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  title: Text(t('Print to a Rongta printer (Wi-Fi/LAN)')),
                  subtitle: Text(_enabled ? t('Receipts print on {width}mm paper (Settings > Company on the web).', {'width': width})
                      : t("Off: the device's print dialog is used.")),
                  value: _enabled,
                  onChanged: (v) => setState(() => _enabled = v),
                ),
                if (_enabled) ...[
                  const SizedBox(height: 8),
                  OutlinedButton.icon(
                    icon: _searching
                        ? const SizedBox.square(dimension: 18, child: CircularProgressIndicator(strokeWidth: 2))
                        : const Icon(Icons.wifi_find_outlined),
                    label: Text(_searching ? t('Searching the network…') : t('Search for printers')),
                    onPressed: _searching || _busy ? null : _find,
                  ),
                  if (search != null && !_searching) ...[
                    const SizedBox(height: 8),
                    if (search.networks.isEmpty)
                      Text(t("This device isn't on a Wi-Fi or LAN network. Connect it to the printer's Wi-Fi and search again."),
                          style: TextStyle(color: Theme.of(context).colorScheme.error))
                    else
                      Text(t('Searched {networks}', {'networks': search.networks.join(', ')}),
                          style: TextStyle(color: Theme.of(context).colorScheme.onSurfaceVariant)),
                    const SizedBox(height: 8),
                    if (search.networks.isNotEmpty && search.found.isEmpty)
                      Text(
                        t('No printers found. Check that the printer is on the same Wi-Fi, and that POS may use the local network (Settings > POS > Local Network).'),
                        style: TextStyle(color: Theme.of(context).colorScheme.onSurfaceVariant),
                      )
                    else if (search.found.isNotEmpty)
                      Card(
                        margin: EdgeInsets.zero,
                        child: Column(children: [
                          for (final host in search.found)
                            ListTile(
                              leading: const Icon(Icons.print_outlined),
                              title: Text(host),
                              subtitle: Text(t('Port {port}', {'port': _port.text.trim()})),
                              trailing: ref.watch(printerProvider).ready && ref.watch(printerProvider).host == host
                                  ? Icon(Icons.check_circle, color: Theme.of(context).colorScheme.primary)
                                  : Text(t('Connect'), style: TextStyle(color: Theme.of(context).colorScheme.primary)),
                              onTap: _busy ? null : () => _connect(host),
                            ),
                        ]),
                      ),
                  ],
                  const SizedBox(height: 16),
                  TextField(
                    controller: _host,
                    keyboardType: TextInputType.url,
                    autocorrect: false,
                    decoration: InputDecoration(labelText: t('Printer IP address'), hintText: t('e.g. 192.168.1.100')),
                  ),
                  const SizedBox(height: 12),
                  TextField(
                    controller: _port,
                    keyboardType: TextInputType.number,
                    decoration: InputDecoration(labelText: t('Port'), helperText: t('Usually 9100')),
                  ),
                  const SizedBox(height: 8),
                  SwitchListTile(
                    contentPadding: EdgeInsets.zero,
                    title: Text(t('Print the receipt after each sale')),
                    value: _autoPrint,
                    onChanged: (v) => setState(() => _autoPrint = v),
                  ),
                  const SizedBox(height: 8),
                  OutlinedButton.icon(
                    icon: const Icon(Icons.print_outlined),
                    label: Text(t('Test print')),
                    onPressed: _busy ? null : _test,
                  ),
                ],
                const SizedBox(height: 16),
                ErrorNote(_error),
                if (_error != null) const SizedBox(height: 16),
                FilledButton(onPressed: _busy ? null : _save, child: Text(t('Save'))),
              ]),
            ),
          ),
        ),
      ),
    );
  }
}
