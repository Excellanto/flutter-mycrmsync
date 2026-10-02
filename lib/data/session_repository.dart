import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:mycrmsync/platform/native_bridges.dart';
import 'package:shared_preferences/shared_preferences.dart';

class SessionData {
  const SessionData({
    required this.accessToken,
    this.tokenType = 'Bearer',
    this.expiresAt,
    this.authUserId,
    this.authUserName,
    this.authUserEmail,
    this.authTenantId,
    this.authIntsysuser,
    this.authUserRole,
    this.integratedUserId,
    this.integratedUserName,
    this.integratedUserEmail,
    this.integratedUserRole,
    this.integratedSystemSlug,
    this.integratedSystemName,
    this.userId,
    this.userName,
    this.userRole,
  });

  final String accessToken;
  final String tokenType;
  final String? expiresAt;
  final int? authUserId;
  final String? authUserName;
  final String? authUserEmail;
  final int? authTenantId;
  final String? authIntsysuser;
  final String? authUserRole;
  final String? integratedUserId;
  final String? integratedUserName;
  final String? integratedUserEmail;
  final String? integratedUserRole;
  final String? integratedSystemSlug;
  final String? integratedSystemName;
  final String? userId;
  final String? userName;
  final String? userRole;

  String get authorization => '$tokenType $accessToken';

  String? get effectiveUserId =>
      userId ?? integratedUserId ?? authUserId?.toString();

  String? get effectiveUserName =>
      userName ?? integratedUserName ?? authUserName;

  String? get effectiveRole =>
      (userRole ?? authUserRole ?? integratedUserRole)?.trim().toLowerCase();

  bool get isAdmin {
    final role = effectiveRole;
    if (role == 'tenant_admin') return true;
    if (role == 'tenant_user') return false;
    if (role == null || role.isEmpty) return false;
    return role.contains('admin') || role == 'agency' || role == 'account';
  }

  bool get isLoggedIn =>
      accessToken.isNotEmpty && (effectiveUserId?.isNotEmpty ?? false);

  Map<String, dynamic> toNativeMap() => {
        'accessToken': accessToken,
        'tokenType': tokenType,
        'expiresAt': expiresAt,
        'authUserId': authUserId,
        'authUserName': authUserName,
        'authUserEmail': authUserEmail,
        'authTenantId': authTenantId,
        'authIntsysuser': authIntsysuser,
        'authUserRole': authUserRole,
        'integratedUserId': integratedUserId,
        'integratedUserName': integratedUserName,
        'integratedUserEmail': integratedUserEmail,
        'integratedUserRole': integratedUserRole,
        'integratedSystemSlug': integratedSystemSlug,
        'integratedSystemName': integratedSystemName,
        'userId': effectiveUserId,
        'userName': userName ?? integratedUserName ?? authUserName,
        'userRole': userRole ?? authUserRole ?? integratedUserRole,
      };

  factory SessionData.fromVerifyJson(Map<String, dynamic> data) {
    final user = Map<String, dynamic>.from(data['user'] as Map? ?? {});
    final integrated = user['integrated_user'] is Map
        ? Map<String, dynamic>.from(user['integrated_user'] as Map)
        : null;
    final system = data['Integrated_system'] is Map
        ? Map<String, dynamic>.from(data['Integrated_system'] as Map)
        : (data['integrated_system'] is Map
            ? Map<String, dynamic>.from(data['integrated_system'] as Map)
            : null);
    final role = (data['userrole'] ??
            data['userRole'] ??
            user['userrole'] ??
            user['userRole'] ??
            integrated?['role'])
        ?.toString();
    final authId = user['id'] is int
        ? user['id'] as int
        : int.tryParse('${user['id']}');
    return SessionData(
      accessToken: '${data['access_token'] ?? data['accessToken'] ?? ''}',
      tokenType: '${data['token_type'] ?? data['tokenType'] ?? 'Bearer'}',
      expiresAt: (data['expires_at'] ?? data['expiresAt'])?.toString(),
      authUserId: authId,
      authUserName: user['name']?.toString(),
      authUserEmail: user['email']?.toString(),
      authTenantId: user['tenant_id'] is int
          ? user['tenant_id'] as int
          : int.tryParse('${user['tenant_id']}'),
      authIntsysuser: user['intsysuser']?.toString(),
      authUserRole: role,
      integratedUserId: integrated?['id']?.toString(),
      integratedUserName: integrated?['name']?.toString(),
      integratedUserEmail: integrated?['email']?.toString(),
      integratedUserRole: integrated?['role']?.toString(),
      integratedSystemSlug: system?['slug']?.toString(),
      integratedSystemName: system?['name']?.toString(),
      userId: integrated?['id']?.toString() ?? authId?.toString(),
      userName: integrated?['name']?.toString() ?? user['name']?.toString(),
      userRole: role,
    );
  }
}

