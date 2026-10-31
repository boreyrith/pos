import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../api/models.dart';
import '../../core/i18n.dart';
import '../../core/money.dart';
import '../../core/pricing.dart';
import '../../state/cart.dart';
import '../../state/data.dart';
import '../../state/session.dart';
import '../../widgets/common.dart';
import 'customer_picker.dart';
import 'line_editor.dart';
import 'payment_screen.dart';

/// The sale being rung up, with its totals and what can be done with it.
class CartView extends ConsumerWidget {
  const CartView({super.key, this.onClose});

  /// Shown on phones, where the cart is its own page.
  final VoidCallback? onClose;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cart = ref.watch(cartProvider);
    final totals = ref.watch(cartTotalsProvider);
    final base = ref.watch(currenciesProvider).value?.firstOrNull;
    final store = ref.watch(storeProvider).value;
    String fmt(double v) => base == null ? money(v, store?.currency ?? 'USD') : amountIn(v, base);
    final scheme = Theme.of(context).colorScheme;

    return Column(children: [
      // Customer and what the cart came from.
      Padding(
        padding: const EdgeInsets.fromLTRB(12, 8, 4, 0),
        child: Row(children: [
          Expanded(
            child: ActionChip(
              avatar: const Icon(Icons.person_outline, size: 18),
              label: Text(cart.customer?.name ?? t('Walk-in Customer'), overflow: TextOverflow.ellipsis),
              onPressed: () async {
                final c = await pickCustomer(context);
                if (c != null) ref.read(cartProvider.notifier).setCustomer(c);
              },
            ),
          ),
          if (cart.hold != null)
            Padding(
              padding: const EdgeInsets.only(left: 8),
              child: Chip(label: Text(cart.hold!.holdNumber), avatar: const Icon(Icons.pause_circle_outline, size: 18)),
            ),
          PopupMenuButton<String>(
            onSelected: (v) => switch (v) {
              'note' => _editNote(context, ref, cart.note),
              'clear' => _clear(context, ref),
              _ => null,
            },
            itemBuilder: (_) => [
              PopupMenuItem(value: 'note', child: Text(cart.note.isEmpty ? t('Add note') : t('Edit note'))),
              PopupMenuItem(
                  value: 'clear',
                  enabled: !cart.isEmpty || cart.hold != null,
                  child: Text(cart.hold != null ? t('Put back on hold') : t('Clear sale'))),
            ],
          ),
          if (onClose != null) IconButton(icon: const Icon(Icons.close), onPressed: onClose),
        ]),
      ),
      if (cart.note.isNotEmpty)
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 4, 16, 0),
          child: Row(children: [
            Icon(Icons.sticky_note_2_outlined, size: 16, color: scheme.outline),
            const SizedBox(width: 6),
            Expanded(child: Text(cart.note, style: TextStyle(color: scheme.outline), maxLines: 2, overflow: TextOverflow.ellipsis)),
          ]),
        ),
      const Divider(),
      Expanded(
        child: cart.isEmpty
            ? Center(
                child: Column(mainAxisSize: MainAxisSize.min, children: [
                  Icon(Icons.shopping_basket_outlined, size: 48, color: scheme.outlineVariant),
                  const SizedBox(height: 8),
                  Text(t('Tap an item to add it'), style: TextStyle(color: scheme.outline)),
                ]),
              )
            : ListView.separated(
                itemCount: cart.lines.length,
                separatorBuilder: (_, _) => const Divider(height: 1, indent: 12, endIndent: 12),
                itemBuilder: (context, i) => _LineTile(index: i, line: cart.lines[i], split: totals?.lines[i], fmt: fmt),
              ),
      ),
      if (!cart.isEmpty && totals != null) _Totals(cart: cart, totals: totals, fmt: fmt),
    ]);
  }

  Future<void> _editNote(BuildContext context, WidgetRef ref, String note) async {
    final controller = TextEditingController(text: note);
    final text = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(t('Note')),
        content: TextField(controller: controller, autofocus: true, maxLength: 255, maxLines: 3),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: Text(t('Cancel'))),
          FilledButton(onPressed: () => Navigator.pop(context, controller.text.trim()), child: Text(t('Save'))),
        ],
      ),
    );
    if (text != null) ref.read(cartProvider.notifier).setNote(text);
  }

  Future<void> _clear(BuildContext context, WidgetRef ref) async {
    final cart = ref.read(cartProvider);
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        content: Text(cart.hold != null
            ? t('Put {hold} back on hold? Changes made since you resumed it are dropped.', {'hold': cart.hold!.holdNumber})
            : t('Remove everything from this sale?')),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: Text(t('Cancel'))),
          FilledButton(onPressed: () => Navigator.pop(context, true), child: Text(t('OK'))),
        ],
      ),
    );
    if (ok != true) return;
    if (cart.hold != null) {
      try {
        await ref.read(apiProvider).post('/held-sales/${cart.hold!.id}/put-back', (_) => null);
      } catch (e) {
        if (context.mounted) showSnack(context, errorText(e));
        return;
      }
    }
    ref.read(cartProvider.notifier).clear();
    if (context.mounted) _closeIfOwnPage(context);
  }
}

