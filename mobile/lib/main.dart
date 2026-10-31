import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'core/i18n.dart';
import 'screens/change_password_screen.dart';
import 'screens/home_shell.dart';
import 'screens/login_screen.dart';
import 'state/session.dart';
import 'widgets/common.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final prefs = await SharedPreferences.getInstance();
  runApp(ProviderScope(
    overrides: [prefsProvider.overrideWithValue(prefs)],
    child: const PosApp(),
  ));
}

class PosApp extends ConsumerWidget {
  const PosApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final lang = ref.watch(langProvider);
    final session = ref.watch(sessionProvider);
    return MaterialApp(
      // A new key rebuilds every screen in the new language.
      key: ValueKey(lang),
      title: 'POS',
      debugShowCheckedModeBanner: false,
      theme: buildTheme(Brightness.light),
      darkTheme: buildTheme(Brightness.dark),
      home: switch (session) {
        SessionLoading() => Scaffold(
            body: Center(
              child: Column(mainAxisSize: MainAxisSize.min, children: [
                const CircularProgressIndicator(),
                const SizedBox(height: 16),
                Text(t('Loading…')),
              ]),
            ),
          ),
        SignedOut(:final message) => LoginScreen(message: message),
        SignedIn(:final user) when user.mustChangePassword => const ChangePasswordScreen(required: true),
        SignedIn() => const HomeShell(),
      },
    );
  }
}
