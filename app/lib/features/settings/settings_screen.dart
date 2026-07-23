import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../app/theme.dart';
import '../../app/tokens.dart';

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    return Scaffold(
      body: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                IconButton(
                  tooltip: 'Back',
                  onPressed: () =>
                      context.canPop() ? context.pop() : context.go('/'),
                  icon: const Icon(Icons.arrow_back),
                  color: AppTokens.textSecondary,
                ),
              ],
            ),
            Expanded(
              child: SingleChildScrollView(
                padding: const EdgeInsets.all(AppTokens.s5),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('ABOUT', style: text.displaySmall),
                    const SizedBox(height: AppTokens.s5),
                    Text(
                      'ResellBox AI detects sneaker-box damage from close-up '
                      'photos and scores resale risk. School project — not '
                      'affiliated with StockX or GOAT.',
                      style: text.bodyMedium,
                    ),
                    const SizedBox(height: AppTokens.s5),
                    Text('SIZING', style: text.labelSmall),
                    const SizedBox(height: AppTokens.s2),
                    Text(
                      'Box-face homography · nominal 35 cm box length',
                      style: AppText.mono(size: 13),
                    ),
                    const SizedBox(height: AppTokens.s5),
                    Text('MODE', style: text.labelSmall),
                    const SizedBox(height: AppTokens.s2),
                    Text('Demo (mock analysis)', style: AppText.mono(size: 13)),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
