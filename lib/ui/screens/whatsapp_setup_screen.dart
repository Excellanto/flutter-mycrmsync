import 'package:flutter/material.dart';
import 'package:mycrmsync/core/config.dart';
import 'package:mycrmsync/platform/native_bridges.dart';
import 'package:mycrmsync/theme/app_theme.dart';
import 'package:mycrmsync/ui/widgets/app_shell.dart';

class WhatsAppSetupScreen extends StatefulWidget {
  const WhatsAppSetupScreen({super.key, required this.onNavigate});

  final void Function(String route) onNavigate;

  @override
  State<WhatsAppSetupScreen> createState() => _WhatsAppSetupScreenState();
}

class _WhatsAppSetupScreenState extends State<WhatsAppSetupScreen>
    with WidgetsBindingObserver {
  bool _enabled = false;
  bool _tracking = true;
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refresh();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refresh();
  }

  Future<void> _refresh() async {
    if (!AppConfig.isAndroid) {
      setState(() => _loading = false);
      return;
    }
    final enabled = await NativeBridges.rememberIfEnabled();
    final tracking = await NativeBridges.isTrackingEnabled();
    if (!mounted) return;
    setState(() {
      _enabled = enabled;
      _tracking = tracking;
      _loading = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    if (!AppConfig.isAndroid) {
      return AppShell(
        title: 'WhatsApp Tracking',
        currentRoute: 'whatsapp_setup',
        onNavigate: widget.onNavigate,
        child: const Center(
          child: Padding(
            padding: EdgeInsets.all(24),
            child: Text(
              'WhatsApp call tracking requires Android accessibility services and is not available on iOS.',
              textAlign: TextAlign.center,
            ),
          ),
        ),
      );
    }

    return AppShell(
      title: 'WhatsApp Tracking',
      currentRoute: 'whatsapp_setup',
      onNavigate: widget.onNavigate,
      child: _loading
          ? const Center(child: CircularProgressIndicator())
          : ListView(
              padding: const EdgeInsets.all(16),
              children: [
                Card(
                  child: ListTile(
                    leading: Icon(
                      _enabled ? Icons.check_circle : Icons.warning_amber,
                      color: _enabled ? AppColors.successGreen : Colors.orange,
                    ),
                    title: Text(
                      _enabled
                          ? 'Accessibility service enabled'
                          : 'Accessibility service off',
                    ),
                    subtitle: const Text(
                      'Detects WhatsApp calls and suggests matching CRM contacts. No audio is recorded.',
                    ),
                    trailing: FilledButton(
                      onPressed: () => NativeBridges.openAccessibilitySettings(),
                      child: Text(_enabled ? 'Settings' : 'Enable'),
                    ),
                  ),
                ),
                const SizedBox(height: 12),
                SwitchListTile(
                  title: const Text('Track WhatsApp calls'),
                  subtitle: const Text('Queue completed calls for CRM registration'),
                  value: _tracking,
                  onChanged: (v) async {
                    await NativeBridges.setTrackingEnabled(v);
                    setState(() => _tracking = v);
                  },
                ),
              ],
            ),
    );
  }
}
