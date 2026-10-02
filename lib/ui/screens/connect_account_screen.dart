import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mycrmsync/providers/providers.dart';
import 'package:mycrmsync/theme/app_theme.dart';

class ConnectAccountScreen extends ConsumerStatefulWidget {
  const ConnectAccountScreen({super.key, required this.onOtpSent});

  final void Function(String email) onOtpSent;

  @override
  ConsumerState<ConnectAccountScreen> createState() =>
      _ConnectAccountScreenState();
}

class _ConnectAccountScreenState extends ConsumerState<ConnectAccountScreen> {
  static const _authInputBg = Color(0xFFF3F6FF);
  static const _gradientTop = Color(0xFFF8FAFF);
  static const _gradientBottom = Color(0xFFEEF4FF);
  static const _shieldPill = Color(0xFFE0E9FF);

  final _email = TextEditingController();
  bool _loading = false;
  String? _error;
  String? _success;

  @override
  void initState() {
    super.initState();
    _email.addListener(() => setState(() {}));
  }

  @override
  void dispose() {
    _email.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final email = _email.text.trim();
    if (email.isEmpty || !email.contains('@')) {
      setState(() {
        _error = 'Enter a valid email';
        _success = null;
      });
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
      _success = null;
    });
    try {
      await ref.read(authApiProvider).sendOtp(email);
      if (!mounted) return;
      setState(() => _success = 'Verification code sent');
      await Future<void>.delayed(const Duration(milliseconds: 800));
      if (!mounted) return;
      widget.onOtpSent(email);
    } on DioException catch (e) {
      setState(() => _error = e.message ?? 'Could not send verification code.');
    } catch (e) {
      setState(() => _error = e.toString());
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Container(
        width: double.infinity,
        decoration: const BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
            colors: [_gradientTop, _gradientBottom],
          ),
        ),
        child: SafeArea(
          child: LayoutBuilder(
            builder: (context, constraints) {
              return SingleChildScrollView(
                padding: const EdgeInsets.symmetric(horizontal: 24),
                child: ConstrainedBox(
                  constraints: BoxConstraints(minHeight: constraints.maxHeight),
                  child: Column(
                    children: [
                      const SizedBox(height: 48),
                      const Text(
                        'MyCrmSync',
                        style: TextStyle(
                          fontSize: 28,
                          fontWeight: FontWeight.w800,
                          color: AppColors.darkBlue,
                          letterSpacing: -0.5,
                        ),
                      ),
                      const SizedBox(height: 12),
                      ClipRRect(
                        borderRadius: BorderRadius.circular(36),
                        child: Image.asset(
                          'assets/logo.png',
                          height: 180,
                          width: 180,
                          fit: BoxFit.contain,
                          errorBuilder: (_, __, ___) => const Icon(
                            Icons.sync,
                            size: 120,
                            color: AppColors.darkBlue,
                          ),
                        ),
                      ),
                      const SizedBox(height: 12),
                      const Text(
                        'Welcome back',
                        style: TextStyle(
                          fontSize: 32,
                          fontWeight: FontWeight.bold,
                          color: AppColors.darkBlue,
                        ),
                      ),
                      const SizedBox(height: 8),
                      const Text(
                        'Enter your credentials to access your workspace.',
                        textAlign: TextAlign.center,
                        style: TextStyle(
                          fontSize: 16,
                          height: 1.375,
                          color: AppColors.textSec,
                        ),
                      ),
                      const SizedBox(height: 40),
                      Material(
                        color: Colors.white,
                        elevation: 8,
                        shadowColor: Colors.black26,
                        borderRadius: BorderRadius.circular(24),
                        child: Padding(
                          padding: const EdgeInsets.all(24),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.stretch,
                            children: [
                              const Text(
                                'EMAIL ADDRESS',
                                style: TextStyle(
                                  fontSize: 12,
                                  fontWeight: FontWeight.bold,
                                  color: AppColors.textSec,
                                  letterSpacing: 0.5,
                                ),
                              ),
                              const SizedBox(height: 8),
                              TextField(
                                controller: _email,
                                keyboardType: TextInputType.emailAddress,
                                autofillHints: const [AutofillHints.email],
                                enabled: !_loading,
                                onChanged: (_) {
                                  if (_error != null || _success != null) {
                                    setState(() {
                                      _error = null;
                                      _success = null;
                                    });
                                  }
                                },
                                style: const TextStyle(color: AppColors.darkBlue),
                                decoration: InputDecoration(
                                  hintText: 'name@company.com',
                                  hintStyle: const TextStyle(
                                    color: AppColors.textGray,
                                  ),
                                  filled: true,
                                  fillColor: _authInputBg,
                                  border: OutlineInputBorder(
                                    borderRadius: BorderRadius.circular(12),
                                    borderSide: BorderSide.none,
                                  ),
                                  enabledBorder: OutlineInputBorder(
                                    borderRadius: BorderRadius.circular(12),
                                    borderSide: BorderSide.none,
                                  ),
                                  focusedBorder: OutlineInputBorder(
                                    borderRadius: BorderRadius.circular(12),
                                    borderSide: BorderSide.none,
                                  ),
                                ),
                              ),
                              const SizedBox(height: 32),
                              SizedBox(
                                height: 56,
                                child: FilledButton(
                                  onPressed:
                                      _loading || _email.text.trim().isEmpty
                                          ? null
                                          : _submit,
                                  style: FilledButton.styleFrom(
                                    backgroundColor: AppColors.darkBlue,
                                    disabledBackgroundColor: AppColors.darkBlue
                                        .withValues(alpha: 0.4),
                                    shape: RoundedRectangleBorder(
                                      borderRadius: BorderRadius.circular(12),
                                    ),
                                  ),
                                  child: _loading
                                      ? const SizedBox(
                                          height: 22,
                                          width: 22,
                                          child: CircularProgressIndicator(
                                            strokeWidth: 2,
                                            color: Colors.white,
                                          ),
                                        )
                                      : const Row(
                                          mainAxisAlignment:
                                              MainAxisAlignment.center,
                                          children: [
                                            Text(
                                              'Login with Email',
                                              style: TextStyle(
                                                fontSize: 16,
                                                fontWeight: FontWeight.bold,
                                                color: Colors.white,
                                              ),
                                            ),
                                            SizedBox(width: 8),
                                            Icon(
                                              Icons.arrow_forward,
                                              size: 18,
                                              color: Colors.white,
                                            ),
                                          ],
                                        ),
                                ),
                              ),
                              if (_error != null || _success != null) ...[
                                const SizedBox(height: 16),
                                Text(
                                  _error ?? _success!,
                                  style: TextStyle(
                                    color: _error != null
                                        ? AppColors.errorRed
                                        : AppColors.successGreen,
                                    fontSize: 14,
                                  ),
                                ),
                              ],
                            ],
                          ),
                        ),
                      ),
                      const SizedBox(height: 32),
                      Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 16,
                          vertical: 8,
                        ),
                        decoration: BoxDecoration(
                          color: _shieldPill,
                          borderRadius: BorderRadius.circular(50),
                        ),
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Icon(
                              Icons.shield_outlined,
                              size: 14,
                              color: AppColors.darkBlue.withValues(alpha: 0.7),
                            ),
                            const SizedBox(width: 8),
                            Text(
                              'SECURE ENTERPRISE CONNECTION',
                              style: TextStyle(
                                fontSize: 10,
                                fontWeight: FontWeight.bold,
                                color:
                                    AppColors.darkBlue.withValues(alpha: 0.7),
                                letterSpacing: 0.5,
                              ),
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(height: 24),
                      Text.rich(
                        TextSpan(
                          style: const TextStyle(
                            fontSize: 12,
                            height: 1.5,
                            color: AppColors.textSec,
                          ),
                          children: const [
                            TextSpan(
                              text:
                                  'By continuing, you agree to the MyCrmSync ',
                            ),
                            TextSpan(
                              text: 'Terms of Service',
                              style: TextStyle(
                                fontWeight: FontWeight.bold,
                                color: AppColors.darkBlue,
                              ),
                            ),
                            TextSpan(text: ' and '),
                            TextSpan(
                              text: 'Privacy Policy',
                              style: TextStyle(
                                fontWeight: FontWeight.bold,
                                color: AppColors.darkBlue,
                              ),
                            ),
                            TextSpan(text: '.'),
                          ],
                        ),
                        textAlign: TextAlign.center,
                      ),
                      const SizedBox(height: 48),
                      Padding(
                        padding: const EdgeInsets.only(bottom: 24),
                        child: Text(
                          'ARCHITECTURE V2.4.0   •   ENCRYPTED WORKSPACE',
                          style: TextStyle(
                            fontSize: 10,
                            fontWeight: FontWeight.bold,
                            color: AppColors.textGray.withValues(alpha: 0.6),
                            letterSpacing: 1,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              );
            },
          ),
        ),
      ),
    );
  }
}
