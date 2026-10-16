import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:device_info_plus/device_info_plus.dart';
import 'package:flutter/services.dart';
import 'dart:io';

class FirebaseManager {
  static final FirebaseFirestore _db = FirebaseFirestore.instance;
  static const MethodChannel _deviceChannel =
      MethodChannel('com.example.hkpickclone/accessibility');

  static Future<String> getDeviceId() async {
    if (Platform.isAndroid) {
      try {
        final androidId =
            await _deviceChannel.invokeMethod<String>('getDeviceId');
        if (androidId != null && androidId.trim().isNotEmpty) {
          return androidId.trim();
        }
      } catch (_) {}
      try {
        final DeviceInfoPlugin deviceInfo = DeviceInfoPlugin();
        final AndroidDeviceInfo androidInfo = await deviceInfo.androidInfo;
        if (androidInfo.id.isNotEmpty) {
          return androidInfo.id;
        }
      } catch (_) {}
    }
    return "UNKNOWN_DEVICE";
  }

  static Future<String> getDeviceName() async {
    final DeviceInfoPlugin deviceInfo = DeviceInfoPlugin();
    if (Platform.isAndroid) {
      try {
        AndroidDeviceInfo androidInfo = await deviceInfo.androidInfo;
        return "${androidInfo.brand} ${androidInfo.model}";
      } catch (_) {}
    }
    return "Unknown Phone";
  }

  static Future<Map<String, dynamic>?> checkLicense() async {
    try {
      String deviceId = await getDeviceId();
      String deviceName = await getDeviceName();

      DocumentSnapshot doc = await _db.collection('licenses').doc(deviceId).get();

      if (!doc.exists) {
        // Tự động đăng ký mã thiết bị lên Firestore với trạng thái chờ kích hoạt
        // để Admin nhìn thấy ngay trong app Admin và chỉ cần bấm duyệt!
        await _db.collection('licenses').doc(deviceId).set({
          'deviceName': deviceName,
          'driverName': 'Chờ kích hoạt',
          'expireUnixTime': 0, // 0 = hết hạn / chưa kích hoạt
          'isActive': false,
          'createdAt': FieldValue.serverTimestamp(),
        });
        return null;
      }

      Map<String, dynamic> data = doc.data() as Map<String, dynamic>;
      bool isActive = data['isActive'] == true;
      int expireTime = (data['expireUnixTime'] as num?)?.toInt() ?? 0;
      int currentTime = DateTime.now().millisecondsSinceEpoch ~/ 1000;

      if (isActive && currentTime <= expireTime) {
        return data; // Hợp lệ
      }
      return null; // Chưa kích hoạt hoặc hết hạn
    } catch (e) {
      print("Firebase check error: $e");
      return null;
    }
  }

  static Stream<DocumentSnapshot<Map<String, dynamic>>>? licenseStream(String deviceId) {
    if (deviceId.isEmpty || deviceId == "UNKNOWN_DEVICE") return null;
    return _db.collection('licenses').doc(deviceId).snapshots();
  }
}
