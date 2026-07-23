import 'dart:convert';
import 'dart:io';

import 'package:drift/native.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:resellbox_app/data/db/app_database.dart';
import 'package:resellbox_app/data/models/analysis_result.dart';
import 'package:resellbox_app/data/models/risk_verdict.dart';

const result = AnalysisResult(
  imageWidth: 100,
  imageHeight: 100,
  detections: [],
  verdict: RiskVerdict.low,
  scaleSource: ScaleSource.none,
);

void main() {
  late Directory temp;
  late AppDatabase db;
  late File source;

  setUp(() async {
    temp = await Directory.systemTemp.createTemp('resell_db_test_');
    source = await File('${temp.path}/source.jpg').writeAsBytes([1, 2, 3]);
    db = AppDatabase.forTesting(
      NativeDatabase.memory(),
      documentsDirectory: temp,
      clock: () => DateTime.utc(2026, 1, 2),
    );
  });

  tearDown(() async {
    await db.close();
    if (await temp.exists()) await temp.delete(recursive: true);
  });

  test('concurrent saves at the same instant use distinct files', () async {
    final ids = await Future.wait([
      db.saveScan(source, result),
      db.saveScan(source, result),
      db.saveScan(source, result),
    ]);

    final records = await Future.wait(ids.map(db.getScan));
    final paths = records.map((record) => record!.imagePath).toSet();
    expect(paths, hasLength(3));
    for (final path in paths) {
      expect(await File(path).readAsBytes(), [1, 2, 3]);
    }
  });

  test('failed source copy does not leave a partial photo', () async {
    final missing = File('${temp.path}/missing.jpg');

    await expectLater(
      db.saveScan(missing, result),
      throwsA(isA<FileSystemException>()),
    );

    final scans = Directory('${temp.path}/scans');
    expect(await scans.list().toList(), isEmpty);
    expect(await db.select(db.scanRecords).get(), isEmpty);
  });

  test('failed database insert removes the copied photo', () async {
    await db.customStatement('DROP TABLE scan_records');

    await expectLater(db.saveScan(source, result), throwsA(anything));

    final scans = Directory('${temp.path}/scans');
    expect(await scans.list().toList(), isEmpty);
  });

  test('delete removes the record even when image cleanup cannot', () async {
    final id = await db.saveScan(source, result);
    final record = (await db.getScan(id))!;
    await File(record.imagePath).delete();
    await Directory(record.imagePath).create();

    await db.deleteScan(id);

    expect(await db.getScan(id), null);
    expect(await Directory(record.imagePath).exists(), isTrue);
  });

  test('history skips a corrupt row while direct lookup reports it', () async {
    final goodId = await db.saveScan(source, result);
    final corruptId = await db
        .into(db.scanRecords)
        .insert(
          ScanRecordsCompanion.insert(
            scannedAt: DateTime.utc(2026),
            imagePath: 'unused',
            resultJson: '{broken',
            verdict: 'low',
          ),
        );

    final records = await db.watchAllScans().first;

    expect(records.map((record) => record.id), [goodId]);
    await expectLater(
      db.getScan(corruptId),
      throwsA(
        isA<FormatException>().having(
          (error) => error.message,
          'message',
          contains('scan $corruptId'),
        ),
      ),
    );
  });

  test('legacy stored row with card_detected still parses', () async {
    // Rows persisted before the box-face sizing migration carry the old
    // boolean instead of scale_source.
    final legacyId = await db
        .into(db.scanRecords)
        .insert(
          ScanRecordsCompanion.insert(
            scannedAt: DateTime.utc(2025, 12, 1),
            imagePath: 'unused',
            resultJson: jsonEncode({
              'image': {'width': 640, 'height': 480},
              'predictions': <Object?>[],
              'verdict': 'low',
              'card_detected': true,
            }),
            verdict: 'low',
          ),
        );

    final record = await db.getScan(legacyId);
    expect(record!.result.scaleSource, ScaleSource.boxFace);
  });

  test('stored result remains valid JSON after save', () async {
    final id = await db.saveScan(source, result);
    final row = await (db.select(
      db.scanRecords,
    )..where((row) => row.id.equals(id))).getSingle();

    expect(jsonDecode(row.resultJson), isA<Map<String, dynamic>>());
  });

  test(
    'orphan cleanup preserves referenced photos and removes stale ones',
    () async {
      final id = await db.saveScan(source, result);
      final referenced = File((await db.getScan(id))!.imagePath);
      final orphan = await File(
        '${temp.path}/scans/orphan.jpg',
      ).writeAsBytes([9]);

      await db.cleanupOrphanedFiles();

      expect(await referenced.exists(), isTrue);
      expect(await orphan.exists(), isFalse);
    },
  );
}
