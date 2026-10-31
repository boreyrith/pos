import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../core/i18n.dart';
import '../state/data.dart';
import '../state/session.dart';
import '../widgets/common.dart';
import 'holds_screen.dart';
import 'more_screen.dart';
import 'orders_screen.dart';
import 'sell/sell_screen.dart';

/// Tabs for what the user may do; a bottom bar on phones, a rail on tablets.
final homeTabProvider = NotifierProvider<HomeTab, int>(HomeTab.new);

class HomeTab extends Notifier<int> {
  /// Everyone starts on the first tab, whichever tab the last person left open.
  @override
  int build() {
    ref.watch(userIdProvider);
    return 0;
  }

  void select(int index) {
    // Lists other tills change too: show them fresh each time their tab is opened.
    ref.invalidate(ordersProvider);
    ref.invalidate(heldSalesProvider);
    state = index;
  }
}

class HomeShell extends ConsumerWidget {
  const HomeShell({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(userProvider);
    final tabs = <(IconData, IconData, String, Widget)>[
      if (user.can('SALES_SELL')) (Icons.point_of_sale_outlined, Icons.point_of_sale, t('Sell'), const SellScreen()),
      if (user.can('ORDERS_VIEW') || user.can('SALES_SELL'))
        (Icons.receipt_long_outlined, Icons.receipt_long, t('Orders'), const OrdersScreen()),
      if (user.can('SALES_SELL')) (Icons.pause_circle_outline, Icons.pause_circle, t('Held sales'), const HoldsScreen()),
      (Icons.person_outline, Icons.person, t('More'), const MoreScreen()),
    ];
    final index = ref.watch(homeTabProvider).clamp(0, tabs.length - 1);
    final body = IndexedStack(index: index, children: [for (final tab in tabs) tab.$4]);
    final wide = isTablet(context);

    if (wide) {
      return Scaffold(
        body: Row(children: [
          NavigationRail(
            selectedIndex: index,
            labelType: NavigationRailLabelType.all,
            onDestinationSelected: ref.read(homeTabProvider.notifier).select,
            destinations: [
              for (final tab in tabs) NavigationRailDestination(icon: Icon(tab.$1), selectedIcon: Icon(tab.$2), label: Text(tab.$3)),
            ],
          ),
          const VerticalDivider(width: 1),
          Expanded(child: body),
        ]),
      );
    }
    return Scaffold(
      body: body,
      bottomNavigationBar: NavigationBar(
        selectedIndex: index,
        onDestinationSelected: ref.read(homeTabProvider.notifier).select,
        destinations: [for (final tab in tabs) NavigationDestination(icon: Icon(tab.$1), selectedIcon: Icon(tab.$2), label: tab.$3)],
      ),
    );
  }
}
