import 'dart:math' as math;

/// The tax a line is charged under; lines with equal keys are taxed together.
class TaxKey {
  const TaxKey({required this.rateId, required this.name, required this.rate, required this.included});

  final int rateId;
  final String name;
  final double rate;
  final bool included;

  @override
  bool operator ==(Object other) =>
      other is TaxKey && other.rateId == rateId && other.name == name && other.rate == rate && other.included == included;

  @override
  int get hashCode => Object.hash(rateId, name, rate, included);
}

class PricingLine {
  const PricingLine({
    required this.unitPrice,
    required this.quantity,
    this.hours,
    required this.tax,
    this.discountType,
    this.discountValue,
  });

  final double unitPrice;
  final int quantity;

  /// Hours worked, for hourly services only.
  final double? hours;
  final TaxKey tax;

  /// An item discount on this line: PERCENT or AMOUNT.
  final String? discountType;
  final double? discountValue;
}

class TaxLine {
  const TaxLine(this.key, this.base, this.tax);

  final TaxKey key;

  /// Net amount after discount.
  final double base;
  final double tax;
}

/// A line's share of the order: [discount] is its item discount plus its share of the order
/// discount; [net] is after both without tax; [paid] = net + tax.
class LineSplit {
  const LineSplit(this.total, this.itemDiscount, this.discount, this.net, this.tax, this.paid);

  final double total;
  final double itemDiscount;
  final double discount;
  final double net;
  final double tax;
  final double paid;
}

class PricingTotals {
  const PricingTotals({
    required this.subtotal,
    required this.itemDiscount,
    required this.discount,
    required this.taxAdded,
    required this.taxIncluded,
    required this.total,
    required this.taxes,
    required this.lines,
    required this.taxableAmount,
    required this.nonTaxableAmount,
  });

  /// Before any discount.
  final double subtotal;

  /// Item discounts on all lines.
  final double itemDiscount;

  /// The order discount, on the subtotal after item discounts.
  final double discount;
  final double taxAdded;
  final double taxIncluded;
  final double total;
  final List<TaxLine> taxes;

  /// Per input line, same order.
  final List<LineSplit> lines;
  final double taxableAmount;
  final double nonTaxableAmount;
}

/// What lines outside the tax (No VAT, or a type not in scope) are labelled. Same as TaxService.NOT_TAXED.
const notTaxed = 'Not taxed';

/// The tax a catalog entry is charged under. Mirrors TaxService.keyFor: nothing under No VAT or
/// for types outside the scope; the default rate is named after the store's tax label; an exempt
/// (0%) rate keeps its name; included only in VAT included mode.
TaxKey taxKeyFor({
  required String type,
  required int taxRateId,
  required String taxRateName,
  required double taxRate,
  required String taxMode,
  required Set<String> taxScope,
  required String taxLabel,
  required int? defaultTaxRateId,
}) {
  if (taxMode == 'NONE' || !taxScope.contains(type)) {
    return TaxKey(rateId: taxRateId, name: notTaxed, rate: 0, included: false);
  }
  final taxed = taxRate > 0;
  final name = taxRateId == defaultTaxRateId ? taxLabel : taxRateName;
  return TaxKey(rateId: taxRateId, name: name, rate: taxed ? taxRate : 0, included: taxed && taxMode == 'INCLUSIVE');
}

// Works in (whole-valued) double cents, exactly like pricing.ts, so both give the same results.
double _toCents(double amount) => _jsRound(amount * 100);

/// JavaScript's Math.round: half rounds up.
double _jsRound(double x) => (x + 0.5).floorToDouble();

/// Half-up rounding for non-negative values.
double _roundCents(double cents) => _jsRound(cents + 1e-9);

double _lineCents(double unitPrice, int quantity, double? hours) => _roundCents(_toCents(unitPrice) * quantity * (hours ?? 1));

double lineTotal(double unitPrice, int quantity, double? hours) => _lineCents(unitPrice, quantity, hours) / 100;

/// Cents taken off [cents] by a % or amount discount (an amount is capped at the whole).
double _discountCents(double cents, String? type, double? value) {
  if (type == null || value == null || value <= 0) return 0;
  return type == 'PERCENT' ? _roundCents(cents * math.min(value, 100) / 100) : math.min(_toCents(value), cents);
}

