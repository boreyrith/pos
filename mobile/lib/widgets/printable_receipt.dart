import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../api/models.dart';
import '../core/i18n.dart';
import '../core/money.dart';
import '../core/pricing.dart';
import '../state/session.dart';
import 'common.dart';

/// The width the receipt is drawn at; printed, it fills 80mm paper.
const receiptWidth = 320.0;

/// A receipt for 80mm paper, always black on white. Mirrors Receipt.tsx on the web. Tax lines follow
/// the order's own tax mode and label (as sold), not the current settings, so reprints never change.
class PrintableReceipt extends ConsumerWidget {
  const PrintableReceipt({super.key, required this.order, required this.store, required this.currencies});

  final Order order;
  final StoreSettings store;
  final List<Currency> currencies;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final vat = order.taxLabel;
    final exclusive = order.taxMode == 'EXCLUSIVE';
    final paid = order.payments.where((p) => p.status == 'PAID' || p.status == 'REFUNDED').firstOrNull;
    final rates = [
      for (final r in order.rates)
        if (r.currency != order.currency) r,
    ];
    final taxed = order.taxes.where((x) => x.rate > 0).toList();
    String m(double v) => money(v, order.currency);
    String show(double v, String? code) {
      final c = currencies.where((c) => c.code == code).firstOrNull;
      return c == null ? money(v, code ?? order.currency) : amountIn(v, c);
    }

    Widget center(String text, {bool bold = false}) =>
        Text(text, textAlign: TextAlign.center, style: bold ? const TextStyle(fontWeight: FontWeight.w700) : null);

