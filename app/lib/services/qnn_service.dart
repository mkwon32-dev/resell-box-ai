import 'dart:io';

import 'package:flutter/services.dart';

class QnnService {
  static const MethodChannel _channel = MethodChannel(
    'com.resellbox.ai/qnn',
  );

  Future<Map<String, dynamic>> analyze(File image) async {
    final result = await _channel.invokeMapMethod<String, dynamic>(
      'analyzeImage',
      {
        'imagePath': image.path,
        // Per the model card (app/assets/models/box_ai_v12_fp16.json):
        // precision 0.87 at confidence 0.6, per-class NMS IoU 0.5.
        'confidenceThreshold': 0.60,
        'nmsThreshold': 0.50,
      },
    );

    if (result == null) {
      throw StateError('QNN returned no result');
    }

    return result;
  }
}
