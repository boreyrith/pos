import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../api/models.dart';
import '../../core/i18n.dart';
import '../../core/money.dart';
import '../../state/cart.dart';
import '../../state/data.dart';
import '../../state/session.dart';
import '../../widgets/common.dart';
import 'scanner_screen.dart';

/// Asks for the hours worked on an hourly service; null when cancelled.
Future<double?> askHours(BuildContext context, Offering o, {double? initial}) {
  final controller = TextEditingController(text: initial == null ? '' : trimNum(initial));
  return showDialog<double>(
    context: context,
    builder: (context) {
      void done() {
        final h = double.tryParse(controller.text.replaceAll(',', '.'));
        if (h != null && h > 0 && h <= 999.99) Navigator.pop(context, (h * 100).roundToDouble() / 100);
      }

      return AlertDialog(
        title: Text(o.displayName(currentLang)),
        content: TextField(
          controller: controller,
          autofocus: true,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          onSubmitted: (_) => done(),
          decoration: InputDecoration(labelText: t('Hours worked'), suffixText: t('h')),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: Text(t('Cancel'))),
          FilledButton(onPressed: done, child: Text(t('Add'))),
        ],
      );
    },
  );
}

/// Adds an entry to the cart, asking for hours first when it is an hourly service.
Future<void> addToCart(BuildContext context, WidgetRef ref, Offering o) async {
  double? hours;
  if (o.hourly) {
    hours = await askHours(context, o);
    if (hours == null) return;
  }
  ref.read(cartProvider.notifier).add(o, hours: hours);
}

class CatalogView extends ConsumerStatefulWidget {
  const CatalogView({super.key});

  @override
  ConsumerState<CatalogView> createState() => _CatalogViewState();
}

class _CatalogViewState extends ConsumerState<CatalogView> {
  final _search = TextEditingController();
  Timer? _debounce;
  String _q = '';
  int? _categoryId;
  bool _favourites = false;

  @override
  void dispose() {
    _debounce?.cancel();
    _search.dispose();
    super.dispose();
  }

