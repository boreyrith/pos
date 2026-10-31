import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../core/i18n.dart';
import '../state/data.dart';
import '../state/session.dart';
import 'change_password_screen.dart';

/// The signed-in user, language, server and signing out.
class MoreScreen extends ConsumerWidget {
  const MoreScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(userProvider);
    final lang = ref.watch(langProvider);
    final store = ref.watch(storeProvider).value;
    final server = ref.watch(serverUrlProvider);
    return Scaffold(
      appBar: AppBar(title: Text(t('More'))),
      body: ListView(children: [
        ListTile(
          leading: CircleAvatar(child: Text(user.fullName.isEmpty ? '?' : user.fullName.characters.first.toUpperCase())),
          title: Text(user.fullName),
          subtitle: Text('${user.username} · ${t(user.role.name)} · ${user.branchName}'),
        ),
        const Divider(),
        ListTile(
          leading: const Icon(Icons.translate),
          title: Text(t('Language')),
          trailing: SegmentedButton<String>(
            segments: const [ButtonSegment(value: 'en', label: Text('EN')), ButtonSegment(value: 'km', label: Text('ខ្មែរ'))],
            selected: {lang},
            showSelectedIcon: false,
            onSelectionChanged: (s) => ref.read(sessionProvider.notifier).setLanguage(s.first),
          ),
        ),
        ListTile(
          leading: const Icon(Icons.lock_outline),
          title: Text(t('Change password')),
          onTap: () => Navigator.push(context, MaterialPageRoute(builder: (_) => const ChangePasswordScreen())),
        ),
        ListTile(
          leading: const Icon(Icons.refresh),
          title: Text(t('Reload store settings')),
          subtitle: Text(t('Prices, currencies, rates and categories')),
          onTap: () {
            ref.invalidate(storeProvider);
            ref.invalidate(currenciesProvider);
            ref.invalidate(categoriesProvider);
            ref.invalidate(catalogProvider);
          },
        ),
        const Divider(),
        ListTile(
          leading: const Icon(Icons.storefront_outlined),
          title: Text(store?.name ?? ''),
          subtitle: Text(Uri.tryParse(server)?.host ?? server),
        ),
        ListTile(
          leading: Icon(Icons.logout, color: Theme.of(context).colorScheme.error),
          title: Text(t('Sign out'), style: TextStyle(color: Theme.of(context).colorScheme.error)),
          onTap: () => ref.read(sessionProvider.notifier).logout(),
        ),
      ]),
    );
  }
}
