import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';

import '../../app/theme.dart';
import '../../app/tokens.dart';
import '../../data/db/app_database.dart';
import '../../data/models/risk_verdict.dart';
import '../../providers.dart';
import 'history_providers.dart';

/// "Inventory rack": photo-tile grid, corner verdict tags, long-press delete.
class HistoryScreen extends ConsumerWidget {
  const HistoryScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final text = Theme.of(context).textTheme;
    final scans = ref.watch(scanHistoryProvider);

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
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: AppTokens.s5),
              child: Text('RACK', style: text.displaySmall),
            ),
            const SizedBox(height: AppTokens.s4),
            Expanded(
              child: scans.when(
                loading: () => const Center(child: CircularProgressIndicator()),
                error: (e, _) => Center(
                  child: Text('Could not load scans', style: text.bodyMedium),
                ),
                data: (records) => records.isEmpty
                    ? Center(
                        child: Text('No scans yet', style: text.bodyMedium),
                      )
                    : GridView.builder(
                        padding: const EdgeInsets.fromLTRB(
                          AppTokens.s5,
                          0,
                          AppTokens.s5,
                          AppTokens.s5,
                        ),
                        gridDelegate:
                            const SliverGridDelegateWithFixedCrossAxisCount(
                              crossAxisCount: 2,
                              mainAxisSpacing: AppTokens.s3,
                              crossAxisSpacing: AppTokens.s3,
                            ),
                        itemCount: records.length,
                        itemBuilder: (context, i) =>
                            _RackTile(record: records[i]),
                      ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _RackTile extends ConsumerWidget {
  const _RackTile({required this.record});

  final ScanRecordData record;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final verdict = record.verdict;
    return Semantics(
      button: true,
      label:
          '${verdict.label} scan from '
          '${DateFormat.yMMMd().add_Hm().format(record.scannedAt)}',
      hint: 'Long press to delete',
      child: GestureDetector(
        onTap: () => context.push('/result/${record.id}', extra: false),
        onLongPress: () async {
          final database = ref.read(databaseProvider);
          final confirmed = await showDialog<bool>(
            context: context,
            builder: (context) => AlertDialog(
              backgroundColor: AppTokens.surfaceRaised,
              title: const Text('Delete scan?'),
              actions: [
                TextButton(
                  onPressed: () => Navigator.pop(context, false),
                  child: const Text('Keep'),
                ),
                TextButton(
                  onPressed: () => Navigator.pop(context, true),
                  child: const Text('Delete'),
                ),
              ],
            ),
          );
          if (confirmed == true) {
            try {
              await database.deleteScan(record.id);
            } catch (_) {
              if (context.mounted) {
                ScaffoldMessenger.of(context).showSnackBar(
                  const SnackBar(content: Text('Could not delete scan.')),
                );
              }
            }
          }
        },
        child: Hero(
          tag: 'scan-photo-${record.id}',
          child: ClipRRect(
            borderRadius: BorderRadius.circular(AppTokens.rMd),
            child: Stack(
              fit: StackFit.expand,
              children: [
                Image.file(
                  File(record.imagePath),
                  fit: BoxFit.cover,
                  errorBuilder: (_, _, _) =>
                      Container(color: AppTokens.surfaceRaised),
                ),
                Positioned(
                  left: AppTokens.s2,
                  top: AppTokens.s2,
                  child: Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: AppTokens.s2,
                      vertical: 2,
                    ),
                    decoration: BoxDecoration(
                      color: verdict.color,
                      borderRadius: BorderRadius.circular(AppTokens.rSm),
                    ),
                    child: Text(
                      verdict.shortLabel,
                      style: const TextStyle(
                        color: AppTokens.onVerdict,
                        fontSize: 11,
                        fontWeight: FontWeight.w800,
                        letterSpacing: 0.8,
                      ),
                    ),
                  ),
                ),
                Positioned(
                  left: AppTokens.s2,
                  bottom: AppTokens.s2,
                  child: Text(
                    DateFormat('MMM d · HH:mm').format(record.scannedAt),
                    style: AppText.mono(size: 10, color: AppTokens.textPrimary),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
