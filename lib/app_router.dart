import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:mycrmsync/core/config.dart';
import 'package:mycrmsync/platform/native_bridges.dart';
import 'package:mycrmsync/providers/providers.dart';
import 'package:mycrmsync/ui/screens/call_logs_screen.dart';
import 'package:mycrmsync/ui/screens/connect_account_screen.dart';
import 'package:mycrmsync/ui/screens/contact_notes_screen.dart';
import 'package:mycrmsync/ui/screens/contacts_screen.dart';
import 'package:mycrmsync/ui/screens/dashboard_screen.dart';
import 'package:mycrmsync/ui/screens/splash_screen.dart';
import 'package:mycrmsync/ui/screens/tasks_screen.dart';
import 'package:mycrmsync/ui/screens/verify_otp_screen.dart';
import 'package:mycrmsync/ui/screens/whatsapp_post_call_dialog.dart';
import 'package:mycrmsync/ui/screens/whatsapp_setup_screen.dart';

final _rootNavigatorKey = GlobalKey<NavigatorState>();

GoRouter createRouter(Ref ref) {
  return GoRouter(
    navigatorKey: _rootNavigatorKey,
    initialLocation: '/splash',
    refreshListenable: _SessionRefresh(ref),
    redirect: (context, state) {
      final sessionAsync = ref.read(sessionProvider);
      if (sessionAsync.isLoading && state.matchedLocation == '/splash') {
        return null;
      }
      final loggedIn = sessionAsync.valueOrNull?.isLoggedIn == true;
      final loc = state.matchedLocation;
      final authRoutes = {'/splash', '/connect', '/verify'};
      if (!loggedIn && !authRoutes.any(loc.startsWith) && loc != '/splash') {
        return '/connect';
      }
      if (loggedIn && (loc == '/connect' || loc.startsWith('/verify'))) {
        return '/dashboard';
      }
      return null;
    },
    routes: [
      GoRoute(
        path: '/splash',
        builder: (context, state) => SplashScreen(
          onDone: () {
            final loggedIn =
                ref.read(sessionProvider).valueOrNull?.isLoggedIn == true;
            context.go(loggedIn ? '/dashboard' : '/connect');
          },
        ),
      ),
      GoRoute(
        path: '/connect',
        builder: (context, state) => ConnectAccountScreen(
          onOtpSent: (email) => context.go(
            '/verify?email=${Uri.encodeComponent(email)}',
          ),
        ),
      ),
      GoRoute(
        path: '/verify',
        builder: (context, state) {
          final email =
              Uri.decodeComponent(state.uri.queryParameters['email'] ?? '');
          return VerifyOtpScreen(
            email: email,
            onBack: () => context.go('/connect'),
            onVerified: () => context.go('/dashboard'),
          );
        },
      ),
      GoRoute(
        path: '/dashboard',
        builder: (context, state) => DashboardScreen(
          onNavigate: (route) => _handleNav(context, route),
        ),
      ),
      GoRoute(
        path: '/tasks',
        builder: (context, state) => TasksScreen(
          onNavigate: (route) => _handleNav(context, route),
        ),
      ),
      GoRoute(
        path: '/contacts',
        builder: (context, state) => ContactsScreen(
          onNavigate: (route) => _handleNav(context, route),
          onOpenNotes: (id, name) => context.go(
            '/notes/${Uri.encodeComponent(id)}/${Uri.encodeComponent(name)}',
          ),
        ),
      ),
      GoRoute(
        path: '/calls',
        builder: (context, state) => CallLogsScreen(
          onNavigate: (route) => _handleNav(context, route),
        ),
      ),
      GoRoute(
        path: '/whatsapp_setup',
        builder: (context, state) {
          if (!AppConfig.isAndroid) {
            return DashboardScreen(onNavigate: (r) => _handleNav(context, r));
          }
          return WhatsAppSetupScreen(
            onNavigate: (route) => _handleNav(context, route),
          );
        },
      ),
      GoRoute(
        path: '/notes/:id/:name',
        builder: (context, state) {
          final id = Uri.decodeComponent(state.pathParameters['id']!);
          final name = Uri.decodeComponent(state.pathParameters['name']!);
          return ContactNotesScreen(
            contactId: id,
            contactName: name,
            onBack: () => context.go('/contacts'),
          );
        },
      ),
    ],
  );
}

void _handleNav(BuildContext context, String route) {
  if (route == 'logout') {
    context.go('/connect');
    return;
  }
  context.go('/$route');
}

class _SessionRefresh extends ChangeNotifier {
  _SessionRefresh(this.ref) {
    ref.listen(sessionProvider, (_, __) => notifyListeners());
  }

  final Ref ref;
}

final routerProvider = Provider<GoRouter>((ref) => createRouter(ref));

/// Hosts post-call dialog listening + cold-start fingerprint consume.
class PostCallHost extends ConsumerStatefulWidget {
  const PostCallHost({super.key, required this.child});

  final Widget child;

  @override
  ConsumerState<PostCallHost> createState() => _PostCallHostState();
}

class _PostCallHostState extends ConsumerState<PostCallHost> {
  StreamSubscription? _sub;
  Map<String, dynamic>? _payload;

  @override
  void initState() {
    super.initState();
    if (AppConfig.isAndroid) {
      _sub = NativeBridges.whatsAppPostCallEvents().listen((event) {
        if (!mounted) return;
        setState(() => _payload = event);
      });
      NativeBridges.consumePostCallFingerprint().then((fp) {
        if (fp != null && fp.isNotEmpty && mounted) {
          setState(() => _payload = {'syncFingerprint': fp, 'displayName': 'WhatsApp'});
        }
      });
    }
  }

  @override
  void dispose() {
    _sub?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Stack(
      children: [
        widget.child,
        if (_payload != null)
          Positioned.fill(
            child: ColoredBox(
              color: Colors.black54,
              child: Center(
                child: WhatsAppPostCallDialog(
                  payload: _payload!,
                  onClosed: () => setState(() => _payload = null),
                ),
              ),
            ),
          ),
      ],
    );
  }
}
