import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../api/models.dart';
import '../core/i18n.dart';
import '../core/money.dart';
import '../state/data.dart';
import '../widgets/common.dart';
import 'receipt_screen.dart';

/// A day's orders at this branch, newest first.
class OrdersScreen extends ConsumerStatefulWidget {
  const OrdersScreen({super.key});

  @override
  ConsumerState<OrdersScreen> createState() => _OrdersScreenState();
}

class _OrdersScreenState extends ConsumerState<OrdersScreen> {
  String _q = '';
  DateTime _day = DateTime.now();
  Timer? _debounce;

  @override
  void dispose() {
    _debounce?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final query = (q: _q, day: DateTime(_day.year, _day.month, _day.day));
    final orders = ref.watch(ordersProvider(query));
    final currencies = ref.watch(currenciesProvider).value ?? const <Currency>[];
    final today = DateUtils.isSameDay(_day, DateTime.now());
    String fmt(double v, String code) {
      final c = currencies.where((c) => c.code == code).firstOrNull;
      return c == null ? money(v, code) : amountIn(v, c);
    }

    return Scaffold(
      appBar: AppBar(
        title: Text(t('Orders')),
        actions: [
          TextButton.icon(
            icon: const Icon(Icons.calendar_today, size: 18),
            label: Text(today ? t('Today') : DateFormat('d MMM y').format(_day)),
            onPressed: () async {
              final picked = await showDatePicker(
                context: context,
                initialDate: _day,
                firstDate: DateTime(2020),
                lastDate: DateTime.now(),
              );
              if (picked != null) setState(() => _day = picked);
            },
          ),
        ],
      ),
      body: Column(children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
          child: TextField(
            onChanged: (v) {
              _debounce?.cancel();
              _debounce = Timer(const Duration(milliseconds: 300), () => setState(() => _q = v));
            },
            decoration: InputDecoration(hintText: t('Search order number or customer'), prefixIcon: const Icon(Icons.search)),
          ),
        ),
        Expanded(
          child: orders.when(
            loading: () => const Center(child: CircularProgressIndicator()),
            error: (e, _) => ErrorView(e, onRetry: () => ref.invalidate(ordersProvider(query))),
            data: (list) {
              final paid = list.where((o) => o.status == 'COMPLETED' || o.status == 'PARTIALLY_REFUNDED');
              final sum = paid.fold<double>(0, (s, o) => s + o.total);
              return RefreshIndicator(
                onRefresh: () async {
                  ref.invalidate(ordersProvider(query));
                  await ref.read(ordersProvider(query).future);
                },
                child: list.isEmpty
                    ? ListView(children: [Padding(padding: const EdgeInsets.all(48), child: Center(child: Text(t('No orders'))))])
                    : ListView.separated(
                        itemCount: list.length + 1,
                        separatorBuilder: (_, _) => const Divider(height: 1),
                        itemBuilder: (context, i) {
                          if (i == 0) {
                            return ListTile(
                              dense: true,
                              title: Text(t('{count} orders', {'count': list.length})),
                              trailing: list.isEmpty ? null : Text(fmt(sum, list.first.currency),
                                  style: const TextStyle(fontWeight: FontWeight.w700)),
                            );
                          }
                          final o = list[i - 1];
                          return ListTile(
                            title: Row(children: [Text(o.orderNumber), const SizedBox(width: 8), StatusChip(o.status)]),
                            subtitle: Text(
                              '${DateFormat('HH:mm').format(o.createdAt.toLocal())} · ${o.walkIn ? (o.customerName ?? t('Walk-in Customer')) : o.customerName ?? ''} · ${o.cashierName}',
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                            ),
                            trailing: Text(fmt(o.total, o.currency), style: const TextStyle(fontWeight: FontWeight.w600)),
                            onTap: () => Navigator.push(context, MaterialPageRoute(builder: (_) => OrderDetailScreen(orderId: o.id))),
                          );
                        },
                      ),
              );
            },
          ),
        ),
      ]),
    );
  }
}
