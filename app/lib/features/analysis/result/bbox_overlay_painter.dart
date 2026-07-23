import 'package:flutter/material.dart';

import '../../../app/tokens.dart';
import '../../../core/bbox_geometry.dart';
import '../../../data/models/detection.dart';

/// Draws detections as thin self-drawing strokes with numbered pins.
/// [reveal] 0..1 staggers each box: box i draws during its slice.
class BboxOverlayPainter extends CustomPainter {
  BboxOverlayPainter({
    required this.detections,
    required this.imageSize,
    required this.reveal,
    this.highlighted,
  });

  final List<Detection> detections;
  final Size imageSize;
  final double reveal;
  final int? highlighted;

  static const _stagger = 0.35; // each box starts this fraction later

  @override
  void paint(Canvas canvas, Size size) {
    for (var i = 0; i < detections.length; i++) {
      final d = detections[i];
      final start = detections.length == 1
          ? 0.0
          : i * _stagger / detections.length;
      final t = ((reveal - start) / (1 - start)).clamp(0.0, 1.0);
      if (t <= 0) continue;

      final rect = scaleDetectionToCanvas(
        cx: d.x,
        cy: d.y,
        w: d.width,
        h: d.height,
        imageSize: imageSize,
        canvasSize: size,
      );
      if (rect == Rect.zero) continue;

      final isHot = highlighted == i;
      final color = d.damageClass.color;
      // Dark halo under the colored stroke keeps the box legible on any
      // photo background (thin colored line alone vanished on dark shots).
      final halo = Paint()
        ..color = Colors.black.withValues(alpha: .6)
        ..style = PaintingStyle.stroke
        ..strokeWidth = isHot ? 6 : 5;
      final paint = Paint()
        ..color = isHot ? color : color.withValues(alpha: .95)
        ..style = PaintingStyle.stroke
        ..strokeWidth = isHot ? 3.5 : 2.5;

      // Self-drawing stroke: extract partial path around the rrect.
      final path = Path()
        ..addRRect(RRect.fromRectAndRadius(rect, const Radius.circular(3)));
      if (t >= 1) {
        canvas.drawPath(path, halo);
        canvas.drawPath(path, paint);
      } else {
        for (final metric in path.computeMetrics()) {
          final partial = metric.extractPath(0, metric.length * t);
          canvas.drawPath(partial, halo);
          canvas.drawPath(partial, paint);
        }
      }

      if (t >= 1) {
        _drawPin(canvas, rect, i, color, isHot);
      }
    }
  }

  void _drawPin(Canvas canvas, Rect rect, int index, Color color, bool hot) {
    const r = 10.0;
    final center = Offset(rect.left, rect.top);
    canvas.drawCircle(
      center,
      r + 1.5,
      Paint()..color = Colors.black.withValues(alpha: .6),
    );
    canvas.drawCircle(center, r, Paint()..color = color);
    if (hot) {
      canvas.drawCircle(
        center,
        r + 3,
        Paint()
          ..color = color
          ..style = PaintingStyle.stroke
          ..strokeWidth = 1.5,
      );
    }
    final tp = TextPainter(
      text: TextSpan(
        text: '${index + 1}',
        style: const TextStyle(
          color: AppTokens.onVerdict,
          fontSize: 11,
          fontWeight: FontWeight.w700,
        ),
      ),
      textDirection: TextDirection.ltr,
    )..layout();
    tp.paint(canvas, center - Offset(tp.width / 2, tp.height / 2));
  }

  /// Index of the detection whose rect contains [position], topmost last.
  int? hitTestDetection(Offset position, Size size) {
    for (var i = detections.length - 1; i >= 0; i--) {
      final d = detections[i];
      final rect = scaleDetectionToCanvas(
        cx: d.x,
        cy: d.y,
        w: d.width,
        h: d.height,
        imageSize: imageSize,
        canvasSize: size,
      ).inflate(12);
      if (rect.contains(position)) return i;
    }
    return null;
  }

  @override
  bool shouldRepaint(BboxOverlayPainter oldDelegate) =>
      oldDelegate.reveal != reveal ||
      oldDelegate.highlighted != highlighted ||
      oldDelegate.detections != detections;
}
