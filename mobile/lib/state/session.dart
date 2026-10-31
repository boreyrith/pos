import 'dart:io';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../api/api_client.dart';
import '../api/models.dart';
import '../core/i18n.dart';

/// Loaded in main() before the app starts.
final prefsProvider = Provider<SharedPreferences>((ref) => throw UnimplementedError());

/// The API address, e.g. https://api.example.com/api. Built with --dart-define=API_URL=... for a
/// store; otherwise the local dev backend (the Android emulator reaches the Mac at 10.0.2.2).
String defaultServerUrl() {
  const fromBuild = String.fromEnvironment('API_URL');
  if (fromBuild.isNotEmpty) return fromBuild;
  return Platform.isAndroid ? 'http://10.0.2.2:8081/api' : 'http://localhost:8081/api';
}

/// Accepts "pos.example.com", "https://api.example.com" or ".../api" and returns the API base URL.
String normalizeServerUrl(String input) {
  var url = input.trim().replaceAll(RegExp(r'/+$'), '');
  if (!url.startsWith(RegExp(r'https?://'))) url = 'https://$url';
  if (!url.endsWith('/api')) url = '$url/api';
  return url;
}

class _SecureTokenStore implements TokenStore {
  _SecureTokenStore(this.server);

  final String server;
  static const _storage = FlutterSecureStorage();

  // One token per server, so switching servers doesn't send a token where it doesn't belong.
  String get _key => 'refresh:$server';

  @override
  Future<String?> read() => _storage.read(key: _key);

  @override
  Future<void> write(String? token) => token == null ? _storage.delete(key: _key) : _storage.write(key: _key, value: token);
}

class ServerUrlNotifier extends Notifier<String> {
  @override
  String build() => ref.watch(prefsProvider).getString('serverUrl') ?? defaultServerUrl();

  Future<void> set(String url) async {
    await ref.read(prefsProvider).setString('serverUrl', url);
    state = url;
  }
}

final serverUrlProvider = NotifierProvider<ServerUrlNotifier, String>(ServerUrlNotifier.new);

final apiProvider = Provider<ApiClient>((ref) {
  final url = ref.watch(serverUrlProvider);
  final api = ApiClient(url, _SecureTokenStore(url));
  api.onSessionEnded = () => ref.read(sessionProvider.notifier).ended();
  return api;
});

sealed class Session {
  const Session();
}

class SessionLoading extends Session {
  const SessionLoading();
}

class SignedOut extends Session {
  const SignedOut([this.message]);

  /// Why, e.g. the session ended or the server couldn't be reached.
  final String? message;
}

class SignedIn extends Session {
  const SignedIn(this.user);

  final User user;
}

class SessionNotifier extends Notifier<Session> {
  User? lastUser;

  @override
  Session build() {
    ref.watch(apiProvider);
    _restore();
    return const SessionLoading();
  }

  Future<void> _restore() async {
    try {
      final tokens = await ref.read(apiProvider).restore();
      _signedIn(tokens);
    } on ApiError catch (e) {
      state = SignedOut(e.message);
    }
  }

  void _signedIn(TokenResponse? tokens) {
    if (tokens == null) {
      state = const SignedOut();
      return;
    }
    currentLang = tokens.user.language;
    ref.read(langProvider.notifier).set(tokens.user.language, remember: false);
    lastUser = tokens.user;
    state = SignedIn(tokens.user);
  }

  Future<void> login(String username, String password) async {
    _signedIn(await ref.read(apiProvider).login(username, password));
  }

  Future<void> changePassword(String current, String next) async {
    _signedIn(await ref.read(apiProvider).changePassword(current, next));
  }

  Future<void> logout() async {
    await ref.read(apiProvider).logout();
    state = const SignedOut();
  }

  void ended() => state = SignedOut(t('Your session has ended. Please sign in again.'));

  /// Saves the language on the account, so it follows the user to every device.
  Future<void> setLanguage(String lang) async {
    ref.read(langProvider.notifier).set(lang);
    final s = state;
    if (s is SignedIn) {
      final user = await ref.read(apiProvider).put('/auth/me/language', (j) => User.fromJson(j as Map<String, dynamic>),
          json: {'language': lang});
      lastUser = user;
      state = SignedIn(user);
    }
  }
}

final sessionProvider = NotifierProvider<SessionNotifier, Session>(SessionNotifier.new);

/// The signed-in user (screens below the sign-in gate only).
/// Screens are taken down a moment after the session ends, so during sign-out this is still the last user.
final userProvider = Provider<User>((ref) {
  final session = ref.watch(sessionProvider);
  return session is SignedIn ? session.user : ref.read(sessionProvider.notifier).lastUser!;
});

/// Who is signed in, for what must start afresh for the next person on the same device.
final userIdProvider = Provider<int?>((ref) => ref.watch(sessionProvider.select((s) => s is SignedIn ? s.user.id : null)));

class LangNotifier extends Notifier<String> {
  @override
  String build() {
    currentLang = ref.read(prefsProvider).getString('lang') ?? 'en';
    return currentLang;
  }

  void set(String lang, {bool remember = true}) {
    currentLang = lang;
    if (remember) ref.read(prefsProvider).setString('lang', lang);
    state = lang;
  }
}

final langProvider = NotifierProvider<LangNotifier, String>(LangNotifier.new);
