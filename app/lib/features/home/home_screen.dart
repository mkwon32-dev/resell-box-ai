import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/theme.dart';
import '../../app/tokens.dart';
import '../../data/db/app_database.dart';
import '../../data/models/risk_verdict.dart';
import '../history/history_providers.dart';

/// Poster composition: wordmark, latest-scan strip, giant SCAN affordance
/// filling the thumb zone.
class HomeScreen extends ConsumerWidget {
  const HomeScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final text = Theme.of(context).textTheme;
    final scans = ref.watch(scanHistoryProvider);

    return Scaffold(
      body: SafeArea(
        child: LayoutBuilder(
          builder: (context, constraints) => SingleChildScrollView(
            padding: const EdgeInsets.all(AppTokens.s5),
            child: ConstrainedBox(
              constraints: BoxConstraints(
                minHeight: (constraints.maxHeight - AppTokens.s7).clamp(
                  0,
                  double.infinity,
                ),
              ),
              child: IntrinsicHeight(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Expanded(
                          child: Text('RESELL\nBOX', style: text.displaySmall),
                        ),
                        IconButton(
                          tooltip: 'About and settings',
                          onPressed: () => context.push('/settings'),
                          icon: const Icon(Icons.more_horiz),
                          color: AppTokens.textFaint,
                        ),
                      ],
                    ),
                    Text('BOX CONDITION · RESALE RISK', style: text.labelSmall),
                    const SizedBox(height: AppTokens.s7),
                    scans.when(
                      loading: () => const SizedBox(
                        height: 84,
                        child: Center(child: CircularProgressIndicator()),
                      ),
                      error: (error, stackTrace) =>
                          Text('Could not load scans', style: text.bodyMedium),
                      data: (records) {
                        if (records.isEmpty) {
                          return Text('No scans yet', style: text.bodyMedium);
                        }
                        return Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Semantics(
                              button: true,
                              label: 'Open scan history',
                              child: GestureDetector(
                                onTap: () => context.push('/history'),
                                child: _LatestStrip(scans: records),
                              ),
                            ),
                            const SizedBox(height: AppTokens.s4),
                            TextButton(
                              onPressed: () => context.push('/history'),
                              child: Text('All scans (${records.length})'),
                            ),
                          ],
                        );
                      },
                    ),
                    const Spacer(),
                    _ScanBlock(onTap: () => context.push('/capture')),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _LatestStrip extends StatelessWidget {
  const _LatestStrip({required this.scans});

  final List<ScanRecordData> scans;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 84,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        itemCount: scans.length.clamp(0, 8),
        separatorBuilder: (_, _) => const SizedBox(width: AppTokens.s3),
        itemBuilder: (context, i) {
          final scan = scans[i];
          final verdict = scan.verdict;
          return ClipRRect(
            borderRadius: BorderRadius.circular(AppTokens.rSm),
            child: Stack(
              children: [
                Image.file(
                  File(scan.imagePath),
                  width: 84,
                  height: 84,
                  fit: BoxFit.cover,
                  errorBuilder: (_, _, _) => Container(
                    width: 84,
                    height: 84,
                    color: AppTokens.surfaceRaised,
                  ),
                ),
                Positioned(
                  left: 0,
                  right: 0,
                  bottom: 0,
                  child: Container(color: verdict.color, height: 4),
                ),
              ],
            ),
          );
        },
      ),
    );
  }
}

class _ScanBlock extends StatelessWidget {
  const _ScanBlock({required this.onTap});

  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    return Material(
      color: AppTokens.accent,
      borderRadius: BorderRadius.circular(AppTokens.rLg),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(AppTokens.rLg),
        child: SizedBox(
          width: double.infinity,
          child: ConstrainedBox(
            constraints: const BoxConstraints(minHeight: 200),
            child: Padding(
              padding: const EdgeInsets.all(AppTokens.s5),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Icon(
                    Icons.center_focus_strong,
                    size: 32,
                    color: AppTokens.bg.withValues(alpha: .85),
                  ),
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    crossAxisAlignment: CrossAxisAlignment.end,
                    children: [
                      Flexible(
                        flex: 3,
                        child: Text(
                          'SCAN',
                          maxLines: 1,
                          overflow: TextOverflow.fade,
                          style: text.displayMedium?.copyWith(
                            color: AppTokens.bg,
                          ),
                        ),
                      ),
                      const SizedBox(width: AppTokens.s2),
                      Flexible(
                        flex: 2,
                        child: Padding(
                          padding: const EdgeInsets.only(bottom: AppTokens.s2),
                          child: Text(
                            'whole box in frame',
                            textAlign: TextAlign.right,
                            style: AppText.mono(
                              size: 12,
                              color: AppTokens.bg.withValues(alpha: .7),
                            ),
                          ),
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
