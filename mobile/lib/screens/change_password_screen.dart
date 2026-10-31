import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../core/i18n.dart';
import '../state/session.dart';
import '../widgets/common.dart';

/// Change your own password. [required]: the account must choose a new one before using the app.
class ChangePasswordScreen extends ConsumerStatefulWidget {
  const ChangePasswordScreen({super.key, this.required = false});

  final bool required;

  @override
  ConsumerState<ChangePasswordScreen> createState() => _ChangePasswordScreenState();
}

class _ChangePasswordScreenState extends ConsumerState<ChangePasswordScreen> {
  final _current = TextEditingController();
  final _next = TextEditingController();
  final _confirm = TextEditingController();
  bool _busy = false;
  Object? _error;

  @override
  void dispose() {
    _current.dispose();
    _next.dispose();
    _confirm.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_next.text.length < 8) {
      setState(() => _error = t('Use at least 8 characters.'));
      return;
    }
    if (_next.text != _confirm.text) {
      setState(() => _error = t('The new passwords don\'t match.'));
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await ref.read(sessionProvider.notifier).changePassword(_current.text, _next.text);
      if (mounted && !widget.required) {
        showSnack(context, t('Password changed'));
        Navigator.pop(context);
      }
    } catch (e) {
      if (mounted) setState(() => _error = e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(t('Change password')),
        actions: [
          if (widget.required)
            TextButton(onPressed: () => ref.read(sessionProvider.notifier).logout(), child: Text(t('Sign out'))),
        ],
      ),
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 400),
              child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                if (widget.required) ...[
                  Text(t('Choose a new password before you continue.')),
                  const SizedBox(height: 16),
                ],
                TextField(controller: _current, obscureText: true, decoration: InputDecoration(labelText: t('Current password'))),
                const SizedBox(height: 12),
                TextField(controller: _next, obscureText: true, decoration: InputDecoration(labelText: t('New password'))),
                const SizedBox(height: 12),
                TextField(
                  controller: _confirm,
                  obscureText: true,
                  onSubmitted: (_) => _submit(),
                  decoration: InputDecoration(labelText: t('Confirm new password')),
                ),
                const SizedBox(height: 16),
                ErrorNote(_error),
                if (_error != null) const SizedBox(height: 16),
                FilledButton(onPressed: _busy ? null : _submit, child: Text(t('Change password'))),
              ]),
            ),
          ),
        ),
      ),
    );
  }
}
