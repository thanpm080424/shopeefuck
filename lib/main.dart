import 'dart:async';
import 'dart:convert';
import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:firebase_core/firebase_core.dart';
import 'firebase_manager.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await Firebase.initializeApp();
  FirebaseFirestore.instance.settings = const Settings(
    persistenceEnabled: false,
  );
  SystemChrome.setPreferredOrientations([
    DeviceOrientation.portraitUp,
    DeviceOrientation.portraitDown,
  ]).then((_) {
    runApp(const MyApp());
  });
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Auto Pick Pro',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.teal, brightness: Brightness.dark),
        scaffoldBackgroundColor: const Color(0xFF0F172A),
        fontFamily: 'Roboto',
      ),
      home: const AutoPickHomePage(),
    );
  }
}

class AutoPickHomePage extends StatefulWidget {
  const AutoPickHomePage({super.key, this.licenseCheck});

  @visibleForTesting
  final Future<Map<String, dynamic>?> Function()? licenseCheck;
  @override
  State<AutoPickHomePage> createState() => _AutoPickHomePageState();
}

class _AutoPickHomePageState extends State<AutoPickHomePage> with WidgetsBindingObserver, TickerProviderStateMixin {
  static const platform = MethodChannel('com.example.hkpickclone/accessibility');
  bool _isAccessibilityEnabled = false;
  late TabController _tabController;

  // --- CHUNG ---
  String _pickMode = 'CLICK';
  bool _useOcrEngine = false;
  int _speedLevel = 3;
  int _swipeDurationMs = 30; // 1ms - 500ms
  double _randomDelayMs = 0;
  bool _isLicenseValid = false;
  Timer? _licenseTimer;
  StreamSubscription? _licenseStreamSub;
  bool _settingsLoaded = false;
  bool _licenseCheckInFlight = false;
  int _expireUnixTime = 0;

  // --- HOA TOC: 6 thẻ (index 0=1đơn, 1=2đơn, 2=3đơn, 3=4đơn, 4=5đơn, 5=6+đơn) ---
  List<bool> _htEnabled = List.filled(6, true);
  List<double> _htMinPrice = List.filled(6, 0.0);
  List<bool> _htEnableProfitRatio = List.filled(6, false);
  List<double> _htMinPricePerKm = List.filled(6, 0.0);
  List<double> _htMinKm = List.filled(6, 0.0);
  List<double> _htMaxKm = [15, 20, 25, 30, 35, 50];
  List<String> _htBlacklist = List.filled(6, '');
  List<List<String>> _htDistricts = List.generate(6, (_) => <String>[]);
  List<bool> _htUseDistricts = List.filled(6, false);
  bool _htRejectCod = true; // Mặc định từ chối đơn Ký quỹ / COD cho tất cả thẻ và tiện chuyến
  bool _selectBestOrder = false; // Quét tất cả thẻ rồi quay lại chọn đơn ngon nhất
  
  // --- TIỆN CHUYẾN ---
  bool _tcEnabled = false;
  String _tcDistrict = 'Quận 1';
  List<String> _tcWards = [];
  String _tcKeywords = '';
  double _tcMaxKm = 5.0;
  double _tcMinPrice = 15.0;

  bool _useHtGlobalDistricts = false;
  List<String> _htGlobalDistricts = [];

  // --- FOOD ---
  double _maxFoodDistance = 1.0;
  String _blacklistFood = "";
  bool _useFoodCodFilter = false;
  double _maxFoodCod = 1000.0;
  bool _acceptZeroCod = true;
  bool _useFoodDistrictFilter = false;
  List<String> _foodDistricts = [];

  // --- LỌC THEO ĐIỂM GIAO ĐÃ GHIM ---
  bool _usePinnedDeliveryRadius = false;
  double? _pinnedLatitude;
  double? _pinnedLongitude;
  String _pinnedAddress = '';
  double _pinnedRadiusKm = 5.0;
  bool _isPinningAddress = false;
  final TextEditingController _pinnedAddressController = TextEditingController();
  final TextEditingController _tcKeywordsController = TextEditingController();

  // --- Danh sách quận ---
  final List<String> _allDistricts = [
    'Quận 1', 'Quận 2', 'Quận 3', 'Quận 4', 'Quận 5', 'Quận 6',
    'Quận 7', 'Quận 8', 'Quận 9', 'Quận 10', 'Quận 11', 'Quận 12',
    'Tân Bình', 'Phú Nhuận', 'Bình Thạnh', 'Gò Vấp', 'Tân Phú', 'Bình Tân',
    'Thủ Đức', 'Hóc Môn', 'Củ Chi', 'Bình Chánh', 'Nhà Bè', 'Cần Giờ'
  ];

  // --- Danh sách Phường mới & cũ theo từng quận ---
  static const Map<String, List<String>> _districtWards = {
    'Quận 1': [
      'Bến Nghé', 'Bến Thành', 'Cầu Kho', 'Cầu Ông Lãnh', 'Cô Giang',
      'Đa Kao', 'Nguyễn Cư Trinh', 'Nguyễn Thái Bình', 'Phạm Ngũ Lão', 'Tân Định'
    ],
    'Quận 2': [
      'An Khánh', 'An Lợi Đông', 'An Phú', 'Bình An (cũ)', 'Bình Khánh (cũ)',
      'Bình Trưng Đông', 'Bình Trưng Tây', 'Cát Lái', 'Thạnh Mỹ Lợi', 'Thảo Điền', 'Thủ Thiêm'
    ],
    'Quận 3': [
      'Phường 1', 'Phường 2', 'Phường 3', 'Phường 4', 'Phường 5',
      'Phường 6 (cũ)', 'Phường 7 (cũ)', 'Phường 8 (cũ)', 'Phường Võ Thị Sáu (mới)',
      'Phường 9', 'Phường 10', 'Phường 11', 'Phường 12', 'Phường 13', 'Phường 14'
    ],
    'Quận 4': [
      'Phường 1', 'Phường 2', 'Phường 3', 'Phường 4', 'Phường 5 (cũ)', 'Phường 6',
      'Phường 8', 'Phường 9', 'Phường 10', 'Phường 12 (cũ)', 'Phường 13', 'Phường 14',
      'Phường 15', 'Phường 16', 'Phường 18'
    ],
    'Quận 5': [
      'Phường 1', 'Phường 2', 'Phường 3', 'Phường 4', 'Phường 5', 'Phường 6',
      'Phường 7', 'Phường 8', 'Phường 9', 'Phường 10', 'Phường 11', 'Phường 12',
      'Phường 13', 'Phường 14', 'Chợ Lớn'
    ],
    'Quận 6': [
      'Phường 1', 'Phường 2', 'Phường 3', 'Phường 4', 'Phường 5', 'Phường 6',
      'Phường 7', 'Phường 8', 'Phường 9', 'Phường 10', 'Phường 11', 'Phường 12',
      'Phường 13', 'Phường 14'
    ],
    'Quận 7': [
      'Bình Thuận', 'Phú Mỹ', 'Phú Thuận', 'Tân Hưng', 'Tân Kiểng',
      'Tân Phong', 'Tân Phú', 'Tân Quy', 'Tân Thuận Đông', 'Tân Thuận Tây', 'Phú Mỹ Hưng'
    ],
    'Quận 8': [
      'Phường 1', 'Phường 2', 'Phường 3', 'Phường 4', 'Phường 5', 'Phường 6',
      'Phường 7', 'Phường 8', 'Phường 9', 'Phường 10', 'Phường 11', 'Phường 12',
      'Phường 13', 'Phường 14', 'Phường 15', 'Phường 16', 'Rạch Ông', 'Xóm Củi'
    ],
    'Quận 9': [
      'Hiệp Phú', 'Long Bình', 'Long Phước', 'Long Thạnh Mỹ', 'Long Trường',
      'Phú Hữu', 'Phước Bình', 'Phước Long A', 'Phước Long B', 'Tân Phú',
      'Tăng Nhơn Phú A', 'Tăng Nhơn Phú B', 'Trường Thạnh'
    ],
    'Quận 10': [
      'Phường 1', 'Phường 2', 'Phường 3 (cũ)', 'Phường 4', 'Phường 5', 'Phường 6',
      'Phường 7', 'Phường 8', 'Phường 9', 'Phường 10', 'Phường 11', 'Phường 12',
      'Phường 13', 'Phường 14', 'Phường 15'
    ],
    'Quận 11': [
      'Phường 1', 'Phường 2', 'Phường 3', 'Phường 4', 'Phường 5', 'Phường 6',
      'Phường 7', 'Phường 8', 'Phường 9', 'Phường 10', 'Phường 11', 'Phường 12',
      'Phường 13', 'Phường 14', 'Phường 15', 'Phường 16'
    ],
    'Quận 12': [
      'An Phú Đông', 'Đông Hưng Thuận', 'Hiệp Thành', 'Tân Chánh Hiệp',
      'Tân Hưng Thuận', 'Tân Thới Hiệp', 'Tân Thới Nhất', 'Thạnh Lộc',
      'Thạnh Xuân', 'Thới An', 'Trung Mỹ Tây'
    ],
    'Bình Thạnh': [
      'Phường 1', 'Phường 2', 'Phường 3', 'Phường 5', 'Phường 6', 'Phường 7',
      'Phường 11', 'Phường 12', 'Phường 13', 'Phường 14', 'Phường 15', 'Phường 17',
      'Phường 19', 'Phường 21', 'Phường 22', 'Phường 24', 'Phường 25', 'Phường 26',
      'Phường 27', 'Phường 28', 'Thanh Đa', 'Hàng Xanh', 'Bà Chiểu'
    ],
    'Gò Vấp': [
      'Phường 1', 'Phường 3', 'Phường 4', 'Phường 5', 'Phường 6', 'Phường 7',
      'Phường 8', 'Phường 9', 'Phường 10', 'Phường 11', 'Phường 12', 'Phường 13',
      'Phường 14', 'Phường 15', 'Phường 16', 'Phường 17'
    ],
    'Phú Nhuận': [
      'Phường 1', 'Phường 2', 'Phường 3', 'Phường 4', 'Phường 5', 'Phường 6 (cũ)',
      'Phường 7', 'Phường 8', 'Phường 9', 'Phường 10', 'Phường 11', 'Phường 12 (cũ)',
      'Phường 13', 'Phường 14 (cũ)', 'Phường 15', 'Phường 17'
    ],
    'Tân Bình': [
      'Phường 1', 'Phường 2', 'Phường 3', 'Phường 4', 'Phường 5', 'Phường 6',
      'Phường 7', 'Phường 8', 'Phường 9', 'Phường 10', 'Phường 11', 'Phường 12',
      'Phường 13', 'Phường 14', 'Phường 15'
    ],
    'Tân Phú': [
      'Hiệp Tân', 'Hòa Thạnh', 'Phú Thạnh', 'Phú Thọ Hòa', 'Phú Trung',
      'Sơn Kỳ', 'Tân Quý', 'Tân Sơn Nhì', 'Tân Thành', 'Tân Thới Hòa', 'Tây Thạnh'
    ],
    'Bình Tân': [
      'An Lạc', 'An Lạc A', 'Bình Hưng Hòa', 'Bình Hưng Hòa A', 'Bình Hưng Hòa B',
      'Bình Trị Đông', 'Bình Trị Đông A', 'Bình Trị Đông B', 'Tân Tạo', 'Tân Tạo A'
    ],
    'Thủ Đức': [
      'Bình Chiểu', 'Bình Thọ', 'Hiệp Bình Chánh', 'Hiệp Bình Phước', 'Linh Chiểu',
      'Linh Đông', 'Linh Tây', 'Linh Trung', 'Linh Xuân', 'Tam Bình', 'Tam Phú', 'Trường Thọ',
      'An Khánh', 'An Phú', 'Thảo Điền', 'Thủ Thiêm', 'Cát Lái', 'Thạnh Mỹ Lợi',
      'Bình Trưng Đông', 'Bình Trưng Tây', 'Hiệp Phú', 'Long Bình', 'Long Phước',
      'Long Thạnh Mỹ', 'Long Trường', 'Phú Hữu', 'Phước Bình', 'Phước Long A',
      'Phước Long B', 'Tân Phú', 'Tăng Nhơn Phú A', 'Tăng Nhơn Phú B', 'Trường Thạnh'
    ],
    'Hóc Môn': [
      'Thị trấn Hóc Môn', 'Bà Điểm', 'Đông Thạnh', 'Nhị Bình', 'Tân Hiệp',
      'Tân Thới Nhì', 'Tân Xuân', 'Thới Tam Thôn', 'Trung Chánh', 'Xuân Thới Đông',
      'Xuân Thới Sơn', 'Xuân Thới Thượng'
    ],
    'Bình Chánh': [
      'Thị trấn Tân Túc', 'An Phú Tây', 'Bình Chánh', 'Bình Hưng', 'Bình Lợi',
      'Đa Phước', 'Hưng Long', 'Lê Minh Xuân', 'Phạm Văn Hai', 'Phong Phú',
      'Quy Đức', 'Tân Kiên', 'Tân Nhựt', 'Tân Quý Tây', 'Vĩnh Lộc A', 'Vĩnh Lộc B'
    ],
    'Nhà Bè': [
      'Thị trấn Nhà Bè', 'Hiệp Phước', 'Long Thới', 'Nhơn Đức', 'Phú Xuân',
      'Phước Kiển', 'Phước Lộc'
    ],
    'Củ Chi': [
      'Thị trấn Củ Chi', 'An Nhơn Tây', 'An Phú', 'Bình Mỹ', 'Hòa Phú',
      'Nhuận Đức', 'Phạm Văn Cội', 'Phú Hòa Đông', 'Phú Mỹ Hưng', 'Tân An Hội',
      'Tân Định', 'Tân Phú Trung', 'Tân Thạnh Đông', 'Tân Thạnh Tây',
      'Tân Thông Hội', 'Thái Mỹ', 'Trung An', 'Trung Lập Hạ', 'Trung Lập Thượng'
    ],
    'Cần Giờ': [
      'Thị trấn Cần Thạnh', 'An Thới Đông', 'Bình Khánh', 'Long Hòa',
      'Lý Nhơn', 'Tam Thôn Hiệp', 'Thạnh An'
    ]
  };

