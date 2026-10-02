import 'package:flutter/material.dart';
import 'package:mycrmsync/theme/app_theme.dart';

class SplashScreen extends StatefulWidget {
  const SplashScreen({super.key, required this.onDone});

  final VoidCallback onDone;

  @override
  State<SplashScreen> createState() => _SplashScreenState();
}

class _SplashScreenState extends State<SplashScreen>
    with SingleTickerProviderStateMixin {
  late final AnimationController _progress;

  @override
  void initState() {
    super.initState();
    _progress = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 2500),
    )..forward();
    Future.delayed(const Duration(milliseconds: 3000), () {
      if (mounted) widget.onDone();
    });
  }

  @override
  void dispose() {
    _progress.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.white,
      body: SafeArea(
        child: Stack(
          children: [
            Center(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  ClipRRect(
                    borderRadius: BorderRadius.circular(36),
                    child: Image.asset(
                      'assets/logo.png',
                      width: 180,
                      height: 180,
                      fit: BoxFit.contain,
                      errorBuilder: (_, __, ___) => const Icon(
                        Icons.sync,
                        size: 120,
                        color: AppColors.darkBlue,
                      ),
                    ),
                  ),
                  const SizedBox(height: 48),
                  const Text(
                    'MyCrmSync',
                    style: TextStyle(
                      color: AppColors.darkBlue,
                      fontSize: 40,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    'Smart Calling for Smart Sales',
                    style: TextStyle(
                      color: AppColors.textGray.withValues(alpha: 0.9),
                      fontSize: 18,
                      fontWeight: FontWeight.w500,
                    ),
                  ),
                ],
              ),
            ),
            Positioned(
              left: 60,
              right: 60,
              bottom: 80,
              child: Column(
                children: [
                  AnimatedBuilder(
                    animation: _progress,
                    builder: (context, _) {
                      return ClipRRect(
                        borderRadius: BorderRadius.circular(2),
                        child: LinearProgressIndicator(
                          value: _progress.value * 0.8,
                          minHeight: 3,
                          backgroundColor:
                              AppColors.darkBlue.withValues(alpha: 0.1),
                          valueColor: AlwaysStoppedAnimation(
                            AppColors.darkBlue.withValues(alpha: 0.7),
                          ),
                        ),
                      );
                    },
                  ),
                  const SizedBox(height: 20),
                  Text(
                    'CONNECTING WORKSPACE',
                    style: TextStyle(
                      color: AppColors.textGray.withValues(alpha: 0.7),
                      fontSize: 12,
                      fontWeight: FontWeight.bold,
                      letterSpacing: 1.5,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
