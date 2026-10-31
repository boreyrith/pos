import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:qr_flutter/qr_flutter.dart';

import '../../api/models.dart';
import '../../core/i18n.dart';
import '../../core/money.dart';
import '../../state/cart.dart';
import '../../state/data.dart';
import '../../state/session.dart';
import '../../widgets/common.dart';
import '../receipt_screen.dart';

/// Shows the KHQR code of an order waiting for payment and checks every few seconds whether it was
/// paid. Leaving keeps the order waiting (the cart can't be charged twice); Cancel ends it.
class KhqrScreen extends ConsumerStatefulWidget {
  const KhqrScreen({super.key, required this.orderId});

  final int orderId;

  @override
  ConsumerState<KhqrScreen> createState() => _KhqrScreenState();
}

class _KhqrScreenState extends ConsumerState<KhqrScreen> {
  Order? _order;
  Payment? _payment;
  Object? _error;
  bool _busy = false;
  Timer? _poll;
  Timer? _tick;

  @override
  void initState() {
    super.initState();
    _load();
    _tick = Timer.periodic(const Duration(seconds: 1), (_) => setState(() {}));
  }

  @override
  void dispose() {
    _poll?.cancel();
    _tick?.cancel();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final order = await ref.read(apiProvider).get('/orders/${widget.orderId}', (j) => Order.fromJson(j as Map<String, dynamic>));
      if (!mounted) return;
      if (order.status != 'PENDING_PAYMENT') return _settled(order);
      setState(() {
        _order = order;
        _payment = order.payments.where((p) => p.method == 'KHQR').lastOrNull;
      });
      _poll = Timer.periodic(const Duration(seconds: 3), (_) => _check());
    } catch (e) {
      if (mounted) setState(() => _error = e);
    }
  }

  Future<void> _check() async {
    final p = _payment;
    if (p == null) return;
    try {
      final fresh = await ref.read(apiProvider).get('/payments/${p.id}', (j) => Payment.fromJson(j as Map<String, dynamic>));
      if (!mounted) return;
      setState(() => _payment = fresh);
      if (fresh.status != 'PENDING') {
        _poll?.cancel();
        final order = await ref.read(apiProvider).get('/orders/${widget.orderId}', (j) => Order.fromJson(j as Map<String, dynamic>));
        if (mounted) _settled(order);
      }
    } catch (_) {
      // A missed check is retried on the next tick.
    }
  }

  /// The order was paid, cancelled or expired: settle the cart to match.
  void _settled(Order order) {
    final notifier = ref.read(cartProvider.notifier);
    final pendingHere = ref.read(cartProvider).pendingOrderId == order.id;
    ref.invalidate(catalogProvider);
    ref.invalidate(ordersProvider);
    if (order.status == 'CANCELLED') {
      if (pendingHere) notifier.setPendingOrder(null);
      showSnack(context, t('The KHQR payment was not completed. The sale is still in the cart.'));
      Navigator.pop(context);
    } else {
      if (pendingHere) notifier.clear();
      Navigator.pushReplacement(context, MaterialPageRoute(builder: (_) => ReceiptScreen(order: order, justSold: true)));
    }
  }

  Future<void> _cancel() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        content: Text(t('Cancel this KHQR payment? The sale stays in the cart.')),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: Text(t('Keep waiting'))),
          FilledButton(onPressed: () => Navigator.pop(context, true), child: Text(t('Cancel payment'))),
        ],
      ),
    );
    if (ok != true) return;
    await _run(() async {
      final order = await ref.read(apiProvider).post('/orders/${widget.orderId}/cancel', (j) => Order.fromJson(j as Map<String, dynamic>));
      _poll?.cancel();
      if (mounted) _settled(order);
    });
  }

  /// Test mode only: confirms the payment by hand.
  Future<void> _simulatePaid() => _run(() async {
        await ref.read(apiProvider).post('/payments/${_payment!.id}/simulate-paid', (_) => null);
        await _check();
      });

  Future<void> _run(Future<void> Function() action) async {
    setState(() => _busy = true);
    try {
      await action();
    } catch (e) {
      if (mounted) showSnack(context, errorText(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final store = ref.watch(storeProvider).value;
    final currencies = ref.watch(currenciesProvider).value ?? const <Currency>[];
    final p = _payment;
    final left = p?.expiresAt?.difference(DateTime.now());
    final scheme = Theme.of(context).colorScheme;

    Widget body;
    if (_error != null) {
      body = ErrorView(_error!, onRetry: () {
        setState(() => _error = null);
        _load();
      });
    } else if (_order == null || p == null || p.khqrPayload == null) {
      body = const Center(child: CircularProgressIndicator());
    } else {
      final currency = currencies.where((c) => c.code == p.currency).firstOrNull;
      final amount = p.currencyAmount ?? p.amount;
      body = SingleChildScrollView(
        padding: const EdgeInsets.all(24),
        child: Column(children: [
          Text(_order!.orderNumber, style: TextStyle(color: scheme.outline)),
          const SizedBox(height: 4),
          Text(currency == null ? money(amount, p.currency ?? _order!.currency) : amountIn(amount, currency),
              style: Theme.of(context).textTheme.headlineMedium?.copyWith(fontWeight: FontWeight.w700)),
          const SizedBox(height: 16),
          Container(
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(16), boxShadow: const [
              BoxShadow(color: Color(0x22000000), blurRadius: 12, offset: Offset(0, 4)),
            ]),
            child: Column(children: [
              Container(
                width: 260,
                padding: const EdgeInsets.symmetric(vertical: 8),
                decoration: BoxDecoration(color: const Color(0xFFE1232E), borderRadius: BorderRadius.circular(8)),
                child: const Text('KHQR', textAlign: TextAlign.center,
                    style: TextStyle(color: Colors.white, fontWeight: FontWeight.w800, letterSpacing: 2)),
              ),
              const SizedBox(height: 12),
              QrImageView(data: p.khqrPayload!, size: 260, backgroundColor: Colors.white),
            ]),
          ),
          const SizedBox(height: 16),
          Row(mainAxisAlignment: MainAxisAlignment.center, children: [
            const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2)),
            const SizedBox(width: 10),
            Text(t('Waiting for payment…')),
          ]),
          if (left != null && !left.isNegative)
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Text(
                t('Expires in {time}', {'time': '${left.inMinutes}:${(left.inSeconds % 60).toString().padLeft(2, '0')}'}),
                style: TextStyle(color: scheme.outline),
              ),
            ),
          const SizedBox(height: 24),
          if (store?.khqrSimulated ?? false) ...[
            Text(t('Test mode: no money moves. Confirm the payment by hand.'),
                textAlign: TextAlign.center, style: const TextStyle(color: Brand.warning)),
            const SizedBox(height: 8),
            FilledButton.tonal(onPressed: _busy ? null : _simulatePaid, child: Text(t('Mark as paid (test)'))),
            const SizedBox(height: 8),
          ],
          OutlinedButton(
            onPressed: _busy ? null : _cancel,
            style: OutlinedButton.styleFrom(foregroundColor: scheme.error),
            child: Text(t('Cancel payment')),
          ),
        ]),
      );
    }
    return Scaffold(appBar: AppBar(title: Text(t('KHQR payment'))), body: SafeArea(child: Center(child: body)));
  }
}
