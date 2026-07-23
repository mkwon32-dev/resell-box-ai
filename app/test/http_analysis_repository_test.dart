import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:resellbox_app/data/models/risk_verdict.dart';
import 'package:resellbox_app/data/repositories/http_analysis_repository.dart';

class _FakeAdapter implements HttpClientAdapter {
  _FakeAdapter(this.handler);

  final FutureOr<ResponseBody> Function(RequestOptions options) handler;

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async => handler(options);

  @override
  void close({bool force = false}) {}
}

ResponseBody jsonResponse(Object? body, {int status = 200}) =>
    ResponseBody.fromString(
      jsonEncode(body),
      status,
      headers: {
        Headers.contentTypeHeader: ['application/json'],
      },
    );

void main() {
  late Directory temp;
  late File image;

  setUp(() async {
    temp = await Directory.systemTemp.createTemp('resell_http_test_');
    image = await File('${temp.path}/photo.jpg').writeAsBytes([1, 2, 3]);
  });

  tearDown(() async {
    await temp.delete(recursive: true);
  });

  test('parses a valid JSON object and applies all request timeouts', () async {
    final dio = Dio();
    dio.httpClientAdapter = _FakeAdapter((options) {
      expect(options.uri.toString(), 'https://api.example/v1/analyze');
      expect(options.connectTimeout, const Duration(seconds: 3));
      expect(options.sendTimeout, const Duration(seconds: 3));
      expect(options.receiveTimeout, const Duration(seconds: 3));
      return jsonResponse({'predictions': []});
    });
    final repository = HttpAnalysisRepository(
      baseUrl: 'https://api.example/v1',
      dio: dio,
      requestTimeout: const Duration(seconds: 3),
    );

    final result = await repository.analyze(image);

    expect(result.verdict, RiskVerdict.low);
  });

  test('accepts JSON text when content-type is incorrect', () async {
    final dio = Dio();
    dio.httpClientAdapter = _FakeAdapter(
      (_) => ResponseBody.fromString('{"predictions":[]}', 200),
    );
    final repository = HttpAnalysisRepository(
      baseUrl: 'https://api.example',
      dio: dio,
    );

    expect((await repository.analyze(image)).verdict, RiskVerdict.low);
  });

  test('rejects null, list, and malformed JSON bodies clearly', () async {
    for (final body in [null, <Object?>[]]) {
      final dio = Dio();
      dio.httpClientAdapter = _FakeAdapter((_) => jsonResponse(body));
      final repository = HttpAnalysisRepository(
        baseUrl: 'https://api.example',
        dio: dio,
      );
      await expectLater(repository.analyze(image), throwsFormatException);
    }

    final dio = Dio();
    dio.httpClientAdapter = _FakeAdapter(
      (_) => ResponseBody.fromString('{broken', 200),
    );
    final repository = HttpAnalysisRepository(
      baseUrl: 'https://api.example',
      dio: dio,
    );
    await expectLater(repository.analyze(image), throwsFormatException);
  });

  test('rejects non-success status even if Dio accepts it', () async {
    final dio = Dio(BaseOptions(validateStatus: (_) => true));
    dio.httpClientAdapter = _FakeAdapter(
      (_) => jsonResponse({'detail': 'down'}, status: 503),
    );
    final repository = HttpAnalysisRepository(
      baseUrl: 'https://api.example',
      dio: dio,
    );

    await expectLater(repository.analyze(image), throwsA(isA<DioException>()));
  });
}
