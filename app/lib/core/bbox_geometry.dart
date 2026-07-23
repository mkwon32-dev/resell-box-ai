import 'package:flutter/rendering.dart';

/// Maps a center-based model-space box to a Rect in widget space, assuming
/// the image is rendered with [fit] (default BoxFit.contain) and centered
/// inside [canvasSize]. Handles both letterboxing and source cropping.
Rect scaleDetectionToCanvas({
  required double cx,
  required double cy,
  required double w,
  required double h,
  required Size imageSize,
  required Size canvasSize,
  BoxFit fit = BoxFit.contain,
}) {
  if (imageSize.isEmpty || canvasSize.isEmpty) return Rect.zero;
  final fitted = applyBoxFit(fit, imageSize, canvasSize);
  if (fitted.source.isEmpty || fitted.destination.isEmpty) return Rect.zero;

  final scaleX = fitted.destination.width / fitted.source.width;
  final scaleY = fitted.destination.height / fitted.source.height;
  final sourceDx = (imageSize.width - fitted.source.width) / 2;
  final sourceDy = (imageSize.height - fitted.source.height) / 2;
  final dx = (canvasSize.width - fitted.destination.width) / 2;
  final dy = (canvasSize.height - fitted.destination.height) / 2;
  return Rect.fromCenter(
    center: Offset(
      (cx - sourceDx) * scaleX + dx,
      (cy - sourceDy) * scaleY + dy,
    ),
    width: w * scaleX,
    height: h * scaleY,
  );
}