/// The money an item discount takes off a line.
double itemDiscountOf(PricingLine l) =>
    _discountCents(_lineCents(l.unitPrice, l.quantity, l.hours), l.discountType, l.discountValue) / 100;

class _Group {
  _Group(this.key);

  final TaxKey key;
  double gross = 0;
  final members = <int>[];
}

/// Mirrors PricingCalculator.java and pricing.ts: item discounts first, then the order discount
/// shared over tax groups by value, then per group an included tax is taken out (amount x r / (1 + r))
/// and an added tax goes on top (amount x r); each group's discount and tax are shared over its
/// lines by value, the last line taking the leftover cent.
PricingTotals calculateTotals(List<PricingLine> lines, String? discountType, double discountValue) {
  final groups = <TaxKey, _Group>{};
  final gross = [for (final l in lines) _lineCents(l.unitPrice, l.quantity, l.hours)];
  final itemOff = [for (var i = 0; i < lines.length; i++) _discountCents(gross[i], lines[i].discountType, lines[i].discountValue)];
  final cents = [for (var i = 0; i < lines.length; i++) gross[i] - itemOff[i]];
  final grossSubtotal = gross.fold<double>(0, (n, c) => n + c);
  final itemDiscount = itemOff.fold<double>(0, (n, c) => n + c);
  var subtotal = 0.0;
  for (var i = 0; i < lines.length; i++) {
    subtotal += cents[i];
    final g = groups.putIfAbsent(lines[i].tax, () => _Group(lines[i].tax));
    g.gross += cents[i];
    g.members.add(i);
  }

  final discount = _discountCents(subtotal, discountType, discountValue);

  final taxes = <TaxLine>[];
  final splits = List<LineSplit?>.filled(lines.length, null);
  var taxable = 0.0, nonTaxable = 0.0, added = 0.0, included = 0.0;
  var left = discount;
  final all = groups.values.toList();
  for (var i = 0; i < all.length; i++) {
    final g = all[i];
    final share = i == all.length - 1 ? left : (subtotal == 0 ? 0.0 : _roundCents(discount * g.gross / subtotal));
    left -= share;
    final amount = g.gross - share;
    double tax, base;
    if (g.key.included) {
      tax = _roundCents(amount * g.key.rate / (1 + g.key.rate));
      base = amount - tax;
      included += tax;
    } else {
      base = amount;
      tax = _roundCents(amount * g.key.rate);
      added += tax;
    }
    taxes.add(TaxLine(g.key, base / 100, tax / 100));
    if (g.key.rate > 0) {
      taxable += base;
    } else {
      nonTaxable += base;
    }

    var dLeft = share, tLeft = tax;
    for (var n = 0; n < g.members.length; n++) {
      final j = g.members[n];
      final t = cents[j];
      final last = n == g.members.length - 1;
      final d = last ? dLeft : (g.gross == 0 ? 0.0 : _roundCents(share * t / g.gross));
      final lt = last ? tLeft : (g.gross == 0 ? 0.0 : _roundCents(tax * t / g.gross));
      dLeft -= d;
      tLeft -= lt;
      final net = g.key.included ? t - d - lt : t - d;
      splits[j] = LineSplit(gross[j] / 100, itemOff[j] / 100, (itemOff[j] + d) / 100, net / 100, lt / 100, (net + lt) / 100);
    }
  }

  return PricingTotals(
    subtotal: grossSubtotal / 100,
    itemDiscount: itemDiscount / 100,
    discount: discount / 100,
    taxAdded: added / 100,
    taxIncluded: included / 100,
    total: (subtotal - discount + added) / 100,
    taxes: taxes,
    lines: splits.cast<LineSplit>(),
    taxableAmount: taxable / 100,
    nonTaxableAmount: nonTaxable / 100,
  );
}

/// "VAT 10%"
String taxLabelOf(String name, double rate) {
  if (rate <= 0 || name.contains('%')) return name;
  final pct = double.parse((rate * 100).toStringAsFixed(2));
  return '$name ${pct == pct.roundToDouble() ? pct.toInt() : pct}%';
}
