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
import 'cart_view.dart';
import 'catalog_view.dart';
import 'khqr_screen.dart';

/// The till: the catalog, and the cart beside it on tablets or behind a bar on phones.
class SellScreen extends ConsumerStatefulWidget {
  const SellScreen({super.key});

  @override
  ConsumerState<SellScreen> createState() => _SellScreenState();
}

class _SellScreenState extends ConsumerState<SellScreen> {
  int? _checkedPending;

  /// A cart may still have a KHQR payment waiting (the app was closed during it): show its QR code
  /// again rather than let it be charged twice. If it was paid or cancelled meanwhile, settle the cart.
  Future<void> _settlePending(int orderId) async {
    _checkedPending = orderId;
    try {
      final order = await ref.read(apiProvider).get('/orders/$orderId', (j) => Order.fromJson(j as Map<String, dynamic>));
      if (!mounted) return;
      if (order.status == 'PENDING_PAYMENT') {
        Navigator.push(context, MaterialPageRoute(builder: (_) => KhqrScreen(orderId: orderId)));
      } else if (order.status != 'CANCELLED') {
        ref.read(cartProvider.notifier).clear();
        showSnack(context, t('Order {number} was paid by KHQR.', {'number': order.orderNumber}));
        Navigator.push(context, MaterialPageRoute(builder: (_) => ReceiptScreen(order: order)));
      } else {
        ref.read(cartProvider.notifier).setPendingOrder(null);
      }
    } on ApiError catch (e) {
      if (e.status == 404) ref.read(cartProvider.notifier).setPendingOrder(null);
    }
  }

  @override
  Widget build(BuildContext context) {
    final cart = ref.watch(cartProvider);
    final user = ref.watch(userProvider);
    final store = ref.watch(storeProvider);
    final pending = cart.pendingOrderId;
    if (pending != null && pending != _checkedPending) {
      WidgetsBinding.instance.addPostFrameCallback((_) => _settlePending(pending));
    }
    final wide = isTablet(context);
    final cartWidth = MediaQuery.sizeOf(context).width >= 1000 ? 400.0 : 340.0;

    final catalog = Column(children: [
      if (pending != null)
        MaterialBanner(
          content: Text(t('This sale is waiting for a KHQR payment.')),
          leading: const Icon(Icons.qr_code_2),
          actions: [
            TextButton(
              onPressed: () => Navigator.push(context, MaterialPageRoute(builder: (_) => KhqrScreen(orderId: pending))),
              child: Text(t('Show QR code')),
            ),
          ],
        ),
      const Expanded(child: CatalogView()),
    ]);

    return Scaffold(
      appBar: AppBar(
        title: Text(store.value?.name ?? t('Sell'), overflow: TextOverflow.ellipsis),
        actions: [
          Padding(
            padding: const EdgeInsets.only(right: 12),
            child: Center(child: Text(user.branchName, style: Theme.of(context).textTheme.bodySmall)),
          ),
        ],
      ),
      body: store.hasError
          ? ErrorView(store.error!, onRetry: () => ref.invalidate(storeProvider))
          : wide
              ? Row(children: [
                  Expanded(child: catalog),
                  const VerticalDivider(width: 1),
                  SizedBox(width: cartWidth, child: const CartView()),
                ])
              : catalog,
      bottomNavigationBar: wide || cart.isEmpty ? null : _CartBar(cart: cart),
    );
  }
}

/// Phones: what's in the cart, opening it full screen.
class _CartBar extends ConsumerWidget {
  const _CartBar({required this.cart});

  final Cart cart;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final totals = ref.watch(cartTotalsProvider);
    final base = ref.watch(currenciesProvider).value?.firstOrNull;
    final scheme = Theme.of(context).colorScheme;
    return Material(
      color: scheme.primary,
      child: InkWell(
        onTap: () => Navigator.push(
          context,
          MaterialPageRoute(
            fullscreenDialog: true,
            builder: (context) => Scaffold(body: SafeArea(child: CartView(onClose: () => Navigator.pop(context)))),
          ),
        ),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
          child: Row(children: [
            Badge(
              label: Text('${cart.itemCount}'),
              child: Icon(Icons.shopping_basket, color: scheme.onPrimary),
            ),
            const SizedBox(width: 16),
            Expanded(child: Text(t('View cart'), style: TextStyle(color: scheme.onPrimary, fontWeight: FontWeight.w600))),
            if (totals != null)
              Text(base == null ? money(totals.total, 'USD') : amountIn(totals.total, base),
                  style: TextStyle(color: scheme.onPrimary, fontWeight: FontWeight.w700, fontSize: 16)),
            Icon(Icons.chevron_right, color: scheme.onPrimary),
          ]),
        ),
      ),
    );
  }
}
