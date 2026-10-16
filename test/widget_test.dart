import 'package:flutter/services.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:hkpickclone/main.dart';

void main() {
  const platform = MethodChannel('com.example.hkpickclone/accessibility');

  setUp(() {
    SharedPreferences.setMockInitialValues({});
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(platform, (call) async {
      switch (call.method) {
        case 'checkAccessibility':
          return false;
        case 'getDeviceId':
          return 'aabbccddeeff0011';
        default:
          return null;
      }
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(platform, null);
  });

  testWidgets('driver access uses the device license screen, not account login',
      (tester) async {
    tester.view.physicalSize = const Size(393, 852);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(MaterialApp(
      home: AutoPickHomePage(licenseCheck: () async => null),
    ));
    await tester.pump(const Duration(milliseconds: 100));

    expect(find.text('Kích Hoạt Bản Quyền'), findsOneWidget);
    expect(find.text('Đăng nhập tài xế'), findsNothing);
    expect(find.text('ĐĂNG NHẬP'), findsNothing);
    expect(find.text('MÃ THIẾT BỊ CỦA BẠN'), findsOneWidget);
    expect(find.text('COPY MÃ'), findsOneWidget);
  });
}
