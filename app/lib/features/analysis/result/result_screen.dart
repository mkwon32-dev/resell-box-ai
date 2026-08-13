import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../app/theme.dart';
import '../../../app/tokens.dart';
import '../../../data/db/app_database.dart';
import '../../history/history_providers.dart';
import 'bbox_overlay_painter.dart';
import 'damage_list_tile.dart';
import 'verdict_badge.dart';

/// P0 "The Reveal", act two. With [reveal] true (fresh scan): 300ms hold →
/// verdict banner spring-slams in + haptic → bboxes self-draw staggered →
/// spec rows fade up. History revisits skip the theater.
class ResultScreen extends ConsumerStatefulWidget {
  const ResultScreen({super.key, required this.scanId, required this.reveal});

  final int scanId;
  final bool reveal;

  @override
  ConsumerState<ResultScreen> createState() => _ResultScreenState();
}

class _ResultScreenState extends ConsumerState<ResultScreen>
    with TickerProviderStateMixin {
  late final AnimationController _banner;
  late final AnimationController _boxes;
  late final AnimationController _rows;
  int? _highlighted;
  bool _started = false;

  @override
  void initState() {
    super.initState();
    _banner = AnimationController(vsync: this, duration: AppTokens.tReveal);
    _boxes = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 900),
    );
    _rows = AnimationController(vsync: this, duration: AppTokens.tMed);
  }

  void _startReveal(bool reduceMotion) {
    if (_started) return;
    _started = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      _runReveal(reduceMotion);
    });
  }

  Future<void> _runReveal(bool reduceMotion) async {
    if (!widget.reveal || reduceMotion) {
      _banner.value = 1;
      _boxes.value = 1;
      _rows.value = 1;
      return;
    }
    await Future<void>.delayed(const Duration(milliseconds: 300));
    if (!mounted) return;
    await HapticFeedback.heavyImpact();
    if (!mounted) return;
    await _banner.forward();
    if (!mounted) return;
    await _boxes.forward();
    if (!mounted) return;
    await _rows.forward();
  }

  @override
  void dispose() {
    _banner.dispose();
    _boxes.dispose();
    _rows.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final scan = ref.watch(scanByIdProvider(widget.scanId));
    final reduceMotion = MediaQuery.disableAnimationsOf(context);

    return Scaffold(
      body: scan.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => const _MissingScan(),
        data: (record) {
          if (record == null) {
            return const _MissingScan();
          }
          _startReveal(reduceMotion);
          return _buildResult(context, record);
        },
      ),
    );
  }

  Widget _buildResult(BuildContext context, ScanRecordData record) {
    final result = record.result;
    final text = Theme.of(context).textTheme;

    final header = Row(
      children: [
        IconButton(
          tooltip: 'Back',
          onPressed: () => context.canPop() ? context.pop() : context.go('/'),
          icon: const Icon(Icons.arrow_back),
          color: AppTokens.textSecondary,
        ),
      ],
    );
    final banner = AnimatedBuilder(
      animation: _banner,
      builder: (context, child) {
        final t = AppTokens.easeOutQuint.transform(_banner.value);
        return Opacity(
          opacity: t.clamp(0, 1),
          child: Transform.scale(
            scale: 1.15 - 0.15 * t,
            alignment: Alignment.centerLeft,
            child: child,
          ),
        );
      },
      child: VerdictBanner(result: result),
    );
    final photo = Padding(
      padding: const EdgeInsets.symmetric(horizontal: AppTokens.s5),
      child: Hero(
        tag: 'scan-photo-${record.id}',
        child: _PhotoWithBoxes(
          record: record,
          boxesAnimation: _boxes,
          highlighted: _highlighted,
          onHit: (i) =>
              setState(() => _highlighted = _highlighted == i ? null : i),
        ),
      ),
    );
    // Provenance line: warn when sizes are unavailable, otherwise say how
    // the cm scale was obtained (card beats box-face beats box-edge).
    final scaleWarning = result.detections.isEmpty && result.scaleSource.hasScale
        ? null
        : Padding(
            padding: const EdgeInsets.symmetric(horizontal: AppTokens.s5),
            child: Text(
              result.scaleSource.hasScale
                  ? result.scaleSource.label
                  : result.detections.isEmpty
                  ? 'Box not fully in frame'
                  : 'No box edges — sizes unavailable',
              style: AppText.mono(
                size: 12,
                color: result.scaleSource.hasScale
                    ? AppTokens.textSecondary
                    : AppTokens.riskCaution,
              ),
            ),
          );
    final rows = FadeTransition(
      opacity: _rows,
      child: SlideTransition(
        position: _rows.drive(
          Tween(
            begin: const Offset(0, .06),
            end: Offset.zero,
          ).chain(CurveTween(curve: AppTokens.easeOutQuint)),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (result.detections.isEmpty)
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: AppTokens.s5),
                child: Text('No damage found', style: text.bodyMedium),
              )
            else
              ...List.generate(
                result.detections.length,
                (i) => DamageListTile(
                  index: i,
                  detection: result.detections[i],
                  sizesAvailable: result.scaleSource.hasScale,
                  highlighted: _highlighted == i,
                  onTap: () => setState(
                    () => _highlighted = _highlighted == i ? null : i,
                  ),
                ),
              ),
            const SizedBox(height: AppTokens.s4),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: AppTokens.s5),
              child: SizedBox(
                width: double.infinity,
                child: FilledButton(
                  onPressed: () => context.pushReplacement('/capture'),
                  child: const Text('Scan another'),
                ),
              ),
            ),
            const SizedBox(height: AppTokens.s4),
          ],
        ),
      ),
    );

    return SafeArea(
      child: LayoutBuilder(
        builder: (context, constraints) {
          final textScale = MediaQuery.textScalerOf(context).scale(1);
          final needsScrolling =
              constraints.maxHeight < 650 ||
              textScale > 1.3 ||
              result.detections.length > 4;
          final beforePhoto = <Widget>[
            header,
            banner,
            const SizedBox(height: AppTokens.s4),
          ];
          final afterPhoto = <Widget>[
            const SizedBox(height: AppTokens.s4),
            ?scaleWarning,
            rows,
          ];
          if (needsScrolling) {
            return SingleChildScrollView(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [...beforePhoto, photo, ...afterPhoto],
              ),
            );
          }
          return Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              ...beforePhoto,
              Expanded(child: photo),
              ...afterPhoto,
            ],
          );
        },
      ),
    );
  }
}

