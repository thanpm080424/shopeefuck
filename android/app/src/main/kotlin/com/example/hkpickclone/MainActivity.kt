package com.example.hkpickclone

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

import android.os.Bundle

class MainActivity : FlutterActivity() {
    private val CHANNEL = "com.example.hkpickclone/accessibility"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AutoPickService.verifyAppIntegrity(this)) {
            android.util.Log.e("HK_BENCHMARK", "INTEGRITY_CHECK_FAILED: App signature or package ID is invalid!")
            finishAffinity()
            return
        }
        startService(Intent(this, AppLifecycleService::class.java))
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "geocodeAddress" -> {
                    val address = call.argument<String>("address").orEmpty()
                    if (address.isBlank()) {
                        result.error("EMPTY_ADDRESS", "Nhập địa chỉ cần ghim.", null)
                    } else {
                        DeliveryRadiusGeocoder.resolvePinAddress(this, address) { point ->
                            if (point == null) {
                                result.error("GEOCODING_FAILED", "Không tìm được tọa độ từ địa chỉ này.", null)
                            } else {
                                result.success(
                                    mapOf(
                                        "latitude" to point.latitude,
                                        "longitude" to point.longitude,
                                        "label" to point.label
                                    )
                                )
                            }
                        }
                    }
                }
                "checkAccessibility" -> {
                    result.success(isAccessibilityServiceEnabled(context, AutoPickService::class.java))
                }
                "openAccessibilitySettings" -> {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    startActivity(intent)
                    result.success(true)
                }
                "toggleAutoRun" -> {
                    val enable = call.argument<Boolean>("enable") ?: false
                    AutoPickService.isAutoRunEnabled = enable
                    result.success(true)
                }
                    "updateSettings" -> {
                    val mode = call.argument<String>("mode") ?: "HOA_TOC"
                    AutoPickService.currentMode = mode
                    
                    val pickMode = call.argument<String>("pickMode") ?: "CLICK"
                    AutoPickService.pickMode = pickMode
                    
                    val useOcrEngine = call.argument<Boolean>("useOcrEngine") ?: false
                                        
                    val speedLevel = call.argument<Int>("speedLevel") ?: 1
                    AutoPickService.speedLevel = speedLevel
                    val swipeDurationMs = (call.argument<Int>("swipeDurationMs")?.toLong()
                        ?: call.argument<Any>("swipeDurationMs")?.toString()?.toLongOrNull() ?: 30L).coerceIn(1L, 500L)
                    AutoPickService.swipeDurationMs = swipeDurationMs
                    AutoPickService.maxRandomDelayMs = call.argument<Any>("randomDelayMs")?.toString()?.toDoubleOrNull() ?: 0.0

                    AutoPickService.tcEnabled = call.argument<Boolean>("tcEnabled") ?: false
                    AutoPickService.tcDistrict = call.argument<String>("tcDistrict") ?: ""
                    val tcWardsList = call.argument<List<String>>("tcWards")
                    AutoPickService.tcWards = tcWardsList?.toMutableList() ?: mutableListOf()
                    AutoPickService.tcKeywords = call.argument<String>("tcKeywords") ?: ""
                    AutoPickService.tcKeywordsList = AutoPickService.tcKeywords.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                    AutoPickService.tcMaxKm = call.argument<Any>("tcMaxKm")?.toString()?.toDoubleOrNull() ?: 5.0
                    AutoPickService.tcMinPrice = call.argument<Any>("tcMinPrice")?.toString()?.toDoubleOrNull() ?: 15.0

                    
                    AutoPickService.selectBestOrderEnabled = call.argument<Boolean>("selectBestOrder") ?: false
                    AutoPickService.bestOrderPrioritizePrice = call.argument<Boolean>("bestOrderPrioritizePrice") ?: true
                    AutoPickService.bestOrderPrioritizeRatio = call.argument<Boolean>("bestOrderPrioritizeRatio") ?: true
                    AutoPickService.bestOrderPrioritizeCount = call.argument<Boolean>("bestOrderPrioritizeCount") ?: true
                    AutoPickService.bestOrderPrioritizePoints = call.argument<Boolean>("bestOrderPrioritizePoints") ?: false
                    AutoPickService.htRejectCodGlobal = call.argument<Boolean>("htRejectCodGlobal") ?: true
                    val loadRefreshIntervalMs = (call.argument<Int>("loadRefreshIntervalMs")?.toLong()
                        ?: call.argument<Any>("loadRefreshIntervalMs")?.toString()?.toLongOrNull() ?: 500L).coerceIn(100L, 2000L)
                    AutoPickService.loadRefreshIntervalMs = loadRefreshIntervalMs
                    
                    val htCardsJson = call.argument<String>("htCardsJson") ?: "[]"
                    val foodCardsJson = call.argument<String>("foodCardsJson") ?: "[]"
                    val useHtGlobalDistricts = call.argument<Boolean>("useHtGlobalDistricts") ?: false
                    val htGlobalDistricts = call.argument<List<String>>("htGlobalDistricts") ?: emptyList()
                    val useFoodDistrictFilter = call.argument<Boolean>("useFoodDistrictFilter") ?: false
                    val foodDistricts = call.argument<List<String>>("foodDistricts")?.toMutableList() ?: mutableListOf<String>()
                    
                    // Lưu vào SharedPreferences để service tự khôi phục khi restart
                    val prefs = context.getSharedPreferences("FlutterSharedPreferences", android.content.Context.MODE_PRIVATE)
                    prefs.edit()
                        .putString("flutter.htCards", htCardsJson)
                        .putString("flutter.foodCards", foodCardsJson)
                        .putString("flutter.pick_mode", pickMode)
                        .putLong("flutter.swipeDurationMs", swipeDurationMs)
                        .putBoolean("flutter.selectBestOrder", AutoPickService.selectBestOrderEnabled)
                        .putBoolean("flutter.bestOrderPrioritizePrice", AutoPickService.bestOrderPrioritizePrice)
                        .putBoolean("flutter.bestOrderPrioritizeRatio", AutoPickService.bestOrderPrioritizeRatio)
                        .putBoolean("flutter.bestOrderPrioritizeCount", AutoPickService.bestOrderPrioritizeCount)
                        .putBoolean("flutter.bestOrderPrioritizePoints", AutoPickService.bestOrderPrioritizePoints)
                        .putLong("flutter.loadRefreshIntervalMs", AutoPickService.loadRefreshIntervalMs)
                        .putBoolean("flutter.tcEnabled", AutoPickService.tcEnabled)
                        .putString("flutter.tcDistrict", AutoPickService.tcDistrict)
                        .putString("flutter.tcKeywords", AutoPickService.tcKeywords)
                        .apply()
                    
                    // Parse HoaToc cards from JSON
                    var htCardSettingsLoaded = false
                    try {
                        val jsonArray = org.json.JSONArray(htCardsJson)
                        if (jsonArray.length() < 6) throw IllegalArgumentException("Expected 6 HoaToc cards")
                        val parsedCards = Array(6) { AutoPickService.Companion.HtCard() }
                        for (i in 0 until minOf(jsonArray.length(), 6)) {
                            val obj = jsonArray.getJSONObject(i)
                            parsedCards[i] = AutoPickService.Companion.HtCard(
                                enabled = obj.optBoolean("enabled", true),
                                minPrice = obj.optDouble("minPrice", 0.0),
                                minKm = obj.optDouble("minKm", 0.0),
                                maxKm = obj.optDouble("maxKm", 50.0),
                                blacklist = obj.optString("blacklist", ""),
                                useDistricts = obj.optBoolean("useDistricts", false),
                                districts = mutableListOf<String>().apply {
                                    val arr = obj.optJSONArray("districts")
                                    if (arr != null) {
                                        for (j in 0 until arr.length()) add(arr.getString(j))
                                    }
                                },
                                rejectCod = obj.optBoolean("rejectCod", true),
                                enableProfitRatio = obj.optBoolean("enableProfitRatio", false),
                                minPricePerKm = obj.optDouble("minPricePerKm", 0.0)
                            )
                        }
                        AutoPickService.htCards = parsedCards
                        htCardSettingsLoaded = true
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "Error parsing htCardsJson: ${e.message}")
                    }
                    
                    AutoPickService.useHtGlobalDistricts = call.argument<Boolean>("useHtGlobalDistricts") ?: false
                    AutoPickService.htGlobalDistricts.clear()
                    call.argument<List<String>>("htGlobalDistricts")?.let { 
                        AutoPickService.htGlobalDistricts.addAll(it)
                    }
                    
                    // Food settings
                    AutoPickService.maxFoodDistance = call.argument<Any>("maxFoodDistance")?.toString()?.toDoubleOrNull() ?: 1.0
                    AutoPickService.blacklistFood = call.argument<String>("blacklistFood") ?: ""
                    AutoPickService.useFoodCodFilter = call.argument<Boolean>("useFoodCodFilter") ?: false
                    AutoPickService.maxFoodCod = call.argument<Any>("maxFoodCod")?.toString()?.toDoubleOrNull() ?: 100.0
                    AutoPickService.acceptZeroCod = call.argument<Boolean>("acceptZeroCod") ?: true
                    AutoPickService.useFoodDistrictFilter = call.argument<Boolean>("useFoodDistrictFilter") ?: false
                    AutoPickService.foodDistricts = call.argument<List<String>>("foodDistricts")?.toMutableList() ?: mutableListOf<String>()
                    AutoPickService.usePinnedDeliveryRadius = call.argument<Boolean>("usePinnedDeliveryRadius") ?: false
                    AutoPickService.pinnedLatitude = call.argument<Any>("pinnedLatitude")?.toString()?.toDoubleOrNull()
                    AutoPickService.pinnedLongitude = call.argument<Any>("pinnedLongitude")?.toString()?.toDoubleOrNull()
                    AutoPickService.pinnedRadiusKm = (call.argument<Any>("pinnedRadiusKm")?.toString()?.toDoubleOrNull() ?: 5.0).coerceIn(0.5, 30.0)

                    val nativeSettings = org.json.JSONObject()
                        .put("mode", AutoPickService.currentMode)
                        .put("pickMode", AutoPickService.pickMode)
                        .put("speedLevel", AutoPickService.speedLevel)
                        .put("swipeDurationMs", AutoPickService.swipeDurationMs)
                        .put("randomDelayMs", AutoPickService.maxRandomDelayMs)
                        .put("htCards", org.json.JSONArray(htCardsJson))
                        .put("tcEnabled", AutoPickService.tcEnabled)
                        .put("tcKeywords", AutoPickService.tcKeywords)
                        .put("tcMaxKm", AutoPickService.tcMaxKm)
                        .put("tcMinPrice", AutoPickService.tcMinPrice)
                        .put("useHtGlobalDistricts", AutoPickService.useHtGlobalDistricts)
                        .put("htRejectCodGlobal", AutoPickService.htRejectCodGlobal)
                        .put("htGlobalDistricts", org.json.JSONArray(AutoPickService.htGlobalDistricts))
                        .put("maxFoodDistance", AutoPickService.maxFoodDistance)
                        .put("blacklistFood", AutoPickService.blacklistFood)
                        .put("useFoodCodFilter", AutoPickService.useFoodCodFilter)
                        .put("maxFoodCod", AutoPickService.maxFoodCod)
                        .put("acceptZeroCod", AutoPickService.acceptZeroCod)
                        .put("useFoodDistrictFilter", AutoPickService.useFoodDistrictFilter)
                        .put("foodDistricts", org.json.JSONArray(AutoPickService.foodDistricts))
                        .put("usePinnedDeliveryRadius", AutoPickService.usePinnedDeliveryRadius)
                        .put("pinnedLatitude", AutoPickService.pinnedLatitude)
                        .put("pinnedLongitude", AutoPickService.pinnedLongitude)
                        .put("pinnedRadiusKm", AutoPickService.pinnedRadiusKm)
                    prefs.edit().putString("hkpick.nativeSettings", nativeSettings.toString()).apply()
                    AutoPickService.settingsReady = htCardSettingsLoaded
                    AutoPickService.primeCombatMode()
                    android.util.Log.i(
                        "MainActivity",
                        "SETTINGS_SYNC result=${if (htCardSettingsLoaded) "ok" else "invalid_cards"} mode=$mode"
                    )
                    
                    result.success(true)
                }
                "checkOverlayPermission" -> {
                    result.success(Settings.canDrawOverlays(context))
                }
                "getDeviceId" -> {
                    val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                    result.success(androidId)
                }
                "toggleFloating" -> {
                    val show = call.argument<Boolean>("show") ?: true
                    AutoPickService.toggleFloatingMenu(show)
                    result.success(true)
                }
                "openOverlaySettings" -> {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}"))
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    startActivity(intent)
                    result.success(true)
                }
                "syncOnlineLicense" -> {
                    val expireUnixTime = (call.argument<Any>("expireUnixTime") as? Number)?.toLong() ?: 0L
                    val graceSeconds = (call.argument<Any>("graceSeconds") as? Number)?.toLong() ?: 21600L
                    val prefs = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
                    val nowSeconds = System.currentTimeMillis() / 1000
                    val currentElapsed = android.os.SystemClock.elapsedRealtime()
                    val bootTime = System.currentTimeMillis() - currentElapsed
                    prefs.edit()
                        .putBoolean("flutter.isActive", true)
                        .putLong("flutter.expireUnixTime", expireUnixTime)
                        .putLong("flutter.lastOnlineVerifiedTime", nowSeconds)
                        .putLong("flutter.lastElapsedRealtime", currentElapsed)
                        .putLong("flutter.bootTime", bootTime)
                        .putLong("flutter.offlineGraceSeconds", graceSeconds)
                        .apply()
                    result.success(true)
                }
                "revokeLicense" -> {
                    val prefs = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putBoolean("flutter.isActive", false)
                        .putLong("flutter.expireUnixTime", 0L)
                        .putLong("flutter.lastOnlineVerifiedTime", 0L)
                        .putLong("flutter.lastElapsedRealtime", 0L)
                        .putLong("flutter.bootTime", 0L)
                        .apply()
                    AutoPickService.toggleFloatingMenu(false)
                    result.success(true)
                }
                "checkLicenseValid" -> {
                    result.success(AutoPickService.checkLicenseValid(context))
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun isAccessibilityServiceEnabled(context: Context, service: Class<*>): Boolean {
        val expectedComponentName = context.packageName + "/" + service.name
        val enabledServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        if (enabledServices != null) {
            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServices)
            while (colonSplitter.hasNext()) {
                val componentName = colonSplitter.next()
                if (componentName.equals(expectedComponentName, ignoreCase = true)) {
                    return true
                }
            }
        }
        return false
    }
}
