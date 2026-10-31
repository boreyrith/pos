import 'package:intl/intl.dart';

import '../api/models.dart';

/// Formats an amount in a currency, e.g. "$12.35" or "50,700 ៛". Mirrors amountIn in format.ts.
String amountIn(double amount, Currency c) {
  final f = NumberFormat.decimalPatternDigits(locale: 'en_US', decimalDigits: c.decimals);
  final n = f.format(amount);
  return c.code == 'USD' ? (amount < 0 ? '-\$${n.substring(1)}' : '\$$n') : '$n ${c.symbol}';
}

/// Formats an amount in a currency known only by code (USD gets a $, others the code).
String money(double amount, String code) {
  if (code == 'USD') return NumberFormat.currency(locale: 'en_US', symbol: '\$').format(amount);
  return '${NumberFormat.decimalPatternDigits(locale: 'en_US', decimalDigits: code == 'KHR' ? 0 : 2).format(amount)} $code';
}

/// Rounds to the nearest amount that can be handed over in cash, e.g. 50,696.75 riel to 50,700.
double roundCash(double amount, Currency c) {
  final steps = (amount / c.cashRounding + 1e-9).roundToDouble();
  return double.parse((steps * c.cashRounding).toStringAsFixed(c.decimals));
}

/// A base amount in another currency at [rate] (today's by default), rounded for cash.
double cashIn(double baseAmount, Currency c, {double? rate}) => roundCash(baseAmount * (rate ?? c.rate ?? 1), c);

/// Active currencies with a rate, base first.
List<Currency> activeCurrencies(List<Currency> all) {
  final list = all.where((c) => (c.active || c.base) && c.rate != null).toList()
    ..sort((a, b) => a.base == b.base ? a.sortOrder.compareTo(b.sortOrder) : (a.base ? -1 : 1));
  return list;
}

/// "2 pcs", "1.5", trimmed of needless decimals.
String trimNum(double n) => n == n.roundToDouble() ? n.toInt().toString() : n.toStringAsFixed(2).replaceFirst(RegExp(r'0+$'), '');
