import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mycrmsync/platform/native_bridges.dart';
import 'package:mycrmsync/providers/providers.dart';
import 'package:mycrmsync/theme/app_theme.dart';

class VerifyOtpScreen extends ConsumerStatefulWidget {
  const VerifyOtpScreen({
    super.key,
    required this.email,
    required this.onVerified,
    required this.onBack,
  });

  final String email;
  final VoidCallback onVerified;
  final VoidCallback onBack;

  @override
  ConsumerState<VerifyOtpScreen> createState() => _VerifyOtpScreenState();
}

class _VerifyOtpScreenState extends ConsumerState<VerifyOtpScreen> {
  static const _authInputBg = Color(0xFFF3F6FF);
  static const _gradientTop = Color(0xFFF8FAFF);
  static const _gradientBottom = Color(0xFFEEF4FF);
  static const _shieldPill = Color(0xFFE0E9FF);

  final _digits = List.generate(4, (_) => TextEditingController());
  final _focusNodes = List.generate(4, (_) => FocusNode());
  bool _loading = false;
  String? _error;

  String get _otp => _digits.map((c) => c.text).join();

  @override
  void dispose() {
    for (final c in _digits) {
      c.dispose();
    }
    for (final n in _focusNodes) {
      n.dispose();
    }
    super.dispose();
  }

  Future<void> _submit() async {
    final otp = _otp;
    if (otp.length < 4) {
      setState(() => _error = 'Enter the 4-digit code from your email.');
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final session = await ref.read(authApiProvider).verifyOtp(
            email: widget.email,
            otp: otp,
          );
      await ref.read(sessionProvider.notifier).save(session);
      await NativeBridges.requestRuntimePermissions();
      await NativeBridges.enqueueNow();
      if (!mounted) return;
      widget.onVerified();
    } on DioException catch (e) {
      setState(
        () => _error =
            e.response?.data?.toString() ?? e.message ?? 'Invalid login code.',
      );
    } catch (e) {
      setState(() => _error = e.toString());
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  void _onDigitChanged(int index, String value) {
    final filtered = value.replaceAll(RegExp(r'\D'), '');
    if (filtered.length > 1) {
      final chars = filtered.split('').take(4).toList();
      for (var i = 0; i < 4; i++) {
        _digits[i].text = i < chars.length ? chars[i] : '';
      }
      final next = (chars.length - 1).clamp(0, 3);
      _focusNodes[next].requestFocus();
      setState(() => _error = null);
      return;
    }

    if (filtered.isEmpty) {
      _digits[index].clear();
      if (index > 0) _focusNodes[index - 1].requestFocus();
    } else {
      _digits[index].value = TextEditingValue(
        text: filtered,
        selection: const TextSelection.collapsed(offset: 1),
      );
      if (index < 3) _focusNodes[index + 1].requestFocus();
    }
    setState(() => _error = null);
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
                      Align(
                        alignment: Alignment.centerLeft,
                        child: IconButton(
                          onPressed: _loading ? null : widget.onBack,
                          icon: const Icon(
                            Icons.arrow_back,
                            color: AppColors.darkBlue,
                          ),
                        ),
                      ),
                      const SizedBox(height: 8),
                      const Text(
                        'MyCrmSync',
                        style: TextStyle(
                          fontSize: 28,
                          fontWeight: FontWeight.w800,
                          color: AppColors.darkBlue,
                          letterSpacing: -0.5,
                        ),
                      ),
                      const SizedBox(height: 24),
                      ClipRRect(
                        borderRadius: BorderRadius.circular(36),
                        child: Image.asset(
                          'assets/logo.png',
                          height: 140,
                          width: 140,
                          fit: BoxFit.contain,
                          errorBuilder: (_, __, ___) => const Icon(
                            Icons.sync,
                            size: 100,
                            color: AppColors.darkBlue,
                          ),
                        ),
                      ),
                      const SizedBox(height: 28),
                      const Text(
                        'Enter verification code',
                        textAlign: TextAlign.center,
                        style: TextStyle(
                          fontSize: 28,
                          fontWeight: FontWeight.bold,
                          color: AppColors.darkBlue,
                        ),
                      ),
                      const SizedBox(height: 8),
                      Text(
                        'We sent a 4-digit code to\n${widget.email}',
                        textAlign: TextAlign.center,
                        style: const TextStyle(
                          fontSize: 15,
                          height: 1.45,
                          color: AppColors.textSec,
                        ),
                      ),
                      const SizedBox(height: 8),
                      const Text(
                        'Code expires in 10 minutes.',
                        textAlign: TextAlign.center,
                        style: TextStyle(
                          fontSize: 13,
                          color: AppColors.textGray,
                        ),
                      ),
                      const SizedBox(height: 36),
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
                                'ONE-TIME CODE',
                                style: TextStyle(
                                  fontSize: 12,
                                  fontWeight: FontWeight.bold,
                                  color: AppColors.textSec,
                                  letterSpacing: 0.5,
                                ),
                              ),
                              const SizedBox(height: 20),
                              Row(
                                mainAxisAlignment: MainAxisAlignment.center,
                                children: List.generate(4, (index) {
                                  return Padding(
                                    padding: EdgeInsets.only(
                                      left: index == 0 ? 0 : 12,
                                    ),
                                    child: SizedBox(
                                      width: 56,
                                      height: 56,
                                      child: TextField(
                                        controller: _digits[index],
                                        focusNode: _focusNodes[index],
                                        enabled: !_loading,
                                        textAlign: TextAlign.center,
                                        keyboardType: TextInputType.number,
                                        style: const TextStyle(
                                          fontSize: 22,
                                          fontWeight: FontWeight.bold,
                                          color: AppColors.darkBlue,
                                        ),
                                        inputFormatters: [
                                          FilteringTextInputFormatter
                                              .digitsOnly,
                                          LengthLimitingTextInputFormatter(4),
                                        ],
                                        decoration: InputDecoration(
                                          counterText: '',
                                          filled: true,
                                          fillColor: _authInputBg,
                                          contentPadding: EdgeInsets.zero,
                                          border: OutlineInputBorder(
                                            borderRadius:
                                                BorderRadius.circular(12),
                                            borderSide: BorderSide.none,
                                          ),
                                        ),
                                        onChanged: (v) =>
                                            _onDigitChanged(index, v),
                                      ),
                                    ),
                                  );
                                }),
                              ),
                              const SizedBox(height: 32),
                              SizedBox(
                                height: 56,
                                child: FilledButton(
                                  onPressed: _loading || _otp.length < 4
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
                                              'Validate OTP',
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
                              if (_error != null) ...[
                                const SizedBox(height: 16),
                                Text(
                                  _error!,
                                  style: const TextStyle(
                                    color: AppColors.errorRed,
                                    fontSize: 14,
                                  ),
                                ),
                              ],
                            ],
                          ),
                        ),
                      ),
                      const SizedBox(height: 28),
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
