import 'km_mobile.dart';
import 'km_web.g.dart';

/// The interface language, "en" or "km". The app rebuilds from the root when it changes.
String currentLang = 'en';

/// `t(text, vars)` in the current language: the app's own Khmer first, then the web app's.
/// {placeholders} are filled from [vars]. Text with no Khmer passes through in English.
String t(String text, [Map<String, Object?> vars = const {}]) {
  final khmer = currentLang == 'km' ? (kmMobile[text] ?? kmWeb[text]) : null;
  return _fill(khmer ?? text.split('||').first, vars);
}

String _fill(String s, Map<String, Object?> vars) =>
    vars.isEmpty ? s : s.replaceAllMapped(RegExp(r'\{(\w+)\}'), (m) => vars.containsKey(m[1]) ? '${vars[m[1]]}' : m[0]!);

/// Translates a message from the server (errors, validation): an exact match first, then the
/// patterns for messages with names and numbers in them. Mirrors translateServer in i18n.ts.
String translateServer(String text) {
  if (currentLang != 'km' || text.isEmpty) return text;
  final exact = kmMobile[text] ?? kmWeb[text];
  if (exact != null) return exact;
  for (final (pattern, khmer) in kmPatterns) {
    final m = pattern.firstMatch(text);
    if (m != null) {
      return khmer.replaceAllMapped(RegExp(r'\$(\d)'), (g) => translateServer(m.group(int.parse(g[1]!)) ?? ''));
    }
  }
  return text;
}

/// The text to show for an error from the API or elsewhere.
String errorText(Object error) => translateServer(error.toString().replaceFirst('Exception: ', ''));
