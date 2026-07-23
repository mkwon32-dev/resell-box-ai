import 'dart:io';

import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../features/analysis/analyzing_screen.dart';
import '../features/analysis/result/result_screen.dart';
import '../features/capture/capture_screen.dart';
import '../features/history/history_screen.dart';
import '../features/home/home_screen.dart';
import '../features/settings/settings_screen.dart';

final appRouter = GoRouter(
  errorBuilder: (context, state) =>
      _RouteErrorScreen(message: state.error?.toString() ?? 'Page not found'),
  routes: [
    GoRoute(path: '/', builder: (context, state) => const HomeScreen()),
    GoRoute(
      path: '/capture',
      builder: (context, state) => const CaptureScreen(),
    ),
    GoRoute(
      path: '/analyzing',
      builder: (context, state) {
        final photo = state.extra;
        if (photo is! File) {
          return const _RouteErrorScreen(
            message: 'Choose a photo before starting analysis.',
          );
        }
        return AnalyzingScreen(photo: photo);
      },
    ),
    GoRoute(
      path: '/result/:id',
      builder: (context, state) {
        final scanId = int.tryParse(state.pathParameters['id'] ?? '');
        if (scanId == null || scanId < 1) {
          return const _RouteErrorScreen(message: 'Invalid scan link.');
        }
        return ResultScreen(
          scanId: scanId,
          reveal: state.extra is bool ? state.extra! as bool : false,
        );
      },
    ),
    GoRoute(
      path: '/history',
      builder: (context, state) => const HistoryScreen(),
    ),
    GoRoute(
      path: '/settings',
      builder: (context, state) => const SettingsScreen(),
    ),
  ],
);

class _RouteErrorScreen extends StatelessWidget {
  const _RouteErrorScreen({required this.message});

  final String message;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(message, textAlign: TextAlign.center),
                const SizedBox(height: 16),
                FilledButton(
                  onPressed: () => context.go('/'),
                  child: const Text('Back to home'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
