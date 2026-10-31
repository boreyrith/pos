import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../api/models.dart';
import '../../core/i18n.dart';
import '../../state/session.dart';
import '../../widgets/common.dart';

/// Picks a customer by name, phone or code. Returns a walk-in customer to sell to nobody in particular.
Future<Customer?> pickCustomer(BuildContext context) => showModalBottomSheet<Customer>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      builder: (_) => const _CustomerPicker(),
    );

class _CustomerPicker extends ConsumerStatefulWidget {
  const _CustomerPicker();

  @override
  ConsumerState<_CustomerPicker> createState() => _CustomerPickerState();
}

class _CustomerPickerState extends ConsumerState<_CustomerPicker> {
  Timer? _debounce;
  Future<List<Customer>>? _results;

  @override
  void initState() {
    super.initState();
    _search('');
  }

  @override
  void dispose() {
    _debounce?.cancel();
    super.dispose();
  }

  void _search(String q) {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 250), () {
      setState(() {
        _results = ref.read(apiProvider).get('/customers', (j) => ApiPage.parse(j, Customer.fromJson).items,
            query: {'q': q.trim(), 'activeOnly': true, 'size': 20});
      });
    });
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(context).bottom),
      child: SizedBox(
        height: MediaQuery.sizeOf(context).height * 0.75,
        child: Column(children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
            child: TextField(
              autofocus: true,
              onChanged: _search,
              decoration: InputDecoration(hintText: t('Search name, phone or code'), prefixIcon: const Icon(Icons.search)),
            ),
          ),
          ListTile(
            leading: const Icon(Icons.person_outline),
            title: Text(t('Walk-in Customer')),
            onTap: () => Navigator.pop(context, Customer(id: 0, name: '', walkIn: true)),
          ),
          const Divider(height: 1),
          Expanded(
            child: FutureBuilder<List<Customer>>(
              future: _results,
              builder: (context, snap) {
                if (snap.hasError) return ErrorView(snap.error!);
                if (!snap.hasData) return const Center(child: CircularProgressIndicator());
                final list = snap.data!.where((c) => !c.walkIn).toList();
                if (list.isEmpty) return Center(child: Text(t('No customers found')));
                return ListView.builder(
                  itemCount: list.length,
                  itemBuilder: (context, i) {
                    final c = list[i];
                    return ListTile(
                      leading: CircleAvatar(child: Text(c.name.isEmpty ? '?' : c.name.characters.first.toUpperCase())),
                      title: Text(c.name),
                      subtitle: Text([c.code, c.phone].whereType<String>().join(' · ')),
                      onTap: () => Navigator.pop(context, c),
                    );
                  },
                );
              },
            ),
          ),
        ]),
      ),
    );
  }
}
