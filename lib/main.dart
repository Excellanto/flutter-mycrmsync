import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mycrmsync/app_router.dart';
import 'package:mycrmsync/theme/app_theme.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const ProviderScope(child: MyCrmSyncApp()));
}

class MyCrmSyncApp extends ConsumerWidget {
  const MyCrmSyncApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final router = ref.watch(routerProvider);
    return MaterialApp.router(
      title: 'MyCrmSync',
      debugShowCheckedModeBanner: false,
      theme: buildAppTheme(),
      routerConfig: router,
      builder: (context, child) => ColoredBox(
        color: Colors.white,
        child: PostCallHost(child: child ?? const SizedBox.shrink()),
      ),
    );
  }
}

