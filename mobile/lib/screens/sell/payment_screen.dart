import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../api/api_client.dart';
import '../../api/models.dart';
import '../../core/i18n.dart';
import '../../core/money.dart';
import '../../state/cart.dart';
import '../../state/data.dart';
import '../../state/session.dart';
import '../../widgets/common.dart';
import '../receipt_screen.dart';
import 'khqr_screen.dart';

const _baseNotes = [1, 5, 10, 20, 50, 100];

/// Takes payment for the cart: cash in any mix of active currencies with change in the chosen one,
/// or a KHQR code the customer scans with their bank app.
class PaymentScreen extends ConsumerStatefulWidget {
  const PaymentScreen({super.key});

  @override
  ConsumerState<PaymentScreen> createState() => _PaymentScreenState();
}

class _PaymentScreenState extends ConsumerState<PaymentScreen> {
  String _method = 'CASH';
  final _amounts = <String, TextEditingController>{};
  String? _changeCode;
  String? _khqrCode;
  bool _busy = false;
  Object? _error;

  @override
  void initState() {
    super.initState();
    // Rates change during the day: charge at today's (after the first frame; providers can't change during a build).
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) ref.invalidate(currenciesProvider);
    });
  }

  @override
  void dispose() {
    for (final c in _amounts.values) {
      c.dispose();
    }
    super.dispose();
  }

  TextEditingController _amount(Currency c, double total, bool isBase) =>
      _amounts.putIfAbsent(c.code, () => TextEditingController(text: isBase ? total.toStringAsFixed(c.decimals) : ''));

  void _setOnly(String code, String value) {
    for (final e in _amounts.entries) {
      e.value.text = e.key == code ? value : '';
    }
    setState(() {});
  }

  Future<void> _charge(double total, List<Map<String, Object?>>? tenders) async {
    final cart = ref.read(cartProvider);
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final order = await ref.read(apiProvider).post('/orders', (j) => Order.fromJson(j as Map<String, dynamic>), json: {
        'items': [for (final l in cart.lines) l.toPayload()],
        'discountType': cart.discountValue > 0 ? cart.discountType : null,
        'discountValue': cart.discountValue > 0 ? cart.discountValue : null,
        'customerId': cart.customer?.id,
        'note': cart.note.isEmpty ? null : cart.note,
        'paymentMethod': _method,
        'tenders': tenders,
        'changeCurrency': _method == 'CASH' ? _changeCode : null,
        'khqrCurrency': _method == 'KHQR' ? _khqrCode : null,
        'heldSaleId': cart.hold?.id,
        // The server refuses the sale if its total differs from what the cashier was shown.
        'expectedTotal': double.parse(total.toStringAsFixed(2)),
      });
      ref.invalidate(catalogProvider);
      ref.invalidate(heldSalesProvider);
      ref.invalidate(ordersProvider);
      if (!mounted) return;
      if (order.status == 'PENDING_PAYMENT') {
        ref.read(cartProvider.notifier).setPendingOrder(order.id);
        Navigator.pushReplacement(context, MaterialPageRoute(builder: (_) => KhqrScreen(orderId: order.id)));
      } else {
        ref.read(cartProvider.notifier).clear();
        Navigator.pushReplacement(context, MaterialPageRoute(builder: (_) => ReceiptScreen(order: order, justSold: true)));
      }
    } on ApiError catch (e) {
      if (e.code == 'PRICES_CHANGED') {
        // Prices moved since the cart was built: load today's and let the cashier check before charging.
        final dropped = await ref.read(cartProvider.notifier).refreshPrices().catchError((_) => <String>[]);
        if (!mounted) return;
        showSnack(
            context,
            t('Prices changed, the cart now shows today\'s. Check the total with the customer and charge again.') +
                (dropped.isEmpty ? '' : ' ${dropped.join(', ')}'));
        Navigator.pop(context);
        return;
      }
      if (mounted) setState(() => _error = e);
    } catch (e) {
      if (mounted) setState(() => _error = e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final totals = ref.watch(cartTotalsProvider);
    final currencies = ref.watch(currenciesProvider);
    return Scaffold(
      appBar: AppBar(title: Text(t('Payment'))),
      body: totals == null
          ? const Center(child: CircularProgressIndicator())
          : currencies.when(
              loading: () => const Center(child: CircularProgressIndicator()),
              error: (e, _) => ErrorView(e, onRetry: () => ref.invalidate(currenciesProvider)),
              data: (list) => list.isEmpty
                  ? ErrorView(t('No currencies set up'))
                  : _body(context, totals.total, list),
            ),
    );
  }

  Widget _body(BuildContext context, double total, List<Currency> currencies) {
    final base = currencies.first;
    final scheme = Theme.of(context).colorScheme;
    final khqrCurrencies = currencies.where((c) => c.code == 'USD' || c.code == 'KHR').toList();
    _changeCode ??= base.code;
    _khqrCode ??= khqrCurrencies.any((c) => c.code == base.code) ? base.code : khqrCurrencies.firstOrNull?.code;

    // Cash: what was handed over, in base money.
    final lines = [
      for (final c in currencies)
        (currency: c, amount: double.tryParse(_amount(c, total, c.base).text.replaceAll(',', '')) ?? 0),
    ].where((l) => l.amount > 0).toList();
    final badDecimals = currencies.any((c) {
      final v = _amounts[c.code]?.text ?? '';
      return v.contains('.') && v.split('.')[1].length > c.decimals;
    });
    final received = lines.fold<double>(0, (s, l) => s + l.amount / (l.currency.rate ?? 1));
    final remaining = total - received;
    // Mirrors CashSettlement.java: a shortfall under half a cash step of a currency used counts as paid.
    final paid = remaining <= 1e-9 || lines.any((l) => remaining * (l.currency.rate ?? 1) * 2 < l.currency.cashRounding);
    final changeCurrency = currencies.firstWhere((c) => c.code == _changeCode, orElse: () => base);
    final change = remaining < 0 ? cashIn(-remaining, changeCurrency) : 0.0;
    final dueIn = lines.isEmpty ? [base] : {for (final l in lines) l.currency}.toList();
    final cashValid = lines.isNotEmpty && paid && !badDecimals;

    return SafeArea(
      child: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 520),
          child: ListView(padding: const EdgeInsets.all(16), children: [
            Text(amountIn(total, base), textAlign: TextAlign.center,
                style: Theme.of(context).textTheme.displaySmall?.copyWith(fontWeight: FontWeight.w700)),
            if (currencies.length > 1)
              Text(
                currencies.skip(1).map((c) => amountIn(cashIn(total, c), c)).join(' · '),
                textAlign: TextAlign.center,
                style: TextStyle(color: scheme.outline),
              ),
            const SizedBox(height: 16),
            SegmentedButton<String>(
              segments: [
                ButtonSegment(value: 'CASH', icon: const Icon(Icons.payments_outlined), label: Text(t('Cash'))),
                ButtonSegment(value: 'KHQR', icon: const Icon(Icons.qr_code_2), label: const Text('KHQR')),
              ],
              selected: {_method},
              onSelectionChanged: (s) => setState(() => _method = s.first),
            ),
            const SizedBox(height: 20),
            if (_method == 'CASH') ...[
              Text(t('Cash received'), style: const TextStyle(fontWeight: FontWeight.w600)),
              const SizedBox(height: 8),
              for (final c in currencies)
                Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: Row(children: [
                    SizedBox(width: 48, child: Text(c.code, style: const TextStyle(fontWeight: FontWeight.w600))),
                    Expanded(
                      child: TextField(
                        controller: _amount(c, total, c.base),
                        keyboardType: TextInputType.numberWithOptions(decimal: c.decimals > 0),
                        textAlign: TextAlign.right,
                        style: const TextStyle(fontSize: 18),
                        onChanged: (_) => setState(() {}),
                        onTap: () => _amounts[c.code]!.selection =
                            TextSelection(baseOffset: 0, extentOffset: _amounts[c.code]!.text.length),
                        decoration: const InputDecoration(hintText: '0'),
                      ),
                    ),
                    const SizedBox(width: 8),
                    OutlinedButton(
                      style: OutlinedButton.styleFrom(minimumSize: const Size(0, 44)),
                      onPressed: () => _setOnly(c.code, cashIn(total, c).toStringAsFixed(c.decimals)),
                      child: Text(t('Exact')),
                    ),
                  ]),
                ),
              Wrap(spacing: 8, children: [
                for (final n in _baseNotes.where((n) => n > total).take(4))
                  ActionChip(label: Text(amountIn(n.toDouble(), base)), onPressed: () => _setOnly(base.code, '$n')),
              ]),
              if (badDecimals)
                Text(t('Too many decimals for that currency.'), style: TextStyle(color: scheme.error, fontSize: 12)),
              if (currencies.length > 1) ...[
                const SizedBox(height: 12),
                Row(children: [
                  Text(t('Give change in')),
                  const SizedBox(width: 12),
                  SegmentedButton<String>(
                    segments: [for (final c in currencies) ButtonSegment(value: c.code, label: Text(c.code))],
                    selected: {changeCurrency.code},
                    showSelectedIcon: false,
                    onSelectionChanged: (s) => setState(() => _changeCode = s.first),
                  ),
                ]),
              ],
              const SizedBox(height: 16),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                decoration: BoxDecoration(
                  color: paid ? scheme.surfaceContainerHighest : Brand.warning.withValues(alpha: 0.12),
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Row(children: [
                  Expanded(child: Text(paid ? t('Change') : t('Still to pay'), style: TextStyle(color: paid ? null : Brand.warning))),
                  Text(
                    paid
                        ? amountIn(change, changeCurrency)
                        : dueIn
                            .map((c) => amountIn(
                                ((remaining * (c.rate ?? 1)) / c.cashRounding - 1e-9).ceilToDouble() * c.cashRounding, c))
                            .join(' ${t('or')} '),
                    style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700, color: paid ? null : Brand.warning),
                  ),
                ]),
              ),
              const SizedBox(height: 16),
              ErrorNote(_error),
              if (_error != null) const SizedBox(height: 12),
              FilledButton(
                onPressed: cashValid && !_busy
                    ? () => _charge(total, [for (final l in lines) {'currency': l.currency.code, 'amount': l.amount}])
                    : null,
                child: _busy ? const _Spinner() : Text(t('Complete sale'), style: const TextStyle(fontSize: 16)),
              ),
            ] else ...[
              if (khqrCurrencies.length > 1) ...[
                Text(t('Charge the QR code in')),
                const SizedBox(height: 8),
                SegmentedButton<String>(
                  segments: [
                    for (final c in khqrCurrencies) ButtonSegment(value: c.code, label: Text(amountIn(cashIn(total, c), c))),
                  ],
                  selected: {_khqrCode!},
                  showSelectedIcon: false,
                  onSelectionChanged: (s) => setState(() => _khqrCode = s.first),
                ),
                const SizedBox(height: 16),
              ],
              Text(t('The customer scans the code with their banking app. The sale completes when the payment arrives.'),
                  style: TextStyle(color: scheme.outline)),
              const SizedBox(height: 16),
              ErrorNote(_error),
              if (_error != null) const SizedBox(height: 12),
              FilledButton.icon(
                icon: const Icon(Icons.qr_code_2),
                onPressed: _busy || khqrCurrencies.isEmpty ? null : () => _charge(total, null),
                label: _busy ? const _Spinner() : Text(t('Show QR code'), style: const TextStyle(fontSize: 16)),
              ),
            ],
          ]),
        ),
      ),
    );
  }
}

class _Spinner extends StatelessWidget {
  const _Spinner();

  @override
  Widget build(BuildContext context) =>
      const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white));
}
