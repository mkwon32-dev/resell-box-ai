import 'package:flutter/material.dart';

import '../../app/theme.dart';
import '../../app/tokens.dart';

/// Corner brackets framing where the whole box should sit, breathing subtly
/// (P2 "Scanner"). Static when reduced motion is requested. Replaces the old
/// reference-card guide: sizing now comes from the box itself, so the only
/// ask is that the entire box is in frame.
class BoxGuideOverlay extends StatefulWidget {
  const BoxGuideOverlay({super.key});

  /// Landscape region roughly matching a 3/4 view of a closed sneaker box.
  static const double frameAspect = 4 / 3;

  @override
  State<BoxGuideOverlay> createState() => _BoxGuideOverlayState();
}

class _BoxGuideOverlayState extends State<BoxGuideOverlay>
    with SingleTickerProviderStateMixin {
  late final AnimationController _breath = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 2400),
  );
  bool? _reduceMotion;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final reduceMotion = MediaQuery.disableAnimationsOf(context);
    if (_reduceMotion == reduceMotion) return;
    _reduceMotion = reduceMotion;
    if (reduceMotion) {
      _breath.stop();
    } else {
      _breath.repeat(reverse: true);
    }
  }

  @override
  void dispose() {
    _breath.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final reduceMotion = _reduceMotion ?? false;
    return IgnorePointer(
      child: Align(
        alignment: const Alignment(0, -0.1),
        child: AnimatedBuilder(
          animation: _breath,
          builder: (context, child) {
            final t = reduceMotion
                ? 0.5
                : Curves.easeInOut.transform(_breath.value);
            return Opacity(
              opacity: 0.55 + 0.35 * t,
              child: Transform.scale(scale: 1 + 0.015 * t, child: child),
            );
          },
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              SizedBox(
                width: 260,
                height: 260 / BoxGuideOverlay.frameAspect,
                child: CustomPaint(painter: _CornerBracketPainter()),
              ),
              const SizedBox(height: AppTokens.s2),
              Text(
                'whole box in frame',
                style: AppText.mono(size: 11, color: AppTokens.textPrimary),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _CornerBracketPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = AppTokens.textPrimary
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.5
      ..strokeCap = StrokeCap.round;
    final arm = 0.14 * size.shortestSide;
    const r = 6.0;
    final w = size.width;
    final h = size.height;
    // One path per corner: arm along each edge joined by a rounded corner.
    final corners = <Path>[
      Path()
        ..moveTo(0, arm)
        ..lineTo(0, r)
        ..arcToPoint(const Offset(r, 0), radius: const Radius.circular(r))
        ..lineTo(arm, 0),
      Path()
        ..moveTo(w - arm, 0)
        ..lineTo(w - r, 0)
        ..arcToPoint(Offset(w, r), radius: const Radius.circular(r))
        ..lineTo(w, arm),
      Path()
        ..moveTo(w, h - arm)
        ..lineTo(w, h - r)
        ..arcToPoint(Offset(w - r, h), radius: const Radius.circular(r))
        ..lineTo(w - arm, h),
      Path()
        ..moveTo(arm, h)
        ..lineTo(r, h)
        ..arcToPoint(Offset(0, h - r), radius: const Radius.circular(r))
        ..lineTo(0, h - arm),
    ];
    for (final path in corners) {
      canvas.drawPath(path, paint);
    }
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}
