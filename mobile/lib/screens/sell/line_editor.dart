import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/i18n.dart';
import '../../core/money.dart';
import '../../state/cart.dart';
import '../../state/session.dart';

/// Changes a cart line: quantity, hours, and an item discount for those allowed to give one.
Future<void> editLine(BuildContext context, int index) => showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      builder: (_) => _LineEditor(index),
    );

class _LineEditor extends ConsumerStatefulWidget {
  const _LineEditor(this.index);

  final int index;

  @override
  ConsumerState<_LineEditor> createState() => _LineEditorState();
}

class _LineEditorState extends ConsumerState<_LineEditor> {
  late final CartLine _line = ref.read(cartProvider).lines[widget.index];
  late int _qty = _line.quantity;
  late final _hours = TextEditingController(text: _line.hours == null ? '' : trimNum(_line.hours!));
  late String _discountType = _line.discountType ?? 'PERCENT';
  late final _discount = TextEditingController(text: _line.hasDiscount ? trimNum(_line.discountValue!) : '');
  String? _error;

  @override
  void dispose() {
    _hours.dispose();
    _discount.dispose();
    super.dispose();
  }

  void _save() {
    final user = ref.read(userProvider);
    double? hours;
    if (_line.offering.hourly) {
      hours = double.tryParse(_hours.text.replaceAll(',', '.'));
      if (hours == null || hours <= 0 || hours > 999.99) {
        setState(() => _error = t('Enter the hours worked.'));
        return;
      }
    }
    final value = double.tryParse(_discount.text.replaceAll(',', '.')) ?? 0;
    if (value < 0 || (_discountType == 'PERCENT' && value > 100)) {
      setState(() => _error = t('Enter a discount between 0 and 100%.'));
      return;
    }
    final max = user.role.maxItemDiscountPct;
    if (value > 0 && _discountType == 'PERCENT' && max != null && value > max) {
      setState(() => _error = t('Your role can take at most {pct}% off an item.', {'pct': trimNum(max)}));
      return;
    }
    final updated = CartLine(
      offering: _line.offering,
      quantity: _qty,
      hours: hours,
      discountType: value > 0 ? _discountType : null,
      discountValue: value > 0 ? value : null,
    );
    ref.read(cartProvider.notifier).updateLine(widget.index, updated);
    Navigator.pop(context);
  }

  @override
  Widget build(BuildContext context) {
    final user = ref.watch(userProvider);
    final o = _line.offering;
    return Padding(
      padding: EdgeInsets.fromLTRB(20, 20, 20, 20 + MediaQuery.viewInsetsOf(context).bottom),
      child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Text(o.displayName(currentLang), style: Theme.of(context).textTheme.titleLarge),
        Text(o.sku, style: Theme.of(context).textTheme.bodySmall),
        const SizedBox(height: 16),
        Row(children: [
          Expanded(child: Text(t('Quantity'))),
          IconButton.outlined(icon: const Icon(Icons.remove), onPressed: _qty > 1 ? () => setState(() => _qty--) : null),
          SizedBox(width: 56, child: Text('$_qty', textAlign: TextAlign.center, style: const TextStyle(fontSize: 20))),
          IconButton.outlined(icon: const Icon(Icons.add), onPressed: () => setState(() => _qty++)),
        ]),
        if (o.hourly) ...[
          const SizedBox(height: 12),
          TextField(
            controller: _hours,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: InputDecoration(labelText: t('Hours worked'), suffixText: t('h')),
          ),
        ],
        if (user.can('DISCOUNT_ITEMS')) ...[
          const SizedBox(height: 16),
          Text(t('Item discount')),
          const SizedBox(height: 8),
          Row(children: [
            SegmentedButton<String>(
              segments: const [ButtonSegment(value: 'PERCENT', label: Text('%')), ButtonSegment(value: 'AMOUNT', label: Text('\$'))],
              selected: {_discountType},
              showSelectedIcon: false,
              onSelectionChanged: (s) => setState(() => _discountType = s.first),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: TextField(
                controller: _discount,
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                decoration: InputDecoration(hintText: '0'),
              ),
            ),
          ]),
        ],
        if (_error != null) ...[
          const SizedBox(height: 12),
          Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
        ],
        const SizedBox(height: 20),
        Row(children: [
          TextButton.icon(
            icon: const Icon(Icons.delete_outline),
            label: Text(t('Remove')),
            style: TextButton.styleFrom(foregroundColor: Theme.of(context).colorScheme.error),
            onPressed: () {
              ref.read(cartProvider.notifier).setQuantity(widget.index, 0);
              Navigator.pop(context);
            },
          ),
          const Spacer(),
          FilledButton(onPressed: _save, child: Text(t('Done'))),
        ]),
      ]),
    );
  }
}
