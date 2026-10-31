import 'dart:async';

import 'package:dio/dio.dart';

import 'models.dart';

/// An error response from the API, in RFC 9457 problem-detail form.
class ApiError implements Exception {
  ApiError(this.status, this.message, {this.code, this.fieldErrors = const {}});

  final int status;
  final String message;

  /// A machine-readable reason, e.g. PRICES_CHANGED.
  final String? code;
  final Map<String, String> fieldErrors;

  @override
  String toString() => message;
}

/// The server couldn't be reached (restarting, offline); the session itself may be fine.
class ServerUnavailableError extends ApiError {
  ServerUnavailableError() : super(503, "Can't reach the POS server right now. Check the connection and try again in a moment.");
}

/// Stores the refresh token between app starts (the keychain on iOS, the keystore on Android).
abstract class TokenStore {
  Future<String?> read();
  Future<void> write(String? token);
}

/// Talks to the POS API. The access token is kept in memory; the refresh token, which the server
/// sends as an HttpOnly cookie for browsers, is read from Set-Cookie, kept in [TokenStore] and sent
/// back as a Cookie header. The server rotates it on every use, so refreshes are shared.
class ApiClient {
  ApiClient(this.baseUrl, this.tokens)
      : _dio = Dio(BaseOptions(
          baseUrl: baseUrl,
          connectTimeout: const Duration(seconds: 10),
          receiveTimeout: const Duration(seconds: 30),
          // Errors are turned into ApiError below.
          validateStatus: (_) => true,
          headers: {'Accept': 'application/json'},
        ));

  /// e.g. https://api.example.com/api
  final String baseUrl;
  final TokenStore tokens;
  final Dio _dio;

  String? _accessToken;
  Future<TokenResponse?>? _refreshing;

  /// Called when the server says the session is over.
  void Function() onSessionEnded = () {};

  static const _refreshCookie = 'pos_refresh';

  /// The URL of a catalog entry's image (public, versioned so a new image is fetched straight away).
  String? imageUrl(Offering o) => o.hasImage ? '$baseUrl/offerings/${o.id}/image?v=${o.imageVersion}' : null;

  String logoUrl(int version) => '$baseUrl/settings/company/logo?v=$version';

  Future<TokenResponse> login(String username, String password) async {
    final res = await _send('POST', '/auth/login', json: {'username': username, 'password': password});
    if (res.statusCode != 200) throw _toError(res);
    return _takeSession(res);
  }

  /// Signs in again with the saved refresh token; null when there is none or it is no longer valid.
  Future<TokenResponse?> restore() => refreshSession();

  Future<void> logout() async {
    final token = await tokens.read();
    if (token != null) {
      try {
        await _send('POST', '/auth/logout', headers: {'Cookie': '$_refreshCookie=$token'});
      } catch (_) {
        // signing out locally is enough
      }
    }
    _accessToken = null;
    await tokens.write(null);
  }

  Future<TokenResponse> changePassword(String current, String next) async {
    final res = await _authed('POST', '/auth/password', json: {'currentPassword': current, 'newPassword': next});
    return _takeSession(res);
  }

  Future<T> get<T>(String path, T Function(dynamic json) parse, {Map<String, dynamic>? query}) async {
    final res = await _authed('GET', path, query: query);
    return parse(res.data);
  }

  Future<T> post<T>(String path, T Function(dynamic json) parse, {Object? json, Map<String, dynamic>? query}) async {
    final res = await _authed('POST', path, json: json, query: query);
    return parse(res.data);
  }

  Future<T> put<T>(String path, T Function(dynamic json) parse, {Object? json}) async {
    final res = await _authed('PUT', path, json: json);
    return parse(res.data);
  }

  Future<void> delete(String path) async {
    await _authed('DELETE', path);
  }

  /// Gets a new access token with the refresh token. Calls are shared, because the server rotates
  /// the token on every use. Null only when the server says the session is over (401/403); if the
  /// server can't be reached it retries briefly, then throws [ServerUnavailableError].
  Future<TokenResponse?> refreshSession() {
    return _refreshing ??= () async {
      try {
        final token = await tokens.read();
        if (token == null) return null;
        for (var attempt = 0;; attempt++) {
          Response<dynamic>? res;
          try {
            res = await _send('POST', '/auth/refresh', headers: {'Cookie': '$_refreshCookie=$token'});
          } on DioException {
            // network error: retry below
          }
          if (res != null && (res.statusCode == 401 || res.statusCode == 403)) {
            await tokens.write(null);
            return null;
          }
          if (res != null && res.statusCode == 200) return await _takeSession(res);
          if (attempt >= 3) throw ServerUnavailableError();
          await Future<void>.delayed(Duration(seconds: attempt + 1));
        }
      } finally {
        _refreshing = null;
      }
    }();
  }

  Future<TokenResponse> _takeSession(Response<dynamic> res) async {
    final session = TokenResponse.fromJson(res.data as Map<String, dynamic>);
    _accessToken = session.accessToken;
    for (final header in res.headers['set-cookie'] ?? const <String>[]) {
      final pair = header.split(';').first;
      final eq = pair.indexOf('=');
      if (eq > 0 && pair.substring(0, eq).trim() == _refreshCookie) {
        final value = pair.substring(eq + 1).trim();
        await tokens.write(value.isEmpty ? null : value);
      }
    }
    return session;
  }

  Future<Response<dynamic>> _authed(String method, String path, {Object? json, Map<String, dynamic>? query}) async {
    Future<Response<dynamic>> send() => _send(method, path,
        json: json, query: query, headers: {if (_accessToken != null) 'Authorization': 'Bearer $_accessToken'});

    Response<dynamic> res;
    try {
      res = await send();
      if (res.statusCode == 401) {
        final session = await refreshSession();
        if (session == null) {
          onSessionEnded();
          throw ApiError(401, 'Your session has ended. Please sign in again.');
        }
        res = await send();
      }
    } on DioException {
      throw ServerUnavailableError();
    }
    final status = res.statusCode ?? 0;
    if (status < 200 || status >= 300) throw _toError(res);
    return res;
  }

  Future<Response<dynamic>> _send(String method, String path,
      {Object? json, Map<String, dynamic>? query, Map<String, String>? headers}) {
    return _dio.request<dynamic>(
      path,
      data: json,
      queryParameters: query == null ? null : {for (final e in query.entries) if (e.value != null && e.value != '') e.key: e.value},
      options: Options(method: method, headers: headers, contentType: json == null ? null : Headers.jsonContentType),
    );
  }

  ApiError _toError(Response<dynamic> res) {
    final status = res.statusCode ?? 0;
    final body = res.data is Map ? res.data as Map : const {};
    final message = (body['detail'] ?? body['title'])?.toString() ??
        (status == 403 ? 'You do not have permission to do this' : 'Request failed ($status)');
    final errors = body['errors'] is Map
        ? (body['errors'] as Map).map((k, v) => MapEntry(k.toString(), v.toString()))
        : const <String, String>{};
    return ApiError(status, message, code: body['code']?.toString(), fieldErrors: errors);
  }
}
