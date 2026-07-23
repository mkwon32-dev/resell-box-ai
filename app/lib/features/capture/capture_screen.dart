import 'dart:async';
import 'dart:io';

import 'package:camera/camera.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:go_router/go_router.dart';
import 'package:image_picker/image_picker.dart';
import 'package:path_provider/path_provider.dart';

import '../../app/theme.dart';
import '../../app/tokens.dart';
import 'box_guide_overlay.dart';

/// Full-bleed viewfinder with box-framing guide. Gallery and bundled-sample
/// fallbacks keep the flow demoable without a camera (emulator/WSL2).
class CaptureScreen extends StatefulWidget {
  const CaptureScreen({super.key});

  @override
  State<CaptureScreen> createState() => _CaptureScreenState();
}

class _CaptureScreenState extends State<CaptureScreen>
    with WidgetsBindingObserver {
  CameraController? _camera;
  bool _cameraFailed = false;
  bool _busy = false;
  bool _cameraInitializing = false;
  bool _retryCameraInit = false;
  bool _cameraAllowed = true;
  int _sampleIndex = 0;

  static const _samples = [
    'assets/samples/sample_scuff.jpg',
    'assets/samples/sample_dent.jpg',
    'assets/samples/sample_tear.jpg',
  ];

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _initCamera();
  }

  Future<void> _initCamera() async {
    if (!_cameraAllowed || !mounted) return;
    if (_cameraInitializing) {
      _retryCameraInit = true;
      return;
    }
    _cameraInitializing = true;
    _retryCameraInit = false;
    if (_cameraFailed) setState(() => _cameraFailed = false);
    CameraController? controller;
    try {
      final cameras = await availableCameras();
      if (cameras.isEmpty) throw CameraException('none', 'No cameras');
      controller = CameraController(
        cameras.first,
        ResolutionPreset.high,
        enableAudio: false,
      );
      await controller.initialize();
      if (!mounted || !_cameraAllowed) {
        await controller.dispose();
        return;
      }
      setState(() => _camera = controller);
    } catch (_) {
      await controller?.dispose();
      if (mounted && _cameraAllowed) {
        setState(() => _cameraFailed = true);
      }
    } finally {
      _cameraInitializing = false;
      if (_retryCameraInit && _cameraAllowed && _camera == null && mounted) {
        unawaited(_initCamera());
      }
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _cameraAllowed = state == AppLifecycleState.resumed;
    if (!_cameraAllowed) {
      _retryCameraInit = false;
      final camera = _camera;
      if (camera != null) {
        setState(() => _camera = null);
        unawaited(camera.dispose());
      }
      return;
    }
    unawaited(_initCamera());
  }

  @override
  void dispose() {
    _cameraAllowed = false;
    WidgetsBinding.instance.removeObserver(this);
    _camera?.dispose();
    super.dispose();
  }

  Future<void> _shoot() async {
    final camera = _camera;
    if (camera == null || _busy) return;
    setState(() => _busy = true);
    try {
      unawaited(HapticFeedback.mediumImpact());
      final shot = await camera.takePicture();
      _openAnalysis(File(shot.path));
    } catch (_) {
      _showError('Could not take a photo. Try gallery or a sample.');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _pickFromGallery() async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final picked = await ImagePicker().pickImage(source: ImageSource.gallery);
      if (picked != null) _openAnalysis(File(picked.path));
    } catch (_) {
      _showError('Could not open the photo library.');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _useSample() async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final asset = _samples[_sampleIndex++ % _samples.length];
      final data = await rootBundle.load(asset);
      final dir = await getTemporaryDirectory();
      final file = File(
        '${dir.path}/sample_${DateTime.now().millisecondsSinceEpoch}.jpg',
      );
      await file.writeAsBytes(data.buffer.asUint8List(), flush: true);
      _openAnalysis(file);
    } catch (_) {
      _showError('Could not load the sample photo.');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _openAnalysis(File photo) {
    if (!mounted) return;
    context.pushReplacement('/analyzing', extra: photo);
  }

  void _showError(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(message)));
  }

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    final camera = _camera;

    return Scaffold(
      backgroundColor: AppTokens.bg,
      body: Stack(
        fit: StackFit.expand,
        children: [
          if (camera != null && camera.value.isInitialized)
            CameraPreview(camera)
          else
            Container(
              color: AppTokens.surface,
              alignment: Alignment.center,
              child: Padding(
                padding: const EdgeInsets.all(AppTokens.s6),
                child: Text(
                  _cameraFailed
                      ? 'No camera — use gallery or a sample photo'
                      : 'Starting camera…',
                  style: text.bodyMedium,
                  textAlign: TextAlign.center,
                ),
              ),
            ),
          const BoxGuideOverlay(),
          SafeArea(
            child: Column(
              children: [
                Align(
                  alignment: Alignment.centerLeft,
                  child: IconButton(
                    tooltip: 'Close camera',
                    onPressed: () =>
                        context.canPop() ? context.pop() : context.go('/'),
                    icon: const Icon(Icons.close),
                    color: AppTokens.textPrimary,
                  ),
                ),
                const Spacer(),
                Padding(
                  padding: const EdgeInsets.only(bottom: AppTokens.s5),
                  child: Row(
                    mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                    children: [
                      _SideAction(
                        icon: Icons.photo_library_outlined,
                        label: 'gallery',
                        enabled: !_busy,
                        onTap: _pickFromGallery,
                      ),
                      _Shutter(
                        enabled: camera != null && !_busy,
                        onTap: _shoot,
                      ),
                      _SideAction(
                        icon: Icons.inventory_2_outlined,
                        label: 'sample',
                        enabled: !_busy,
                        onTap: _useSample,
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _Shutter extends StatelessWidget {
  const _Shutter({required this.enabled, required this.onTap});

  final bool enabled;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      button: true,
      label: 'Take photo',
      enabled: enabled,
      child: GestureDetector(
        onTap: enabled ? onTap : null,
        child: AnimatedOpacity(
          duration: AppTokens.tFast,
          opacity: enabled ? 1 : 0.4,
          child: Container(
            width: 76,
            height: 76,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              border: Border.all(color: AppTokens.textPrimary, width: 3),
            ),
            padding: const EdgeInsets.all(5),
            child: const DecoratedBox(
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: AppTokens.textPrimary,
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _SideAction extends StatelessWidget {
  const _SideAction({
    required this.icon,
    required this.label,
    required this.enabled,
    required this.onTap,
  });

  final IconData icon;
  final String label;
  final bool enabled;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      button: true,
      label: label,
      enabled: enabled,
      child: GestureDetector(
        onTap: enabled ? onTap : null,
        behavior: HitTestBehavior.opaque,
        child: AnimatedOpacity(
          duration: AppTokens.tFast,
          opacity: enabled ? 1 : 0.4,
          child: Padding(
            padding: const EdgeInsets.all(AppTokens.s2),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(icon, color: AppTokens.textPrimary, size: 28),
                const SizedBox(height: AppTokens.s1),
                Text(
                  label,
                  style: AppText.mono(size: 11, color: AppTokens.textSecondary),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