/// Phones show the cart as its own page: close it once the sale is gone. (On tablets it sits
/// beside the catalog on the first page, which can't be popped.)
void _closeIfOwnPage(BuildContext context) {
  final navigator = Navigator.of(context);
  if (navigator.canPop()) navigator.pop();
}

class _LineTile extends ConsumerWidget {
  const _LineTile({required this.index, required this.line, required this.split, required this.fmt});

  final int index;
  final CartLine line;
  final LineSplit? split;
  final String Function(double) fmt;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final o = line.offering;
    final scheme = Theme.of(context).colorScheme;
    final detail = [
      if (o.hourly) '${fmt(o.price)} × ${trimNum(line.hours ?? 0)} ${t('h')}' else fmt(o.price),
      if (line.hasDiscount)
        line.discountType == 'PERCENT' ? '−${trimNum(line.discountValue!)}%' : '−${fmt(line.discountValue!)}',
    ].join('  ·  ');
    return InkWell(
      onTap: () => editLine(context, index),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 8, 8, 8),
        child: Row(children: [
          ClipRRect(borderRadius: BorderRadius.circular(8), child: OfferingImage(o, size: 44)),
          const SizedBox(width: 10),
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(o.displayName(currentLang), maxLines: 2, overflow: TextOverflow.ellipsis),
              Text(detail, style: TextStyle(fontSize: 12, color: line.hasDiscount ? Brand.success : scheme.outline)),
            ]),
          ),
          _qtyButton(Icons.remove, () => ref.read(cartProvider.notifier).setQuantity(index, line.quantity - 1)),
          SizedBox(width: 32, child: Text('${line.quantity}', textAlign: TextAlign.center, style: const TextStyle(fontWeight: FontWeight.w600))),
          _qtyButton(Icons.add, () => ref.read(cartProvider.notifier).setQuantity(index, line.quantity + 1)),
          SizedBox(
            width: 76,
            child: Text(
              fmt((split?.total ?? 0) - (split?.itemDiscount ?? 0)),
              textAlign: TextAlign.right,
              style: const TextStyle(fontWeight: FontWeight.w600, fontFeatures: [FontFeature.tabularFigures()]),
            ),
          ),
        ]),
      ),
    );
  }

  Widget _qtyButton(IconData icon, VoidCallback onPressed) => SizedBox(
        width: 34,
        height: 34,
        child: IconButton.filledTonal(padding: EdgeInsets.zero, iconSize: 18, icon: Icon(icon), onPressed: onPressed),
      );
}

class _Totals extends ConsumerWidget {
  const _Totals({required this.cart, required this.totals, required this.fmt});

