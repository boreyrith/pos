import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../api/models.dart';
import '../core/money.dart';
import 'session.dart';

/// Company details and tax settings every screen needs.
final storeProvider = FutureProvider<StoreSettings>((ref) {
  return ref.watch(apiProvider).get('/store', (j) => StoreSettings.fromJson(j as Map<String, dynamic>));
});

/// Active currencies with a rate, base first. Rates change during the day: refreshed on each checkout.
final currenciesProvider = FutureProvider<List<Currency>>((ref) async {
  final all = await ref.watch(apiProvider).get('/currencies', (j) => (j as List).map((e) => Currency.fromJson(e)).toList());
  return activeCurrencies(all);
});

final categoriesProvider = FutureProvider<List<Category>>((ref) {
  return ref.watch(apiProvider).get('/categories', (j) => (j as List).map((e) => Category.fromJson(e)).toList());
});

final favouriteIdsProvider = FutureProvider<Set<int>>((ref) async {
  // Favourites are each user's own.
  ref.watch(userIdProvider);
  final ids = await ref.watch(apiProvider).get('/offerings/favourites/ids', (j) => (j as List).cast<int>());
  return ids.toSet();
});

/// What the catalog shows: everything (optionally searched or in a category), or favourites.
typedef CatalogQuery = ({String q, int? categoryId, bool favourites});

final catalogProvider = FutureProvider.autoDispose.family<List<Offering>, CatalogQuery>((ref, query) async {
  final api = ref.watch(apiProvider);
  ref.watch(userIdProvider);
  if (query.favourites) {
    return api.get('/offerings/favourites', (j) => (j as List).map((e) => Offering.fromJson(e)).toList());
  }
  final page = await api.get('/offerings', (j) => ApiPage.parse(j, Offering.fromJson), query: {
    'q': query.q.trim(),
    'categoryId': query.categoryId,
    'forSale': true,
    'size': 200,
  });
  return page.items;
});

/// Today's catalog entries for these ids (for a restored or resumed cart).
Future<List<Offering>> freshOfferings(Ref ref, Iterable<int> ids) async {
  if (ids.isEmpty) return [];
  final page = await ref.read(apiProvider).get('/offerings', (j) => ApiPage.parse(j, Offering.fromJson),
      query: {'ids': ids.toSet().join(','), 'forSale': true, 'size': 200});
  return page.items;
}

/// Open held sales (HELD and IN_USE) for this branch.
final heldSalesProvider = FutureProvider.autoDispose<List<HeldSale>>((ref) {
  return ref.watch(apiProvider).get('/held-sales', (j) => (j as List).map((e) => HeldSale.fromJson(e)).toList());
});

/// A day's orders, optionally searched.
typedef OrdersQuery = ({String q, DateTime day});

final ordersProvider = FutureProvider.autoDispose.family<List<OrderSummary>, OrdersQuery>((ref, query) async {
  final day = DateFormat('yyyy-MM-dd').format(query.day);
  final page = await ref.watch(apiProvider).get('/orders', (j) => ApiPage.parse(j, OrderSummary.fromJson),
      query: {'q': query.q.trim(), 'from': day, 'to': day, 'size': 100});
  return page.items;
});
