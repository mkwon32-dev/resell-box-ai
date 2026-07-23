import 'package:flutter/material.dart';

import '../../app/tokens.dart';

/// Damage classes are open-ended: the Roboflow project currently emits
/// `dent`/`surface_damage`, the README targets `scratch`/`dent`/`tear`.
enum DamageClass {
  dent,
  surfaceDamage,
  scratch,
  tear,
  unknown;

  static DamageClass fromString(String? raw) {
    switch (raw?.trim().toLowerCase().replaceAll(' ', '_')) {
      case 'dent':
        return DamageClass.dent;
      case 'surface_damage':
        return DamageClass.surfaceDamage;
      case 'scratch':
        return DamageClass.scratch;
      case 'tear':
        return DamageClass.tear;
      default:
        return DamageClass.unknown;
    }
  }
}

extension DamageClassX on DamageClass {
  String get label => switch (this) {
    DamageClass.dent => 'DENT',
    DamageClass.surfaceDamage => 'SURFACE',
    DamageClass.scratch => 'SCRATCH',
    DamageClass.tear => 'TEAR',
    DamageClass.unknown => 'DAMAGE',
  };

  String get wire => switch (this) {
    DamageClass.surfaceDamage => 'surface_damage',
    _ => name,
  };

  Color get color =>
      AppTokens.classColors[wire] ?? AppTokens.classColors['unknown']!;
}

/// One detected damage region. Mirrors Roboflow prediction JSON:
/// x/y are the box CENTER in pixels of the analyzed image.
class Detection {
  const Detection({
    required this.x,
    required this.y,
    required this.width,
    required this.height,
    required this.damageClass,
    required this.confidence,
    this.widthCm,
    this.heightCm,
  });

  final double x;
  final double y;
  final double width;
  final double height;
  final DamageClass damageClass;
  final double confidence;
  final double? widthCm;
  final double? heightCm;

  double? get longestSideCm {
    if (widthCm == null && heightCm == null) return null;
    if ((widthCm != null && (!widthCm!.isFinite || widthCm! < 0)) ||
        (heightCm != null && (!heightCm!.isFinite || heightCm! < 0))) {
      return double.nan;
    }
    if (widthCm == null) return heightCm;
    if (heightCm == null) return widthCm;
    return widthCm! > heightCm! ? widthCm : heightCm;
  }

  bool get hasSize =>
      widthCm != null &&
      heightCm != null &&
      widthCm!.isFinite &&
      heightCm!.isFinite &&
      widthCm! >= 0 &&
      heightCm! >= 0;

  Detection withoutMeasurements() => widthCm == null && heightCm == null
      ? this
      : Detection(
          x: x,
          y: y,
          width: width,
          height: height,
          damageClass: damageClass,
          confidence: confidence,
        );

  factory Detection.fromJson(Map<String, dynamic> json) {
    final rawClass = json['class'];
    if (rawClass != null && rawClass is! String) {
      throw const FormatException('Detection "class" must be a string');
    }

    final width = _requiredFiniteNumber(json, 'width');
    final height = _requiredFiniteNumber(json, 'height');
    final confidence = _optionalFiniteNumber(json, 'confidence') ?? 0;
    final widthCm = _optionalFiniteNumber(json, 'width_cm');
    final heightCm = _optionalFiniteNumber(json, 'height_cm');
    if (width < 0 || height < 0) {
      throw const FormatException('Detection dimensions cannot be negative');
    }
    if (confidence < 0 || confidence > 1) {
      throw const FormatException(
        'Detection confidence must be between 0 and 1',
      );
    }
    if ((widthCm != null && widthCm < 0) ||
        (heightCm != null && heightCm < 0)) {
      throw const FormatException('Measured dimensions cannot be negative');
    }

    return Detection(
      x: _requiredFiniteNumber(json, 'x'),
      y: _requiredFiniteNumber(json, 'y'),
      width: width,
      height: height,
      damageClass: DamageClass.fromString(rawClass as String?),
      confidence: confidence,
      widthCm: widthCm,
      heightCm: heightCm,
    );
  }

  Map<String, dynamic> toJson() => {
    'x': x,
    'y': y,
    'width': width,
    'height': height,
    'class': damageClass.wire,
    'confidence': confidence,
    if (widthCm != null) 'width_cm': widthCm,
    if (heightCm != null) 'height_cm': heightCm,
  };
}

double _requiredFiniteNumber(Map<String, dynamic> json, String key) {
  if (!json.containsKey(key) || json[key] is! num) {
    throw FormatException('Detection "$key" must be a number');
  }
  final value = (json[key] as num).toDouble();
  if (!value.isFinite) {
    throw FormatException('Detection "$key" must be finite');
  }
  return value;
}

double? _optionalFiniteNumber(Map<String, dynamic> json, String key) {
  final raw = json[key];
  if (raw == null) return null;
  if (raw is! num) {
    throw FormatException('Detection "$key" must be a number');
  }
  final value = raw.toDouble();
  if (!value.isFinite) {
    throw FormatException('Detection "$key" must be finite');
  }
  return value;
}