  // Card colors
  final List<Color> _cardColors = [
    Colors.blueAccent,
    Colors.green,
    Colors.orangeAccent,
    Colors.purpleAccent,
    Colors.teal,
    Colors.redAccent,
  ];
  final List<String> _cardTitles = [
    '1 Đơn (Đơn Lẻ)',
    '2 Đơn Hàng',
    '3 Đơn Hàng',
    '4 Đơn Hàng',
    '5 Đơn Hàng',
    '6+ Đơn Hàng',
  ];
  final List<IconData> _cardIcons = [
    Icons.looks_one,
    Icons.looks_two,
    Icons.looks_3,
    Icons.looks_4,
    Icons.looks_5,
    Icons.looks_6,
  ];

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _tabController = TabController(length: 2, vsync: this);
    _tabController.addListener(() { setState(() {}); _updateNativeSettings(); });
    _checkAccessibility();
    _loadSettings();
    _initLicenseStream();
    _licenseTimer = Timer.periodic(const Duration(seconds: 15), (timer) {
      if (mounted) _loadSettings();
    });
    _checkAccessibility();
  }

  void _initLicenseStream() async {
    final devId = await FirebaseManager.getDeviceId();
    if (devId.isEmpty || devId == 'UNKNOWN_DEVICE') return;
    _licenseStreamSub?.cancel();
    _licenseStreamSub = FirebaseManager.licenseStream(devId)?.listen((snapshot) {
      if (!mounted) return;
      _loadSettings();
    });
  }

  Future<void> _persistLicenseStatus(bool isActive, int expireUnixTime) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('isActive', isActive);
    await prefs.setInt('expireUnixTime', isActive ? expireUnixTime : 0);
    try {
      if (isActive) {
        await platform.invokeMethod('syncOnlineLicense', {
          'expireUnixTime': expireUnixTime,
          'graceSeconds': 21600,
        });
      } else {
        await platform.invokeMethod('revokeLicense');
      }
    } catch (_) {}
  }

  @override
  void dispose() {
    _licenseStreamSub?.cancel();
    _licenseTimer?.cancel();
    _pinnedAddressController.dispose();
    _tcKeywordsController.dispose();
    WidgetsBinding.instance.removeObserver(this);
    _tabController.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _checkAccessibility();
    }
  }

  Future<void> _checkAccessibility() async {
    try {
      final bool result = await platform.invokeMethod('checkAccessibility');
      if (!mounted) return;
      setState(() { _isAccessibilityEnabled = result; });
    } on PlatformException catch (_) {
      if (!mounted) return;
      setState(() { _isAccessibilityEnabled = false; });
    }
  }

  Future<void> _openAccessibilitySettings() async {
    try { await platform.invokeMethod('openAccessibilitySettings'); } catch (_) {}
  }

  Future<void> _pinDeliveryAddress() async {
    final address = _pinnedAddressController.text.trim();
    if (address.isEmpty || _isPinningAddress) return;
    setState(() => _isPinningAddress = true);
    try {
      final result = await platform.invokeMapMethod<String, dynamic>(
        'geocodeAddress',
        {'address': address},
      );
      final latitude = (result?['latitude'] as num?)?.toDouble();
      final longitude = (result?['longitude'] as num?)?.toDouble();
      if (latitude == null || longitude == null || !mounted) {
        throw PlatformException(code: 'GEOCODING_FAILED', message: 'Không nhận được tọa độ.');
      }
      setState(() {
        _pinnedLatitude = latitude;
        _pinnedLongitude = longitude;
        _pinnedAddress = address;
        _usePinnedDeliveryRadius = true;
      });
      await _updateNativeSettings();
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Đã ghim: ${result?['label'] ?? address}')),
        );
      }
    } on PlatformException catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(error.message ?? 'Không tìm được tọa độ địa chỉ này.')),
        );
      }
    } finally {
      if (mounted) setState(() => _isPinningAddress = false);
    }
  }

  Future<void> _loadSettings() async {
    if (_licenseCheckInFlight) return;
    _licenseCheckInFlight = true;
    try {
      await _loadSettingsInternal();
    } finally {
      _licenseCheckInFlight = false;
    }
  }

  Future<void> _loadSettingsInternal() async {
    final prefs = await SharedPreferences.getInstance();
    
    // --- FIREBASE LICENSE CHECK ---
    Map<String, dynamic>? licenseData =
        await (widget.licenseCheck?.call() ?? FirebaseManager.checkLicense());
    if (!mounted) return;
    
    final bool isValid = licenseData != null;
    final int expireTime = licenseData != null ? (licenseData['expireUnixTime'] ?? 0) : 0;

    setState(() {
      _isLicenseValid = isValid;
      _expireUnixTime = expireTime;
      _pickMode = prefs.getString('pick_mode') ?? 'CLICK';
      _useOcrEngine = prefs.getBool('useOcrEngine') ?? false;
      _swipeDurationMs = (prefs.getInt('swipeDurationMs') ?? 30).clamp(1, 500);
      _speedLevel = prefs.getInt('speedLevel') ?? (_swipeDurationMs <= 20 ? 3 : _swipeDurationMs <= 50 ? 2 : 1);
      _randomDelayMs = prefs.getDouble('randomDelayMs') ?? 0.0;

      for (int i = 0; i < 6; i++) {
        _htEnabled[i] = prefs.getBool('ht_${i}_enabled') ?? true;
        _htEnableProfitRatio[i] = prefs.getBool('ht_${i}_enableProfitRatio') ?? false;
        _htMinPricePerKm[i] = prefs.getDouble('ht_${i}_minPricePerKm') ?? 0.0;
        _htMinPrice[i] = prefs.getDouble('ht_${i}_minPrice') ?? 0.0;
        _htMinKm[i] = prefs.getDouble('ht_${i}_minKm') ?? 0.0;
        _htMaxKm[i] = prefs.getDouble('ht_${i}_maxKm') ?? [15.0, 20.0, 25.0, 30.0, 35.0, 50.0][i];
        _htBlacklist[i] = prefs.getString('ht_${i}_blacklist') ?? '';
        _htDistricts[i] = prefs.getStringList('ht_${i}_districts') ?? [];
        _htUseDistricts[i] = prefs.getBool('ht_${i}_useDistricts') ?? false;
      }
      _htRejectCod = prefs.getBool('ht_rejectCod_v4') ?? true;
      _selectBestOrder = prefs.getBool('selectBestOrder') ?? false;

      _tcEnabled = prefs.getBool('tcEnabled') ?? false;
      _tcDistrict = prefs.getString('tcDistrict') ?? 'Quận 1';
      final defaultWards = _districtWards[_tcDistrict] ?? [];
      _tcWards = prefs.getStringList('tcWards') ?? List.from(defaultWards);
      _tcKeywords = prefs.getString('tcKeywords') ?? '';
      _tcMaxKm = prefs.getDouble('tcMaxKm') ?? 5.0;
      _tcMinPrice = prefs.getDouble('tcMinPrice') ?? 15.0;

      _useHtGlobalDistricts = prefs.getBool('useHtGlobalDistricts') ?? false;
      _htGlobalDistricts = prefs.getStringList('htGlobalDistricts') ?? [];

      _maxFoodDistance = prefs.getDouble('maxFoodDistance') ?? 1.0;
      _blacklistFood = prefs.getString('blacklistFood') ?? "";
      _useFoodCodFilter = prefs.getBool('useFoodCodFilter') ?? false;
      _maxFoodCod = prefs.getDouble('maxFoodCod') ?? 1000.0;
      _acceptZeroCod = prefs.getBool('acceptZeroCod') ?? true;
      _useFoodDistrictFilter = prefs.getBool('useFoodDistrictFilter') ?? false;
      _foodDistricts = prefs.getStringList('foodDistricts') ?? [];

      _usePinnedDeliveryRadius = prefs.getBool('usePinnedDeliveryRadius') ?? false;
      _pinnedLatitude = prefs.getDouble('pinnedLatitude');
      _pinnedLongitude = prefs.getDouble('pinnedLongitude');
      _pinnedAddress = prefs.getString('pinnedAddress') ?? '';
      _pinnedRadiusKm = (prefs.getDouble('pinnedRadiusKm') ?? 5.0).clamp(0.5, 30.0).toDouble();
      if (!_settingsLoaded) {
        _pinnedAddressController.text = _pinnedAddress;
        _tcKeywordsController.text = _tcKeywords;
      }
      
      _settingsLoaded = true;
    });
    
    if (_isLicenseValid) {
      await _persistLicenseStatus(true, expireTime);
      await _updateNativeSettings();
    } else {
      // Disable bot if invalid
      await _persistLicenseStatus(false, 0);
      await platform.invokeMethod('toggleAutoRun', {'enable': false}); await platform.invokeMethod('toggleFloating', {'show': false});
    }
  }

  Future<void> _updateNativeSettings() async {
    if (!_settingsLoaded) return;
    
    final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('isActive', _isLicenseValid);
      await prefs.setInt('expireUnixTime', _expireUnixTime);
    await prefs.setString('pick_mode', _pickMode);
    await prefs.setBool('useOcrEngine', _useOcrEngine);
    await prefs.setInt('swipeDurationMs', _swipeDurationMs);
    await prefs.setInt('speedLevel', _speedLevel);
    await prefs.setDouble('randomDelayMs', _randomDelayMs);
    await prefs.setString('currentMode', _tabController.index == 0 ? 'HOA_TOC' : 'FOOD');

    for (int i = 0; i < 6; i++) {
      await prefs.setBool('ht_${i}_enabled', _htEnabled[i]);
      await prefs.setDouble('ht_${i}_minPrice', _htMinPrice[i]);
      await prefs.setBool('ht_${i}_enableProfitRatio', _htEnableProfitRatio[i]);
      await prefs.setDouble('ht_${i}_minPricePerKm', _htMinPricePerKm[i]);
      await prefs.setDouble('ht_${i}_minKm', _htMinKm[i]);
      await prefs.setDouble('ht_${i}_maxKm', _htMaxKm[i]);
      await prefs.setString('ht_${i}_blacklist', _htBlacklist[i]);
      await prefs.setStringList('ht_${i}_districts', _htDistricts[i]);
      await prefs.setBool('ht_${i}_useDistricts', _htUseDistricts[i]);
    }
    await prefs.setBool('ht_rejectCod_v4', _htRejectCod);
    await prefs.setBool('selectBestOrder', _selectBestOrder);
    
    await prefs.setBool('tcEnabled', _tcEnabled);
    await prefs.setString('tcDistrict', _tcDistrict);
    await prefs.setStringList('tcWards', _tcWards);
    final cleanWards = _tcWards.map((w) => w.replaceAll(RegExp(r'\s*\((cũ|mới)\)', caseSensitive: false), '').trim()).toList();
    _tcKeywords = cleanWards.join(',');
    await prefs.setString('tcKeywords', _tcKeywords);
    await prefs.setDouble('tcMaxKm', _tcMaxKm);
    await prefs.setDouble('tcMinPrice', _tcMinPrice);

    await prefs.setBool('useHtGlobalDistricts', _useHtGlobalDistricts);
    await prefs.setStringList('htGlobalDistricts', _htGlobalDistricts);

    await prefs.setDouble('maxFoodDistance', _maxFoodDistance);
    await prefs.setString('blacklistFood', _blacklistFood);
    await prefs.setBool('useFoodCodFilter', _useFoodCodFilter);
    await prefs.setDouble('maxFoodCod', _maxFoodCod);
    await prefs.setBool('acceptZeroCod', _acceptZeroCod);
    await prefs.setBool('useFoodDistrictFilter', _useFoodDistrictFilter);
    await prefs.setStringList('foodDistricts', _foodDistricts);
    await prefs.setBool('usePinnedDeliveryRadius', _usePinnedDeliveryRadius);
    await prefs.setString('pinnedAddress', _pinnedAddress);
    await prefs.setDouble('pinnedRadiusKm', _pinnedRadiusKm);
    if (_pinnedLatitude != null && _pinnedLongitude != null) {
      await prefs.setDouble('pinnedLatitude', _pinnedLatitude!);
      await prefs.setDouble('pinnedLongitude', _pinnedLongitude!);
    } else {
      await prefs.remove('pinnedLatitude');
      await prefs.remove('pinnedLongitude');
    }

    try {
      final htCards = List.generate(6, (i) => <String, dynamic>{
        'enabled': _htEnabled[i],
        'minPrice': _htMinPrice[i],
        'enableProfitRatio': _htEnableProfitRatio[i],
        'minPricePerKm': _htMinPricePerKm[i],
        'minKm': _htMinKm[i],
        'maxKm': _htMaxKm[i],
        'blacklist': _htBlacklist[i],
        'districts': _htDistricts[i],
          'useDistricts': _htUseDistricts[i],
        'rejectCod': _htRejectCod,
      });

      await platform.invokeMethod('updateSettings', {
        'mode': _tabController.index == 0 ? 'HOA_TOC' : 'FOOD',
        'pickMode': _pickMode,
        'useOcrEngine': _useOcrEngine,
        'swipeDurationMs': _swipeDurationMs,
        'speedLevel': _speedLevel,
        'randomDelayMs': _randomDelayMs.toInt(),
        'htCardsJson': jsonEncode(htCards),
        'htRejectCodGlobal': _htRejectCod,
        'selectBestOrder': _selectBestOrder,
        
      'tcEnabled': _tcEnabled,
      'tcDistrict': _tcDistrict,
      'tcWards': cleanWards,
      'tcKeywords': _tcKeywords,
      'tcMaxKm': _tcMaxKm,
      'tcMinPrice': _tcMinPrice,

      'useHtGlobalDistricts': _useHtGlobalDistricts,
        'htGlobalDistricts': _htGlobalDistricts,
        'maxFoodDistance': _maxFoodDistance,
        'blacklistFood': _blacklistFood,
        'useFoodCodFilter': _useFoodCodFilter,
        'maxFoodCod': _maxFoodCod,
        'acceptZeroCod': _acceptZeroCod,
        'useFoodDistrictFilter': _useFoodDistrictFilter,
        'foodDistricts': _foodDistricts,
        'usePinnedDeliveryRadius': _usePinnedDeliveryRadius,
        'pinnedLatitude': _pinnedLatitude,
        'pinnedLongitude': _pinnedLongitude,
        'pinnedRadiusKm': _pinnedRadiusKm,
      });
    } catch (_) {}
  }

  Future<void> _toggleService() async {
    if (!_isAccessibilityEnabled) { _openAccessibilitySettings(); return; }
    await _updateNativeSettings();
  }

  @override
  Widget build(BuildContext context) {
    if (!_isLicenseValid) {
      return _buildActivationScreen();
    }
    
    return Scaffold(
      backgroundColor: const Color(0xFF0F172A),
      appBar: AppBar(
        title: const Text('AUTO PICK PRO', style: TextStyle(fontWeight: FontWeight.w900, color: Colors.white, letterSpacing: 1.5)),
        backgroundColor: const Color(0xFF0F172A),
        elevation: 0,
        centerTitle: true,
        bottom: TabBar(
          controller: _tabController,
          indicator: BoxDecoration(borderRadius: BorderRadius.circular(50), color: const Color(0xFF3B82F6)),
          labelColor: Colors.white,
          unselectedLabelColor: Colors.grey.shade400,
          labelStyle: const TextStyle(fontWeight: FontWeight.bold),
          indicatorSize: TabBarIndicatorSize.tab,
          dividerColor: Colors.transparent,
          tabs: const [
            Tab(text: '📦 HỎA TỐC'),
            Tab(text: '🍔 FOOD'),
          ],
        ),
      ),
      body: SingleChildScrollView(
        physics: const BouncingScrollPhysics(),
        padding: const EdgeInsets.symmetric(horizontal: 16.0, vertical: 20.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // --- TỐC ĐỘ VUỐT CỬ CHỈ (1ms - 500ms) ---
            Container(
              padding: const EdgeInsets.all(16),
              margin: const EdgeInsets.only(bottom: 12),
              decoration: BoxDecoration(color: const Color(0xFF1E293B), borderRadius: BorderRadius.circular(16), border: Border.all(color: Colors.orangeAccent.withOpacity(0.3))),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      const Row(
                        children: [
                          Icon(Icons.speed, color: Colors.orangeAccent, size: 20),
                          SizedBox(width: 8),
                          Text('TỐC ĐỘ VUỐT (CỬ CHỈ)', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w900, color: Colors.orangeAccent, letterSpacing: 1.2)),
                        ],
                      ),
                      GestureDetector(
                        onTap: () {
                          _showEditNumberDialog('Tốc độ vuốt (1 - 500 ms)', _swipeDurationMs.toDouble(), (val) {
                            setState(() {
                              _swipeDurationMs = val.round().clamp(1, 500);
                              _speedLevel = 3;
                            });
                            _updateNativeSettings();
                          });
                        },
                        child: Container(
                          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                          decoration: BoxDecoration(
                            color: Colors.orangeAccent.withOpacity(0.2),
                            borderRadius: BorderRadius.circular(8),
                            border: Border.all(color: Colors.orangeAccent.withOpacity(0.4)),
                          ),
                          child: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Text(
                                '$_swipeDurationMs ms',
                                style: const TextStyle(color: Colors.greenAccent, fontWeight: FontWeight.bold, fontSize: 16),
                              ),
                              const SizedBox(width: 6),
                              const Icon(Icons.edit, size: 14, color: Colors.orangeAccent),
                            ],
                          ),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  const Text(
                    'Tốc độ quét luôn chạy 1ms siêu tốc. Chạm vào ô số để nhập bàn phím hoặc kéo thanh trượt từ 1ms đến 500ms.',
                    style: TextStyle(color: Colors.white60, fontSize: 12),
                  ),
                  const SizedBox(height: 12),
                  SliderTheme(
                    data: SliderThemeData(
                      activeTrackColor: Colors.orangeAccent,
                      inactiveTrackColor: Colors.orangeAccent.withOpacity(0.2),
                      thumbColor: Colors.orangeAccent,
                      overlayColor: Colors.orangeAccent.withOpacity(0.2),
                      trackHeight: 6,
                    ),
                    child: Slider(
                      value: _swipeDurationMs.toDouble().clamp(1.0, 500.0),
                      min: 1,
                      max: 500,
                      divisions: 499,
                      label: '$_swipeDurationMs ms',
                      onChanged: (val) {
                        setState(() {
                          _swipeDurationMs = val.round().clamp(1, 500);
                          _speedLevel = 3;
                        });
                        _updateNativeSettings();
                      },
                    ),
                  ),
                  const Padding(
                    padding: EdgeInsets.symmetric(horizontal: 10.0),
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text('⚡ 1ms (Cực nhanh)', style: TextStyle(color: Colors.white54, fontSize: 12, fontWeight: FontWeight.bold)),
                        Text('🎯 50ms (Chuẩn)', style: TextStyle(color: Colors.white54, fontSize: 12, fontWeight: FontWeight.bold)),
                        Text('🐢 500ms (Chậm)', style: TextStyle(color: Colors.white54, fontSize: 12, fontWeight: FontWeight.bold)),
                      ],
                    ),
                  ),
                ],
              ),
            ),

            // --- OCR TOGGLE ---
            Container(
              padding: const EdgeInsets.all(12),
              margin: const EdgeInsets.only(bottom: 12),
              decoration: BoxDecoration(color: const Color(0xFF1E293B), borderRadius: BorderRadius.circular(16), border: Border.all(color: Colors.blueAccent.withOpacity(0.3))),
              child: Row(children: [
                Container(padding: const EdgeInsets.all(10), decoration: BoxDecoration(color: Colors.blueAccent.withOpacity(0.2), borderRadius: BorderRadius.circular(12)), child: const Icon(Icons.document_scanner, color: Colors.blueAccent)),
                const SizedBox(width: 12),
                const Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text('CHẾ ĐỘ GIẢ LẬP (TEST ẢNH)', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w900, color: Colors.blueAccent, letterSpacing: 1.2)),
                  SizedBox(height: 2),
                  Text('Bật OCR khi test với ảnh Gallery', style: TextStyle(color: Colors.white54, fontSize: 13)),
                ])),
                Switch(value: _useOcrEngine, activeColor: Colors.blueAccent, onChanged: (val) { setState(() => _useOcrEngine = val); _updateNativeSettings(); }),
              ]),
            ),

            // --- CHẾ ĐỘ NHẬN ĐƠN (BẤM TRỰC TIẾP / VUỐT CỬ CHỈ) ---
            Container(
              padding: const EdgeInsets.all(12),
              margin: const EdgeInsets.only(bottom: 12),
              decoration: BoxDecoration(
                color: const Color(0xFF1E293B),
                borderRadius: BorderRadius.circular(16),
                border: Border.all(color: _pickMode == 'CLICK' ? Colors.orangeAccent.withOpacity(0.4) : Colors.cyanAccent.withOpacity(0.4)),
              ),
              child: Row(children: [
                Container(
                  padding: const EdgeInsets.all(10),
                  decoration: BoxDecoration(
                    color: (_pickMode == 'CLICK' ? Colors.orangeAccent : Colors.cyanAccent).withOpacity(0.2),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Icon(
                    _pickMode == 'CLICK' ? Icons.touch_app : Icons.swipe,
                    color: _pickMode == 'CLICK' ? Colors.orangeAccent : Colors.cyanAccent,
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        _pickMode == 'CLICK' ? '⚡ BẤM TRỰC TIẾP' : '👆 VUỐT CỬ CHỈ',
                        style: TextStyle(
                          fontSize: 14,
                          fontWeight: FontWeight.w900,
                          color: _pickMode == 'CLICK' ? Colors.orangeAccent : Colors.cyanAccent,
                          letterSpacing: 1.2,
                        ),
                      ),
                      const SizedBox(height: 2),
                      Text(
                        _pickMode == 'CLICK'
                            ? 'Bấm nút tức thì (chống nảy thẻ, delay 80-120ms)'
                            : 'Vuốt cử chỉ ngón tay (theo tọa độ điểm 3 ➜ 2)',
                        style: const TextStyle(color: Colors.white70, fontSize: 12),
                      ),
                    ],
                  ),
                ),
                Switch(
                  value: _pickMode == 'CLICK',
                  activeColor: Colors.orangeAccent,
                  inactiveThumbColor: Colors.cyanAccent,
                  inactiveTrackColor: Colors.cyanAccent.withOpacity(0.3),
                  onChanged: (val) {
                    setState(() => _pickMode = val ? 'CLICK' : 'SWIPE');
                    _updateNativeSettings();
                  },
                ),
              ]),
            ),

            // --- DELAY GIẢ LẬP ---
            Container(
              padding: const EdgeInsets.all(12),
              margin: const EdgeInsets.only(bottom: 16),
              decoration: BoxDecoration(color: const Color(0xFF1E293B), borderRadius: BorderRadius.circular(16), border: Border.all(color: Colors.greenAccent.withOpacity(0.3))),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
                  const Text('ĐỘ TRỄ CLICK (Chống Ban)', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w900, color: Colors.white, letterSpacing: 1.2)),
                  GestureDetector(
                    onTap: () {
                      _showEditNumberDialog('Độ trễ (ms)', _randomDelayMs, (val) {
                        setState(() => _randomDelayMs = val.clamp(0, 1000));
                        _updateNativeSettings();
                      });
                    },
                    child: Container(
                      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                      decoration: BoxDecoration(color: Colors.greenAccent.withOpacity(0.1), borderRadius: BorderRadius.circular(6), border: Border.all(color: Colors.greenAccent.withOpacity(0.5))),
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Text('${_randomDelayMs.toInt()} ms', style: const TextStyle(fontSize: 14, fontWeight: FontWeight.bold, color: Colors.greenAccent)),
                          const SizedBox(width: 4),
                          const Icon(Icons.edit, size: 14, color: Colors.greenAccent),
                        ],
                      ),
                    ),
                  ),
                ]),
                const SizedBox(height: 4),
                SliderTheme(
                  data: SliderThemeData(activeTrackColor: Colors.greenAccent, inactiveTrackColor: Colors.greenAccent.withOpacity(0.2), thumbColor: Colors.greenAccent, overlayColor: Colors.greenAccent.withOpacity(0.2)),
                  child: Slider(value: _randomDelayMs, min: 0, max: 1000, divisions: 20, onChanged: (val) { setState(() => _randomDelayMs = val); _updateNativeSettings(); }),
                ),
              ]),
            ),

            _buildPinnedDeliverySettings(),

            const Padding(padding: EdgeInsets.only(left: 4, bottom: 12), child: Text('ĐIỀU KIỆN CHỐT ĐƠN', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w900, color: Colors.white54, letterSpacing: 1.5))),

            if (_tabController.index == 0) _buildHoaTocSettings()
            else _buildFoodSettings(),

            const SizedBox(height: 16),

            if (!_isAccessibilityEnabled)
              Container(
                padding: const EdgeInsets.all(12), margin: const EdgeInsets.only(bottom: 16),
                decoration: BoxDecoration(color: Colors.redAccent.withOpacity(0.1), borderRadius: BorderRadius.circular(16), border: Border.all(color: Colors.redAccent.withOpacity(0.5))),
                child: ListTile(onTap: _openAccessibilitySettings, contentPadding: EdgeInsets.zero,
                  leading: const Icon(Icons.warning_amber_rounded, color: Colors.redAccent, size: 32),
                  title: const Text('CHƯA CẤP QUYỀN TRỢ NíNG', style: TextStyle(color: Colors.redAccent, fontWeight: FontWeight.w900)),
                  subtitle: const Text('Bấm vào đây để mở Cài đặt', style: TextStyle(color: Colors.white70)),
                  trailing: const Icon(Icons.chevron_right, color: Colors.redAccent)),
              ),

            SizedBox(width: double.infinity, height: 50, child: ElevatedButton(
              onPressed: _toggleService,
              style: ElevatedButton.styleFrom(backgroundColor: const Color(0xFF3B82F6), shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)), elevation: 8, shadowColor: const Color(0xFF3B82F6).withOpacity(0.5)),
              child: const Text('LƯU CẤU HÌNH NỀN', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w900, color: Colors.white, letterSpacing: 1.5)),
            )),
            const SizedBox(height: 12),
            Row(children: [
              Expanded(child: SizedBox(height: 50, child: ElevatedButton.icon(
                onPressed: () => platform.invokeMethod('toggleFloating', {'show': true}),
                icon: const Icon(Icons.visibility, color: Colors.white), label: const Text('HIỆN BẢNG', style: TextStyle(color: Colors.white, fontWeight: FontWeight.w900)),
                style: ElevatedButton.styleFrom(backgroundColor: const Color(0xFF10B981), shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16))),
              ))),
              const SizedBox(width: 12),
              Expanded(child: SizedBox(height: 50, child: ElevatedButton.icon(
                onPressed: () => platform.invokeMethod('toggleFloating', {'show': false}),
                icon: const Icon(Icons.visibility_off, color: Colors.white), label: const Text('ẨN BẢNG', style: TextStyle(color: Colors.white, fontWeight: FontWeight.w900)),
                style: ElevatedButton.styleFrom(backgroundColor: const Color(0xFF64748B), shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16))),
              ))),
            ]),
            const SizedBox(height: 20),
          ],
        ),
      ),
    );
  }



  // =====================================================
  // =================== HỎA TỐC =======================
  // =====================================================

  
  Widget _buildTienChuyenCard() {
    return Card(
      color: const Color(0xFF1E293B),
      elevation: 4,
      margin: const EdgeInsets.only(bottom: 12),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(16),
        side: BorderSide(
          color: _tcEnabled ? Colors.tealAccent : Colors.white12,
          width: _tcEnabled ? 1.5 : 1,
        ),
      ),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Container(
                  padding: const EdgeInsets.all(8),
                  decoration: BoxDecoration(
                    color: _tcEnabled ? Colors.tealAccent.withOpacity(0.15) : Colors.white10,
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Icon(
                    Icons.directions_bike,
                    color: _tcEnabled ? Colors.tealAccent : Colors.white38,
                    size: 26,
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          const Text(
                            'ĐƠN TIỆN CHUYẾN',
                            style: TextStyle(
                              fontWeight: FontWeight.w900,
                              color: Colors.white,
                              fontSize: 16,
                              letterSpacing: 0.5,
                            ),
                          ),
                          const SizedBox(width: 6),
                          Container(
                            padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                            decoration: BoxDecoration(
                              color: _tcEnabled ? Colors.teal : Colors.grey.shade800,
                              borderRadius: BorderRadius.circular(6),
                            ),
                            child: Text(
                              _tcEnabled ? 'ĐANG BẬT' : 'ĐÃ TẮT',
                              style: const TextStyle(fontSize: 10, fontWeight: FontWeight.bold, color: Colors.white),
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 2),
                      const Text(
                        'Ưu tiên bắt đơn về nhà / đúng tuyến đường',
                        style: TextStyle(color: Colors.white54, fontSize: 11),
                      ),
                    ],
                  ),
                ),
                Switch(
                  value: _tcEnabled,
                  activeColor: Colors.tealAccent,
                  onChanged: (val) {
                    setState(() => _tcEnabled = val);
                    _updateNativeSettings();
                  },
                ),
              ],
            ),
            if (_tcEnabled) ...[
              const SizedBox(height: 14),
              const Divider(color: Colors.white12, height: 1),
              const SizedBox(height: 12),
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  const Row(
                    children: [
                      Icon(Icons.location_city, color: Colors.tealAccent, size: 20),
                      SizedBox(width: 8),
                      Text(
                        'Quận muốn về:',
                        style: TextStyle(color: Colors.white, fontSize: 14, fontWeight: FontWeight.bold),
                      ),
                    ],
                  ),
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 2),
                    decoration: BoxDecoration(
                      color: Colors.tealAccent.withOpacity(0.15),
                      borderRadius: BorderRadius.circular(10),
                      border: Border.all(color: Colors.tealAccent.withOpacity(0.4)),
                    ),
                    child: DropdownButtonHideUnderline(
                      child: DropdownButton<String>(
                        value: _allDistricts.contains(_tcDistrict) ? _tcDistrict : _allDistricts.first,
                        dropdownColor: const Color(0xFF1E293B),
                        icon: const Icon(Icons.arrow_drop_down, color: Colors.tealAccent),
                        style: const TextStyle(color: Colors.tealAccent, fontWeight: FontWeight.w900, fontSize: 14),
                        items: _allDistricts.map((d) => DropdownMenuItem(value: d, child: Text(d))).toList(),
                        onChanged: (newDist) {
                          if (newDist != null) {
                            setState(() {
                              _tcDistrict = newDist;
                              final defaultWards = _districtWards[newDist] ?? [];
                              _tcWards = List.from(defaultWards);
                            });
                            _updateNativeSettings();
                          }
                        },
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 14),
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text(
                    'Phường đã chọn: ${_tcWards.length}/${_districtWards[_tcDistrict]?.length ?? 0}',
                    style: const TextStyle(color: Colors.white70, fontSize: 13, fontWeight: FontWeight.bold),
                  ),
                  Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      TextButton(
                        style: TextButton.styleFrom(
                          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                          visualDensity: VisualDensity.compact,
                        ),
                        onPressed: () {
                          setState(() {
                            _tcWards = List.from(_districtWards[_tcDistrict] ?? []);
                          });
                          _updateNativeSettings();
                        },
                        child: const Text('Chọn tất cả', style: TextStyle(color: Colors.tealAccent, fontSize: 12, fontWeight: FontWeight.bold)),
                      ),
                      TextButton(
                        style: TextButton.styleFrom(
                          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                          visualDensity: VisualDensity.compact,
                        ),
                        onPressed: () {
                          setState(() {
                            _tcWards = [];
                          });
                          _updateNativeSettings();
                        },
                        child: const Text('Bỏ chọn', style: TextStyle(color: Colors.white54, fontSize: 12)),
                      ),
                    ],
                  ),
                ],
              ),
              const SizedBox(height: 8),
              Container(
                constraints: const BoxConstraints(maxHeight: 280),
                padding: const EdgeInsets.all(8),
                decoration: BoxDecoration(
                  color: const Color(0xFF0F172A),
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(color: Colors.white12),
                ),
                child: SingleChildScrollView(
                  child: Wrap(
                    spacing: 6,
                    runSpacing: 6,
                    children: (_districtWards[_tcDistrict] ?? []).map((ward) {
                      final isSelected = _tcWards.contains(ward);
                      return FilterChip(
                        label: Text(
                          ward,
                          style: TextStyle(
                            fontSize: 12,
                            color: isSelected ? Colors.white : Colors.white70,
                            fontWeight: isSelected ? FontWeight.bold : FontWeight.normal,
                          ),
                        ),
                        selected: isSelected,
                        selectedColor: Colors.teal.shade700,
                        backgroundColor: const Color(0xFF1E293B),
                        checkmarkColor: Colors.tealAccent,
                        side: BorderSide(
                          color: isSelected ? Colors.tealAccent : Colors.white24,
                          width: isSelected ? 1.5 : 1,
                        ),
                        visualDensity: VisualDensity.compact,
                        materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                        onSelected: (selected) {
                          setState(() {
                            if (selected) {
                              if (!_tcWards.contains(ward)) _tcWards.add(ward);
                            } else {
                              _tcWards.remove(ward);
                            }
                          });
                          _updateNativeSettings();
                        },
                      );
                    }).toList(),
                  ),
                ),
              ),
              const SizedBox(height: 12),
              Container(
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: Colors.tealAccent.withOpacity(0.08),
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: Colors.tealAccent.withOpacity(0.2)),
                ),
                child: const Row(
                  children: [
                    Icon(Icons.bolt, color: Colors.tealAccent, size: 18),
                    SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        '⚡ Bất chấp giá & km: Chỉ cần đơn giao về đúng Phường bạn đã chọn là bot nhận ngay (hỗ trợ cả tên phường mới và phường cũ).',
                        style: TextStyle(color: Colors.tealAccent, fontSize: 11, height: 1.3),
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildHoaTocSettings() {
    return Column(
      children: [
        _buildTienChuyenCard(),
        // --- LỌC QUẬN DÙNG CHUNG ---
        Container(
          padding: const EdgeInsets.all(12),
          margin: const EdgeInsets.only(bottom: 12),
          decoration: BoxDecoration(color: const Color(0xFF1E293B), borderRadius: BorderRadius.circular(16), border: Border.all(color: Colors.pinkAccent.withOpacity(0.3))),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  const Text('DÙNG CHUNG QUẬN (Mọi thẻ)', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w900, color: Colors.pinkAccent, letterSpacing: 1.2)),
                  Switch(value: _useHtGlobalDistricts, activeColor: Colors.pinkAccent, onChanged: (val) { setState(() => _useHtGlobalDistricts = val); _updateNativeSettings(); }),
                ],
              ),
              if (_useHtGlobalDistricts) ...[
                const Text('Chỉ nhận các quận đã chọn bên dưới cho TẤT CẢ các thẻ Hỏa Tốc (Bỏ qua lọc quận riêng của từng thẻ).', style: TextStyle(color: Colors.white54, fontSize: 13, height: 1.4)),
                const SizedBox(height: 8),
                Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
                  Text('Đã chọn: ${_htGlobalDistricts.isEmpty ? "Tất cả" : "${_htGlobalDistricts.length} quận"}', style: const TextStyle(color: Colors.white70, fontSize: 14)),
                  TextButton.icon(onPressed: () => _showGlobalDistrictPicker(), icon: const Icon(Icons.edit_location_alt, size: 16, color: Colors.pinkAccent), label: const Text('Chọn', style: TextStyle(color: Colors.pinkAccent))),
                ]),
                if (_htGlobalDistricts.isNotEmpty)
                  Wrap(spacing: 4, runSpacing: 4, children: _htGlobalDistricts.map((d) => Chip(
                    label: Text(d, style: const TextStyle(color: Colors.white, fontSize: 11)),
                    backgroundColor: Colors.pinkAccent.withOpacity(0.2), visualDensity: VisualDensity.compact, materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  )).toList()),
              ] else ...[
                const Text('Đang dùng lọc Quận RIÊNG cho từng thẻ.', style: TextStyle(color: Colors.white30, fontSize: 12, fontStyle: FontStyle.italic)),
              ]
            ],
          ),
        ),
        
        // --- TỪ CHỐI ĐƠN KÝ QUỸ / COD DÙNG CHUNG ---
        Container(
          padding: const EdgeInsets.all(12),
          margin: const EdgeInsets.only(bottom: 12),
          decoration: BoxDecoration(
            color: const Color(0xFF1E293B),
            borderRadius: BorderRadius.circular(16),
            border: Border.all(color: _htRejectCod ? Colors.redAccent.withOpacity(0.4) : Colors.white12),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  const Row(
                    children: [
                      Icon(Icons.money_off, color: Colors.redAccent, size: 20),
                      SizedBox(width: 8),
                      Text('TỪ CHỐI ĐƠN KÝ QUỸ / COD', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w900, color: Colors.redAccent, letterSpacing: 1.0)),
                    ],
                  ),
                  Switch(
                    value: _htRejectCod,
                    activeColor: Colors.redAccent,
                    onChanged: (val) {
                      setState(() => _htRejectCod = val);
                      _updateNativeSettings();
                    },
                  ),
                ],
              ),
              const SizedBox(height: 4),
              const Text(
                'Tự động vuốt bỏ tất cả đơn COD, đơn thu tiền, ứng tiền, đơn yêu cầu ký quỹ (Áp dụng cho mọi thẻ 1-6 đơn & tiện chuyến).',
                style: TextStyle(color: Colors.white54, fontSize: 12, height: 1.4),
              ),
            ],
          ),
        ),
        
        // --- CHỌN ĐƠN NGON NHẤT (LƯỚT HẾT RỒI QUAY LẠI) ---
        Container(
          padding: const EdgeInsets.all(12),
          margin: const EdgeInsets.only(bottom: 12),
          decoration: BoxDecoration(
            color: const Color(0xFF1E293B),
            borderRadius: BorderRadius.circular(16),
            border: Border.all(color: _selectBestOrder ? Colors.amberAccent.withOpacity(0.5) : Colors.white12),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  const Row(
                    children: [
                      Icon(Icons.auto_awesome, color: Colors.amberAccent, size: 20),
                      SizedBox(width: 8),
                      Text(
                        'QUAY LẠI CHỌN ĐƠN NGON NHẤT',
                        style: TextStyle(
                          fontSize: 13,
                          fontWeight: FontWeight.w900,
                          color: Colors.amberAccent,
                          letterSpacing: 0.8,
                        ),
                      ),
                    ],
                  ),
                  Switch(
                    value: _selectBestOrder,
                    activeColor: Colors.amberAccent,
                    onChanged: (val) {
                      setState(() => _selectBestOrder = val);
                      _updateNativeSettings();
                    },
                  ),
                ],
              ),
              const SizedBox(height: 4),
              const Text(
                'Khi BẬT: Ứng dụng lướt xem hết các đơn trên màn hình để so sánh, sau đó lướt ngược lại nhận đơn có tỷ lệ Cước/Km cao nhất (Ưu tiên Tiện chuyến).\nKhi TẮT: Tự động nhận ngay đơn hợp lệ đầu tiên xuất hiện.',
                style: TextStyle(color: Colors.white54, fontSize: 12, height: 1.4),
              ),
            ],
          ),
        ),
        
        ...List.generate(6, (i) => _buildOrderCard(i)),
      ],
    );
  }

  Widget _buildOrderCard(int index) {
    final color = _cardColors[index];
    final title = _cardTitles[index];
    final icon = _cardIcons[index];
    final enabled = _htEnabled[index];

    return Card(
      color: const Color(0xFF1E293B),
      elevation: 4,
      margin: const EdgeInsets.only(bottom: 10),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16), side: BorderSide(color: enabled ? color.withOpacity(0.5) : Colors.white12, width: enabled ? 1.5 : 1)),
      child: Theme(
        data: Theme.of(context).copyWith(dividerColor: Colors.transparent),
        child: ExpansionTile(
          initiallyExpanded: false,
          tilePadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
          leading: Icon(icon, color: enabled ? color : Colors.white24, size: 28),
          title: Text(title, style: TextStyle(fontWeight: FontWeight.w900, color: enabled ? color : Colors.white38, fontSize: 16)),
          subtitle: Text(
            enabled ? 'Cước ≥ ${_htMinPrice[index].toInt()}k | ${_htMinKm[index].toInt()}-${_htMaxKm[index].toInt()} km' : 'ĐÃ TẮT — Lướt bỏ',
            style: TextStyle(color: enabled ? Colors.white54 : Colors.redAccent, fontSize: 12),
          ),
          trailing: Switch(value: enabled, activeColor: color, onChanged: (val) { setState(() => _htEnabled[index] = val); _updateNativeSettings(); }),
          children: [
            if (enabled) Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                const Divider(color: Colors.white12),
                const SizedBox(height: 8),
                _buildCardSlider('Cước tối thiểu', _htMinPrice[index], 0, 500, color, '${_htMinPrice[index].toInt()}k', (val) { setState(() => _htMinPrice[index] = val); _updateNativeSettings(); }),
                const SizedBox(height: 8),
                Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
                    const Text('Bật Lọc Giá/Km (Đơn Lời)', style: TextStyle(color: Colors.white, fontSize: 14, fontWeight: FontWeight.bold)),
                    Switch(value: _htEnableProfitRatio[index], activeColor: color, onChanged: (val) { setState(() => _htEnableProfitRatio[index] = val); _updateNativeSettings(); }),
                ]),
                if (_htEnableProfitRatio[index]) ...[
                    _buildCardSlider('Mức giá / 1 km', _htMinPricePerKm[index], 1, 30, color, '${_htMinPricePerKm[index].toInt()}k/km', (val) { setState(() => _htMinPricePerKm[index] = val); _updateNativeSettings(); }),
                ],
                const SizedBox(height: 8),
                _buildCardSlider('Km tối thiểu', _htMinKm[index], 0, 30, color, '${_htMinKm[index].toInt()} km', (val) { setState(() => _htMinKm[index] = val); _updateNativeSettings(); }),
                const SizedBox(height: 8),
                _buildCardSlider('Km tối đa', _htMaxKm[index], 1, 50, color, '${_htMaxKm[index].toInt()} km', (val) { setState(() => _htMaxKm[index] = val); _updateNativeSettings(); }),

                if (index == 0) ...[
                  const SizedBox(height: 8),
                  Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
                    const Text('Từ chối đơn COD', style: TextStyle(color: Colors.white70, fontSize: 14)),
                    Switch(value: _htRejectCod, activeColor: Colors.redAccent, onChanged: (val) { setState(() => _htRejectCod = val); _updateNativeSettings(); }),
                  ]),
                ],

                const SizedBox(height: 8),
                if (_useHtGlobalDistricts) ...[
                  const Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
                    Text('Quận: Đang dùng Lọc Chung', style: TextStyle(color: Colors.white30, fontSize: 13, fontStyle: FontStyle.italic)),
                  ]),
                ] else ...[
                  Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
                    const Text('Bật Lọc Quận', style: TextStyle(color: Colors.white, fontSize: 14, fontWeight: FontWeight.bold)),
                    Switch(value: _htUseDistricts[index], activeColor: color, onChanged: (val) { setState(() => _htUseDistricts[index] = val); _updateNativeSettings(); }),
                  ]),
                  if (_htUseDistricts[index]) ...[
                    Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
                      Text('Quận: ${_htDistricts[index].isEmpty ? "Tất cả" : "${_htDistricts[index].length} quận"}', style: const TextStyle(color: Colors.white70, fontSize: 14)),
                      TextButton.icon(onPressed: () => _showDistrictPicker(index, color), icon: Icon(Icons.edit_location_alt, size: 16, color: color), label: Text('Chọn', style: TextStyle(color: color))),
                    ]),
                    if (_htDistricts[index].isNotEmpty)
                      Wrap(spacing: 4, runSpacing: 4, children: _htDistricts[index].map((d) => Chip(
                        label: Text(d, style: const TextStyle(color: Colors.white, fontSize: 11)),
                        backgroundColor: color.withOpacity(0.2), visualDensity: VisualDensity.compact, materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                      )).toList()),
                  ],
                ],

                const SizedBox(height: 8),
                TextFormField(
                  initialValue: _htBlacklist[index],
                  style: const TextStyle(color: Colors.white, fontSize: 13),
                  decoration: InputDecoration(
                    hintText: 'Blacklist: tên đường, tòa nhà (dấu phẩy)', hintStyle: const TextStyle(color: Colors.white30, fontSize: 12),
                    border: OutlineInputBorder(borderRadius: BorderRadius.circular(10)),
                    contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                    filled: true, fillColor: const Color(0xFF0F172A),
                    enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(10), borderSide: BorderSide(color: color.withOpacity(0.3))),
                    focusedBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(10), borderSide: BorderSide(color: color)),
                  ),
                  onChanged: (val) { _htBlacklist[index] = val; _updateNativeSettings(); },
                ),
              ]),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildCardSlider(String title, double value, double min, double max, Color color, String valueLabel, Function(double) onChanged) {
    return Row(children: [
      Expanded(flex: 3, child: Text(title, style: const TextStyle(color: Colors.white70, fontSize: 13))),
      Expanded(flex: 5, child: SliderTheme(
        data: SliderThemeData(activeTrackColor: color, inactiveTrackColor: color.withOpacity(0.15), thumbColor: color, overlayColor: color.withOpacity(0.2), trackHeight: 4, thumbShape: const RoundSliderThumbShape(enabledThumbRadius: 8)),
        child: Slider(value: value, min: min, max: max, divisions: (max - min).toInt().clamp(1, 500), onChanged: onChanged),
      )),
      GestureDetector(
        onTap: () {
          _showEditNumberDialog(title, value, (newVal) {
             onChanged(newVal.clamp(min, max));
          });
        },
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 4),
          decoration: BoxDecoration(color: color.withOpacity(0.1), borderRadius: BorderRadius.circular(6), border: Border.all(color: color.withOpacity(0.4))),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(valueLabel, style: TextStyle(color: color, fontWeight: FontWeight.bold, fontSize: 13)),
              const SizedBox(width: 4),
              Icon(Icons.edit, size: 12, color: color),
            ],
          ),
        ),
      ),
    ]);
  }

  void _showDistrictPicker(int cardIndex, Color color) {
    showDialog(context: context, builder: (context) => StatefulBuilder(builder: (context, setDialogState) {
      return AlertDialog(
        title: Text('Chọn Quận — ${_cardTitles[cardIndex]}', style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
        backgroundColor: const Color(0xFF1E293B),
        content: SingleChildScrollView(child: Column(mainAxisSize: MainAxisSize.min, children: [
          Row(children: [
            TextButton(onPressed: () { setDialogState(() => _htDistricts[cardIndex] = List.from(_allDistricts)); setState(() {}); _updateNativeSettings(); }, child: Text('Chọn tất cả', style: TextStyle(color: color))),
            TextButton(onPressed: () { setDialogState(() => _htDistricts[cardIndex] = []); setState(() {}); _updateNativeSettings(); }, child: const Text('Bỏ chọn', style: TextStyle(color: Colors.white54))),
          ]),
          const SizedBox(height: 8),
          Wrap(spacing: 6, runSpacing: 6, children: _allDistricts.map((district) {
            bool isSelected = _htDistricts[cardIndex].contains(district);
            return FilterChip(
              label: Text(district, style: TextStyle(fontSize: 13, color: isSelected ? Colors.white : Colors.white70)),
              selected: isSelected, selectedColor: color.withOpacity(0.3), backgroundColor: Colors.white10, checkmarkColor: color,
              onSelected: (bool selected) {
                setDialogState(() { if (selected) _htDistricts[cardIndex].add(district); else _htDistricts[cardIndex].remove(district); });
                setState(() {}); _updateNativeSettings();
              },
            );
          }).toList()),
        ])),
        actions: [TextButton(onPressed: () => Navigator.pop(context), child: Text('Xong', style: TextStyle(color: color, fontWeight: FontWeight.bold)))],
      );
    }));
  }

  void _showGlobalDistrictPicker() {
    showDialog(context: context, builder: (context) => StatefulBuilder(builder: (context, setDialogState) {
      return AlertDialog(
        title: const Text('Chọn Quận Dùng Chung', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
        backgroundColor: const Color(0xFF1E293B),
        content: SingleChildScrollView(child: Column(mainAxisSize: MainAxisSize.min, children: [
          Row(children: [
            TextButton(onPressed: () { setDialogState(() => _htGlobalDistricts = List.from(_allDistricts)); setState(() {}); _updateNativeSettings(); }, child: const Text('Chọn tất cả', style: TextStyle(color: Colors.pinkAccent))),
            TextButton(onPressed: () { setDialogState(() => _htGlobalDistricts = []); setState(() {}); _updateNativeSettings(); }, child: const Text('Bỏ chọn', style: TextStyle(color: Colors.white54))),
          ]),
          const SizedBox(height: 8),
          Wrap(spacing: 6, runSpacing: 6, children: _allDistricts.map((district) {
            bool isSelected = _htGlobalDistricts.contains(district);
            return FilterChip(
              label: Text(district, style: TextStyle(fontSize: 13, color: isSelected ? Colors.white : Colors.white70)),
              selected: isSelected, selectedColor: Colors.pinkAccent.withOpacity(0.3), backgroundColor: Colors.white10, checkmarkColor: Colors.pinkAccent,
              onSelected: (bool selected) {
                setDialogState(() { if (selected) _htGlobalDistricts.add(district); else _htGlobalDistricts.remove(district); });
                setState(() {}); _updateNativeSettings();
              },
            );
          }).toList()),
        ])),
        actions: [TextButton(onPressed: () => Navigator.pop(context), child: const Text('Xong', style: TextStyle(color: Colors.pinkAccent, fontWeight: FontWeight.bold)))],
      );
    }));
  }

  void _showEditNumberDialog(String title, double currentValue, Function(double) onSaved) {
    TextEditingController controller = TextEditingController(text: currentValue.toStringAsFixed(1).replaceAll('.0', ''));
    showDialog(context: context, builder: (context) {
      return AlertDialog(
        backgroundColor: const Color(0xFF1E293B),
        title: Text('Nhập $title', style: const TextStyle(color: Colors.white, fontSize: 16, fontWeight: FontWeight.bold)),
        content: TextField(
          controller: controller,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          style: const TextStyle(color: Colors.white, fontSize: 18),
          decoration: const InputDecoration(
            hintText: 'Nhập số...',
            hintStyle: TextStyle(color: Colors.white54),
            enabledBorder: UnderlineInputBorder(borderSide: BorderSide(color: Colors.white30)),
            focusedBorder: UnderlineInputBorder(borderSide: BorderSide(color: Colors.blueAccent)),
          ),
          autofocus: true,
          onChanged: (text) {
            double? val = double.tryParse(text.replaceAll(',', '.'));
            if (val != null) onSaved(val);
          },
          onSubmitted: (text) {
            double? val = double.tryParse(text.replaceAll(',', '.'));
            if (val != null) onSaved(val);
            Navigator.pop(context);
          },
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: const Text('Hủy', style: TextStyle(color: Colors.white54))),
          TextButton(onPressed: () {
            double? val = double.tryParse(controller.text.replaceAll(',', '.'));
            if (val != null) onSaved(val);
            Navigator.pop(context);
          }, child: const Text('Lưu', style: TextStyle(color: Colors.blueAccent, fontWeight: FontWeight.bold, fontSize: 16))),
        ],
      );
    });
  }

  // =====================================================
  // ===================== FOOD =========================
  // =====================================================

  Widget _buildPinnedDeliverySettings() {
    final hasPin = _pinnedLatitude != null && _pinnedLongitude != null;
    return Container(
      padding: const EdgeInsets.all(14),
      margin: const EdgeInsets.only(bottom: 16),
      decoration: BoxDecoration(
        color: const Color(0xFF1E293B),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: Colors.tealAccent.withOpacity(0.35)),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          const Icon(Icons.location_on, color: Colors.tealAccent),
          const SizedBox(width: 8),
          const Expanded(child: Text('GHIM ĐIỂM GIAO', style: TextStyle(fontWeight: FontWeight.w900, color: Colors.tealAccent, letterSpacing: 1.0))),
          Switch(
            value: _usePinnedDeliveryRadius && hasPin,
            activeColor: Colors.tealAccent,
            onChanged: hasPin ? (enabled) {
              setState(() => _usePinnedDeliveryRadius = enabled);
              _updateNativeSettings();
            } : null,
          ),
        ]),
        const Text(
          'Địa chỉ giao trong vùng ghim mới được nhận tự động. Mặc định 5 km; khoảng cách tính theo đường chim bay.',
          style: TextStyle(color: Colors.white70, fontSize: 13, height: 1.35),
        ),
        const SizedBox(height: 10),
        TextField(
          controller: _pinnedAddressController,
          enabled: !_isPinningAddress,
          style: const TextStyle(color: Colors.white),
          textInputAction: TextInputAction.done,
          onSubmitted: (_) => _pinDeliveryAddress(),
          decoration: InputDecoration(
            hintText: 'Nhập địa chỉ muốn ghim, ví dụ: 12 Nguyễn Oanh, Gò Vấp, TP.HCM',
            hintStyle: const TextStyle(color: Colors.white38, fontSize: 12),
            filled: true,
            fillColor: const Color(0xFF0F172A),
            border: OutlineInputBorder(borderRadius: BorderRadius.circular(10)),
            contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
          ),
        ),
        const SizedBox(height: 8),
        Row(children: [
          Expanded(
            child: OutlinedButton.icon(
              onPressed: _isPinningAddress ? null : _pinDeliveryAddress,
              icon: _isPinningAddress
                  ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Icon(Icons.push_pin_outlined),
              label: Text(_isPinningAddress ? 'ĐANG TÌM TỌA ĐỘ' : (hasPin ? 'GHIM / CẬP NHẬT' : 'GHIM ĐỊA CHỈ')),
              style: OutlinedButton.styleFrom(foregroundColor: Colors.tealAccent),
            ),
          ),
          if (hasPin) IconButton(
            tooltip: 'Xóa điểm ghim',
            onPressed: () {
              setState(() {
                _pinnedLatitude = null;
                _pinnedLongitude = null;
                _pinnedAddress = '';
                _usePinnedDeliveryRadius = false;
                _pinnedAddressController.clear();
              });
              _updateNativeSettings();
            },
            icon: const Icon(Icons.delete_outline, color: Colors.redAccent),
          ),
        ]),
        if (hasPin) ...[
          const SizedBox(height: 4),
          Text(
            'Đang ghim: ${_pinnedAddress.isEmpty ? '${_pinnedLatitude!.toStringAsFixed(5)}, ${_pinnedLongitude!.toStringAsFixed(5)}' : _pinnedAddress}',
            style: const TextStyle(color: Colors.tealAccent, fontSize: 12),
          ),
          if (_usePinnedDeliveryRadius) ...[
            const SizedBox(height: 8),
            Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
              const Text('Bán kính nhận đơn', style: TextStyle(color: Colors.white70)),
              Text('${_pinnedRadiusKm.toStringAsFixed(1)} km', style: const TextStyle(color: Colors.tealAccent, fontWeight: FontWeight.bold)),
            ]),
            Slider(
              value: _pinnedRadiusKm,
              min: 0.5,
              max: 30,
              divisions: 59,
              activeColor: Colors.tealAccent,
              onChanged: (value) {
                setState(() => _pinnedRadiusKm = value);
                _updateNativeSettings();
              },
            ),
          ],
        ],
        const SizedBox(height: 4),
        const Text(
          'Nếu địa chỉ trên đơn bị che hoặc không dò được tọa độ, app sẽ giữ đơn lại và không tự bấm nhận.',
          style: TextStyle(color: Colors.white54, fontSize: 12, height: 1.35),
        ),
      ]),
    );
  }

  Widget _buildFoodSettings() {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(color: const Color(0xFF1E293B), borderRadius: BorderRadius.circular(20)),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Container(padding: const EdgeInsets.all(8), decoration: BoxDecoration(color: const Color(0x33FF9800), borderRadius: BorderRadius.circular(10)), child: const Icon(Icons.social_distance, color: Colors.orange, size: 24)),
          const SizedBox(width: 12),
          const Expanded(child: Text('Khoảng cách tối đa', style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold))),
          GestureDetector(
            onTap: () {
              _showEditNumberDialog('Khoảng cách tối đa (km)', _maxFoodDistance, (val) {
                setState(() => _maxFoodDistance = val.clamp(0.1, 50.0));
                _updateNativeSettings();
              });
            },
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
              decoration: BoxDecoration(color: Colors.orange.withOpacity(0.1), borderRadius: BorderRadius.circular(8), border: Border.all(color: Colors.orange.withOpacity(0.5))),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text('≤ ${_maxFoodDistance.toStringAsFixed(1)} km', style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w900, color: Colors.orange)),
                  const SizedBox(width: 4),
                  const Icon(Icons.edit, size: 16, color: Colors.orange),
                ],
              ),
            ),
          ),
        ]),
        const SizedBox(height: 16),
        SliderTheme(
          data: SliderThemeData(activeTrackColor: Colors.orange, inactiveTrackColor: Colors.orangeAccent.withOpacity(0.2), thumbColor: Colors.orange, overlayColor: Colors.orange.withOpacity(0.2), trackHeight: 6.0),
          child: Slider(value: _maxFoodDistance, min: 0.1, max: 5.0, divisions: 49, onChanged: (val) { setState(() => _maxFoodDistance = val); _updateNativeSettings(); }),
        ),
        const SizedBox(height: 8),
        Text('Bất chấp giá tiền, hễ thấy đơn Food nào có khoảng cách <= ${_maxFoodDistance.toStringAsFixed(1)} km thì app sẽ nhận ngay.', style: const TextStyle(color: Colors.grey, fontSize: 13, height: 1.4)),

        const Divider(height: 32),
        Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
          const Text('Lọc theo Tiền THU (COD)', style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
          Switch(value: _useFoodCodFilter, activeColor: Colors.orange, onChanged: (val) { setState(() => _useFoodCodFilter = val); _updateNativeSettings(); }),
        ]),
        if (_useFoodCodFilter) ...[
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              const Text('Chỉ nhận đơn thu <=', style: TextStyle(color: Colors.orange, fontWeight: FontWeight.bold)),
              GestureDetector(
                onTap: () {
                  _showEditNumberDialog('Tiền thu tối đa (k)', _maxFoodCod, (val) {
                    setState(() => _maxFoodCod = val.clamp(10, 2000));
                    _updateNativeSettings();
                  });
                },
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                  decoration: BoxDecoration(color: Colors.orange.withOpacity(0.1), borderRadius: BorderRadius.circular(8), border: Border.all(color: Colors.orange.withOpacity(0.5))),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text('${_maxFoodCod.toInt()}k', style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w900, color: Colors.orange)),
                      const SizedBox(width: 4),
                      const Icon(Icons.edit, size: 14, color: Colors.orange),
                    ],
                  ),
                ),
              ),
            ],
          ),
          SliderTheme(
            data: SliderThemeData(activeTrackColor: Colors.orange, inactiveTrackColor: Colors.orangeAccent.withOpacity(0.2), thumbColor: Colors.orange, overlayColor: Colors.orange.withOpacity(0.2), trackHeight: 6.0),
            child: Slider(value: _maxFoodCod, min: 10, max: 2000, divisions: 199, label: '${_maxFoodCod.toInt()}k', onChanged: (val) { setState(() => _maxFoodCod = val); _updateNativeSettings(); }),
          ),
        ],
        const SizedBox(height: 16),
        Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
          const Text('Nhận đơn 0đ (Đã thanh toán)', style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
          Switch(value: _acceptZeroCod, activeColor: Colors.green, onChanged: (val) { setState(() => _acceptZeroCod = val); _updateNativeSettings(); }),
        ]),
        const SizedBox(height: 8),
        const Text('Nếu tắt, AI sẽ LƯỚT BỎ các đơn không cần thu tiền mặt (Thu: 0đ).', style: TextStyle(color: Colors.grey, fontSize: 13, height: 1.4)),

        const Divider(height: 32),
        Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
          const Text('Lọc Khu Vực', style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
          Switch(value: _useFoodDistrictFilter, activeColor: Colors.orange, onChanged: (val) { setState(() => _useFoodDistrictFilter = val); _updateNativeSettings(); }),
        ]),
        if (_useFoodDistrictFilter) ...[
          const Text('Chọn các Quận muốn chạy:', style: TextStyle(color: Colors.grey, fontSize: 13)),
          const SizedBox(height: 8),
          Wrap(spacing: 8.0, runSpacing: 4.0, children: _allDistricts.map((district) {
            bool isSelected = _foodDistricts.contains(district);
            return FilterChip(label: Text(district), selected: isSelected, selectedColor: Colors.orangeAccent.withOpacity(0.2), checkmarkColor: Colors.orange,
              onSelected: (bool selected) { setState(() { if (selected) _foodDistricts.add(district); else _foodDistricts.remove(district); }); _updateNativeSettings(); });
          }).toList()),
        ],

        const Divider(height: 32),
        const Text('Blacklist / Từ khóa né đơn Food', style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
        const SizedBox(height: 8),
        const Text('Nhập tên quán, mall muốn NÉ (cách nhau bởi dấu phẩy).', style: TextStyle(color: Colors.grey, fontSize: 13, height: 1.4)),
        const SizedBox(height: 12),
        TextFormField(
          initialValue: _blacklistFood,
          decoration: InputDecoration(hintText: 'Nhập từ khóa...', border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
            contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12), filled: true, fillColor: Colors.grey.shade50),
          onChanged: (val) { _blacklistFood = val; _updateNativeSettings(); },
        ),
      ]),
    );
  }

  Widget _buildActivationScreen() {
    return Scaffold(
      backgroundColor: const Color(0xFF0F172A),
      appBar: AppBar(
        title: const Text("Kích Hoạt Bản Quyền", style: TextStyle(fontWeight: FontWeight.bold)),
        backgroundColor: const Color(0xFF0F172A),
        elevation: 0,
      ),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(24.0),
          child: FutureBuilder<String>(
            future: FirebaseManager.getDeviceId(),
            builder: (context, snapshot) {
              final deviceId = snapshot.data ?? 'Đang tải...';
              return Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Container(
                    padding: const EdgeInsets.all(20),
                    decoration: BoxDecoration(
                      color: Colors.redAccent.withOpacity(0.1),
                      shape: BoxShape.circle,
                    ),
                    child: const Icon(Icons.lock_outline, size: 80, color: Colors.redAccent),
                  ),
                  const SizedBox(height: 30),
                  const Text("Thiết bị chưa có bản quyền\nhoặc đã hết hạn!", textAlign: TextAlign.center, style: TextStyle(color: Colors.redAccent, fontSize: 18, fontWeight: FontWeight.bold)),
                  const SizedBox(height: 30),
                  Container(
                    padding: const EdgeInsets.all(16),
                    decoration: BoxDecoration(
                      color: const Color(0xFF1E293B),
                      borderRadius: BorderRadius.circular(16),
                      border: Border.all(color: Colors.white12),
                    ),
                    child: Column(
                      children: [
                        const Text("MÃ THIẾT BỊ CỦA BẠN", style: TextStyle(color: Colors.white54, fontSize: 12, fontWeight: FontWeight.bold, letterSpacing: 1.2)),
                        const SizedBox(height: 8),
                        SelectableText(
                          deviceId,
                          textAlign: TextAlign.center,
                          style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w900, color: Colors.blueAccent, letterSpacing: 1.5)
                        ),
                        const SizedBox(height: 16),
                        ElevatedButton.icon(
                          icon: const Icon(Icons.copy),
                          label: const Text("COPY MÃ", style: TextStyle(fontWeight: FontWeight.bold)),
                          style: ElevatedButton.styleFrom(
                            backgroundColor: Colors.blueAccent,
                            foregroundColor: Colors.white,
                            padding: const EdgeInsets.symmetric(horizontal: 30, vertical: 12),
                            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(50)),
                          ),
                          onPressed: snapshot.hasData ? () {
                            Clipboard.setData(ClipboardData(text: deviceId));
                            ScaffoldMessenger.of(context).showSnackBar(
                              const SnackBar(content: Text("Đã copy mã thiết bị!", style: TextStyle(color: Colors.white)), backgroundColor: Colors.green)
                            );
                          } : null,
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 20),
                  const Text("Gửi mã này cho Admin để được cấp hoặc gia hạn key", textAlign: TextAlign.center, style: TextStyle(color: Colors.white70, fontSize: 14)),
                  const SizedBox(height: 40),
                  ElevatedButton.icon(
                    icon: const Icon(Icons.refresh),
                    label: const Text("KIỂM TRA LẠI", style: TextStyle(fontWeight: FontWeight.bold)),
                    style: ElevatedButton.styleFrom(
                      backgroundColor: Colors.green,
                      foregroundColor: Colors.white,
                      padding: const EdgeInsets.symmetric(horizontal: 40, vertical: 16),
                      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                    ),
                    onPressed: () async {
                      await _loadSettings();
                      if (!context.mounted) return;
                      if (_isLicenseValid) {
                        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text("Kích hoạt thành công!", style: TextStyle(color: Colors.white)), backgroundColor: Colors.green));
                      } else {
                        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text("Vẫn chưa có bản quyền! Vui lòng liên hệ Admin."), backgroundColor: Colors.redAccent));
                      }
                    },
                  )
                ],
              );
            }
          ),
        ),
      ),
    );
  }
}
