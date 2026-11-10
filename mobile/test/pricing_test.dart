import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:pos_mobile/core/pricing.dart';

double? _n(dynamic v) => v == null ? null : double.parse(v.toString());

/// The same cases PricingCalculator.java and pricing.ts are checked against.
void main() {
  final cases = jsonDecode(File('../backend/src/test/resources/pricing-cases.json').readAsStringSync()) as List;

  for (final c in cases.cast<Map<String, dynamic>>()) {
    test(c['name'] as String, () {
      final lines = [
        for (final l in (c['lines'] as List).cast<Map<String, dynamic>>())
          PricingLine(
            unitPrice: _n(l['unitPrice'])!,
            quantity: l['quantity'] as int,
            hours: _n(l['hours']),
            tax: TaxKey(
              rateId: l['tax']['rateId'] as int,
              name: l['tax']['name'] as String,
              rate: _n(l['tax']['rate'])!,
              included: l['tax']['included'] as bool,
            ),
            discountType: l['discountType'] as String?,
            discountValue: _n(l['discountValue']),
            promoDiscount: _n(l['promoDiscount']),
          ),
      ];
      final t = calculateTotals(lines, c['discountType'] as String?, _n(c['discountValue']) ?? 0,
          orderPromo: _n(c['orderPromo']) ?? 0, points: _n(c['points']) ?? 0);
      final e = c['expected'] as Map<String, dynamic>;
      // Promotion and points cases also pin down each layer.
      if (e.containsKey('promoDiscount')) {
        expect(t.promoDiscount, _n(e['promoDiscount']), reason: 'promoDiscount');
        expect(t.orderPromoDiscount, _n(e['orderPromoDiscount']), reason: 'orderPromoDiscount');
        expect(t.pointsDiscount, _n(e['pointsDiscount']), reason: 'pointsDiscount');
      }
      expect(t.subtotal, _n(e['subtotal']), reason: 'subtotal');
      expect(t.itemDiscount, _n(e['itemDiscount']) ?? 0, reason: 'itemDiscount');
      expect(t.discount, _n(e['discount']), reason: 'discount');
      expect(t.taxAdded, _n(e['taxAdded']), reason: 'taxAdded');
      expect(t.taxIncluded, _n(e['taxIncluded']), reason: 'taxIncluded');
      expect(t.total, _n(e['total']), reason: 'total');
      expect(t.taxableAmount, _n(e['taxableAmount']), reason: 'taxableAmount');
      expect(t.nonTaxableAmount, _n(e['nonTaxableAmount']), reason: 'nonTaxableAmount');
      final el = (e['lines'] as List?)?.cast<Map<String, dynamic>>() ?? const [];
      for (var i = 0; i < el.length; i++) {
        expect(t.lines[i].total, _n(el[i]['total']), reason: 'line $i total');
        expect(t.lines[i].discount, _n(el[i]['discount']), reason: 'line $i discount');
        if (el[i].containsKey('promoDiscount')) {
          expect(t.lines[i].promoDiscount, _n(el[i]['promoDiscount']), reason: 'line $i promoDiscount');
        }
        expect(t.lines[i].net, _n(el[i]['net']), reason: 'line $i net');
        expect(t.lines[i].tax, _n(el[i]['tax']), reason: 'line $i tax');
        expect(t.lines[i].paid, _n(el[i]['paid']), reason: 'line $i paid');
      }
    });
  }
}
