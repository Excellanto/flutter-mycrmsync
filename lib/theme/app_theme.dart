import 'package:flutter/material.dart';

class AppColors {
  static const darkBlue = Color(0xFF001F3F);
  static const primaryBlue = Color(0xFF2563EB);
  static const lightIndigo = Color(0xFFEEF2FF);
  static const lightBg = Color(0xFFF5F7FB);
  static const darkBg = Color(0xFF0B1220);
  static const cardWhite = Colors.white;
  static const textMain = Color(0xFF0F172A);
  static const textSec = Color(0xFF64748B);
  static const textGray = Color(0xFF94A3B8);
  static const successGreen = Color(0xFF16A34A);
  static const errorRed = Color(0xFFDC2626);
  static const inputBg = Color(0xFFF1F5F9);
}

ThemeData buildAppTheme() {
  return ThemeData(
    useMaterial3: true,
    colorScheme: ColorScheme.fromSeed(
      seedColor: AppColors.primaryBlue,
      primary: AppColors.primaryBlue,
    ),
    scaffoldBackgroundColor: AppColors.lightBg,
    appBarTheme: const AppBarTheme(
      backgroundColor: Colors.white,
      foregroundColor: AppColors.textMain,
      elevation: 0,
      centerTitle: false,
    ),
    drawerTheme: const DrawerThemeData(backgroundColor: Colors.white),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: AppColors.inputBg,
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(12),
        borderSide: BorderSide.none,
      ),
    ),
  );
}
