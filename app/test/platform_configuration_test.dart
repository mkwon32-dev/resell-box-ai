import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  group('mobile permission configuration', () {
    test(
      'Android release manifest supports network and optional camera use',
      () {
        final manifest = File(
          'android/app/src/main/AndroidManifest.xml',
        ).readAsStringSync();

        expect(
          manifest,
          contains('android.permission.INTERNET'),
          reason: 'Release builds need network access for the analysis API.',
        );
        expect(
          manifest,
          contains('android.permission.CAMERA'),
          reason: 'The camera plugin cannot capture without this permission.',
        );
        expect(
          manifest,
          contains('android:name="android.hardware.camera.any"'),
        );
        expect(
          manifest,
          contains('android:required="false"'),
          reason:
              'Gallery and sample-photo flows work without camera hardware.',
        );
        expect(
          manifest,
          contains(
            'android:name="android.permission.RECORD_AUDIO"\n'
            '        tools:node="remove"',
          ),
          reason: 'Still-photo capture must not request microphone access.',
        );
        expect(
          manifest,
          contains(
            'android:name="android.permission.WRITE_EXTERNAL_STORAGE"\n'
            '        tools:node="remove"',
          ),
          reason:
              'The app uses scoped cache files and the system photo picker.',
        );
        expect(manifest, contains('android:label="ResellBox AI"'));
      },
    );

    test('iOS explains camera and photo-library access', () {
      final infoPlist = File('ios/Runner/Info.plist').readAsStringSync();

      expect(infoPlist, contains('<key>NSCameraUsageDescription</key>'));
      expect(infoPlist, contains('<key>NSPhotoLibraryUsageDescription</key>'));
      expect(
        infoPlist,
        contains(
          '<key>CFBundleDisplayName</key>\n\t<string>ResellBox AI</string>',
        ),
      );
    });
  });
}
