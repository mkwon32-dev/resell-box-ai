import 'dart:convert';
import 'dart:io';

import 'package:dio/dio.dart';

import '../models/analysis_result.dart';
import 'analysis_repository.dart';

/// Real backend client. Expects the FastAPI contract:
/// POST {baseUrl}/analyze  (multipart field "photo")
/// → AnalysisResult JSON (Roboflow predictions + width_cm/height_cm/verdict
/// + scale_source: "none" | "box_edge" | "box_face").
class HttpAnalysisRepository implements AnalysisRepository {
  HttpAnalysisRepository({
    required String baseUrl,
    Dio? dio,
    this.requestTimeout = const Duration(seconds: 15),
  }) : _dio = dio ?? Dio() {
    _dio.options.baseUrl = _normalizedBaseUrl(baseUrl);
  }

  final Dio _dio;
  final Duration requestTimeout;

  @override
  Future<AnalysisResult> analyze(File image) async {
    final form = FormData.fromMap({
      'photo': await MultipartFile.fromFile(image.path),
    });
    final response = await _dio.post<Object?>(
      'analyze',
      data: form,
      options: Options(
        connectTimeout: requestTimeout,
        sendTimeout: requestTimeout,
        receiveTimeout: requestTimeout,
      ),
    );
    final status = response.statusCode;
    if (status == null || status < 200 || status >= 300) {
      throw DioException.badResponse(
        statusCode: status ?? 0,
        requestOptions: response.requestOptions,
        response: response,
      );
    }

    Object? body = response.data;
    if (body is String) {
      try {
        body = jsonDecode(body);
      } on FormatException catch (error) {
        throw FormatException(
          'Backend returned invalid JSON: ${error.message}',
        );
      }
    }
    if (body is! Map<String, dynamic>) {
      throw const FormatException('Backend response must be a JSON object');
    }
    return AnalysisResult.fromJson(body);
  }
}

String _normalizedBaseUrl(String baseUrl) {
  final uri = Uri.tryParse(baseUrl);
  if (uri == null ||
      (uri.scheme != 'http' && uri.scheme != 'https') ||
      uri.host.isEmpty) {
    throw ArgumentError.value(baseUrl, 'baseUrl', 'Must be an HTTP(S) URL');
  }
  return baseUrl.endsWith('/') ? baseUrl : '$baseUrl/';
}
