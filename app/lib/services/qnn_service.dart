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
        'confidenceThreshold': 0.20,
        'nmsThreshold': 0.45,
      },
    );

    if (result == null) {
      throw StateError('QNN returned no result');
    }

    return result;
  }
}