    return Container(
      width: receiptWidth,
      color: Colors.white,
      padding: const EdgeInsets.all(14),
      child: DefaultTextStyle(
        style: const TextStyle(color: Colors.black, fontSize: 12, height: 1.35),
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, mainAxisSize: MainAxisSize.min, children: [
          if (store.hasLogo)
            Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxHeight: 64, maxWidth: 180),
                child: CachedNetworkImage(imageUrl: ref.watch(apiProvider).logoUrl(store.logoVersion), fit: BoxFit.contain),
              ),
            ),
          Text(store.name, textAlign: TextAlign.center, style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700)),
          if (store.legalName != null) center(store.legalName!),
          if (store.address != null) center(store.address!),
          if (store.phone != null) center('${t('Tel')} ${store.phone}'),
          if (store.taxId != null && order.taxMode != 'NONE') center('${t('{vat} TIN', {'vat': vat})} ${store.taxId}'),
          const SizedBox(height: 4),
          center(order.branchName),
          center(t('Receipt {number}', {'number': order.orderNumber})),
          center(dateTime(order.completedAt ?? order.createdAt)),
          center(t('Cashier: {name}', {'name': order.cashierName})),
          if (!order.walkIn || order.customerName != null) center(t('Customer: {name}', {'name': order.customerName})),
          if (!order.walkIn && order.customerPhone != null) center('${t('Tel')} ${order.customerPhone}'),
          if (!order.walkIn && order.customerTaxId != null) center(t('Customer VAT TIN {tin}', {'tin': order.customerTaxId})),
          if (!order.isPaid) center('*** ${orderStatusLabel(order.status)} ***', bold: true),
          if (order.status == 'REFUNDED') center('*** ${t('REFUNDED')} ***', bold: true),
          if (order.status == 'PARTIALLY_REFUNDED') center('*** ${t('PARTLY REFUNDED')} ***', bold: true),
          const _Rule(),
          for (final item in order.items) _item(item, m),
          const _Rule(),
          _Row(exclusive ? t('Subtotal (excl. {vat})', {'vat': vat}) : t('Subtotal'), m(order.subtotal)),
          if (order.itemDiscount > 0) _Row(t('Item discounts'), '-${m(order.itemDiscount)}'),
          if (order.discountAmount > 0)
            _Row(
              '${order.itemDiscount > 0 ? t('Order discount') : t('Discount')}'
              '${order.discountType == 'PERCENT' ? ' ${trimNum(order.discountValue ?? 0)}%' : ''}',
              '-${m(order.discountAmount)}',
            ),
          if (exclusive && order.taxableAmount > 0 && order.taxes.any((x) => x.tax > 0))
            _Row(t('Taxable amount'), m(order.taxableAmount)),
          for (final x in order.taxes.where((x) => !x.included && x.tax > 0)) _Row(taxLabelOf(x.name, x.rate), m(x.tax)),
          const SizedBox(height: 2),
          _Row(t('TOTAL'), m(order.total), bold: true),
          // The total in the other currencies, at the rates the order was sold at.
          for (final r in rates)
            if (currencies.where((c) => c.code == r.currency).firstOrNull case final c?)
              _Row('@ ${trimNum(r.rate)}', amountIn(cashIn(order.total, c, rate: r.rate), c)),
          if (order.taxIncluded > 0) _Row(t('Includes {vat}', {'vat': vat}), m(order.taxIncluded)),
          if (order.returns.isNotEmpty) ...[
            for (final r in order.returns) _Row(t('Refund {number}', {'number': r.returnNumber}), '-${m(r.total)}'),
            _Row(t('Net paid'), m(order.total - order.refundedTotal)),
          ],
          if (order.taxMode != 'NONE' && taxed.isNotEmpty) ...[
            const SizedBox(height: 6),
            Text(t('Tax summary'), style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 11)),
            for (final x in taxed)
              _Row(
                t(x.included ? '{tax} incl. on {base}' : '{tax} added on {base}', {'tax': taxLabelOf(x.name, x.rate), 'base': m(x.base)}),
                m(x.tax),
                size: 11,
              ),
          ],
          if (paid != null) ...[
            const _Rule(),
            if (paid.method == 'KHQR')
              _Row(t('Paid by KHQR'), show(paid.currencyAmount ?? paid.amount, paid.currency ?? order.currency))
            else
              for (final x in paid.tenders) _Row(t('Cash {currency}', {'currency': x.currency}), show(x.amount, x.currency)),
            if ((paid.changeAmount ?? 0) > 0) _Row(t('Change'), show(paid.changeAmount!, paid.changeCurrency)),
          ],
          const _Rule(),
          if (store.receiptFooter != null) center(store.receiptFooter!),
        ]),
      ),
    );
  }

  Widget _item(OrderItem item, String Function(double) m) {
    final unit = item.hours != null
        ? ' × ${trimNum(item.hours!)} ${t('h')}'
        : item.unit != null
            ? ' /${item.unit}'
            : '';
    final exempt = item.taxRate == 0 && order.taxMode != 'NONE' ? ' (${t(item.taxName)})' : '';
    return Padding(
      padding: const EdgeInsets.only(bottom: 4),
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Text(item.name),
        if (item.nameKm?.isNotEmpty ?? false) Text(item.nameKm!),
        _Row('${item.quantity} × ${m(item.unitPrice)}$unit$exempt', m(item.lineTotal)),
        for (final c in item.contents)
          Padding(
            padding: EdgeInsets.only(left: (c.depth + 1) * 8.0),
            child: Text('· ${trimNum(c.quantity)}${c.unit == null ? '' : ' ${c.unit}'} ${c.name}'
                '${c.nameKm?.isNotEmpty ?? false ? ' (${c.nameKm})' : ''}'),
          ),
        if (item.priceOverridden) Text('  ${t('Price changed (was {price})', {'price': m(item.listPrice)})}'),
        if (item.itemDiscount > 0)
          _Row(
            '  ${t('Discount')}${item.itemDiscountType == 'PERCENT' ? ' ${trimNum(item.itemDiscountValue ?? 0)}%' : ''}',
            '-${m(item.itemDiscount)}',
          ),
        if (item.staffName != null) Text('  ${t('By {name}', {'name': item.staffName})}'),
      ]),
    );
  }
}

class _Row extends StatelessWidget {
  const _Row(this.label, this.value, {this.bold = false, this.size});

  final String label;
  final String value;
  final bool bold;
  final double? size;

  @override
  Widget build(BuildContext context) {
    final style = TextStyle(fontWeight: bold ? FontWeight.w700 : null, fontSize: size ?? (bold ? 14 : null));
    return Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Expanded(child: Text(label, style: style)),
      const SizedBox(width: 8),
      Text(value, style: style),
    ]);
  }
}

/// The dashed line between a receipt's sections.
class _Rule extends StatelessWidget {
  const _Rule();

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 6),
        child: Text('- ' * 60, maxLines: 1, softWrap: false, overflow: TextOverflow.clip, style: const TextStyle(height: 0.8)),
      );
}

/// Waits for what the receipt shows from the network, so a print doesn't miss it.
Future<void> precacheReceipt(BuildContext context, WidgetRef ref, StoreSettings store) async {
  if (!store.hasLogo) return;
  final url = ref.read(apiProvider).logoUrl(store.logoVersion);
  await precacheImage(CachedNetworkImageProvider(url), context, onError: (_, _) {});
}
