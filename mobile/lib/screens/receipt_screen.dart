import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../api/models.dart';
import '../core/i18n.dart';
import '../core/money.dart';
import '../core/receipt_pdf.dart';
import '../core/receipt_printer.dart';
import '../state/data.dart';
import '../state/printer.dart';
import '../state/session.dart';
import '../widgets/common.dart';
import '../widgets/printable_receipt.dart';

/// An order as a receipt, ready to print or share. [justSold]: shown straight after the sale, with
/// the change to hand over (and printed at once if this device's Rongta printer is set to).
class ReceiptScreen extends ConsumerStatefulWidget {
  const ReceiptScreen({super.key, required this.order, this.justSold = false});

  final Order order;
  final bool justSold;

  @override
  ConsumerState<ReceiptScreen> createState() => _ReceiptScreenState();
}

class _ReceiptScreenState extends ConsumerState<ReceiptScreen> {
  final _receipt = GlobalKey();
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    final printer = ref.read(printerProvider);
    if (widget.justSold && widget.order.isPaid && printer.ready && printer.autoPrint) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        final store = ref.read(storeProvider).value;
        if (mounted && store != null) _print(store);
      });
    }
  }

  /// Prints on this device's Rongta printer when one is set up, otherwise through the print dialog.
  Future<void> _print(StoreSettings store) {
    final printer = ref.read(printerProvider);
    if (!printer.ready) return _output(printReceipt, store);
    return _send(store, printer);
  }

  Future<void> _send(StoreSettings store, PrinterSettings printer) async {
    setState(() => _busy = true);
    try {
      final boundary = await _drawn(store);
      await sendToPrinter(printer.host, printer.port, await receiptForPrinter(boundary, store.receiptWidth));
    } catch (e) {
      if (mounted) showSnack(context, errorText(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// The receipt on screen, once what it shows from the network (the logo) is drawn.
  Future<RenderRepaintBoundary> _drawn(StoreSettings store) async {
    await precacheReceipt(context, ref, store);
    // A frame, so a logo that just arrived is drawn.
    await WidgetsBinding.instance.endOfFrame;
    return _receipt.currentContext!.findRenderObject() as RenderRepaintBoundary;
  }

  /// Captures the receipt on screen, then hands it to [action].
  Future<void> _output(Future<void> Function(ReceiptImage receipt, String name) action, StoreSettings store) async {
    setState(() => _busy = true);
    try {
      final boundary = await _drawn(store);
      await action(await ReceiptImage.capture(boundary, store.receiptWidth), 'receipt-${widget.order.orderNumber}');
    } catch (e) {
      if (mounted) showSnack(context, errorText(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final order = widget.order;
    final store = ref.watch(storeProvider).value;
    final currencies = ref.watch(currenciesProvider).value ?? const <Currency>[];
    final cash = order.payments.where((p) => p.method == 'CASH' && p.status == 'PAID').firstOrNull;
    final change = cash?.changeAmount ?? cash?.changeDue ?? 0;
    final changeIn = currencies.where((c) => c.code == cash?.changeCurrency).firstOrNull;

    return Scaffold(
      appBar: AppBar(
        title: Text(widget.justSold ? t('Sale complete') : order.orderNumber),
        automaticallyImplyLeading: !widget.justSold,
        actions: [
          if (store != null && order.isPaid) ...[
            IconButton(
              tooltip: t('Share'),
              icon: const Icon(Icons.ios_share),
              onPressed: _busy ? null : () => _output(shareReceipt, store),
            ),
            IconButton(
              tooltip: t('Print receipt'),
              icon: const Icon(Icons.print_outlined),
              onPressed: _busy ? null : () => _print(store),
            ),
          ],
        ],
      ),
      body: SafeArea(
        // Not a ListView: all of a long receipt must be laid out for it to print.
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: Center(
            child: Column(mainAxisSize: MainAxisSize.min, children: [
              if (widget.justSold) ...[
                const Icon(Icons.check_circle, color: Brand.success, size: 56),
                const SizedBox(height: 8),
                if (change > 0)
                  Text(
                    '${t('Change')}: ${changeIn == null ? money(change, cash?.changeCurrency ?? order.currency) : amountIn(change, changeIn)}',
                    textAlign: TextAlign.center,
                    style: Theme.of(context).textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w700),
                  ),
                const SizedBox(height: 16),
              ],
              if (store == null)
                const Padding(padding: EdgeInsets.all(32), child: CircularProgressIndicator())
              else
                DecoratedBox(
                  decoration: const BoxDecoration(boxShadow: [BoxShadow(color: Color(0x22000000), blurRadius: 10, offset: Offset(0, 3))]),
                  child: RepaintBoundary(
                    key: _receipt,
                    child: PrintableReceipt(order: order, store: store, currencies: currencies),
                  ),
                ),
              const SizedBox(height: 16),
              SizedBox(
                width: receiptWidthFor(store),
                child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                  if (store != null && order.isPaid)
                    OutlinedButton.icon(
                      icon: const Icon(Icons.print_outlined),
                      label: Text(t('Print receipt')),
                      onPressed: _busy ? null : () => _print(store),
                    ),
                  if (widget.justSold) ...[
                    const SizedBox(height: 8),
                    FilledButton.icon(
                      icon: const Icon(Icons.add_shopping_cart),
                      label: Text(t('New sale'), style: const TextStyle(fontSize: 16)),
                      onPressed: () => Navigator.popUntil(context, (r) => r.isFirst),
                    ),
                  ],
                ]),
              ),
            ]),
          ),
        ),
      ),
    );
  }
}

/// Loads an order and shows it as a receipt.
class OrderDetailScreen extends ConsumerWidget {
  const OrderDetailScreen({super.key, required this.orderId});

  final int orderId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final order = ref.watch(_orderProvider(orderId));
    return order.when(
      data: (o) => ReceiptScreen(order: o),
      loading: () => Scaffold(appBar: AppBar(), body: const Center(child: CircularProgressIndicator())),
      error: (e, _) => Scaffold(appBar: AppBar(), body: ErrorView(e, onRetry: () => ref.invalidate(_orderProvider(orderId)))),
    );
  }
}

final _orderProvider = FutureProvider.autoDispose.family<Order, int>((ref, id) {
  return ref.watch(apiProvider).get('/orders/$id', (j) => Order.fromJson(j as Map<String, dynamic>));
});
