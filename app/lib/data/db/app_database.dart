import 'dart:convert';
import 'dart:io';

import 'package:drift/drift.dart';
import 'package:drift_flutter/drift_flutter.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

import '../models/analysis_result.dart';
import '../models/risk_verdict.dart';

part 'app_database.g.dart';

class ScanRecords extends Table {
  IntColumn get id => integer().autoIncrement()();
  DateTimeColumn get scannedAt => dateTime()();
  TextColumn get imagePath => text()();
  TextColumn get resultJson => text()();
  TextColumn get verdict => text()();
}

class ScanRecordData {
  const ScanRecordData({
    required this.id,
    required this.scannedAt,
    required this.imagePath,
    required this.result,
  });

  final int id;
  final DateTime scannedAt;
  final String imagePath;
  final AnalysisResult result;

  RiskVerdict get verdict => result.verdict;
}

@DriftDatabase(tables: [ScanRecords])
class AppDatabase extends _$AppDatabase {
  AppDatabase()
    : _documentsDirectory = getApplicationDocumentsDirectory,
      _clock = DateTime.now,
      super(driftDatabase(name: 'resellbox'));

  /// Creates a database with injectable storage for deterministic tests.
  AppDatabase.forTesting(
    super.executor, {
    required Directory documentsDirectory,
    DateTime Function()? clock,
  }) : _documentsDirectory = (() async => documentsDirectory),
       _clock = clock ?? DateTime.now;

  final Future<Directory> Function() _documentsDirectory;
  final DateTime Function() _clock;
  int _fileSequence = 0;
  Future<void> _pendingWrites = Future.value();
  bool _closing = false;

  @override
  int get schemaVersion => 1;

  /// Copies the photo into app documents (source may be a temp file) and
  /// persists the analysis result.
  Future<int> saveScan(File photo, AnalysisResult result) =>
      _runWrite(() => _saveScan(photo, result));

  Future<int> _saveScan(File photo, AnalysisResult result) async {
    final docs = await _documentsDirectory();
    final scansDir = Directory(p.join(docs.path, 'scans'));
    await scansDir.create(recursive: true);
    final now = _clock();
    final stored = await _copyToUniqueFile(photo, scansDir, now);
    try {
      return await into(scanRecords).insert(
        ScanRecordsCompanion.insert(
          scannedAt: now,
          imagePath: stored.path,
          resultJson: jsonEncode(result.toJson()),
          verdict: result.verdict.wire,
        ),
      );
    } catch (error, stackTrace) {
      // The filesystem isn't part of the SQLite transaction. Compensate for a
      // failed insert so a database error doesn't leak an untracked photo.
      await _deleteFileBestEffort(stored);
      Error.throwWithStackTrace(error, stackTrace);
    }
  }

  Future<File> _copyToUniqueFile(
    File source,
    Directory scansDir,
    DateTime scannedAt,
  ) async {
    final extension = p.extension(source.path);
    late File destination;
    while (true) {
      final sequence = _fileSequence++;
      destination = File(
        p.join(
          scansDir.path,
          'scan_${scannedAt.microsecondsSinceEpoch}_$sequence$extension',
        ),
      );
      try {
        await destination.create(exclusive: true);
        break;
      } on FileSystemException {
        if (!await destination.exists()) rethrow;
      }
    }

    try {
      await source.openRead().pipe(destination.openWrite());
      return destination;
    } catch (error, stackTrace) {
      await _deleteFileBestEffort(destination);
      Error.throwWithStackTrace(error, stackTrace);
    }
  }

  Stream<List<ScanRecordData>> watchAllScans() {
    final query = select(scanRecords)
      ..orderBy([(t) => OrderingTerm.desc(t.scannedAt)]);
    return query.watch().map((rows) {
      final records = <ScanRecordData>[];
      for (final row in rows) {
        try {
          records.add(_decodeRow(row));
        } on FormatException {
          // One corrupt legacy record should not make the entire history
          // stream unusable. Direct lookup still surfaces the corruption.
        }
      }
      return records;
    });
  }

  Future<ScanRecordData?> getScan(int id) async {
    final row = await (select(
      scanRecords,
    )..where((t) => t.id.equals(id))).getSingleOrNull();
    if (row == null) return null;
    return _decodeRow(row);
  }

  Future<void> deleteScan(int id) => _runWrite(() => _deleteScan(id));

  Future<void> _deleteScan(int id) async {
    final row = await (select(
      scanRecords,
    )..where((t) => t.id.equals(id))).getSingleOrNull();
    if (row == null) return;

    // Remove the authoritative record first. A missing/locked image must not
    // leave an undeletable ghost record in history.
    await (delete(scanRecords)..where((t) => t.id.equals(id))).go();
    await _deleteFileBestEffort(File(row.imagePath));
  }

  /// Removes photos that are no longer referenced by a scan record.
  ///
  /// Writes and cleanup are serialized so cleanup cannot mistake a photo that
  /// is between its copy and database insert for an orphan.
  Future<void> cleanupOrphanedFiles() => _runWrite(() async {
    final docs = await _documentsDirectory();
    final scansDir = Directory(p.join(docs.path, 'scans'));
    if (!await scansDir.exists()) return;

    final rows = await select(scanRecords).get();
    final referenced = rows
        .map((row) => p.normalize(p.absolute(row.imagePath)))
        .toSet();
    await for (final entity in scansDir.list(followLinks: false)) {
      if (entity is File &&
          !referenced.contains(p.normalize(p.absolute(entity.path)))) {
        await _deleteFileBestEffort(entity);
      }
    }
  });

  Future<T> _runWrite<T>(Future<T> Function() operation) {
    if (_closing) {
      return Future.error(StateError('The database is closing'));
    }
    final next = _pendingWrites.then((_) => operation());
    _pendingWrites = next.then<void>(
      (_) {},
      onError: (Object _, StackTrace _) {},
    );
    return next;
  }

  @override
  Future<void> close() async {
    _closing = true;
    await _pendingWrites;
    await super.close();
  }

  ScanRecordData _decodeRow(ScanRecord row) {
    try {
      final decoded = jsonDecode(row.resultJson);
      if (decoded is! Map<String, dynamic>) {
        throw const FormatException('Stored result must be a JSON object');
      }
      return ScanRecordData(
        id: row.id,
        scannedAt: row.scannedAt,
        imagePath: row.imagePath,
        result: AnalysisResult.fromJson(decoded),
      );
    } on FormatException catch (error) {
      throw FormatException(
        'Invalid result for scan ${row.id}: ${error.message}',
      );
    }
  }

  Future<void> _deleteFileBestEffort(File file) async {
    try {
      if (await file.exists()) await file.delete();
    } on FileSystemException {
      // The row is authoritative. Leaving an orphan is preferable to making
      // the user-visible record impossible to delete; a later OS/app cleanup
      // can reclaim an inaccessible file.
    }
  }
}
