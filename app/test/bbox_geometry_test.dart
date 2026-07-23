import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:resellbox_app/core/bbox_geometry.dart';

void main() {
  group('scaleDetectionToCanvas', () {
    test('1:1 scale, same aspect', () {
      final rect = scaleDetectionToCanvas(
        cx: 50,
        cy: 50,
        w: 20,
        h: 10,
        imageSize: const Size(100, 100),
        canvasSize: const Size(100, 100),
      );
      expect(rect, const Rect.fromLTWH(40, 45, 20, 10));
    });

    test('uniform downscale', () {
      final rect = scaleDetectionToCanvas(
        cx: 100,
        cy: 100,
        w: 40,
        h: 40,
        imageSize: const Size(200, 200),
        canvasSize: const Size(100, 100),
      );
      expect(rect, const Rect.fromLTWH(40, 40, 20, 20));
    });

    test('letterbox offset: portrait image in landscape canvas', () {
      // Image 100x200 contained in 200x200 canvas → dest 100x200 at dx=50.
      final rect = scaleDetectionToCanvas(
        cx: 50,
        cy: 100,
        w: 20,
        h: 20,
        imageSize: const Size(100, 200),
        canvasSize: const Size(200, 200),
      );
      expect(rect.center, const Offset(100, 100));
      expect(rect.width, 20);
      expect(rect.height, 20);
    });

    test('letterbox offset: landscape image in taller canvas', () {
      // Image 200x100 contained in 200x200 → dest 200x100 at dy=50.
      final rect = scaleDetectionToCanvas(
        cx: 0,
        cy: 0,
        w: 10,
        h: 10,
        imageSize: const Size(200, 100),
        canvasSize: const Size(200, 200),
      );
      expect(rect.center, const Offset(0, 50));
    });

    test('cover fit accounts for the centered source crop', () {
      // A 200x100 image covering a 100x100 canvas crops 50px from each side.
      final rect = scaleDetectionToCanvas(
        cx: 50,
        cy: 50,
        w: 20,
        h: 20,
        imageSize: const Size(200, 100),
        canvasSize: const Size(100, 100),
        fit: BoxFit.cover,
      );
      expect(rect.center, const Offset(0, 50));
      expect(rect.width, 20);
      expect(rect.height, 20);
    });

    test('degenerate sizes return zero rect', () {
      expect(
        scaleDetectionToCanvas(
          cx: 0,
          cy: 0,
          w: 1,
          h: 1,
          imageSize: Size.zero,
          canvasSize: const Size(10, 10),
        ),
        Rect.zero,
      );
    });
  });
}
