import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../api/models.dart';
import '../core/pricing.dart';
import 'data.dart';
import 'session.dart';

class CartLine {
  const CartLine({required this.offering, this.quantity = 1, this.hours, this.discountType, this.discountValue});

  final Offering offering;
  final int quantity;

  /// Hours worked, for hourly services.
  final double? hours;

  /// An item discount (needs "Discount items"): PERCENT or AMOUNT.
  final String? discountType;
  final double? discountValue;

  bool get hasDiscount => discountType != null && (discountValue ?? 0) > 0;

  CartLine copyWith({int? quantity, double? hours, String? discountType, double? discountValue, bool clearDiscount = false}) =>
      CartLine(
        offering: offering,
        quantity: quantity ?? this.quantity,
        hours: hours ?? this.hours,
        discountType: clearDiscount ? null : discountType ?? this.discountType,
        discountValue: clearDiscount ? null : discountValue ?? this.discountValue,
      );

  /// A line of a checkout or hold request.
  Map<String, Object?> toPayload() => {
        'offeringId': offering.id,
        'quantity': quantity,
        'hours': hours,
        'discountType': hasDiscount ? discountType : null,
        'discountValue': hasDiscount ? discountValue : null,
      };
}

class Cart {
  const Cart({
    this.lines = const [],
    this.discountType = 'PERCENT',
    this.discountValue = 0,
    this.customer,
    this.note = '',
    this.hold,
    this.pendingOrderId,
  });

  final List<CartLine> lines;

  /// The order discount: PERCENT or AMOUNT of [discountValue].
  final String discountType;
  final double discountValue;

  /// Null sells to the Walk-in Customer.
  final Customer? customer;
  final String note;

  /// The held sale this cart was resumed from.
  final HeldSale? hold;

  /// A KHQR order created from this cart and still waiting to be paid: it must not be charged again.
  final int? pendingOrderId;

  bool get isEmpty => lines.isEmpty;
  int get itemCount => lines.fold(0, (n, l) => n + l.quantity);

  Cart copyWith({
    List<CartLine>? lines,
    String? discountType,
    double? discountValue,
    Customer? customer,
    bool clearCustomer = false,
    String? note,
    HeldSale? hold,
    bool clearHold = false,
    int? pendingOrderId,
    bool clearPending = false,
  }) =>
      Cart(
        lines: lines ?? this.lines,
        discountType: discountType ?? this.discountType,
        discountValue: discountValue ?? this.discountValue,
        customer: clearCustomer ? null : customer ?? this.customer,
        note: note ?? this.note,
        hold: clearHold ? null : hold ?? this.hold,
        pendingOrderId: clearPending ? null : pendingOrderId ?? this.pendingOrderId,
      );

  Map<String, Object?> toJson() => {
        'lines': [for (final l in lines) l.toPayload()],
        'discountType': discountType,
        'discountValue': discountValue,
        'customer': customer == null ? null : {'id': customer!.id, 'name': customer!.name, 'phone': customer!.phone},
        'note': note,
        'holdId': hold?.id,
        'holdNumber': hold?.holdNumber,
        'pendingOrderId': pendingOrderId,
      };
}

/// The sale being rung up. Saved on the device after every change, so closing the app (or a
/// crash during a KHQR payment) doesn't lose it.
class CartNotifier extends Notifier<Cart> {
  /// The cart is kept per user, so another cashier signing in on this phone starts empty.
  String? get _key {
    final s = ref.read(sessionProvider);
    return s is SignedIn ? 'cart:${s.user.id}' : null;
  }

  @override
  Cart build() {
    ref.watch(sessionProvider.select((s) => s is SignedIn ? s.user.id : null));
    _restore();
    return const Cart();
  }

  void _save(Cart value) {
    state = value;
    final key = _key;
    if (key != null) ref.read(prefsProvider).setString(key, jsonEncode(value.toJson()));
  }

  Future<void> _restore() async {
    final key = _key;
    final raw = key == null ? null : ref.read(prefsProvider).getString(key);
    if (raw == null) return;
    try {
      final j = jsonDecode(raw) as Map<String, dynamic>;
      final saved = (j['lines'] as List).cast<Map<String, dynamic>>();
      final fresh = {for (final o in await freshOfferings(ref, saved.map((l) => l['offeringId'] as int))) o.id: o};
      final c = j['customer'] as Map<String, dynamic>?;
      state = Cart(
        lines: [
          for (final l in saved)
            if (fresh[l['offeringId']] != null)
              CartLine(
                offering: fresh[l['offeringId']]!,
                quantity: l['quantity'] as int,
                hours: (l['hours'] as num?)?.toDouble(),
                discountType: l['discountType'] as String?,
                discountValue: (l['discountValue'] as num?)?.toDouble(),
              ),
        ],
        discountType: j['discountType'] as String? ?? 'PERCENT',
        discountValue: (j['discountValue'] as num?)?.toDouble() ?? 0,
        customer: c == null ? null : Customer(id: c['id'] as int, name: c['name'] as String, phone: c['phone'] as String?, walkIn: false),
        note: j['note'] as String? ?? '',
        hold: j['holdId'] == null ? null : _heldRef(j['holdId'] as int, j['holdNumber'] as String? ?? ''),
        pendingOrderId: j['pendingOrderId'] as int?,
      );
    } catch (_) {
      // An unreadable saved cart is dropped.
    }
  }

