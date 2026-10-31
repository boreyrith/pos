import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../api/api_client.dart';
import '../api/models.dart';
import '../core/i18n.dart';
import '../core/money.dart';
import '../state/cart.dart';
import '../state/data.dart';
import '../state/session.dart';
import '../widgets/common.dart';
import 'home_shell.dart';

/// Sales parked to finish later, here or on another till.
class HoldsScreen extends ConsumerWidget {
  const HoldsScreen({super.key});

  Future<void> _resume(BuildContext context, WidgetRef ref, HeldSale hold, {bool takeOver = false}) async {
    final cart = ref.read(cartProvider);
    if (!cart.isEmpty || cart.pendingOrderId != null) {
      showSnack(context, t('Finish or hold the current sale first.'));
      return;
    }
    try {
      final resumed = await ref.read(apiProvider).post('/held-sales/${hold.id}/resume',
          (j) => ResumedHold.fromJson(j as Map<String, dynamic>), query: takeOver ? {'takeOver': true} : null);
      await ref.read(cartProvider.notifier).loadHold(resumed);
      ref.invalidate(heldSalesProvider);
      ref.read(homeTabProvider.notifier).select(0);
      if (context.mounted && resumed.warnings.isNotEmpty) {
        showSnack(context, resumed.warnings.map(translateServer).join('\n'));
      }
    } on ApiError catch (e) {
      if (!context.mounted) return;
      // In use on another till: offer to take it over.
      if (e.status == 409 && hold.lockedBy != null && !hold.lockedByMe && !takeOver) {
        final ok = await showDialog<bool>(
          context: context,
          builder: (context) => AlertDialog(
            content: Text(t('{name} has this sale open. Take it over?', {'name': hold.lockedBy})),
            actions: [
              TextButton(onPressed: () => Navigator.pop(context, false), child: Text(t('Cancel'))),
              FilledButton(onPressed: () => Navigator.pop(context, true), child: Text(t('Take over'))),
            ],
          ),
        );
        if (ok == true && context.mounted) await _resume(context, ref, hold, takeOver: true);
        return;
      }
      showSnack(context, errorText(e));
    }
  }

  Future<void> _void(BuildContext context, WidgetRef ref, HeldSale hold) async {
    final controller = TextEditingController();
    final reason = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(t('Void {hold}', {'hold': hold.holdNumber})),
        content: TextField(controller: controller, autofocus: true, decoration: InputDecoration(labelText: t('Reason'))),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: Text(t('Cancel'))),
          FilledButton(onPressed: () => Navigator.pop(context, controller.text.trim()), child: Text(t('Void'))),
        ],
      ),
    );
    if (reason == null || reason.isEmpty) return;
    try {
      await ref.read(apiProvider).post('/held-sales/${hold.id}/void', (_) => null, json: {'reason': reason});
      ref.invalidate(heldSalesProvider);
    } catch (e) {
      if (context.mounted) showSnack(context, errorText(e));
    }
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final holds = ref.watch(heldSalesProvider);
    final base = ref.watch(currenciesProvider).value?.firstOrNull;
    final scheme = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(title: Text(t('Held sales'))),
      body: holds.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => ErrorView(e, onRetry: () => ref.invalidate(heldSalesProvider)),
        data: (list) => RefreshIndicator(
          onRefresh: () async {
            ref.invalidate(heldSalesProvider);
            await ref.read(heldSalesProvider.future);
          },
          child: list.isEmpty
              ? ListView(children: [Padding(padding: const EdgeInsets.all(48), child: Center(child: Text(t('No held sales'))))])
              : ListView.separated(
                  padding: const EdgeInsets.all(12),
                  itemCount: list.length,
                  separatorBuilder: (_, _) => const SizedBox(height: 8),
                  itemBuilder: (context, i) {
                    final h = list[i];
                    final inUse = h.status == 'IN_USE';
                    return Card(
                      child: ListTile(
                        contentPadding: const EdgeInsets.fromLTRB(16, 8, 4, 8),
                        title: Text(h.label?.isNotEmpty == true ? '${h.label} · ${h.holdNumber}' : h.holdNumber),
                        subtitle: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                          Text([
                            h.itemCount == 1 ? t('1 item') : t('{count} items', {'count': h.itemCount}),
                            if (h.customerName != null) h.customerName!,
                            h.createdBy,
                            DateFormat('d MMM, HH:mm').format(h.updatedAt.toLocal()),
                          ].join(' · ')),
                          if (inUse)
                            Text(h.lockedByMe ? t('Open on your till') : t('Open on {name}\'s till', {'name': h.lockedBy}),
                                style: const TextStyle(color: Brand.warning)),
                          if (h.note != null) Text(h.note!, style: TextStyle(color: scheme.outline, fontStyle: FontStyle.italic)),
                        ]),
                        trailing: Row(mainAxisSize: MainAxisSize.min, children: [
                          Text(base == null ? money(h.estimatedTotal, 'USD') : amountIn(h.estimatedTotal, base),
                              style: const TextStyle(fontWeight: FontWeight.w700)),
                          PopupMenuButton<String>(
                            onSelected: (v) => v == 'void' ? _void(context, ref, h) : _resume(context, ref, h),
                            itemBuilder: (_) => [
                              PopupMenuItem(value: 'resume', child: Text(t('Resume'))),
                              PopupMenuItem(value: 'void', child: Text(t('Void'))),
                            ],
                          ),
                        ]),
                        onTap: () => _resume(context, ref, h),
                      ),
                    );
                  },
                ),
        ),
      ),
    );
  }
}
