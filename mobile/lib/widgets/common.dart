import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../api/models.dart';
import '../core/i18n.dart';
import '../state/session.dart';

/// Brand colours, as on the web app.
abstract final class Brand {
  static const primary = Color(0xFF1D5FB3);
  static const danger = Color(0xFFB42318);
  static const success = Color(0xFF067647);
  static const warning = Color(0xFFB54708);
}

ThemeData buildTheme(Brightness brightness) {
  final scheme = ColorScheme.fromSeed(seedColor: Brand.primary, brightness: brightness, primary: brightness == Brightness.light ? Brand.primary : null);
  return ThemeData(
    colorScheme: scheme,
    useMaterial3: true,
    // Khmer needs a font that has it; the system fallback handles it on both platforms.
    inputDecorationTheme: const InputDecorationTheme(border: OutlineInputBorder(), isDense: true),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(minimumSize: const Size(64, 48), shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10))),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(minimumSize: const Size(64, 48), shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10))),
    ),
    cardTheme: CardThemeData(
      elevation: 0,
      margin: EdgeInsets.zero,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12), side: BorderSide(color: scheme.outlineVariant)),
    ),
  );
}

/// Tablets (every iPad, upright or sideways) show the rail and the cart beside the catalog. Goes by
/// width, so an iPad sharing its screen with another app in a narrow split falls back to the phone layout.
bool isTablet(BuildContext context) => MediaQuery.sizeOf(context).width >= 700;

/// An error with a retry button.
class ErrorView extends StatelessWidget {
  const ErrorView(this.error, {super.key, this.onRetry});

  final Object error;
  final VoidCallback? onRetry;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          Icon(Icons.cloud_off, size: 40, color: Theme.of(context).colorScheme.error),
          const SizedBox(height: 12),
          Text(errorText(error), textAlign: TextAlign.center),
          if (onRetry != null) ...[
            const SizedBox(height: 12),
            OutlinedButton(onPressed: onRetry, child: Text(t('Try again'))),
          ],
        ]),
      ),
    );
  }
}

/// An inline error note, for forms.
class ErrorNote extends StatelessWidget {
  const ErrorNote(this.error, {super.key});

  final Object? error;

  @override
  Widget build(BuildContext context) {
    if (error == null) return const SizedBox.shrink();
    final scheme = Theme.of(context).colorScheme;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(color: scheme.errorContainer, borderRadius: BorderRadius.circular(8)),
      child: Text(errorText(error!), style: TextStyle(color: scheme.onErrorContainer)),
    );
  }
}

void showSnack(BuildContext context, String message) {
  ScaffoldMessenger.of(context)
    ..hideCurrentSnackBar()
    ..showSnackBar(SnackBar(content: Text(message), behavior: SnackBarBehavior.floating));
}

/// A catalog entry's picture, or its initials on a tinted tile.
class OfferingImage extends ConsumerWidget {
  const OfferingImage(this.offering, {super.key, this.size});

  final Offering offering;
  final double? size;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final url = ref.watch(apiProvider).imageUrl(offering);
    final scheme = Theme.of(context).colorScheme;
    final placeholder = Container(
      width: size,
      height: size,
      color: scheme.primaryContainer.withValues(alpha: 0.5),
      alignment: Alignment.center,
      child: Text(
        offering.name.trim().isEmpty ? '?' : offering.name.trim().characters.first.toUpperCase(),
        style: TextStyle(fontSize: (size ?? 64) * 0.4, color: scheme.onPrimaryContainer, fontWeight: FontWeight.w600),
      ),
    );
    if (url == null) return placeholder;
    return CachedNetworkImage(
      imageUrl: url,
      width: size,
      height: size,
      fit: BoxFit.cover,
      placeholder: (_, _) => placeholder,
      errorWidget: (_, _, _) => placeholder,
    );
  }
}

/// "24 Oct 2026, 13:05" in local time.
String dateTime(DateTime d) => DateFormat('d MMM y, HH:mm').format(d.toLocal());

/// English labels for order statuses (translated with t()).
String orderStatusLabel(String status) => switch (status) {
      'PENDING_PAYMENT' => t('Awaiting payment'),
      'COMPLETED' => t('Completed'),
      'CANCELLED' => t('Cancelled'),
      'PARTIALLY_REFUNDED' => t('Partially refunded'),
      'REFUNDED' => t('Refunded'),
      _ => status,
    };

class StatusChip extends StatelessWidget {
  const StatusChip(this.status, {super.key});

  final String status;

  @override
  Widget build(BuildContext context) {
    final color = switch (status) {
      'COMPLETED' => Brand.success,
      'PENDING_PAYMENT' || 'PARTIALLY_REFUNDED' => Brand.warning,
      'CANCELLED' || 'REFUNDED' => Brand.danger,
      _ => Colors.grey,
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(color: color.withValues(alpha: 0.12), borderRadius: BorderRadius.circular(20)),
      child: Text(orderStatusLabel(status), style: TextStyle(color: color, fontSize: 12, fontWeight: FontWeight.w600)),
    );
  }
}

/// A label and a value on one line, for totals.
class AmountRow extends StatelessWidget {
  const AmountRow(this.label, this.value, {super.key, this.bold = false, this.color});

  final String label;
  final String value;
  final bool bold;
  final Color? color;

  @override
  Widget build(BuildContext context) {
    final style = TextStyle(
      fontWeight: bold ? FontWeight.w700 : FontWeight.normal,
      fontSize: bold ? 18 : 14,
      color: color,
      fontFeatures: const [FontFeature.tabularFigures()],
    );
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(children: [Expanded(child: Text(label, style: style)), Text(value, style: style)]),
    );
  }
}
