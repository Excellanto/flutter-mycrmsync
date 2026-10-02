import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mycrmsync/data/api_client.dart';
import 'package:mycrmsync/data/session_repository.dart';
import 'package:mycrmsync/platform/native_bridges.dart';

final sessionRepositoryProvider = Provider<SessionRepository>((ref) {
  return SessionRepository();
});

final sessionProvider =
    StateNotifierProvider<SessionNotifier, AsyncValue<SessionData?>>((ref) {
  return SessionNotifier(ref.watch(sessionRepositoryProvider));
});

class SessionNotifier extends StateNotifier<AsyncValue<SessionData?>> {
  SessionNotifier(this._repo) : super(const AsyncValue.loading()) {
    _load();
  }

  final SessionRepository _repo;

  Future<void> _load() async {
    try {
      final session = await _repo.load();
      // Re-mirror to Android crm_prefs so WorkManager workers stay authenticated.
      if (session != null && session.isLoggedIn) {
        await NativeBridges.setSession(session.toNativeMap());
        await NativeBridges.schedulePeriodicIfLoggedIn();
      }
      state = AsyncValue.data(session);
    } catch (e, st) {
      state = AsyncValue.error(e, st);
    }
  }

  Future<void> save(SessionData session) async {
    await _repo.save(session);
    state = AsyncValue.data(session);
  }

  Future<void> clear() async {
    await _repo.clear();
    state = const AsyncValue.data(null);
  }

  Future<void> reload() => _load();
}

final apiClientProvider = Provider<ApiClient>((ref) {
  final repo = ref.watch(sessionRepositoryProvider);
  return ApiClient(
    sessionRepository: repo,
    onUnauthorized: () {
      ref.read(sessionProvider.notifier).clear();
    },
  );
});

final authApiProvider = Provider<AuthApi>((ref) {
  return AuthApi(ref.watch(apiClientProvider));
});

final crmApiProvider = Provider<CrmApi>((ref) {
  return CrmApi(ref.watch(apiClientProvider));
});

class SelectedDashboardTask {
  const SelectedDashboardTask({
    required this.title,
    required this.body,
    required this.dueLabel,
    required this.statusLabel,
    required this.contactName,
    required this.isReminder,
  });

  final String title;
  final String body;
  final String dueLabel;
  final String statusLabel;
  final String contactName;
  final bool isReminder;
}

final selectedDashboardTaskProvider = StateProvider<SelectedDashboardTask?>((ref) => null);