class _MissingScan extends StatelessWidget {
  const _MissingScan();

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Column(
        children: [
          Align(
            alignment: Alignment.centerLeft,
            child: IconButton(
              tooltip: 'Back',
              onPressed: () =>
                  context.canPop() ? context.pop() : context.go('/'),
              icon: const Icon(Icons.arrow_back),
              color: AppTokens.textSecondary,
            ),
          ),
          Expanded(
            child: Center(
              child: Text(
                'Scan not found',
                style: Theme.of(context).textTheme.bodyMedium,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _PhotoWithBoxes extends StatelessWidget {
  const _PhotoWithBoxes({
    required this.record,
    required this.boxesAnimation,
    required this.highlighted,
    required this.onHit,
  });

  final ScanRecordData record;
  final Animation<double> boxesAnimation;
  final int? highlighted;
  final ValueChanged<int> onHit;

  @override
  Widget build(BuildContext context) {
    final result = record.result;
    final imageSize = Size(
      result.imageWidth.toDouble(),
      result.imageHeight.toDouble(),
    );
    final aspect = imageSize.isEmpty ? 4 / 3 : imageSize.aspectRatio;

    return Align(
      child: AspectRatio(
        aspectRatio: aspect,
        child: LayoutBuilder(
          builder: (context, constraints) {
            final canvasSize = Size(
              constraints.maxWidth,
              constraints.maxHeight,
            );
            final painter = BboxOverlayPainter(
              detections: result.detections,
              imageSize: imageSize,
              reveal: boxesAnimation.value,
              highlighted: highlighted,
            );
            return GestureDetector(
              onTapUp: (details) {
                final hit = painter.hitTestDetection(
                  details.localPosition,
                  canvasSize,
                );
                if (hit != null) onHit(hit);
              },
              child: ClipRRect(
                borderRadius: BorderRadius.circular(AppTokens.rMd),
                child: Stack(
                  fit: StackFit.expand,
                  children: [
                    Image.file(
                      File(record.imagePath),
                      fit: BoxFit.contain,
                      errorBuilder: (context, error, stackTrace) => Container(
                        color: AppTokens.surfaceRaised,
                        alignment: Alignment.center,
                        child: const Icon(
                          Icons.broken_image_outlined,
                          size: 48,
                          color: AppTokens.textFaint,
                        ),
                      ),
                    ),
                    AnimatedBuilder(
                      animation: boxesAnimation,
                      builder: (context, _) => CustomPaint(
                        painter: BboxOverlayPainter(
                          detections: result.detections,
                          imageSize: imageSize,
                          reveal: boxesAnimation.value,
                          highlighted: highlighted,
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
    );
  }
}