  void _onSearch(String value) {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 300), () => setState(() => _q = value));
  }

  /// Looks a barcode (or SKU) up and adds what it finds.
  Future<void> _addCode(String code) async {
    try {
      final o = await ref.read(apiProvider).get('/offerings/barcode/${Uri.encodeComponent(code)}',
          (j) => Offering.fromJson(j as Map<String, dynamic>));
      if (!mounted) return;
      await addToCart(context, ref, o);
      if (mounted) showSnack(context, t('Added {name}', {'name': o.displayName(currentLang)}));
    } catch (e) {
      if (mounted) showSnack(context, errorText(e));
    }
  }

  Future<void> _scan() async {
    final code = await Navigator.push<String>(context, MaterialPageRoute(builder: (_) => const ScannerScreen()));
    if (code != null) await _addCode(code);
  }

  Future<void> _toggleFavourite(Offering o, bool isFavourite) async {
    final api = ref.read(apiProvider);
    try {
      if (isFavourite) {
        await api.delete('/offerings/${o.id}/favourite');
      } else {
        await api.put('/offerings/${o.id}/favourite', (_) => null);
      }
      ref.invalidate(favouriteIdsProvider);
      if (_favourites) ref.invalidate(catalogProvider);
    } catch (e) {
      if (mounted) showSnack(context, errorText(e));
    }
  }

  @override
  Widget build(BuildContext context) {
    final query = (q: _q, categoryId: _favourites ? null : _categoryId, favourites: _favourites && _q.isEmpty);
    final catalog = ref.watch(catalogProvider(query));
    final categories = ref.watch(categoriesProvider).value ?? const <Category>[];
    final favourites = ref.watch(favouriteIdsProvider).value ?? const <int>{};
    final base = ref.watch(currenciesProvider).value?.firstOrNull;

    return Column(children: [
      Padding(
        padding: const EdgeInsets.fromLTRB(12, 8, 12, 4),
        child: TextField(
          controller: _search,
          onChanged: _onSearch,
          textInputAction: TextInputAction.search,
          // A hardware scanner types the code and presses Enter.
          onSubmitted: (v) {
            if (v.trim().isEmpty) return;
            final items = catalog.value ?? const <Offering>[];
            if (items.length == 1) {
              addToCart(context, ref, items.first);
            } else {
              _addCode(v.trim());
            }
            _search.clear();
            setState(() => _q = '');
          },
          decoration: InputDecoration(
            hintText: t('Search name, SKU or barcode'),
            prefixIcon: const Icon(Icons.search),
            suffixIcon: Row(mainAxisSize: MainAxisSize.min, children: [
              if (_search.text.isNotEmpty)
                IconButton(
                  icon: const Icon(Icons.clear),
                  onPressed: () {
                    _search.clear();
                    setState(() => _q = '');
                  },
                ),
              IconButton(tooltip: t('Scan barcode'), icon: const Icon(Icons.qr_code_scanner), onPressed: _scan),
            ]),
          ),
        ),
      ),
      SizedBox(
        height: 48,
        child: ListView(
          scrollDirection: Axis.horizontal,
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
          children: [
            _chip(t('All'), !_favourites && _categoryId == null, () => setState(() {
                  _favourites = false;
                  _categoryId = null;
                })),
            _chip(t('Favourites'), _favourites, () => setState(() => _favourites = true), icon: Icons.star),
            for (final c in categories)
              _chip(c.name,!_favourites && _categoryId == c.id, () => setState(() {
                    _favourites = false;
                    _categoryId = c.id;
                  })),
          ],
        ),
      ),
      Expanded(
        child: catalog.when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (e, _) => ErrorView(e, onRetry: () => ref.invalidate(catalogProvider(query))),
          data: (items) {
            if (items.isEmpty) {
              return Center(child: Text(_favourites ? t('No favourites yet. Long-press an item to add it.') : t('Nothing found')));
            }
            return RefreshIndicator(
              onRefresh: () async {
                ref.invalidate(catalogProvider(query));
                await ref.read(catalogProvider(query).future);
              },
              child: GridView.builder(
                padding: const EdgeInsets.fromLTRB(12, 4, 12, 12),
                gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
                  maxCrossAxisExtent: 170,
                  mainAxisSpacing: 10,
                  crossAxisSpacing: 10,
                  childAspectRatio: 0.78,
                ),
                itemCount: items.length,
                itemBuilder: (context, i) {
                  final o = items[i];
                  final fav = favourites.contains(o.id);
                  return _OfferingTile(
                    offering: o,
                    price: base == null ? money(o.price, 'USD') : amountIn(o.price, base),
                    favourite: fav,
                    onTap: () => addToCart(context, ref, o),
                    onLongPress: () => _toggleFavourite(o, fav),
                  );
                },
              ),
            );
          },
        ),
      ),
    ]);
  }

  Widget _chip(String label, bool selected, VoidCallback onTap, {IconData? icon}) => Padding(
        padding: const EdgeInsets.only(right: 6),
        child: ChoiceChip(
          label: Text(label),
          avatar: icon == null ? null : Icon(icon, size: 16),
          selected: selected,
          showCheckmark: false,
          onSelected: (_) => onTap(),
        ),
      );
}

class _OfferingTile extends StatelessWidget {
  const _OfferingTile({
    required this.offering,
    required this.price,
    required this.favourite,
    required this.onTap,
    required this.onLongPress,
  });

  final Offering offering;
  final String price;
  final bool favourite;
  final VoidCallback onTap;
  final VoidCallback onLongPress;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final stock = offering.stock;
    final out = stock != null && stock <= 0;
    return Card(
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        onLongPress: onLongPress,
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Expanded(
            child: Stack(fit: StackFit.expand, children: [
              OfferingImage(offering),
              if (favourite) const Positioned(top: 6, right: 6, child: Icon(Icons.star, color: Colors.amber, size: 20)),
              if (stock != null)
                Positioned(
                  left: 6,
                  bottom: 6,
                  child: Container(
                    padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
                    decoration: BoxDecoration(
                      color: (out ? Brand.danger : Colors.black).withValues(alpha: 0.65),
                      borderRadius: BorderRadius.circular(10),
                    ),
                    child: Text(out ? t('Out of stock') : '$stock ${offering.unit ?? ''}'.trim(),
                        style: const TextStyle(color: Colors.white, fontSize: 11)),
                  ),
                ),
            ]),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(8, 6, 8, 8),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(offering.displayName(currentLang), maxLines: 2, overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontSize: 13, height: 1.2)),
              const SizedBox(height: 2),
              Text(offering.hourly ? '$price / ${t('h')}' : price,
                  style: TextStyle(fontWeight: FontWeight.w700, color: scheme.primary)),
            ]),
          ),
        ]),
      ),
    );
  }
}