  final Cart cart;
  final PricingTotals totals;
  final String Function(double) fmt;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final scheme = Theme.of(context).colorScheme;
    return Material(
      color: scheme.surfaceContainerLow,
      child: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 10, 16, 12),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            AmountRow(t('Subtotal'), fmt(totals.subtotal)),
            if (totals.itemDiscount > 0) AmountRow(t('Item discounts'), '−${fmt(totals.itemDiscount)}', color: Brand.success),
            InkWell(
              onTap: () => _editDiscount(context, ref),
              child: AmountRow(
                cart.discountValue > 0
                    ? '${t('Discount')}${cart.discountType == 'PERCENT' ? ' ${trimNum(cart.discountValue)}%' : ''}  ✎'
                    : '${t('Add discount')}  ✎',
                totals.discount > 0 ? '−${fmt(totals.discount)}' : '',
                color: totals.discount > 0 ? Brand.success : scheme.primary,
              ),
            ),
            for (final tax in totals.taxes.where((x) => x.tax > 0))
              AmountRow(
                tax.key.included
                    ? t('{tax} included', {'tax': taxLabelOf(tax.key.name, tax.key.rate)})
                    : taxLabelOf(tax.key.name, tax.key.rate),
                fmt(tax.tax),
                color: tax.key.included ? scheme.outline : null,
              ),
            const Divider(),
            AmountRow(t('Total'), fmt(totals.total), bold: true),
            const SizedBox(height: 10),
            Row(children: [
              Expanded(
                child: OutlinedButton.icon(
                  icon: const Icon(Icons.pause),
                  label: Text(t('Hold')),
                  onPressed: () => _hold(context, ref),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                flex: 2,
                child: FilledButton(
                  onPressed: () => Navigator.push(context, MaterialPageRoute(builder: (_) => const PaymentScreen())),
                  child: Text('${t('Charge')} ${fmt(totals.total)}', style: const TextStyle(fontSize: 16)),
                ),
              ),
            ]),
          ]),
        ),
      ),
    );
  }

  Future<void> _editDiscount(BuildContext context, WidgetRef ref) async {
    var type = cart.discountType;
    final controller = TextEditingController(text: cart.discountValue > 0 ? trimNum(cart.discountValue) : '');
    final result = await showDialog<(String, double)>(
      context: context,
      builder: (context) => StatefulBuilder(
        builder: (context, setState) => AlertDialog(
          title: Text(t('Order discount')),
          content: Row(children: [
            SegmentedButton<String>(
              segments: const [ButtonSegment(value: 'PERCENT', label: Text('%')), ButtonSegment(value: 'AMOUNT', label: Text('\$'))],
              selected: {type},
              showSelectedIcon: false,
              onSelectionChanged: (s) => setState(() => type = s.first),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: TextField(
                controller: controller,
                autofocus: true,
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                decoration: const InputDecoration(hintText: '0'),
              ),
            ),
          ]),
          actions: [
            TextButton(onPressed: () => Navigator.pop(context, (type, 0.0)), child: Text(t('Remove'))),
            FilledButton(
              onPressed: () {
                final v = double.tryParse(controller.text.replaceAll(',', '.')) ?? 0;
                Navigator.pop(context, (type, type == 'PERCENT' ? v.clamp(0, 100).toDouble() : v.clamp(0, double.infinity).toDouble()));
              },
              child: Text(t('Apply')),
            ),
          ],
        ),
      ),
    );
    if (result != null) ref.read(cartProvider.notifier).setDiscount(result.$1, result.$2);
  }

  /// Parks the sale so it can be finished later, here or on another till.
  Future<void> _hold(BuildContext context, WidgetRef ref) async {
    final cart = ref.read(cartProvider);
    final controller = TextEditingController(text: cart.hold?.label ?? '');
    final label = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(t('Hold sale')),
        content: TextField(
          controller: controller,
          autofocus: true,
          maxLength: 100,
          decoration: InputDecoration(labelText: t('Label (optional)'), hintText: t('e.g. Table 4 or the customer\'s name')),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: Text(t('Cancel'))),
          FilledButton(onPressed: () => Navigator.pop(context, controller.text.trim()), child: Text(t('Hold'))),
        ],
      ),
    );
    if (label == null) return;
    final body = {
      'label': label.isEmpty ? null : label,
      'customerId': cart.customer?.id,
      'note': cart.note.isEmpty ? null : cart.note,
      'discountType': cart.discountValue > 0 ? cart.discountType : null,
      'discountValue': cart.discountValue > 0 ? cart.discountValue : null,
      'lines': [for (final l in cart.lines) l.toPayload()],
    };
    try {
      final api = ref.read(apiProvider);
      final held = cart.hold == null
          ? await api.post('/held-sales', (j) => HeldSale.fromJson(j as Map<String, dynamic>), json: body)
          : await api.put('/held-sales/${cart.hold!.id}', (j) => HeldSale.fromJson(j as Map<String, dynamic>), json: body);
      ref.read(cartProvider.notifier).clear();
      ref.invalidate(heldSalesProvider);
      if (!context.mounted) return;
      showSnack(context, t('{hold} is on hold', {'hold': held.holdNumber}));
      _closeIfOwnPage(context);
    } catch (e) {
      if (context.mounted) showSnack(context, errorText(e));
    }
  }
}