class SessionRepository {
  SessionRepository({
    FlutterSecureStorage? secureStorage,
  }) : _secure = secureStorage ?? const FlutterSecureStorage();

  final FlutterSecureStorage _secure;
  static const _tokenKey = 'access_token';
  static const _sessionJsonKey = 'session_blob';

  Future<SessionData?> load() async {
    final token = await _secure.read(key: _tokenKey);
    if (token == null || token.isEmpty) return null;
    final prefs = await SharedPreferences.getInstance();
    final blob = prefs.getString(_sessionJsonKey);
    if (blob == null) {
      return SessionData(accessToken: token);
    }
    // Lightweight restore from prefs keys
    return SessionData(
      accessToken: token,
      tokenType: prefs.getString('token_type') ?? 'Bearer',
      expiresAt: prefs.getString('token_expires_at'),
      authUserId: prefs.getInt('auth_user_id'),
      authUserName: prefs.getString('auth_user_name'),
      authUserEmail: prefs.getString('auth_user_email'),
      authTenantId: prefs.getInt('auth_tenant_id'),
      authIntsysuser: prefs.getString('auth_intsysuser'),
      authUserRole: prefs.getString('auth_user_role'),
      integratedUserId: prefs.getString('integrated_user_id'),
      integratedUserName: prefs.getString('integrated_user_name'),
      integratedUserEmail: prefs.getString('integrated_user_email'),
      integratedUserRole: prefs.getString('integrated_user_role'),
      integratedSystemSlug: prefs.getString('integrated_system_slug'),
      integratedSystemName: prefs.getString('integrated_system_name'),
      userId: prefs.getString('user_id'),
      userName: prefs.getString('user_name'),
      userRole: prefs.getString('user_role'),
    );
  }

  Future<void> save(SessionData session) async {
    await _secure.write(key: _tokenKey, value: session.accessToken);
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_sessionJsonKey, '1');
    await prefs.setString('token_type', session.tokenType);
    if (session.expiresAt != null) {
      await prefs.setString('token_expires_at', session.expiresAt!);
    }
    if (session.authUserId != null) {
      await prefs.setInt('auth_user_id', session.authUserId!);
    }
    if (session.authUserName != null) {
      await prefs.setString('auth_user_name', session.authUserName!);
    }
    if (session.authUserEmail != null) {
      await prefs.setString('auth_user_email', session.authUserEmail!);
    }
    if (session.authTenantId != null) {
      await prefs.setInt('auth_tenant_id', session.authTenantId!);
    }
    if (session.authIntsysuser != null) {
      await prefs.setString('auth_intsysuser', session.authIntsysuser!);
    }
    if (session.authUserRole != null) {
      await prefs.setString('auth_user_role', session.authUserRole!);
    }
    if (session.integratedUserId != null) {
      await prefs.setString('integrated_user_id', session.integratedUserId!);
    }
    if (session.integratedUserName != null) {
      await prefs.setString('integrated_user_name', session.integratedUserName!);
    }
    if (session.integratedUserEmail != null) {
      await prefs.setString('integrated_user_email', session.integratedUserEmail!);
    }
    if (session.integratedUserRole != null) {
      await prefs.setString('integrated_user_role', session.integratedUserRole!);
    }
    if (session.integratedSystemSlug != null) {
      await prefs.setString('integrated_system_slug', session.integratedSystemSlug!);
    }
    if (session.integratedSystemName != null) {
      await prefs.setString('integrated_system_name', session.integratedSystemName!);
    }
    if (session.effectiveUserId != null) {
      await prefs.setString('user_id', session.effectiveUserId!);
    }
    if (session.userName != null) {
      await prefs.setString('user_name', session.userName!);
    }
    if (session.userRole != null) {
      await prefs.setString('user_role', session.userRole!);
    }
    await NativeBridges.setSession(session.toNativeMap());
    await NativeBridges.schedulePeriodicIfLoggedIn();
  }

  Future<void> clear() async {
    await _secure.deleteAll();
    final prefs = await SharedPreferences.getInstance();
    await prefs.clear();
    await NativeBridges.clearSession();
  }
}
