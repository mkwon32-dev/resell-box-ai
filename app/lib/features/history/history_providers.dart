import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/db/app_database.dart';
import '../../providers.dart';

final scanHistoryProvider = StreamProvider<List<ScanRecordData>>(
  (ref) => ref.watch(databaseProvider).watchAllScans(),
);

final scanByIdProvider = FutureProvider.family<ScanRecordData?, int>(
  (ref, id) => ref.watch(databaseProvider).getScan(id),
);