  static HeldSale _heldRef(int id, String number) => HeldSale(
      id: id, holdNumber: number, status: 'IN_USE', itemCount: 0, estimatedTotal: 0, createdBy: '', updatedAt: DateTime.now(), lockedByMe: true);

  /// Adds one of [o] (or [hours] of an hourly service as a new line).
  void add(Offering o, {double? hours}) {
    final lines = [...state.lines];
    final i = o.hourly ? -1 : lines.indexWhere((l) => l.offering.id == o.id && !l.hasDiscount);
    if (i >= 0) {
      lines[i] = lines[i].copyWith(quantity: lines[i].quantity + 1);
    } else {
      lines.add(CartLine(offering: o, hours: hours));
    }
    _save(state.copyWith(lines: lines));
  }

  void setQuantity(int index, int quantity) {
    final lines = [...state.lines];
    if (quantity <= 0) {
      lines.removeAt(index);
    } else {
      lines[index] = lines[index].copyWith(quantity: quantity.clamp(1, 10000));
    }
    _save(state.copyWith(lines: lines));
  }

  void updateLine(int index, CartLine line) {
    final lines = [...state.lines];
    lines[index] = line;
    _save(state.copyWith(lines: lines));
  }

  /// Reloads the lines' prices and stock; lines no longer for sale are dropped. Returns their names.
  Future<List<String>> refreshPrices() async {
    final fresh = {for (final o in await freshOfferings(ref, state.lines.map((l) => l.offering.id))) o.id: o};
    final dropped = [for (final l in state.lines) if (fresh[l.offering.id] == null) l.offering.name];
    _save(state.copyWith(lines: [
      for (final l in state.lines)
        if (fresh[l.offering.id] != null)
          CartLine(
            offering: fresh[l.offering.id]!,
            quantity: l.quantity,
            hours: l.hours,
            discountType: l.discountType,
            discountValue: l.discountValue,
          ),
    ]));
    return dropped;
  }

  void setDiscount(String type, double value) => _save(state.copyWith(discountType: type, discountValue: value));

  void setCustomer(Customer? c) => _save(c == null || c.walkIn ? state.copyWith(clearCustomer: true) : state.copyWith(customer: c));

  void setNote(String note) => _save(state.copyWith(note: note));

  void setPendingOrder(int? orderId) =>
      _save(orderId == null ? state.copyWith(clearPending: true) : state.copyWith(pendingOrderId: orderId));

  /// Loads a resumed held sale with today's prices.
  Future<void> loadHold(ResumedHold r) async {
    final fresh = {for (final o in await freshOfferings(ref, r.lines.map((l) => l.offeringId))) o.id: o};
    Customer? customer;
    if (r.hold.customerId != null) {
      customer = Customer(id: r.hold.customerId!, name: r.hold.customerName ?? '', walkIn: false);
    }
    _save(Cart(
      lines: [
        for (final l in r.lines)
          if (fresh[l.offeringId] != null)
            CartLine(
                offering: fresh[l.offeringId]!,
                quantity: l.quantity,
                hours: l.hours,
                discountType: l.discountType,
                discountValue: l.discountValue),
      ],
      discountType: r.discountType ?? 'PERCENT',
      discountValue: r.discountValue ?? 0,
      customer: customer,
      note: r.hold.note ?? '',
      hold: r.hold,
    ));
  }

  void clear() => _save(const Cart());
}

final cartProvider = NotifierProvider<CartNotifier, Cart>(CartNotifier.new);

/// The cart's totals, worked out exactly as the server will charge them.
final cartTotalsProvider = Provider<PricingTotals?>((ref) {
  final cart = ref.watch(cartProvider);
  final store = ref.watch(storeProvider).value;
  if (store == null) return null;
  return calculateTotals([
    for (final l in cart.lines)
      PricingLine(
        unitPrice: l.offering.price,
        quantity: l.quantity,
        hours: l.hours,
        tax: taxKeyFor(
          type: l.offering.type,
          taxRateId: l.offering.taxRateId,
          taxRateName: l.offering.taxRateName,
          taxRate: l.offering.taxRate,
          taxMode: store.taxMode,
          taxScope: store.taxScope,
          taxLabel: store.taxLabel,
          defaultTaxRateId: store.defaultTaxRateId,
        ),
        discountType: l.hasDiscount ? l.discountType : null,
        discountValue: l.hasDiscount ? l.discountValue : null,
      ),
  ], cart.discountValue > 0 ? cart.discountType : null, cart.discountValue);
});
