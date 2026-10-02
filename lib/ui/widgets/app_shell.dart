import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mycrmsync/core/config.dart';
import 'package:mycrmsync/platform/native_bridges.dart';
import 'package:mycrmsync/providers/providers.dart';
import 'package:mycrmsync/theme/app_theme.dart';

class AppShell extends ConsumerWidget {
  const AppShell({
    super.key,
    required this.title,
    required this.child,
    required this.currentRoute,
    required this.onNavigate,
    this.actions,
    this.floatingActionButton,
  });

  final String title;
  final Widget child;
  final String currentRoute;
  final void Function(String route) onNavigate;
  final List<Widget>? actions;
  final Widget? floatingActionButton;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final session = ref.watch(sessionProvider).valueOrNull;
    return Scaffold(
      backgroundColor: AppColors.lightBg,
      appBar: AppBar(
        backgroundColor: AppColors.lightBg,
        surfaceTintColor: Colors.transparent,
        title: Text(
          title,
          style: const TextStyle(
            fontWeight: FontWeight.bold,
            color: AppColors.textMain,
          ),
        ),
        actions: actions,
      ),
      drawer: Drawer(
        child: SafeArea(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              DrawerHeader(
                decoration: const BoxDecoration(color: AppColors.darkBlue),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text(
                      'MyCrmSync',
                      style: TextStyle(
                        color: Colors.white,
                        fontSize: 22,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      session?.userName ?? session?.authUserEmail ?? 'Signed in',
                      style: const TextStyle(color: Colors.white70),
                    ),
                  ],
                ),
              ),
              _item(Icons.dashboard_outlined, 'Dashboard', 'dashboard'),
              _item(Icons.task_alt_outlined, 'My Tasks', 'tasks'),
              _item(Icons.people_outline, 'Contacts', 'contacts'),
              _item(Icons.call_outlined, 'Call Logs', 'calls'),
              if (AppConfig.isAndroid)
                _item(Icons.chat_outlined, 'WhatsApp Tracking', 'whatsapp_setup'),
              const Spacer(),
              if (!AppConfig.isAndroid)
                const Padding(
                  padding: EdgeInsets.all(16),
                  child: Text(
                    'Call recording and WhatsApp tracking are available on Android.',
                    style: TextStyle(color: AppColors.textSec, fontSize: 12),
                  ),
                ),
              ListTile(
                leading: const Icon(Icons.logout, color: AppColors.errorRed),
                title: const Text('Logout'),
                onTap: () async {
                  Navigator.pop(context);
                  await ref.read(sessionProvider.notifier).clear();
                  onNavigate('logout');
                },
              ),
            ],
          ),
        ),
      ),
      floatingActionButton: floatingActionButton,
      body: child,
    );
  }

  Widget _item(IconData icon, String label, String route) {
    final selected = currentRoute == route;
    return ListTile(
      leading: Icon(icon, color: selected ? AppColors.primaryBlue : null),
      title: Text(
        label,
        style: TextStyle(
          fontWeight: selected ? FontWeight.w600 : FontWeight.normal,
          color: selected ? AppColors.primaryBlue : null,
        ),
      ),
      selected: selected,
      onTap: () => onNavigate(route),
    );
  }
}

Future<void> promptOverlayIfNeeded(BuildContext context) async {
  if (!AppConfig.isAndroid) return;
  final can = await NativeBridges.canDrawOverlays();
  if (can || !context.mounted) return;
  await showDialog<void>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: const Text('Display over other apps'),
      content: const Text(
        'Allow MyCrmSync to show CRM contact suggestions during WhatsApp calls, and note summaries on top of the dialer.',
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Not now')),
        FilledButton(
          onPressed: () {
            Navigator.pop(ctx);
            NativeBridges.openOverlaySettings();
          },
          child: const Text('Allow'),
        ),
      ],
    ),
  );
}
