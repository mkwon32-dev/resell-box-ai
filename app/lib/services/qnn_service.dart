import 'dart:io';

import 'package:flutter/services.dart';

class QnnService {
  static const MethodChannel _channel = MethodChannel(
    'com.resellbox.ai/qnn',
  );

  Future<Map<String, dynamic>> analyze(
    File image, {
    bool forceCpuOnly = false,
  }) async {
    // Confidence and NMS thresholds live in TFLiteDetector; sending them here
    // only implied they were configurable when the native side ignored them.
    final result = await _channel.invokeMapMethod<String, dynamic>(
      'analyzeImage',
      {
        'imagePath': image.path,
        'forceCpuOnly': forceCpuOnly,
      },
    );

    if (result == null) {
      throw StateError('QNN returned no result');
    }

    return result;
  }
}
