import 'dart:async';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/theme.dart';
import '../../app/tokens.dart';
import '../../providers.dart';

/// P0 "The Reveal", act one: shader scan-line sweeps the captured photo
/// while inference runs. Falls back to a plain animated line if the
/// fragment shader fails to load.
class AnalyzingScreen extends ConsumerStatefulWidget {
  const AnalyzingScreen({super.key, required this.photo});

  final File photo;

  @override
  ConsumerState<AnalyzingScreen> createState() => _AnalyzingScreenState();
}

class _AnalyzingScreenState extends ConsumerState<AnalyzingScreen>
    with SingleTickerProviderStateMixin {
  static const _captions = [
    'Finding box',
    'Measuring damage',
    'Scoring risk',
  ];

  late final Ticker _ticker;
  double _progress = 0;
  int _captionIndex = 0;
  ui.Image? _photoImage;
  ui.FragmentShader? _shader;
  bool _done = false;
  Object? _error;
  Timer? _captionTimer;

  @override
  void initState() {
    super.initState();
    _ticker = createTicker(_tick)..start();
    _captionTimer = Timer.periodic(const Duration(milliseconds: 1100), (_) {
      if (mounted) {
        setState(() => _captionIndex = (_captionIndex + 1) % _captions.length);
      }
    });
    _loadAssets();

    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) {
        _run();
      }
    });
  }

  void _tick(Duration elapsed) {
    // 1.6s sweep, loops until analysis lands, then finishes the pass.
    final t = (elapsed.inMilliseconds % 1600) / 1600;
    if (_done && t > 0.97) {
      _ticker.stop();
      _navigate();
      return;
    }
    setState(() => _progress = t);
  }

  Future<void> _loadAssets() async {
    ui.Codec? codec;
    ui.Image? image;
    ui.FragmentShader? shader;
    var transferred = false;
    try {
      final bytes = await widget.photo.readAsBytes();
      codec = await ui.instantiateImageCodec(bytes);
      final frame = await codec.getNextFrame();
      image = frame.image;
      final program = await ui.FragmentProgram.fromAsset(
        'shaders/scanline.frag',
      );
      if (!mounted) return;
      shader = program.fragmentShader();
      setState(() {
        _photoImage = image;
        _shader = shader;
      });
      transferred = true;
    } catch (_) {
      // The regular image + scan-line fallback below remains available.
    } finally {
      codec?.dispose();
      if (!transferred) {
        image?.dispose();
        shader?.dispose();
      }
    }
  }

  Future<void> _run() async {
    final started = DateTime.now();
    try {
      final scanId = await ref
          .read(analysisControllerProvider.notifier)
          .analyze(widget.photo);
      // Hold tension: minimum 2.2s in this screen.
      final elapsed = DateTime.now().difference(started);
      if (elapsed < const Duration(milliseconds: 2200)) {
        await Future<void>.delayed(
          const Duration(milliseconds: 2200) - elapsed,
        );
      }
      if (!mounted) return;
      _pendingScanId = scanId;
      setState(() => _done = true);
    } catch (e, stackTrace) {
      debugPrint('ANALYSIS SCREEN ERROR: $e');
      debugPrintStack(stackTrace: stackTrace);

      if (mounted) {
        _ticker.stop();
        _captionTimer?.cancel();
        setState(() => _error = e);
      }
    }
  }

  int? _pendingScanId;

  void _navigate() {
    final id = _pendingScanId;
    if (id != null && mounted) {
      context.pushReplacement('/result/$id', extra: true /* reveal */);
    }
  }

  @override
  void dispose() {
    _ticker.dispose();
    _captionTimer?.cancel();
    _photoImage?.dispose();
    _shader?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    final reduceMotion = MediaQuery.disableAnimationsOf(context);

    if (_error != null) {
      return Scaffold(
        body: Center(
          child: Padding(
            padding: const EdgeInsets.all(AppTokens.s6),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('SCAN FAILED', style: text.displaySmall),
                const SizedBox(height: AppTokens.s3),
                Text('Could not analyze this photo.', style: text.bodyMedium),
            const SizedBox(height: AppTokens.s3),
            Text(
              _error.toString(),
              style: text.bodySmall,
            ),
                const SizedBox(height: AppTokens.s5),
                FilledButton(
                  onPressed: () => context.pushReplacement('/capture'),
                  child: const Text('Try again'),
                ),
              ],
            ),
          ),
        ),
      );
    }

    return Scaffold(
      backgroundColor: AppTokens.bg,
      body: Stack(
        fit: StackFit.expand,
        children: [
          if (_shader != null && _photoImage != null && !reduceMotion)
            Center(
              child: AspectRatio(
                aspectRatio: _photoImage!.width / _photoImage!.height,
                child: CustomPaint(
                  painter: _ScanShaderPainter(
                    shader: _shader!,
                    image: _photoImage!,
                    progress: _progress,
                  ),
                ),
              ),
            )
          else ...[
            Image.file(
              widget.photo,
              fit: BoxFit.contain,
              errorBuilder: (context, error, stackTrace) => const Center(
                child: Icon(
                  Icons.broken_image_outlined,
                  size: 48,
                  color: AppTokens.textFaint,
                ),
              ),
            ),
            if (!reduceMotion)
              Align(
                alignment: Alignment(0, _progress * 2 - 1),
                child: Container(
                  height: 2,
                  color: AppTokens.textPrimary.withValues(alpha: .85),
                ),
              ),
          ],
          SafeArea(
            child: Align(
              alignment: Alignment.bottomLeft,
              child: Padding(
                padding: const EdgeInsets.all(AppTokens.s5),
                child: AnimatedSwitcher(
                  duration: reduceMotion ? Duration.zero : AppTokens.tMed,
                  child: Text(
                    _captions[_captionIndex],
                    key: ValueKey(_captionIndex),
                    style: AppText.mono(
                      size: 13,
                      color: AppTokens.textSecondary,
                    ),
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _ScanShaderPainter extends CustomPainter {
  _ScanShaderPainter({
    required this.shader,
    required this.image,
    required this.progress,
  });

  final ui.FragmentShader shader;
  final ui.Image image;
  final double progress;

  @override
  void paint(Canvas canvas, Size size) {
    shader
      ..setFloat(0, size.width)
      ..setFloat(1, size.height)
      ..setFloat(2, progress)
      ..setImageSampler(0, image);
    canvas.drawRect(Offset.zero & size, Paint()..shader = shader);
  }

  @override
  bool shouldRepaint(_ScanShaderPainter oldDelegate) =>
      oldDelegate.progress != progress;
}
