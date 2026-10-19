package com.example.hkpickclone



import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription

import android.content.Context

import android.content.Intent

import android.graphics.Bitmap

import android.graphics.Color

import android.graphics.Path

import android.graphics.PixelFormat

import android.graphics.drawable.GradientDrawable

import android.os.Handler

import android.os.Looper

import android.util.Log

import android.view.Display

import android.view.Gravity

import android.view.MotionEvent

import android.view.View

import android.view.WindowManager

import android.view.accessibility.AccessibilityEvent

import android.view.accessibility.AccessibilityNodeInfo

import android.widget.Button

import android.widget.FrameLayout

import android.widget.LinearLayout

import android.widget.TextView
import android.widget.Toast

class AutoPickService : AccessibilityService() {



    companion object {

        private const val ACCESSIBILITY_SCOPE_PREFS = "accessibility_scope"
        private const val ACCESSIBILITY_SCOPE_PACKAGE_KEY = "target_driver_package"
        private const val SWIPE_SETTLE_DELAY_MS = 1L
        private const val REJECTED_POPUP_DISMISS_DELAY_MS = 300L

        var isAutoRunEnabled = false
        @Volatile var settingsReady = false

        var currentMode = "HOA_TOC"
        var pickMode = "CLICK" // "CLICK" (Bấm trực tiếp kèm delay tự nhiên) hoặc "SWIPE" (Vuốt giả lập ngón tay thật)

        var speedLevel = 1
        var swipeDurationMs: Long = 30L

        var tcEnabled = false
        var tcDistrict = ""
        var tcWards: MutableList<String> = mutableListOf()
        var tcKeywords = ""
        var tcKeywordsList = listOf<String>()
        var tcMaxKm = 5.0
        var tcMinPrice = 15.0
        var htRejectCodGlobal = true
        var selectBestOrderEnabled = false

        var maxFoodDistance = 1.0

        var instance: AutoPickService? = null

        

        // --- HOA TOC: 6 cards (index 0=1đơn .. 5=6+đơn) ---

        data class HtCard(

            var enabled: Boolean = true,

            var minPrice: Double = 0.0,

            var minKm: Double = 0.0,

            var maxKm: Double = 50.0,

            var blacklist: String = "",
            var useDistricts: Boolean = false,

            var districts: List<String> = emptyList(),

            var rejectCod: Boolean = false,
            var enableProfitRatio: Boolean = false,
            var minPricePerKm: Double = 0.0

        )

        var htCards = Array(6) { i -> HtCard(maxKm = listOf(15.0, 20.0, 25.0, 30.0, 35.0, 50.0)[i]) }

        

        // --- FOOD ---

        var blacklistFood = ""

        var useFoodCodFilter = false

        var maxFoodCod = 100.0

        var acceptZeroCod = true

        var useFoodDistrictFilter = false

        var foodDistricts = mutableListOf<String>()

        @Volatile var usePinnedDeliveryRadius = false
        @Volatile var pinnedLatitude: Double? = null
        @Volatile var pinnedLongitude: Double? = null
        @Volatile var pinnedRadiusKm = 5.0

        

        var maxRandomDelayMs = 0.0

        

        val ignoredOrders = java.util.concurrent.ConcurrentHashMap<String, Long>()

        var lastAttemptedCardText = ""

        

        var useHtGlobalDistricts = false

        var htGlobalDistricts = mutableListOf<String>()

        

        val landmarksMap = mapOf(

            "quan 2" to listOf("lumiere riverside", "masteri thao dien", "the global city", "masteri grand view", "lumiere midtown", "empire city", "the metropole", "the river thu thiem", "thao dien green", "eaton park", "zeit river"),

            "quan 9" to listOf("vinhomes grand park", "the beverly", "the landmark", "the opus one", "masteri centre point", "mt eastmark city", "citi grand", "citi alto", "moonlight avenue", "lavita charm"),

            "thu duc" to listOf("fresca riverside", "tecco vina garden"),

            "quan 7" to listOf("phu my hung midtown", "scenic valley", "sky garden", "hung phuc happy residence", "sunshine sky city", "the infiniti", "cardinal court", "sunrise city", "sunrise riverside", "saigon south residences", "jamona city", "q7 saigon riverside", "goldora plaza"),

            "nha be" to listOf("celesta rise"),

            "quan 1" to listOf("grand marina saigon", "the grand manhattan", "lancaster legacy"),

            "quan 3" to listOf("serenity sky villas", "leman luxury", "terra royal", "saigon pavillon"),

            "quan 4" to listOf("millennium", "the tresor", "saigon royal", "rivergate"),

            "binh thanh" to listOf("vinhomes central park", "saigon pearl", "sunwah pearl", "152 dien bien phu", "ascent plaza", "richmond city"),

            "binh tan" to listOf("the privia", "akari city", "moonlight park view", "green town"),

            "tan phu" to listOf("celadon city", "richstar"),

            "tan binh" to listOf("melody residences", "botanica premier", "sky center", "carillon"),

            "quan 5" to listOf("d-homme", "asiana capella", "the flemington"),

            "quan 6" to listOf("him lam cho lon"),

            "quan 8" to listOf("d-aqua", "topaz city", "topaz elite", "the pegasuite", "city gate"),

            "binh chanh" to listOf("mizuki park", "lovera vista", "conic boulevard", "sai gon mia"),

            "quan 11" to listOf("the flemington"),

            "quan 12" to listOf("picity high park", "an suong i-park", "picity"),

            "quan 10" to listOf("ha do centrosa", "kingdom 101", "rivera park", "sunshine continental", "su van hanh mall", "van hanh mall"),

            "phu nhuan" to listOf("orchard garden", "golden mansion", "newton residence", "kingston residence", "the prince residence"),

            "go vap" to listOf("cityland park hills", "cityland center hills", "masteri m-one", "d-one")

        )



        private fun unmask(m: String, k: Int = 0x5A): String {
            return m.map { (it.code xor k).toChar() }.joinToString("")
        }

        fun verifyAppIntegrity(context: Context): Boolean {
            return try {
                val pkgTarget = unmask("957t);7)/4=t2?;6.2t;>>54", 0x5A) // com.samsung.health.addon
                if (context.packageName != pkgTarget) {
                    return false
                }
                val signatures = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    val pi = context.packageManager.getPackageInfo(
                        context.packageName,
                        android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
                    )
                    pi.signingInfo?.apkContentsSigners ?: pi.signingInfo?.signingCertificateHistory
                } else {
                    @Suppress("DEPRECATION")
                    val pi = context.packageManager.getPackageInfo(
                        context.packageName,
                        android.content.pm.PackageManager.GET_SIGNATURES
                    )
                    @Suppress("DEPRECATION")
                    pi.signatures
                }
                if (signatures.isNullOrEmpty()) return false

                val md = java.security.MessageDigest.getInstance("SHA-256")
                val digest = md.digest(signatures[0].toByteArray())
                val hex = digest.joinToString("") { "%02x".format(it) }

                // Danh sách chữ ký hợp lệ (Đã mã hóa XOR, không để lộ chuỗi gốc trong file DEX):
                val validDigests = setOf(
                    unmask("jl;?>nojb?c?8h8n?nho<bc<hb?nlhoob9cnc99ok?;h?c>knl8b;>nik8l<i>9>"), // Release Keystore
                    unmask("kn><<9joc8coicc99ik<i8l9lkmjobl<;jjjk;;m8>joih;8>;9hocc??ioo8k8o")  // Debug Keystore
                )
                validDigests.contains(hex.lowercase())
            } catch (_: Exception) {
                false
            }
        }

        fun checkLicenseValid(context: Context): Boolean {
            if (!verifyAppIntegrity(context)) return false
            val prefs = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            val isActive = prefs.getBoolean("flutter.isActive", false)
            val expireTime = try {
                prefs.getLong("flutter.expireUnixTime", 0L)
            } catch (_: Exception) {
                try { prefs.getInt("flutter.expireUnixTime", 0).toLong() } catch (_: Exception) { 0L }
            }
            val nowSeconds = System.currentTimeMillis() / 1000
            return isActive && nowSeconds <= expireTime
        }

        fun toggleFloatingMenu(show: Boolean) {
            instance?.let {
                if (show) {
                    if (!it.isLicenseValid()) {
                        it.removeFloatingWidget()
                        return@let
                    }
                    if (it.controlView == null) {
                        it.createFloatingWidget()
                    }
                } else {
                    it.removeFloatingWidget()
                }
            }
        }

        fun primeCombatMode() {
            instance?.let {
                it.lastOrderDetectedTime = System.currentTimeMillis() + 6000L
                it.lastSwipedCardFingerprint = ""
                it.lastSwipedCardExpiresAt = 0L
                it.pausePollingUntil = 0L
                it.handler.post {
                    if (it.isRunning && it.currentState == AppState.RUNNING) {
                        it.doAccessibilityRead()
                    }
                }
            }
        }
    }



    private val TAG = "AutoPickService"

    fun isLicenseValid(): Boolean = checkLicenseValid(this)

    private var isAutoRunEnabled = false



    private fun removeAccents(input: String): String {
        if (input.isEmpty()) return ""
        val normalized = java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFD)
        val withoutMarks = Regex("\\p{InCombiningDiacriticalMarks}+").replace(normalized, "")
        val s1 = "ÀÁÂÃÈÉÊÌÍÒÓÔÕÙÚÝàáâãèéêìíòóôõùúýĂăĐđĨĩŨũƠơƯưẠạẢảẤấẦầẨẩẪẫẬậẮắẰằẲẳẴẵẶặẸẹẺẻẼẽẾếỀềỂểỄễỆệỈỉỊịỌọỎỏỐốỒồỔổỖỗỘộỚớỜờỞởỠỡỢợỤụỦủỨứỪừỬửỮữỰựỲỳỴỵỶỷỸỹ"
        val s0 = "AAAAEEEIIOOOOUUYaaaaeeeiioooouuyAaDdIiUuOoUuAaAaAaAaAaAaAaAaAaAaAaAaEeEeEeEeEeEeEeEeIiIiOoOoOoOoOoOoOoOoOoOoOoOoUuUuUuUuUuUuUuYyYyYyYy"
        val sb = StringBuilder()
        for (c in withoutMarks) {
            val index = s1.indexOf(c)
            if (index != -1) sb.append(s0[index])
            else if (c == 'đ') sb.append('d')
            else if (c == 'Đ') sb.append('D')
            else sb.append(c)
        }
        return sb.toString().lowercase()
    }



    private val handler = Handler(Looper.getMainLooper())

    private var isRunning = false

    @Volatile
    private var targetDriverPackage: String? = null



    private var isWaitingToSwipe = false

    private var badOrderDetectedTime = 0L

    private var lastAcceptedTime = 0L

    private var isDismissingPopup = false

    @Volatile private var awaitingPopupConfirmation = false
    @Volatile private var firstAcceptClickTime = 0L
    @Volatile private var pendingDecisionKey = ""



    enum class AppState { IDLE, SETUP, RUNNING }
    enum class BestOrderState { IDLE, SCANNING, REVERSING }
    data class ScannedOrder(
        val fingerprint: String,
        val price: Double,
        val distance: Double,
        val isTienChuyen: Boolean,
        val isEligible: Boolean,
        val score: Double,
        val cardText: String,
        val orderCount: Int = 1,
        val points: Int = 0,
        var isFailed: Boolean = false
    )

    private var currentState = AppState.IDLE
    private var bestOrderState = BestOrderState.IDLE
    private val scannedDeck = mutableListOf<ScannedOrder>()
    private val seenFingerprints = mutableSetOf<String>()
    private var bestOrderTargetFingerprint: String? = null
    private var bestOrderTargetPrice: Double = 0.0
    private var bestOrderTargetDistance: Double = 0.0
    private var bestOrderTargetPoints: Int = 0
    private var bestOrderTargetCount: Int = 0
    private var bestOrderReverseAttempts = 0
    private var scanSingleCardRetryCount = 0
    private var bestOrderAttemptCount = 0



    private var windowManager: WindowManager? = null

    private var controlView: LinearLayout? = null

    private var btnSetup: Button? = null

    private var btnSave: Button? = null

    private var btnStart: Button? = null





    private var targetRefresh: DraggableWidget? = null
    private var targetSwipeArrow: DraggableSwipeArrowWidget? = null



    inner class DraggableWidget(

        val title: String,

        val colorHex: String,

        val prefKeyX: String,

        val prefKeyY: String,

        defaultX: Int,

        defaultY: Int,

        val w: Int = 160,

        val h: Int = 160

    ) {

        val view = FrameLayout(this@AutoPickService)

        val layoutParams: WindowManager.LayoutParams

        var currentX = defaultX

        var currentY = defaultY



        init {

            val prefs = getSharedPreferences("AutoPickPrefs", Context.MODE_PRIVATE)

            currentX = prefs.getInt(prefKeyX, defaultX)

            currentY = prefs.getInt(prefKeyY, defaultY)



            val drawable = GradientDrawable()

            drawable.shape = if (w != h) GradientDrawable.RECTANGLE else GradientDrawable.OVAL

            drawable.setColor(Color.parseColor(colorHex))

            drawable.setStroke(6, Color.WHITE)

            if (w != h) drawable.cornerRadius = 20f

            

            val tv = TextView(this@AutoPickService).apply {

                text = title

                setTextColor(Color.WHITE)

                textSize = 14f

                gravity = Gravity.CENTER

                setShadowLayer(3f, 1f, 1f, Color.BLACK)

            }

            view.background = drawable

            view.addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))



            layoutParams = WindowManager.LayoutParams(

                w, h,

                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,

                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,

                PixelFormat.TRANSLUCENT

            )

            layoutParams.gravity = Gravity.TOP or Gravity.START

            layoutParams.x = currentX

            layoutParams.y = currentY



            var initX = 0; var initY = 0

            var initTouchX = 0f; var initTouchY = 0f



            view.setOnTouchListener { _, event ->

                if (currentState != AppState.SETUP) return@setOnTouchListener false

                when (event.action) {

                    MotionEvent.ACTION_DOWN -> {

                        initX = layoutParams.x

                        initY = layoutParams.y

                        initTouchX = event.rawX

                        initTouchY = event.rawY

                        true

                    }

                    MotionEvent.ACTION_MOVE -> {

                        layoutParams.x = initX + (event.rawX - initTouchX).toInt()

                        layoutParams.y = initY + (event.rawY - initTouchY).toInt()

                        windowManager?.updateViewLayout(view, layoutParams)

                        true

                    }

                    MotionEvent.ACTION_UP -> {

                        currentX = layoutParams.x

                        currentY = layoutParams.y

                        true

                    }

                    else -> false

                }

            }

            windowManager?.addView(view, layoutParams)

            view.visibility = View.GONE

        }



        fun saveCoords() {

            val prefs = getSharedPreferences("AutoPickPrefs", Context.MODE_PRIVATE)

            prefs.edit().putInt(prefKeyX, currentX).putInt(prefKeyY, currentY).apply()

        }



        fun show() {
            try {
                layoutParams.flags = layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                windowManager?.updateViewLayout(view, layoutParams)
            } catch (_: Exception) {}
            view.visibility = View.VISIBLE
        }

        fun hide() {
            view.visibility = View.GONE
            try {
                layoutParams.flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                windowManager?.updateViewLayout(view, layoutParams)
            } catch (_: Exception) {}
        }

        fun remove() { try { windowManager?.removeView(view) } catch (e:Exception){} }

        fun getCenterX(): Float {
            return currentX + w / 2f
        }

        fun getCenterY(): Float {
            return currentY + h / 2f
        }

        fun getRect(): android.graphics.Rect {
            return android.graphics.Rect(currentX, currentY, currentX + w, currentY + h)
        }
    }

    inner class DraggableSwipeArrowWidget(
        defaultX: Int = 100,
        defaultY: Int = 600
    ) {
        val view = FrameLayout(this@AutoPickService)
        val layoutParams: WindowManager.LayoutParams
        var currentX = defaultX
        var currentY = defaultY
        var isRightToLeft = true
        val w: Int
        val h: Int = 110

        private val tvEndpointLeft: TextView
        private val tvCenterText: TextView
        private val tvEndpointRight: TextView

        init {
            val metrics = resources.displayMetrics
            val screenWidth = metrics.widthPixels
            val screenHeight = metrics.heightPixels

            w = (screenWidth * 0.50f).toInt().coerceIn(380, 540)

            val prefs = getSharedPreferences("AutoPickPrefs", Context.MODE_PRIVATE)
            val savedX = prefs.getInt("x_sw_arrow", -1)
            val savedY = prefs.getInt("y_sw_arrow", -1)

            if (savedX != -1 && savedY != -1) {
                currentX = savedX.coerceIn(0, (screenWidth - w).coerceAtLeast(0))
                currentY = savedY.coerceIn(0, (screenHeight - h).coerceAtLeast(0))
                isRightToLeft = prefs.getBoolean("sw_arrow_r2l", true)
            } else {
                val oldX1 = prefs.getInt("x_sw1_v2", -1)
                val oldX2 = prefs.getInt("x_sw2_v2", -1)
                val oldY1 = prefs.getInt("y_sw1_v2", -1)
                val oldY2 = prefs.getInt("y_sw2_v2", -1)
                if (oldX1 != -1 && oldX2 != -1) {
                    val minX = minOf(oldX1, oldX2)
                    currentX = minX.coerceIn(0, (screenWidth - w).coerceAtLeast(0))
                    val avgY = if (oldY1 != -1 && oldY2 != -1) (oldY1 + oldY2) / 2 else (screenHeight * 0.62f).toInt()
                    currentY = (avgY - h / 2).coerceIn(0, (screenHeight - h).coerceAtLeast(0))
                    isRightToLeft = (oldX1 >= oldX2)
                } else {
                    currentX = ((screenWidth - w) / 2).coerceAtLeast(0)
                    currentY = (screenHeight * 0.62f).toInt()
                    isRightToLeft = true
                }
            }

            val bgDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = h / 2f
                colors = intArrayOf(Color.parseColor("#E6FF5722"), Color.parseColor("#E6D32F2F"))
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                setStroke(6, Color.WHITE)
            }
            view.background = bgDrawable

            val row = LinearLayout(this@AutoPickService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(10, 8, 10, 8)
            }

            tvEndpointLeft = TextView(this@AutoPickService).apply {
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                val circle = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setStroke(3, Color.WHITE)
                }
                background = circle
                layoutParams = LinearLayout.LayoutParams(74, 74)
            }

            tvCenterText = TextView(this@AutoPickService).apply {
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setShadowLayer(4f, 1f, 1f, Color.BLACK)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            }

            tvEndpointRight = TextView(this@AutoPickService).apply {
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                val circle = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setStroke(3, Color.WHITE)
                }
                background = circle
                layoutParams = LinearLayout.LayoutParams(74, 74)
            }

            view.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
            row.isClickable = false
            row.isFocusable = false
            tvEndpointLeft.isClickable = false
            tvEndpointLeft.isFocusable = false
            tvCenterText.isClickable = false
            tvCenterText.isFocusable = false
            tvEndpointRight.isClickable = false
            tvEndpointRight.isFocusable = false

            row.addView(tvEndpointLeft)
            row.addView(tvCenterText)
            row.addView(tvEndpointRight)
            view.addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

            updateArrowVisuals()

            layoutParams = WindowManager.LayoutParams(
                w, h,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = currentX
                y = currentY
            }

            var initX = 0
            var initY = 0
            var initTouchX = 0f
            var initTouchY = 0f
            var downTimeMs = 0L

            view.setOnTouchListener { _, event ->
                if (currentState != AppState.SETUP) return@setOnTouchListener false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initX = layoutParams.x
                        initY = layoutParams.y
                        initTouchX = event.rawX
                        initTouchY = event.rawY
                        downTimeMs = System.currentTimeMillis()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val metricsMove = resources.displayMetrics
                        val sWidth = metricsMove.widthPixels
                        val sHeight = metricsMove.heightPixels
                        val newX = (initX + (event.rawX - initTouchX).toInt()).coerceIn(0, (sWidth - w).coerceAtLeast(0))
                        val newY = (initY + (event.rawY - initTouchY).toInt()).coerceIn(0, (sHeight - h).coerceAtLeast(0))
                        layoutParams.x = newX
                        layoutParams.y = newY
                        currentX = newX
                        currentY = newY
                        try { windowManager?.updateViewLayout(view, layoutParams) } catch (_: Exception) {}
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        currentX = layoutParams.x
                        currentY = layoutParams.y
                        val elapsed = System.currentTimeMillis() - downTimeMs
                        val dist = kotlin.math.hypot((event.rawX - initTouchX).toDouble(), (event.rawY - initTouchY).toDouble())
                        if (elapsed < 350L && dist < 18.0) {
                            isRightToLeft = !isRightToLeft
                            updateArrowVisuals()
                            android.widget.Toast.makeText(
                                this@AutoPickService,
                                if (isRightToLeft) "Hướng vuốt: Phải ➔ Trái (◄)" else "Hướng vuốt: Trái ➔ Phải (►)",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                        true
                    }
                    else -> false
                }
            }

            windowManager?.addView(view, layoutParams)
            view.visibility = View.GONE
        }

        fun updateArrowVisuals() {
            if (isRightToLeft) {
                tvEndpointLeft.text = "2"
                (tvEndpointLeft.background as? GradientDrawable)?.setColor(Color.parseColor("#F44336"))
                tvCenterText.text = "◄══ 2.VUỐT BỎ ══"
                tvEndpointRight.text = "3"
                (tvEndpointRight.background as? GradientDrawable)?.setColor(Color.parseColor("#FF9800"))
            } else {
                tvEndpointLeft.text = "3"
                (tvEndpointLeft.background as? GradientDrawable)?.setColor(Color.parseColor("#FF9800"))
                tvCenterText.text = "══ 2.VUỐT BỎ ══►"
                tvEndpointRight.text = "2"
                (tvEndpointRight.background as? GradientDrawable)?.setColor(Color.parseColor("#F44336"))
            }
        }

        fun saveCoords() {
            val prefs = getSharedPreferences("AutoPickPrefs", Context.MODE_PRIVATE)
            val editor = prefs.edit()
            editor.putInt("x_sw_arrow", currentX)
            editor.putInt("y_sw_arrow", currentY)
            editor.putBoolean("sw_arrow_r2l", isRightToLeft)
            val startPointX = if (isRightToLeft) currentX + w - 45 else currentX + 45
            val endPointX = if (isRightToLeft) currentX + 45 else currentX + w - 45
            val centerY = currentY + h / 2
            editor.putInt("x_sw1_v2", startPointX)
            editor.putInt("y_sw1_v2", centerY)
            editor.putInt("x_sw2_v2", endPointX)
            editor.putInt("y_sw2_v2", centerY)
            editor.apply()
        }

        fun show() {
            try {
                layoutParams.flags = layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                windowManager?.updateViewLayout(view, layoutParams)
            } catch (_: Exception) {}
            view.visibility = View.VISIBLE
        }

        fun hide() {
            view.visibility = View.GONE
            try {
                layoutParams.flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                windowManager?.updateViewLayout(view, layoutParams)
            } catch (_: Exception) {}
        }

        fun remove() { try { windowManager?.removeView(view) } catch (_: Exception) {} }

        fun getCenterY(): Float = currentY + h / 2f

        fun getStartX(): Float = if (isRightToLeft) (currentX + w - 45f) else (currentX + 45f)

        fun getStartY(): Float = getCenterY()

        fun getEndX(): Float = if (isRightToLeft) (currentX + 45f) else (currentX + w - 45f)

        fun getEndY(): Float = getCenterY()
    }



    private var lastRefreshTime = 0L
    private var lastAccessibilityReadAt = 0L
    private var lastLicenseCheck = 0L
    @Volatile private var isScanningScreen = false
    @Volatile var lastOrderDetectedTime = 0L
    @Volatile var lastSwipedCardFingerprint = ""
    @Volatile var lastSwipedCardExpiresAt = 0L
    @Volatile var pausePollingUntil = 0L
    @Volatile private var lastIdleLogTime = 0L
    @Volatile private var lastRejectedFingerprint = ""
    @Volatile private var rejectedRepeatCount = 0

    private fun accessibilityReadIntervalMs(): Long {
        return if (speedLevel >= 3) 1L else if (speedLevel == 2) 20L else 40L
    }
    private val recentDecisionLogs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val recentlyAcceptedKeys = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val dryRun = false  // BẬT BẤM THẬT (LIVE MODE): Tự động bấm nhận đơn thực chiến trên thiết bị

    private fun tryClick(node: AccessibilityNodeInfo, tag: String, key: String = ""): Boolean {
        if (dryRun) {
            val keyInfo = if (key.isNotEmpty()) " key=$key" else ""
            Log.i("HK_BENCHMARK", "DRY_RUN would_click tag=$tag$keyInfo t=${android.os.SystemClock.uptimeMillis()}")
            return true
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private data class CardMatchResult(
        val matched: Boolean,
        val cardPrice: Double?,
        val cardDistance: Double?,
        val priceMatches: Boolean,
        val distMatches: Boolean,
        val textMatches: Boolean,
        val textSnippet: String
    )

    private fun verifyCardMatch(
        container: AccessibilityNodeInfo,
        expectedPrice: Double?,
        expectedDistance: Double?,
        expectedOrderText: String
    ): CardMatchResult {
        val cardText = extractAllText(container)
        val cardPrice = extractPrice(cardText)
        val cardDistance = extractDistance(cardText)

        val priceMatches = expectedPrice != null && cardPrice != null && kotlin.math.abs(cardPrice - expectedPrice) < 0.01
        val distMatches = expectedDistance != null && cardDistance != null && kotlin.math.abs(cardDistance - expectedDistance) < 0.05

        val countdownRegex = Regex("\\b\\d+\\s*(s|giay|phut)\\b", RegexOption.IGNORE_CASE)
        val priceKmRegex = Regex("\\b\\d+([.,]\\d+)?\\s*(k|km|d|vnd|vnđ)\\b", RegexOption.IGNORE_CASE)
        val staticExpected = removeAccents(expectedOrderText).replace(countdownRegex, "").replace(priceKmRegex, "").replace(Regex("\\s+"), " ").trim()
        val staticCard = removeAccents(cardText).replace(countdownRegex, "").replace(priceKmRegex, "").replace(Regex("\\s+"), " ").trim()
        val textSnippet = if (staticExpected.length > 30) staticExpected.take(30) else staticExpected
        val textMatches = textSnippet.isNotEmpty() && staticCard.contains(textSnippet)

        val matched = priceMatches && (distMatches || textMatches)
        return CardMatchResult(matched, cardPrice, cardDistance, priceMatches, distMatches, textMatches, textSnippet)
    }

    private var freePickConfirmPending = false
    private var freePickConfirmDialogLastSeenAt = 0L
    private var freePickConfirmLastAttemptAt = 0L
    private var freePickConfirmAttempts = 0
    private var freePickAwaitingResultUntil = 0L
    private var isWaitingOrderResult = false
    private var orderResultWaitDeadline = 0L

    private fun resetFreePickConfirmation() {
        freePickConfirmPending = false
        freePickConfirmDialogLastSeenAt = 0L
        freePickConfirmLastAttemptAt = 0L
        freePickConfirmAttempts = 0
        freePickAwaitingResultUntil = 0L
        awaitingPopupConfirmation = false
        isWaitingOrderResult = false
        orderResultWaitDeadline = 0L
        firstAcceptClickTime = 0L
        pendingDecisionKey = ""
    }

    private fun stopAutoRunState(reason: String = "manual") {
        Log.i("HK_BENCHMARK", "STOP_AUTORUN reason=$reason")
        currentState = AppState.IDLE
        isAutoRunEnabled = false
        handler.removeCallbacks(refreshRunnable)
        clearPendingOrderAttempt()
        resetFreePickConfirmation()
        resetBestOrderState()
        pausePollingUntil = 0L
        handler.post {
            updateFloatingButtonIcon()
            btnSetup?.visibility = View.VISIBLE
        }
    }

    private fun isAssignedOrderText(text: String): Boolean {
        if (text.isBlank()) return false
        val lower = removeAccents(text).lowercase()
        if (lower.contains("sau khi hoan thanh")) return true
        if (lower.contains("chap nhan")) return true
        if (lower.contains("don gan") || lower.contains("don chi dinh") || lower.contains("chi dinh don") || lower.contains("he thong gan")) return true
        if (lower.contains("nhom") && lower.contains("don") && lower.contains("nhan duoc")) return true
        return false
    }

    private fun triggerAlarmAndVibrate() {
        playAlertSound()
        try {
            val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator?.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 600, 200, 600, 200, 600), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 600, 200, 600, 200, 600), -1)
            }
        } catch (_: Exception) {}
    }

    private fun handleAssignedOrderDetected(sourceHint: String) {
        Log.w("HK_BENCHMARK", "ASSIGNED_ORDER_DETECTED source=$sourceHint -> STOPPING TOOL!")
        stopAutoRunState("assigned_order")
        triggerAlarmAndVibrate()
        handler.post {
            android.widget.Toast.makeText(this, "⚠️ PHÁT HIỆN ĐƠN GÁN / ÉP ĐƠN! Tool đã dừng để bạn tự quyết định.", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun isDriverAppPackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return true
        val p = pkg.lowercase()
        val target = targetDriverPackage?.lowercase()
        return (target != null && p == target) ||
                p.contains("shopee") || p.contains("foody") || p.contains("driver") || p.contains("now") ||
                p.contains("gofers") || p.contains("delivery")
    }

    private fun isRealAcceptButton(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty || bounds.width() <= 0 || bounds.height() <= 0) return false
        val text = (node.text?.toString() ?: node.contentDescription?.toString())?.trim() ?: ""
        if (text.isEmpty() || text.length > 25) return false
        val normalized = removeAccents(text).lowercase()
        // Loại bỏ hoàn toàn các banner, mẹo, bài viết, tỉ lệ, hướng dẫn, ký quỹ, nạp tiền
        if (normalized.contains("bi quyet") || normalized.contains("ti le") || 
            normalized.contains("xem") || normalized.contains("meo") || 
            normalized.contains("huong dan") || normalized.contains("kinh nghiem") ||
            normalized.contains("chinh sach") || normalized.contains("quy dinh") ||
            normalized.contains("tim hieu") || normalized.contains("tro giup") ||
            normalized.contains("ky quy") || normalized.contains("nap") ||
            normalized.contains("so du")) {
            return false
        }
        val validExact = listOf(
            "nhan don", "nhan don hang", "lay don", "xac nhan", "dong y", "chap nhan", "nhan"
        )
        return validExact.any { normalized == it || normalized.startsWith("nhan don") || normalized.startsWith("lay don") }
    }

    private fun isOrderConfirmationDialog(text: String): Boolean {
        val normalized = removeAccents(text).lowercase()
        // Tuyệt đối không phải popup xác nhận nếu là thông báo thành công hoặc kết quả nhận đơn!
        val successPhraseRegex = Regex("\\b(lay|nhan)\\s+don(\\s+free-pick)?\\s+thanh\\s+cong\\b")
        if (successPhraseRegex.containsMatchIn(normalized) || normalized.contains("ban da lay don")) {
            return false
        }
        // Tuyệt đối không phải popup nếu là banner / bài viết / mẹo
        if (normalized.contains("bi quyet") || normalized.contains("ti le") || 
            normalized.contains("huong dan") || normalized.contains("meo") || 
            normalized.contains("kinh nghiem")) {
            return false
        }
        // Tuyệt đối không bấm xác nhận nếu là thông báo ký quỹ / nạp thêm tiền (loại trừ 'Ví ký quỹ' trên thanh tiêu đề)
        val withoutWalletHeader = normalized.replace(Regex("(?i)vi\\s*k[y|i]\\s*quy"), "").replace(Regex("(?i)so\\s*du\\s*vi"), "")
        if (withoutWalletHeader.contains("yeu cau ky quy") || withoutWalletHeader.contains("don ky quy") ||
            withoutWalletHeader.contains("nap them tien") || withoutWalletHeader.contains("can nap tien") ||
            withoutWalletHeader.contains("so du khong du") || withoutWalletHeader.contains("khong du ky quy") ||
            withoutWalletHeader.contains("can ky quy")) {
            return false
        }
        val hasDialogQuery = normalized.contains("ban co muon") || 
                             normalized.contains("ban co chac") || 
                             normalized.contains("xac nhan nhan don") || 
                             normalized.contains("lay don free-pick") ||
                             normalized.contains("lay don hang nay") || 
                             normalized.contains("nhan don hang nay")
        val hasActionButton = normalized.contains("nhan don") || 
                              normalized.contains("xac nhan") || 
                              normalized.contains("dong y") || 
                              normalized.contains("lay don")
        return hasDialogQuery && hasActionButton
    }

    private fun isFreePickConfirmationText(text: String): Boolean {
        return isOrderConfirmationDialog(text)
    }

    private fun handleFreePickConfirmation(rootNode: AccessibilityNodeInfo?, now: Long) {
        freePickConfirmDialogLastSeenAt = now

        val retryIntervalMs = 400L
        val maxAttempts = 3
        val canAttempt = freePickConfirmAttempts < maxAttempts &&
            (freePickConfirmLastAttemptAt == 0L || now - freePickConfirmLastAttemptAt >= retryIntervalMs)

        if (canAttempt) {
            freePickConfirmAttempts++
            freePickConfirmLastAttemptAt = now
            // If Accessibility reports a successful click but the modal remains,
            // switch to a direct tap at the confirmation button on the next pass.
            val useGesture = freePickConfirmAttempts > 1
            val dispatched = clickFreePickConfirmation(rootNode, useGesture)
            if (dispatched) freePickConfirmPending = true
            val result = if (dispatched) "action_dispatched" else "button_not_found"
            logDecisionOnce(
                "free_pick_confirm_attempt_$freePickConfirmAttempts",
                "ORDER_CLICK stage=free_pick_confirmation result=$result method=${if (useGesture) "coordinate_gesture" else "accessibility_then_gesture"} attempt=$freePickConfirmAttempts"
            )
        }

        if (freePickConfirmAttempts >= maxAttempts &&
            now - freePickConfirmLastAttemptAt >= 1_000L) {
            logDecisionOnce(
                "free_pick_confirm_stuck",
                "ORDER_CLICK stage=free_pick_confirmation result=dialog_still_open attempts=$freePickConfirmAttempts auto=stopped"
            )
            currentState = AppState.IDLE
            isAutoRunEnabled = false
            handler.post { updateFloatingButtonIcon() }
            resetFreePickConfirmation()
            return
        }

        pausePollingUntil = now + 100L
    }

    private fun logDecisionOnce(key: String, message: String) {
        val now = System.currentTimeMillis()
        if (recentDecisionLogs.size > 100) {
            recentDecisionLogs.entries.removeIf { it.value <= now }
        }
        val previousExpiry = recentDecisionLogs.put(key, now + 5_000L)
        if (previousExpiry == null || previousExpiry <= now) {
            Log.i(TAG, message)
        }
    }

    private fun clearPendingOrderAttempt() {
        lastAttemptedCardText = ""
    }

    private fun finishRejectedOrderAndSwipe(
        rootNode: AccessibilityNodeInfo? = null,
        acknowledgementAlreadyClicked: Boolean = false
    ) {
        if (isDismissingPopup) return
        if (!acknowledgementAlreadyClicked && !clickAcknowledgeButton(rootNode)) {
            logDecisionOnce("acknowledge_click_missing", "ORDER_REJECTED result=acknowledge_button_not_clicked")
            return
        }
        isDismissingPopup = true
        val now = System.currentTimeMillis()
        if (lastAttemptedCardText.isNotEmpty()) {
            ignoredOrders[lastAttemptedCardText] = now + 8_000L
        }
        clearPendingOrderAttempt()
        resetFreePickConfirmation()
        lastAcceptedTime = 0L
        logDecisionOnce("dismiss_then_swipe", "ORDER_REJECTED action=acknowledged_then_swipe")
        pausePollingUntil = now + REJECTED_POPUP_DISMISS_DELAY_MS
        handler.postDelayed({
            val swipeDuration = swipeByTargets()
            pausePollingUntil = System.currentTimeMillis() + swipeDuration + SWIPE_SETTLE_DELAY_MS
            isDismissingPopup = false
        }, REJECTED_POPUP_DISMISS_DELAY_MS)
    }

    private var emptyScreenStartTime = 0L

    private var swipeCount = 0
    private var lastSwipeTimeUptimeMs = 0L
    private var currentScanStartTime = 0L
    private var missedSwipeCount = 0



    private val refreshRunnable = object : Runnable {
        override fun run() {
            // Keep the fast poller asleep while the user has paused automation.
            if (!isRunning || currentState != AppState.RUNNING) return

                // Check license expiration (cached - check every 5s)
                val now2 = System.currentTimeMillis()
                if (now2 - lastLicenseCheck > 5000L) {
                    lastLicenseCheck = now2
                    if (!isLicenseValid()) {
                        currentState = AppState.IDLE
                        isAutoRunEnabled = false
                        removeFloatingWidget()
                        return
                    }
                }


                val now = System.currentTimeMillis()

                if (now < pausePollingUntil) {
                    // A swipe already has an intentional settle window. Reposting
                    // every 1 ms while that window is active burns CPU without
                    // reading the screen; sleep until the next eligible scan.
                    val remainingPauseMs = (pausePollingUntil - now).coerceAtLeast(1L)
                    handler.postDelayed(this, remainingPauseMs)
                    return

                }

                

                if (now - lastAccessibilityReadAt >= accessibilityReadIntervalMs()) {

                    lastAccessibilityReadAt = now
                    doAccessibilityRead()

                } else {

                    // // (log removed)

                }

            // Keep one poll chain alive only while automation is running.
            if (isRunning && currentState == AppState.RUNNING) {
                handler.postDelayed(this, accessibilityReadIntervalMs())
            }

        }

    }

    

    private fun getFullAccessibilityText(): String {
        val activeRoot = rootInActiveWindow ?: return ""
        return try {
            val pkg = activeRoot.packageName?.toString().orEmpty()
            if (isDriverAppPackage(pkg)) extractAllText(activeRoot) else ""
        } catch (e: Exception) {
            Log.w(TAG, "ACCESSIBILITY_TEXT_READ_FAILED", e)
            ""
        } finally {
            activeRoot.recycle()
        }
    }

    private fun doAccessibilityRead() {
        if (isScanningScreen) return
        isScanningScreen = true
        currentScanStartTime = android.os.SystemClock.uptimeMillis()
        val activeRoot = rootInActiveWindow ?: run {
            isScanningScreen = false
            return
        }
        try {
            val rootPkg = activeRoot.packageName?.toString().orEmpty()
            if (rootPkg.isNotEmpty() && !isDriverAppPackage(rootPkg) && rootPkg != "android") {
                return
            }

            // Chỉ duyệt cửa sổ phụ khi đang chờ popup xác nhận hoặc đang chờ kết quả nhận đơn
            if (awaitingPopupConfirmation || isWaitingOrderResult) {
                for (window in windows) {
                    try {
                        if (window.isActive) continue
                        val root = window.root ?: continue
                        val pkg = root.packageName?.toString().orEmpty()
                        if (isDriverAppPackage(pkg) || pkg == "android") {
                            val winText = extractAllText(root)
                            val lower = removeAccents(winText).lowercase()
                            if (lower.contains("nhan don") || lower.contains("xac nhan") || lower.contains("dong y") || lower.contains("thanh cong")) {
                                analyzeAndAct(root, winText, "POPUP_WIN")
                                try { root.recycle() } catch (_: Exception) {}
                                return
                            }
                            try { root.recycle() } catch (_: Exception) {}
                        } else {
                            try { root.recycle() } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {}
                }
            }

            // Gọi thẳng analyzeAndAct với activeRoot - siêu tốc độ 3-5ms, không lãng phí Binder IPC
            analyzeAndAct(activeRoot, "", "ACC_FAST")
        } catch (e: Exception) {
            Log.w(TAG, "ACCESSIBILITY_READ_FAILED", e)
        } finally {
            try { activeRoot.recycle() } catch (_: Exception) {}
            isScanningScreen = false
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (!verifyAppIntegrity(this)) {
            Log.e("HK_BENCHMARK", "INTEGRITY_CHECK_FAILED: App signature or package ID is invalid! Shutting down service.")
            disableSelf()
            return
        }
        instance = this
        isRunning = true
        pickMode = getSharedPreferences("AutoPickPrefs", Context.MODE_PRIVATE)
            .getString("pick_mode", "CLICK") ?: "CLICK"
        Log.i("HK_BENCHMARK", "SERVICE_CONNECTED dryRun=$dryRun pickMode=$pickMode")
        targetDriverPackage = getSharedPreferences(ACCESSIBILITY_SCOPE_PREFS, Context.MODE_PRIVATE)
            .getString(ACCESSIBILITY_SCOPE_PACKAGE_KEY, null)
        applyAccessibilityPackageScope(targetDriverPackage)
        val restoredSettings = restoreNativeSettingsFromPreferences()
        if (isLicenseValid()) {
            createFloatingWidget()
            handler.post(refreshRunnable)
        } else {
            removeFloatingWidget()
            currentState = AppState.IDLE
            isAutoRunEnabled = false
            Log.w(TAG, "SERVICE_CONNECTED: License expired or inactive. Suppressing floating widget.")
        }
    }

    private fun applyAccessibilityPackageScope(targetPackage: String?) {
        try {
            val info = serviceInfo ?: return
            info.packageNames = null // Để nhận event linh hoạt mọi app ShopeeFood/Foody/Driver
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            info.notificationTimeout = 0L
            setServiceInfo(info)
        } catch (e: Exception) {
            Log.w(TAG, "ACCESSIBILITY_SCOPE_UPDATE_FAILED", e)
        }
    }

    private fun isDriverAppForeground(): Boolean {
        val root = rootInActiveWindow ?: return true
        return try {
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg.isBlank()) true else isDriverAppPackage(pkg)
        } catch (_: Exception) {
            true
        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }
    }

    private fun lockToForegroundDriverApp(): Boolean {
        val root = rootInActiveWindow ?: run {
            Toast.makeText(this, "Hãy mở màn hình app tài xế rồi mới bấm Bắt đầu.", Toast.LENGTH_LONG).show()
            return false
        }

        try {
            val activePackage = root.packageName?.toString().orEmpty()
            if (activePackage.isBlank() || activePackage == packageName) {
                Toast.makeText(this, "Hãy chuyển sang app tài xế trước khi bật quét.", Toast.LENGTH_LONG).show()
                return false
            }

            val normalizedText = removeAccents(extractAllText(root)).lowercase()
            val driverMarkers = listOf(
                "free-pick", "shopee", "asap", "nhan don", "lay hang", "diem bat dau",
                "khong con don hang nao", "khong co don thuoc dang free-pick"
            )
            val markerCount = driverMarkers.count { marker -> normalizedText.contains(marker) }
            val isKnownDriverPkg = activePackage.contains("driver") || activePackage.contains("shopee") || activePackage.contains("delivery")
            if (markerCount < 1 && !isKnownDriverPkg) {
                Toast.makeText(
                    this,
                    "Chưa nhận diện được màn hình app tài xế. Mở tab đơn Shopee/Free-Pick rồi thử lại.",
                    Toast.LENGTH_LONG
                ).show()
                return false
            }

            targetDriverPackage = activePackage
            getSharedPreferences(ACCESSIBILITY_SCOPE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(ACCESSIBILITY_SCOPE_PACKAGE_KEY, activePackage)
                .apply()
            applyAccessibilityPackageScope(activePackage)
            Log.i(TAG, "ACCESSIBILITY_SCOPE locked_to_driver_app package=$activePackage")
            Toast.makeText(this, "Đã khoanh vùng quét vào app tài xế đang mở.", Toast.LENGTH_SHORT).show()
            return true
        } catch (e: Exception) {
            Log.w(TAG, "ACCESSIBILITY_SCOPE_DETECTION_FAILED", e)
            Toast.makeText(this, "Không xác định được app tài xế đang mở.", Toast.LENGTH_LONG).show()
            return false
        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }
    }

    private fun restoreNativeSettingsFromPreferences(): Boolean {
        val prefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
        settingsReady = false

        return try {
            val snapshotText = prefs.getString("hkpick.nativeSettings", null)
            val snapshot = snapshotText?.let { org.json.JSONObject(it) }
            val cardArray = snapshot?.optJSONArray("htCards")
                ?: prefs.getString("flutter.htCards", null)?.let { org.json.JSONArray(it) }
                ?: return false
            if (cardArray.length() < 6) return false

            val restoredCards = Array(6) { index ->
                val obj = cardArray.getJSONObject(index)
                HtCard(
                    enabled = obj.optBoolean("enabled", false),
                    minPrice = obj.optDouble("minPrice", Double.POSITIVE_INFINITY),
                    minKm = obj.optDouble("minKm", 0.0),
                    maxKm = obj.optDouble("maxKm", 0.0),
                    blacklist = obj.optString("blacklist", ""),
                    useDistricts = obj.optBoolean("useDistricts", false),
                    districts = jsonArrayToStringList(obj.optJSONArray("districts")),
                    rejectCod = obj.optBoolean("rejectCod", false),
                    enableProfitRatio = obj.optBoolean("enableProfitRatio", false),
                    minPricePerKm = obj.optDouble("minPricePerKm", 0.0)
                )
            }
            htCards = restoredCards

            fun savedDouble(name: String, flutterKey: String, default: Double): Double =
                if (snapshot?.has(name) == true) snapshot.optDouble(name, default)
                else prefs.getDouble("flutter.$flutterKey", default)

            fun savedBoolean(name: String, flutterKey: String, default: Boolean): Boolean =
                if (snapshot?.has(name) == true) snapshot.optBoolean(name, default)
                else prefs.getBoolean("flutter.$flutterKey", default)

            fun savedString(name: String, flutterKey: String, default: String): String =
                if (snapshot?.has(name) == true) snapshot.optString(name, default)
                else prefs.getString("flutter.$flutterKey", default) ?: default

            val modeValue = savedString("mode", "currentMode", "HOA_TOC")
            currentMode = if (modeValue == "FOOD") "FOOD" else "HOA_TOC"
            pickMode = savedString("pickMode", "pick_mode", "CLICK")
            speedLevel = if (snapshot?.has("speedLevel") == true) {
                snapshot.optInt("speedLevel", 1)
            } else {
                try { prefs.getLong("flutter.speedLevel", 1L).toInt() }
                catch (_: Exception) { prefs.getInt("flutter.speedLevel", 1) }
            }.coerceIn(1, 3)
            swipeDurationMs = if (snapshot?.has("swipeDurationMs") == true) {
                snapshot.optLong("swipeDurationMs", 30L)
            } else {
                try { prefs.getLong("flutter.swipeDurationMs", 30L) }
                catch (_: Exception) { prefs.getInt("flutter.swipeDurationMs", 30).toLong() }
            }.coerceIn(1L, 500L)
            maxRandomDelayMs = savedDouble("randomDelayMs", "randomDelayMs", 0.0)
            selectBestOrderEnabled = savedBoolean("selectBestOrder", "selectBestOrder", false)

            tcEnabled = savedBoolean("tcEnabled", "tcEnabled", false)
            tcDistrict = savedString("tcDistrict", "tcDistrict", "")
            val savedTcWards = snapshot?.optJSONArray("tcWards")
                ?.let { jsonArrayToStringList(it) }
                ?: readStoredStringList(prefs, "flutter.tcWards")
            tcWards = savedTcWards.toMutableList()
            tcKeywords = savedString("tcKeywords", "tcKeywords", "")
            tcKeywordsList = tcKeywords.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            tcMaxKm = savedDouble("tcMaxKm", "tcMaxKm", 5.0)
            tcMinPrice = savedDouble("tcMinPrice", "tcMinPrice", 15.0)

            useHtGlobalDistricts = savedBoolean("useHtGlobalDistricts", "useHtGlobalDistricts", false)
            val savedHtDistricts = snapshot?.optJSONArray("htGlobalDistricts")
                ?.let { jsonArrayToStringList(it) }
                ?: readStoredStringList(prefs, "flutter.htGlobalDistricts")
            htRejectCodGlobal = savedBoolean("htRejectCodGlobal", "ht_rejectCod", true)
            htGlobalDistricts = savedHtDistricts.toMutableList()

            maxFoodDistance = savedDouble("maxFoodDistance", "maxFoodDistance", 1.0)
            blacklistFood = savedString("blacklistFood", "blacklistFood", "")
            useFoodCodFilter = savedBoolean("useFoodCodFilter", "useFoodCodFilter", false)
            maxFoodCod = savedDouble("maxFoodCod", "maxFoodCod", 100.0)
            acceptZeroCod = savedBoolean("acceptZeroCod", "acceptZeroCod", true)
            useFoodDistrictFilter = savedBoolean("useFoodDistrictFilter", "useFoodDistrictFilter", false)
            val savedFoodDistricts = snapshot?.optJSONArray("foodDistricts")
                ?.let { jsonArrayToStringList(it) }
                ?: readStoredStringList(prefs, "flutter.foodDistricts")
            foodDistricts = savedFoodDistricts.toMutableList()

            usePinnedDeliveryRadius = savedBoolean("usePinnedDeliveryRadius", "usePinnedDeliveryRadius", false)
            pinnedRadiusKm = savedDouble("pinnedRadiusKm", "pinnedRadiusKm", 5.0).coerceIn(0.5, 30.0)
            pinnedLatitude = savedNullableDouble(snapshot, prefs, "pinnedLatitude")
            pinnedLongitude = savedNullableDouble(snapshot, prefs, "pinnedLongitude")

            settingsReady = true
            val cardsSummary = htCards.mapIndexed { index, card ->
                "${index + 1}:${if (card.enabled) "on" else "off"}/${card.minPrice}k/${card.maxKm}km"
            }.joinToString(",")
            Log.i(TAG, "SETTINGS_RESTORED mode=$currentMode cards=$cardsSummary tcEnabled=$tcEnabled")
            true
        } catch (e: Exception) {
            settingsReady = false
            Log.e(TAG, "SETTINGS_RESTORE result=error type=${e.javaClass.simpleName}")
            false
        }
    }

    private fun jsonArrayToStringList(array: org.json.JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            if (array.isNull(index)) null else array.optString(index).takeIf { it.isNotBlank() }
        }
    }

    private fun readStoredStringList(prefs: android.content.SharedPreferences, key: String): List<String> {
        val raw = try { prefs.getString(key, null) } catch (_: Exception) { null } ?: return emptyList()
        val jsonPrefix = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu!"
        val json = when {
            raw.startsWith(jsonPrefix) -> raw.substring(jsonPrefix.length)
            raw.trimStart().startsWith("[") -> raw
            else -> return emptyList()
        }
        return try { jsonArrayToStringList(org.json.JSONArray(json)) } catch (_: Exception) { emptyList() }
    }

    private fun savedNullableDouble(
        snapshot: org.json.JSONObject?,
        prefs: android.content.SharedPreferences,
        key: String
    ): Double? {
        if (snapshot?.has(key) == true && !snapshot.isNull(key)) {
            return snapshot.optDouble(key).takeIf { it.isFinite() }
        }
        return try {
            prefs.getDouble("flutter.$key", Double.NaN).takeIf { it.isFinite() }
        } catch (_: Exception) {
            null
        }
    }

    fun killService() {

        isRunning = false

        isAutoRunEnabled = false

        currentState = AppState.IDLE

        handler.removeCallbacks(refreshRunnable)

        removeFloatingWidget()

        try { windowManager?.removeView(debugView) } catch (e: Exception) {}

        debugView = null

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {

            disableSelf()

        }

    }

    override fun onUnbind(intent: Intent?): Boolean {

        removeFloatingWidget()

        try { windowManager?.removeView(debugView) } catch (e: Exception) {}

        debugView = null

        return super.onUnbind(intent)

    }

    override fun onDestroy() {

        removeFloatingWidget()

        try { windowManager?.removeView(debugView) } catch (e: Exception) {}

        debugView = null

        instance = null

        super.onDestroy()

    }

    private fun createFloatingWidget() {
        if (!isLicenseValid()) {
            removeFloatingWidget()
            return
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        

        targetRefresh = DraggableWidget("1.LOAD", "#994CAF50", "x_ref_v2", "y_ref_v2", 300, 150, 120, 120)
        targetSwipeArrow = DraggableSwipeArrowWidget()

        controlView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#CC000000"))
            setPadding(15, 10, 15, 10)
            gravity = Gravity.CENTER_VERTICAL
            val gradientDrawable = android.graphics.drawable.GradientDrawable()
            gradientDrawable.setColor(Color.parseColor("#CC000000"))
            gradientDrawable.cornerRadius = 40f
            background = gradientDrawable
        }
        
        val dragHandle = TextView(this).apply {
            text = " ✥ "
            setTextColor(Color.WHITE)
            textSize = 22f
            setPadding(0, 0, 15, 0)
        }
        
        btnSetup = Button(this).apply {
            text = "⚙️"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(0, 0, 0, 0)
            minWidth = 0
            minHeight = 0
            layoutParams = LinearLayout.LayoutParams(120, 100).apply { setMargins(0, 0, 15, 0) }
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#555555"))
                cornerRadius = 20f
            }
            setOnClickListener {
                currentState = AppState.SETUP
                targetRefresh?.show()
                targetSwipeArrow?.show()
                btnSetup?.visibility = View.GONE
                btnSave?.visibility = View.VISIBLE
                btnStart?.visibility = View.GONE
            }
        }
        
        btnSave = Button(this).apply {
            text = "💾 LƯU"
            visibility = View.GONE
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, 0, 0, 0)
            minWidth = 0
            minHeight = 0
            layoutParams = LinearLayout.LayoutParams(200, 100).apply { setMargins(0, 0, 15, 0) }
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#4CAF50"))
                cornerRadius = 20f
            }
            setOnClickListener {
                targetRefresh?.saveCoords()
                targetSwipeArrow?.saveCoords()
                
                targetRefresh?.hide()
                targetSwipeArrow?.hide()
                
                currentState = AppState.IDLE
                btnSetup?.visibility = View.VISIBLE
                btnSave?.visibility = View.GONE
                btnStart?.visibility = View.VISIBLE
            }
        }
        
        btnStart = Button(this).apply {
            text = "▶ BẮT ĐẦU"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(0, 0, 0, 0)
            minWidth = 0
            minHeight = 0
            layoutParams = LinearLayout.LayoutParams(240, 100)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#4CAF50"))
                cornerRadius = 20f
            }
            setOnClickListener {
                if (!isLicenseValid()) {
                    currentState = AppState.IDLE
                    isAutoRunEnabled = false
                    removeFloatingWidget()
                    Toast.makeText(
                        this@AutoPickService,
                        "Thiết bị chưa có bản quyền hoặc đã hết hạn!",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }

                val greenBg = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#4CAF50"))
                    cornerRadius = 20f
                }
                val redBg = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#F44336"))
                    cornerRadius = 20f
                }

                if (currentState != AppState.RUNNING && !settingsReady) {
                    Toast.makeText(
                        this@AutoPickService,
                        "Chưa nạp được cấu hình. Hãy mở AutoPick Pro để đồng bộ cài đặt trước.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }
                
                if (currentState == AppState.RUNNING) {
                    stopAutoRunState("button_clicked")
                } else {
                    if (!lockToForegroundDriverApp()) return@setOnClickListener
                    currentState = AppState.RUNNING
                    isAutoRunEnabled = true
                    val now = System.currentTimeMillis()
                    lastOrderDetectedTime = now + 6000L
                    lastSwipeTimeUptimeMs = 0L
                    pausePollingUntil = 0L
                    isWaitingOrderResult = false
                    orderResultWaitDeadline = 0L
                    awaitingPopupConfirmation = false
                    lastSwipedCardFingerprint = ""
                    lastSwipedCardExpiresAt = 0L
                    lastAcceptedTime = 0L
                    emptyScreenStartTime = 0L
                    Log.i("HK_BENCHMARK", "STATE_CHANGED to RUNNING dryRun=$dryRun pickMode=$pickMode")
                    text = "⏸ DỪNG LẠI"
                    background = redBg
                    btnSetup?.visibility = View.GONE
                    handler.removeCallbacks(refreshRunnable)
                    handler.post(refreshRunnable)
                    handler.post {
                        if (isRunning && currentState == AppState.RUNNING) {
                            doAccessibilityRead()
                        }
                    }
                }
                updateFloatingButtonIcon()
            }
        }
        
        controlView?.addView(dragHandle)
        controlView?.addView(btnSetup)
        controlView?.addView(btnSave)
        controlView?.addView(btnStart)
        
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 100
        
        val floatingView = controlView
        if (floatingView != null) {
            floatingView.setOnTouchListener(object : View.OnTouchListener {
                private var initialX = 0
                private var initialY = 0
                private var initialTouchX = 0f
                private var initialTouchY = 0f
                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = params.x
                            initialY = params.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            params.x = initialX + (event.rawX - initialTouchX).toInt()
                            params.y = initialY + (event.rawY - initialTouchY).toInt()
                            windowManager?.updateViewLayout(floatingView, params)
                            return true
                        }
                    }
                    return false
                }
            })
            windowManager?.addView(floatingView, params)
        }
    }

    private fun removeFloatingWidget() {
        try {
            targetRefresh?.remove()
            targetSwipeArrow?.remove()

            if (controlView != null) {
                windowManager?.removeView(controlView)
                controlView = null
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {

        if (event == null || !isRunning || currentState != AppState.RUNNING || !isLicenseValid()) return
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) return
        val eventPkg = event.packageName?.toString().orEmpty()
        if (eventPkg.isNotEmpty() && !isDriverAppPackage(eventPkg) && eventPkg != "android") return

        // Read changes from the selected driver app promptly, with the same
        // speed-level throttle as the polling fallback.
        val eventText = buildString {
            event.text?.forEach { append(it).append(' ') }
            event.contentDescription?.let { append(it).append(' ') }
            event.source?.let { sourceNode ->
                sourceNode.text?.let { append(it).append(' ') }
                sourceNode.contentDescription?.let { append(it).append(' ') }
                sourceNode.recycle()
            }
        }
        val eventHint = removeAccents(eventText).lowercase()

        // 0.A KIỂM TRA ĐƠN GÁN / ĐƠN ÉP NHẬN TRỰC TIẾP TỪ EVENT -> DỪNG TOOL NGAY
        if (isAssignedOrderText(eventHint)) {
            handleAssignedOrderDetected("eventHint")
            try { event.source?.recycle() } catch (_: Exception) {}
            return
        }

        // 0.B KIỂM TRA MÀN HÌNH HẾT ĐƠN / TRỐNG ĐƠN TỪ EVENT -> BẤM TẢI LẠI TRANG / LOAD
        val isEventEmptyState = eventHint.contains("khong con don hang nao") || 
                               eventHint.contains("khong co don thuoc dang free-pick") ||
                               eventHint.contains("hien dang khong co don") ||
                               (eventHint.contains("tai lai trang") && !eventHint.contains("bi quyet"))
        if (isEventEmptyState) {
            val src = event.source
            if (selectBestOrderEnabled && bestOrderState == BestOrderState.SCANNING && scannedDeck.isNotEmpty()) {
                handleEndOfDeckOrEmptyScreen(src)
            } else {
                clickTaiLaiTrangOrTapRefresh(src)
            }
            try { src?.recycle() } catch (_: Exception) {}
            return
        }

        // 0.C KIỂM TRA ĐƠN ĐÃ LẤY THÀNH CÔNG TRỰC TIẾP TỪ EVENT (CHỐT ĐƠN VÀ DỪNG TOOL NGAY)
        // Tuyệt đối không nhầm với thẻ đơn trên màn hình Free-pick (ví dụ quán "Y Khoa Thành Công", "Cơm tấm Thành Công")
        val successPhraseRegex = Regex("\\b(lay|nhan)\\s+don(\\s+free-pick)?\\s+thanh\\s+cong\\b")
        val isOrderCardText = eventHint.contains("lay hang:") || eventHint.contains("giao:") || 
                              eventHint.contains("asap") || eventHint.contains("phuong") || eventHint.contains("quan ")
        val isSuccessDialog = !isOrderCardText && (
            successPhraseRegex.containsMatchIn(eventHint) ||
            eventHint.contains("ban vua nhan duoc don hang") ||
            (eventHint.contains("ban vua nhan") && eventHint.contains("don hang thanh cong"))
        )

        if (isSuccessDialog) {
            Log.i("HK_BENCHMARK", "ORDER_ACCEPTED_DETECTED_IN_EVENT text=$eventHint")
            clickSuccessDialogOkButton(event.source)
            stopAutoRunState("order_accepted_event")
            flashScreenSuccess()
            playAlertSound()
            handler.post {
                Toast.makeText(this, "Đã nhận đơn thành công! Đã dừng tool.", Toast.LENGTH_LONG).show()
            }
            try { event.source?.recycle() } catch (_: Exception) {}
            return
        }

        val isConfirmationDialog = (awaitingPopupConfirmation || isOrderConfirmationDialog(eventHint)) && (
            isOrderConfirmationDialog(eventHint) ||
            eventHint.contains("ban co muon") ||
            eventHint.contains("ban co chac") ||
            eventHint.contains("lay don free-pick") ||
            eventHint.contains("lay don hang nay") ||
            eventHint.contains("xac nhan nhan don")
        )
        val now = System.currentTimeMillis()
        lastOrderDetectedTime = now + 4000L
        if ((isConfirmationDialog || isWaitingOrderResult || now >= pausePollingUntil) &&
            now - lastAccessibilityReadAt >= 1L) {
            lastAccessibilityReadAt = now
            handler.post {
                if (isRunning && currentState == AppState.RUNNING) doAccessibilityRead()
            }
        }

        val source = event.source ?: return

        // Nếu là popup xác nhận đơn (lần 2), bấm ngay lập tức trực tiếp từ event
        if (isConfirmationDialog) {
            val confirmed = clickOrderConfirmationPopup(source)
            if (confirmed) {
                awaitingPopupConfirmation = false
                isWaitingOrderResult = true
                orderResultWaitDeadline = System.currentTimeMillis() + 8000L
                lastAcceptedTime = System.currentTimeMillis()
                flashScreenSuccess()
                playAlertSound()
                pausePollingUntil = System.currentTimeMillis() + 150L
                Log.i("HK_BENCHMARK", "POPUP_CONFIRMED_DIRECTLY_IN_ACCESSIBILITY_EVENT")
                try { source.recycle() } catch (_: Exception) {}
                return
            }
        }

        try {
        val daHieuNodes = source.findAccessibilityNodeInfosByText("Đã hiểu")

        if (daHieuNodes != null && daHieuNodes.isNotEmpty()) {
            var handled = false
            try {
                for (node in daHieuNodes) {
                    if (node.text?.toString()?.equals("Đã hiểu", ignoreCase = true) == true) {
                        val clickableNode = getClickableNode(node)
                        if (clickableNode != null) {
                            try {
                                handled = tryClick(clickableNode, "dismiss_da_hieu")
                            } finally {
                                if (clickableNode !== node) clickableNode.recycle()
                            }
                        }
                        if (!handled) {
                            if (dryRun) {
                                Log.i("HK_BENCHMARK", "DRY_RUN would_gesture tag=dismiss_da_hieu")
                                handled = true
                            } else {
                                val bounds = android.graphics.Rect()
                                node.getBoundsInScreen(bounds)
                                val path = Path().apply { moveTo(bounds.centerX().toFloat(), bounds.centerY().toFloat()) }
                                val gesture = GestureDescription.Builder()
                                    .addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build()
                                handled = dispatchGesture(gesture, null, null)
                            }
                        }
                        break
                    }
                }
            } finally {
                for (node in daHieuNodes) node.recycle()
            }
            if (handled) {
                finishRejectedOrderAndSwipe(acknowledgementAlreadyClicked = true)
                return
            }
        }

        

        val dongNodes = source.findAccessibilityNodeInfosByText("Đóng")

        if (dongNodes != null && dongNodes.isNotEmpty()) {
            var handled = false
            try {
                for (node in dongNodes) {
                    if (node.text?.toString()?.equals("Đóng", ignoreCase = true) == true) {
                        val clickableNode = getClickableNode(node)
                        if (clickableNode != null) {
                            try {
                                handled = tryClick(clickableNode, "dismiss_dong")
                            } finally {
                                if (clickableNode !== node) clickableNode.recycle()
                            }
                        } else {
                            if (dryRun) {
                                Log.i("HK_BENCHMARK", "DRY_RUN would_gesture tag=dismiss_dong")
                                handled = true
                            } else {
                                val bounds = android.graphics.Rect()
                                node.getBoundsInScreen(bounds)
                                val path = Path().apply { moveTo(bounds.centerX().toFloat(), bounds.centerY().toFloat()) }
                                val gesture = GestureDescription.Builder()
                                    .addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build()
                                dispatchGesture(gesture, null, null)
                                handled = true
                            }
                        }
                        break
                    }
                }
            } finally {
                for (node in dongNodes) node.recycle()
            }
            if (handled) return
        }

        } finally {
            source.recycle()
        }

    }

    override fun onKeyEvent(event: android.view.KeyEvent): Boolean {

        if (event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN && event.action == android.view.KeyEvent.ACTION_DOWN) {

            if (!isLicenseValid()) {
                removeFloatingWidget()
                return true
            }

            handler.post {
                btnStart?.performClick()
            }

            return true // Chặn phím không cho ứng dụng khác dùng

        }

        return super.onKeyEvent(event)

    }

    override fun onInterrupt() {
        isRunning = false

        isAutoRunEnabled = false

        currentState = AppState.IDLE

        handler.removeCallbacks(refreshRunnable)

    }

    // DEBUG: Tắt hiển thị debug

    private var debugView: TextView? = null

    private fun showDebugText(text: String) {

        // Đã tắt theo yêu cầu

    }

    

    private fun analyzeAndAct(rootNode: AccessibilityNodeInfo?, allText: String = "", windowDebug: String = "", smartRect: android.graphics.Rect? = null, extraSystemText: String = "") {

        val rootPkg = rootNode?.packageName?.toString().orEmpty()
        if (rootPkg.isNotEmpty() && !isDriverAppPackage(rootPkg) && rootPkg != "android") return

        var targetRect = smartRect ?: android.graphics.Rect(0, 0, 0, 0)

        if (rootNode == null && allText.isEmpty() && extraSystemText.isEmpty()) {
            return
        }

        val lowerRawText = removeAccents((allText + " " + extraSystemText).lowercase())

        // 0. PHÁT HIỆN "ĐƠN GÁN" / "ĐƠN ÉP NHẬN" -> DỪNG TOOL NGAY LẬP TỨC!
        if (isAssignedOrderText(lowerRawText)) {
            handleAssignedOrderDetected("analyzeAndAct lowerRawText")
            return
        }
        if (rootNode != null) {
            val chapNhanList = try { rootNode.findAccessibilityNodeInfosByText("Chấp nhận") } catch (_: Exception) { null }
            val sauKhiList = try { rootNode.findAccessibilityNodeInfosByText("hoàn thành") } catch (_: Exception) { null }
            val hasChapNhan = !chapNhanList.isNullOrEmpty()
            val hasSauKhi = !sauKhiList.isNullOrEmpty()
            chapNhanList?.forEach { try { it.recycle() } catch (_: Exception) {} }
            sauKhiList?.forEach { try { it.recycle() } catch (_: Exception) {} }
            if (hasChapNhan || hasSauKhi) {
                handleAssignedOrderDetected("assigned_modal (Chấp nhận=$hasChapNhan, hoàn thành=$hasSauKhi)")
                return
            }
        }

        // Kiểm tra xem trên màn hình có thẻ đơn hoặc nút "Nhận đơn" không
        val hasNhanDonNodes = try { rootNode?.findAccessibilityNodeInfosByText("Nhận đơn") } catch (_: Exception) { null }
        val hasNhanDonButton = !hasNhanDonNodes.isNullOrEmpty() || lowerRawText.contains("nhan don")
        hasNhanDonNodes?.forEach { try { it.recycle() } catch (_: Exception) {} }

        // 0.A PHÁT HIỆN POPUP LẤY ĐƠN THÀNH CÔNG (CHỐT KÈO - DỪNG TOOL NGAY)
        // Tuyệt đối không dừng nếu màn hình đang hiển thị thẻ đơn (ví dụ tên quán có chữ "Thành Công" như "Y Khoa Thành Công")
        val successPhraseRegex = Regex("\\b(lay|nhan)\\s+don(\\s+free-pick)?\\s+thanh\\s+cong\\b")
        val isCardOnScreen = hasNhanDonButton || lowerRawText.contains("lay hang:") || lowerRawText.contains("giao:") || lowerRawText.contains("asap")

        var hasRealSuccessNode = false
        if (!isCardOnScreen && rootNode != null) {
            val candidateSuccessLabels = listOf("thành công", "Thành công", "THÀNH CÔNG")
            for (label in candidateSuccessLabels) {
                val list = try { rootNode.findAccessibilityNodeInfosByText(label) } catch (_: Exception) { null }
                if (!list.isNullOrEmpty()) {
                    for (node in list) {
                        val nodeText = removeAccents((node.text?.toString() ?: node.contentDescription?.toString()).orEmpty()).lowercase()
                        if (successPhraseRegex.containsMatchIn(nodeText) || nodeText.contains("ban vua nhan duoc don hang")) {
                            hasRealSuccessNode = true
                        }
                        try { node.recycle() } catch (_: Exception) {}
                    }
                }
                if (hasRealSuccessNode) break
            }
        }

        val isOrderAcceptedSuccess = !isCardOnScreen && (
            hasRealSuccessNode ||
            successPhraseRegex.containsMatchIn(lowerRawText) ||
            lowerRawText.contains("ban vua nhan duoc don hang") ||
            (lowerRawText.contains("ban vua nhan") && lowerRawText.contains("don hang thanh cong"))
        )

        if (isOrderAcceptedSuccess) {
            logDecisionOnce("order_accepted_confirmed", "ORDER_ACCEPTED result=confirmed")
            clickSuccessDialogOkButton(rootNode)
            stopAutoRunState("order_accepted_confirmed")
            flashScreenSuccess()
            playAlertSound()
            handler.post {
                Toast.makeText(this, "Đã nhận đơn thành công! Đã dừng tool.", Toast.LENGTH_LONG).show()
            }
            return
        }

        // 0.B PHÁT HIỆN MÀN HÌNH ĐANG LÀM / TIẾN TRÌNH GIAO HÀNG (DỪNG TOOL KHI ĐÃ CÓ ĐƠN)
        // Tuyệt đối không dừng nếu màn hình vẫn là bản đồ Free-pick có nút "Nhận đơn"!
        val activeDetailNodes = if (!hasNhanDonButton && rootNode != null) {
            val list = listOf("Đã đến điểm lấy", "Đã lấy hàng", "Đã đến điểm giao", "Hoàn thành đơn")
            list.any { kw ->
                val f = try { rootNode.findAccessibilityNodeInfosByText(kw) } catch (_: Exception) { null }
                val exists = !f.isNullOrEmpty()
                f?.forEach { try { it.recycle() } catch (_: Exception) {} }
                exists
            }
        } else false

        val activeOrderDetailVisible = !hasNhanDonButton && (
            activeDetailNodes ||
            (lowerRawText.isNotEmpty() && (
                lowerRawText.contains("da den diem lay") ||
                lowerRawText.contains("da lay hang") ||
                lowerRawText.contains("da den diem giao") ||
                lowerRawText.contains("hoan thanh don")
            ))
        )
        if (activeOrderDetailVisible) {
            logDecisionOnce(
                "order_accepted_active_detail",
                "ORDER_ACCEPTED result=active_order_detail_detected auto=stopped"
            )
            stopAutoRunState("active_order_detail")
            flashScreenSuccess()
            playAlertSound()
            return
        }

        // 0.C1 PHÁT HIỆN POPUP LỖI HỆ THỐNG / MẠNG: "Rất tiếc, đã có lỗi xảy ra. Bạn vui lòng thử lại sau nhé!" -> Bấm Đóng & Tự động Load lại!
        var isSystemErrorPopup = lowerRawText.contains("da co loi xay ra") || 
            lowerRawText.contains("vui long thu lai sau") ||
            (lowerRawText.contains("loi") && lowerRawText.contains("thu lai sau"))
        if (!isSystemErrorPopup && rootNode != null) {
            val errNodes1 = try { rootNode.findAccessibilityNodeInfosByText("lỗi xảy ra") } catch (_: Exception) { null }
            val errNodes2 = try { rootNode.findAccessibilityNodeInfosByText("thử lại sau") } catch (_: Exception) { null }
            if (!errNodes1.isNullOrEmpty() || !errNodes2.isNullOrEmpty()) {
                isSystemErrorPopup = true
            }
            errNodes1?.forEach { try { it.recycle() } catch (_: Exception) {} }
            errNodes2?.forEach { try { it.recycle() } catch (_: Exception) {} }
        }
        if (isSystemErrorPopup) {
            Log.i("HK_BENCHMARK", "SYSTEM_ERROR_POPUP_DETECTED: 'Đã có lỗi xảy ra' -> Bấm Đóng và Tự động Load lại!")
            isWaitingOrderResult = false
            awaitingPopupConfirmation = false
            lastAcceptedTime = 0L
            dismissMissedOrderDialog(rootNode)
            pausePollingUntil = System.currentTimeMillis() + 400L
            handler.postDelayed({
                tapRefreshTarget(force = false)
            }, 300L)
            return
        }

        // 0.C PHÁT HIỆN POPUP HỤT ĐƠN / TÀI XẾ KHÁC ĐÃ NHẬN / LỖI NHẬN ĐƠN (CHỈ KHI ĐANG CHỜ KẾT QUẢ ĐƠN)
        val isAwaitingOrder = isWaitingOrderResult || awaitingPopupConfirmation || bestOrderAttemptCount > 0
        var isMissedOrderPopup = isAwaitingOrder && (
            lowerRawText.contains("tai xe khac") || 
            lowerRawText.contains("khong con ton tai") ||
            lowerRawText.contains("da co tai xe") ||
            lowerRawText.contains("da het han") ||
            lowerRawText.contains("da bi huy") ||
            lowerRawText.contains("that bai") ||
            (lowerRawText.contains("khong the nhan") && !lowerRawText.contains("nhan don")) ||
            lowerRawText.contains("chua the nhan") ||
            lowerRawText.contains("gioi han nhan don") ||
            lowerRawText.contains("da hieu")
        )
        if (!isMissedOrderPopup && isAwaitingOrder && rootNode != null) {
            val missedNodes = try { rootNode.findAccessibilityNodeInfosByText("Đã hiểu") } catch (_: Exception) { null }
            if (!missedNodes.isNullOrEmpty()) {
                isMissedOrderPopup = true
            }
            missedNodes?.forEach { try { it.recycle() } catch (_: Exception) {} }
        }

        if (isMissedOrderPopup) {
            isWaitingOrderResult = false
            awaitingPopupConfirmation = false
            lastAcceptedTime = 0L
            Log.i("HK_BENCHMARK", "MISSED_ORDER_POPUP_DETECTED: Bấm Xác nhận/Đã hiểu để đóng dialog!")
            dismissMissedOrderDialog(rootNode)

            if (selectBestOrderEnabled && scannedDeck.isNotEmpty()) {
                // Đánh dấu đơn vừa thử nhận bị hụt
                val failedCard = scannedDeck.firstOrNull { 
                    Math.abs(it.price - bestOrderTargetPrice) < 0.15 && 
                    Math.abs(it.distance - bestOrderTargetDistance) < 0.15 &&
                    (it.orderCount == bestOrderTargetCount || bestOrderTargetCount == 0)
                } ?: scannedDeck.firstOrNull { it.fingerprint == bestOrderTargetFingerprint }
                failedCard?.isFailed = true

                if (bestOrderAttemptCount < 2) {
                    // TÌM ĐƠN NGON THỨ 2!
                    val remaining = getSortedEligibleOrders()
                    if (remaining.isNotEmpty()) {
                        val secondBest = remaining.first()
                        bestOrderAttemptCount = 2
                        val ratio2 = if (secondBest.distance > 0.0) secondBest.price / secondBest.distance else 0.0
                        Log.i("HK_BENCHMARK", "BEST_ORDER_TARGET_FALLBACK_TO_SECOND: Đơn #1 bị hụt! Đã bấm Xác nhận đóng dialog. Bắt đầu tìm đơn ngon thứ 2: count=${secondBest.orderCount} price=${secondBest.price} dist=${secondBest.distance} ratio=${String.format(java.util.Locale.US, "%.2f", ratio2)}")

                        bestOrderState = BestOrderState.REVERSING
                        bestOrderTargetFingerprint = secondBest.fingerprint
                        bestOrderTargetPrice = secondBest.price
                        bestOrderTargetDistance = secondBest.distance
                        bestOrderTargetPoints = secondBest.points
                        bestOrderTargetCount = secondBest.orderCount
                        bestOrderReverseAttempts = 0

                        pausePollingUntil = System.currentTimeMillis() + 80L
                        handler.postDelayed({
                            val swipeDuration = dispatchReverseSwipe()
                            pausePollingUntil = System.currentTimeMillis() + swipeDuration + SWIPE_SETTLE_DELAY_MS
                        }, 80L)
                        return
                    }
                }

                // Nếu không còn đơn ngon thứ 2 hoặc đã thử 2 lần rồi -> BẤM LOAD LẠI NGAY!
                Log.i("HK_BENCHMARK", "BEST_ORDER_ALL_FAILED: Không còn đơn ngon thứ 2 hoặc cả 2 đơn đều bị hụt. Bấm tải lại trang (Load) ngay lập tức!")
                resetBestOrderState()
                pausePollingUntil = System.currentTimeMillis() + 100L
                handler.postDelayed({
                    tapRefreshTarget(force = true)
                }, 100L)
                return
            } else {
                finishRejectedOrderAndSwipe(rootNode = rootNode, acknowledgementAlreadyClicked = true)
                return
            }
        }

        val nowForConfirmDialog = System.currentTimeMillis()

        // Hết hạn chờ popup nếu sau 4 giây không xuất hiện
        if (awaitingPopupConfirmation && nowForConfirmDialog - firstAcceptClickTime > 4000L) {
            awaitingPopupConfirmation = false
            lastAcceptedTime = nowForConfirmDialog
            Log.i(TAG, "AWAITING_POPUP_TIMEOUT -> expired")
        }

        // BƯỚC 2: PHÁT HIỆN VÀ BẤM POPUP XÁC NHẬN (LẦN 2)
        // Bấm popup khi đang trong trạng thái chờ xác nhận HOẶC khi phát hiện dialog xác nhận trên màn hình
        val isPopupDialogVisible = isOrderConfirmationDialog(lowerRawText)
        if (awaitingPopupConfirmation || isPopupDialogVisible) {
            val popupConfirmed = clickOrderConfirmationPopup(rootNode)
            if (popupConfirmed) {
                awaitingPopupConfirmation = false
                isWaitingOrderResult = false
                lastAcceptedTime = System.currentTimeMillis()
                if (pendingDecisionKey.isNotEmpty()) {
                    recentlyAcceptedKeys[pendingDecisionKey] = System.currentTimeMillis() + 5000L
                }
                flashScreenSuccess()
                playAlertSound()
                Log.i("HK_BENCHMARK", "POPUP_SECOND_CLICK_SUCCESS key=$pendingDecisionKey (wasAwaiting=$awaitingPopupConfirmation, dialogVisible=$isPopupDialogVisible)")
                pausePollingUntil = System.currentTimeMillis() + 1200L
                return
            } else if (isPopupDialogVisible) {
                // Popup đang hiển thị trên màn hình nhưng Accessibility chưa nhặt được nút -> Tap trực tiếp tọa độ chuẩn
                val metrics = resources.displayMetrics
                val sw = metrics.widthPixels.toFloat()
                val sh = metrics.heightPixels.toFloat()
                val tapX = sw * 0.72f
                val tapY = sh * 0.565f
                showTouchIndicator(tapX, tapY)
                val path = Path().apply { moveTo(tapX, tapY) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 40)).build()
                dispatchGesture(gesture, null, null)
                Log.i("HK_BENCHMARK", "POPUP_FALLBACK_TAP_DISPATCHED at ($tapX, $tapY)")
                awaitingPopupConfirmation = false
                isWaitingOrderResult = false
                lastAcceptedTime = System.currentTimeMillis()
                pausePollingUntil = System.currentTimeMillis() + 1200L
                return
            } else if (nowForConfirmDialog - firstAcceptClickTime < 3000L) {
                // Đang chờ popup render, nghỉ 40ms rồi quét tiếp
                pausePollingUntil = nowForConfirmDialog + 40L
                return
            }
        }

        // 0. PHÁT HIỆN "ĐƠN GÁN" (HỆ THỐNG ÉP ĐƠN)
        if (isAssignedOrderText(lowerRawText)) {
            handleAssignedOrderDetected("analyzeAndAct fallback lowerRawText")
            return
        }



        // 1. THỬ TÌM MỎ NEO (chỉ khi có rootNode từ Accessibility)

        var foundOrder = false

        var finalCardText = ""

        var finalPrice: Double? = null

        var finalDistance: Double? = null

        var savedAcceptNode: AccessibilityNodeInfo? = null

        val foundAnchors = mutableListOf<String>()

        if (rootNode != null) {
            val startAnchor = android.os.SystemClock.uptimeMillis()
            val screenHeight = resources.displayMetrics.heightPixels
            val primaryAnchors = listOf("ASAP", "Nhận đơn", "km", "Delivery", "Express", "Shopee", "COD", "Điểm", "Giao", "Lấy hàng", "Thu")

            var cardExtractAttempts = 0
            val startCardExtract = android.os.SystemClock.uptimeMillis()

            for (anchor in primaryAnchors) {
                val found = rootNode.findAccessibilityNodeInfosByText(anchor)
                if (found.isNullOrEmpty()) continue
                foundAnchors.add("$anchor(${found.size})")

                for (node in found) {
                    if (!foundOrder) {
                        cardExtractAttempts++
                        var cardContainer: AccessibilityNodeInfo? = node

                        for (i in 0..5) {
                            val parent = cardContainer?.parent
                            if (parent != null) {
                                val pRect = android.graphics.Rect()
                                parent.getBoundsInScreen(pRect)
                                if (pRect.height() > screenHeight * 0.7f || pRect.top < screenHeight * 0.08f) break 
                                cardContainer = parent
                            } else {
                                break
                            }
                        }

                        if (cardContainer != null) {
                            val cardText = extractAllText(cardContainer)
                            val lowerCard = removeAccents(cardText).lowercase()
                            if (!lowerCard.contains("bi quyet") && !lowerCard.contains("ti le")) {
                                val p = extractPrice(cardText)
                                val d = extractDistance(cardText)

                                val isOrderCard = if (p != null && d != null) {
                                    true
                                } else if (currentMode == "FOOD" && d != null) {
                                    lowerCard.contains("quan") || lowerCard.contains("mon") || lowerCard.contains("food") || 
                                    lowerCard.contains("giao") || lowerCard.contains("lay") || lowerCard.contains("shopee")
                                } else {
                                    false
                                }

                                if (isOrderCard) {
                                    foundOrder = true
                                    lastOrderDetectedTime = System.currentTimeMillis() + 5000L
                                    finalPrice = p
                                    finalDistance = d
                                    finalCardText = cardText

                                    val acceptLabels = listOf("Nhận đơn", "NHẬN ĐƠN", "Nhận", "Lấy đơn")
                                    var realAcceptNode: AccessibilityNodeInfo? = null
                                    for (lbl in acceptLabels) {
                                        val acceptNodes = cardContainer.findAccessibilityNodeInfosByText(lbl)
                                        realAcceptNode = acceptNodes?.firstOrNull { isRealAcceptButton(it) }
                                        if (realAcceptNode != null) {
                                            val bounds = android.graphics.Rect()
                                            realAcceptNode.getBoundsInScreen(bounds)
                                            targetRect = bounds
                                            savedAcceptNode = realAcceptNode
                                            for (an in acceptNodes) {
                                                if (an !== realAcceptNode) {
                                                    try { an.recycle() } catch (_: Exception) {}
                                                }
                                            }
                                            break
                                        } else {
                                            acceptNodes?.forEach { try { it.recycle() } catch (_: Exception) {} }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    try { node.recycle() } catch (_: Exception) {}
                }

                if (foundOrder) {
                    // ĐÃ TÌM THẤY ĐƠN: THOÁT NGAY LẬP TỨC! Không quét thêm mỏ neo nào nữa.
                    break
                }
            }
            val anchorQueryMs = android.os.SystemClock.uptimeMillis() - startAnchor
            val cardExtractMs = android.os.SystemClock.uptimeMillis() - startCardExtract

            val nowLog = android.os.SystemClock.uptimeMillis()
            if (foundOrder || nowLog - lastIdleLogTime > 4000L) {
                lastIdleLogTime = nowLog
                Log.i("HK_BENCHMARK", "ANCHOR_PARSE anchorsFound=${foundAnchors.joinToString(",")} anchorQueryMs=$anchorQueryMs cardAttempts=$cardExtractAttempts cardExtractMs=$cardExtractMs foundOrder=$foundOrder price=$finalPrice dist=$finalDistance")
            }
        }

        // 2. NẾU KHÔNG CÓ MỎ NEO: KIỂM TRA THÔNG BÁO HẾT ĐƠN (PHÂN BIỆT RẠCH RÒI TRẠNG THÁI A & B)
        if (!foundOrder && rootNode != null) {
            val emptyKw = listOf("Không còn", "free-pick", "Free-pick", "Tải lại", "tải lại", "Không có", "không có", "Hiện đang", "hiện đang")
            var isStateA = false // "Không còn đơn hàng nào" / "Tải lại trang"
            var isStateB = false // "Hiện đang không có đơn thuộc dạng free-pick"
            for (kw in emptyKw) {
                val nodes = try { rootNode.findAccessibilityNodeInfosByText(kw) } catch (_: Exception) { null }
                if (!nodes.isNullOrEmpty()) {
                    for (n in nodes) {
                        val txt = removeAccents((n.text?.toString() ?: n.contentDescription?.toString()).orEmpty()).lowercase()
                        if (txt.contains("khong co don") || txt.contains("free-pick") || txt.contains("hien dang khong co don")) {
                            isStateB = true
                        } else if (txt.contains("khong con don") || txt.contains("tai lai trang") || (txt.contains("tai lai") && !txt.contains("bi quyet"))) {
                            isStateA = true
                        }
                        try { n.recycle() } catch (_: Exception) {}
                    }
                }
                if (isStateB || isStateA) break
            }
            if (isStateB) {
                // Trạng thái B: Khu vực hoàn toàn không có đơn free-pick
                // CẢNH BÁO: TUYỆT ĐỐI KHÔNG CLICK VÀO LINK "Xem bí quyết tăng tỉ lệ nhận đơn tại đây"!
                // CHỈ bấm vào tọa độ hồng tâm nút LOAD (targetRefresh).
                Log.i("HK_BENCHMARK", "EMPTY_STATE_B: Khong co don free-pick -> Chi bam nut Load Target (bo qua link bi quyet)")
                resetBestOrderState()
                tapRefreshTarget(force = false)
                pausePollingUntil = System.currentTimeMillis() + 400L
                return
            } else if (isStateA) {
                // Trạng thái A: Đã vuốt hết các đơn trong danh sách
                Log.i("HK_BENCHMARK", "EMPTY_STATE_A: Khong con don hang nao")
                if (selectBestOrderEnabled) {
                    handleEndOfDeckOrEmptyScreen(rootNode)
                } else {
                    resetBestOrderState()
                    clickTaiLaiTrangOrTapRefresh(rootNode)
                }
                pausePollingUntil = System.currentTimeMillis() + 400L
                return
            }
        }

        // 3. NẾU KHÔNG CÓ MỎ NEO VÀ CHƯA CÓ TEXT -> THỬ TRÍCH XUẤT DỰ PHÒNG TOÀN MÀN HÌNH
        var currentAllText = allText
        if (!foundOrder && rootNode != null && currentAllText.isEmpty()) {
            currentAllText = extractAllText(rootNode)
        }

        if (!foundOrder && currentAllText.isNotEmpty()) {
            val lowerAll = removeAccents(currentAllText).lowercase()
            if (!lowerAll.contains("bi quyet") && !lowerAll.contains("ti le")) {
                val p = extractPrice(currentAllText)
                val d = extractDistance(currentAllText)
                val isOrderFullscreen = if (p != null && d != null) {
                    true
                } else if (currentMode == "FOOD" && d != null) {
                    lowerAll.contains("quan") || lowerAll.contains("mon") || lowerAll.contains("food") || 
                    lowerAll.contains("giao") || lowerAll.contains("lay") || lowerAll.contains("shopee")
                } else {
                    false
                }
                if (isOrderFullscreen) {
                    foundOrder = true
                    lastOrderDetectedTime = System.currentTimeMillis() + 5000L
                    finalPrice = p
                    finalDistance = d
                    finalCardText = currentAllText
                }
            }
        }



        if (foundOrder) {
            // Khi đã tìm thấy thẻ đơn trên màn hình, chắc chắn các popup trước đã đóng -> reset chốt chặn
            isWaitingOrderResult = false
            awaitingPopupConfirmation = false

            val nowMs = System.currentTimeMillis()

            ignoredOrders.entries.removeIf { it.value < nowMs }

            

            var isNgon = false

            var resultMsg = ""

            var decisionContext = ""

            var swipeNow = false // Vuốt ngay không chờ 3 giây
            var holdForPinnedAddress = false
            var detectedOrderCount = 1

            val screenTextLower = removeAccents(finalCardText).replace("\n", " ").replace("\r", " ").lowercase()
            val swipeFingerprint = screenTextLower.replace(Regex("\\s+"), " ").trim()

            if (swipeFingerprint.isNotEmpty() && swipeFingerprint != lastSwipedCardFingerprint) {
                // Thẻ mới xuất hiện -> Reset đếm vuốt hụt
                missedSwipeCount = 0
            }

            if (nowMs < lastSwipedCardExpiresAt && swipeFingerprint.isNotEmpty() && swipeFingerprint == lastSwipedCardFingerprint) {
                // Đang trong thời gian đợi thẻ trước ổn định, tạm hoãn quét
                val remainingMs = (lastSwipedCardExpiresAt - nowMs).coerceAtLeast(1L)
                pausePollingUntil = nowMs + minOf(remainingMs, 10L)
                return
            }
            
            var isTienChuyenMatch = false
            if (tcEnabled && (tcWards.isNotEmpty() || tcKeywordsList.isNotEmpty())) {
                val cleanCard = screenTextLower
                var dropOff = cleanCard
                val giaoIdx = Math.max(cleanCard.lastIndexOf("giao"), cleanCard.lastIndexOf("den"))
                if (giaoIdx != -1) {
                    dropOff = cleanCard.substring(giaoIdx)
                }

                if (tcDistrict.isNotEmpty() && tcWards.isNotEmpty()) {
                    val distClean = removeAccents(tcDistrict).lowercase()
                    val distNum = Regex("quan\\s*(\\d+)").find(distClean)?.groupValues?.get(1)
                    val distPattern = if (distNum != null) {
                        Regex("\\b(quan|q\\.?)\\s*$distNum\\b")
                    } else {
                        Regex("\\b${Regex.escape(distClean)}\\b")
                    }

                    for (rawWard in tcWards) {
                        val wardClean = removeAccents(rawWard).lowercase()
                        val wardNum = Regex("(phuong|p\\.?)\\s*(\\d+)").find(wardClean)?.groupValues?.get(2)
                            ?: Regex("^(\\d+)$").find(wardClean)?.groupValues?.get(1)

                        if (wardNum != null) {
                            val wardPattern = Regex("\\b(phuong|p\\.?)\\s*$wardNum\\b")
                            val hasWard = wardPattern.containsMatchIn(dropOff) || wardPattern.containsMatchIn(cleanCard)
                            val hasDist = distPattern.containsMatchIn(dropOff) || distPattern.containsMatchIn(cleanCard)
                            if (hasWard && hasDist) {
                                isTienChuyenMatch = true
                                break
                            }
                        } else {
                            if (dropOff.contains(wardClean) || cleanCard.contains(wardClean)) {
                                isTienChuyenMatch = true
                                break
                            }
                        }
                    }
                }

                if (!isTienChuyenMatch && tcKeywordsList.isNotEmpty()) {
                    for (kw in tcKeywordsList) {
                        val kwClean = removeAccents(kw).trim().lowercase()
                        if (kwClean.isNotEmpty() && (dropOff.contains(kwClean) || cleanCard.contains(kwClean))) {
                            isTienChuyenMatch = true
                            break
                        }
                    }
                }
            }

            if (ignoredOrders.containsKey(finalCardText)) {

                isNgon = false

                swipeNow = true

                resultMsg = "⛔ BỎ QUA ĐƠN HẾT HẠN (8 GIÂY)"

            } else if (isDepositOrder(finalCardText)) {

                resultMsg = "⛔ ĐƠN KÝ QUỸ / CẦN NẠP TIỀN → BỎ ❌"

                isNgon = false

                swipeNow = true

            } else if (currentMode == "HOA_TOC") {

                if (finalPrice != null && finalDistance != null && finalDistance > 0) {

                    // Đếm số đơn từ text (hỗ trợ đơn lẻ, chùm, gộp, và số điểm trong Shopee Express / Hỏa Tốc)
                    val checkText = removeAccents(finalCardText).replace(Regex("\\s+"), " ").lowercase()
                    var orderCount = 1 // Mặc định đơn lẻ (không ghi số)

                    // 1. Phân tích phần Header (trước địa chỉ Lấy hàng / Giao hàng để tránh nhầm số nhà / tên đường)
                    val firstAddressIdx = listOf("lay hang", "diem lay", "bat dau", "giao", "nguoi nhan")
                        .map { checkText.indexOf(it) }
                        .filter { it >= 0 }
                        .minOrNull() ?: checkText.length
                    val headerText = checkText.substring(0, firstAddressIdx)

                    // A. Tìm từ khóa chùm/gộp/ghép rõ ràng (VD: "chùm 4", "gộp 5", "ghép 4", "chum 5", "gop 4")
                    val batchRegex = Regex("\\b(?:chum|gop|ghep)\\s*(\\d+)\\b").find(headerText)
                        ?: Regex("\\b(?:chum|gop|ghep)\\s*(\\d+)\\b").find(checkText)

                    // B. Tìm "X đơn hàng" hoặc "X đơn" (loại trừ "X đơn COD")
                    val donHangRegex = Regex("\\b(\\d+)\\s*don\\s*hang\\b").find(headerText)
                        ?: Regex("\\b(\\d+)\\s*don\\s*hang\\b").find(checkText)
                        ?: Regex("\\b(\\d+)\\s*don(?!\\s*cod)\\b").find(headerText)

                    // C. Tìm số điểm giao / điểm trả (VD: "4 điểm giao", "5 điểm trả")
                    val diemGiaoRegex = Regex("\\b(\\d+)\\s*diem\\s*(?:giao|tra|den)\\b").find(checkText)

                    if (batchRegex != null) {
                        orderCount = batchRegex.groupValues[1].toIntOrNull() ?: 1
                    } else if (donHangRegex != null) {
                        orderCount = donHangRegex.groupValues[1].toIntOrNull() ?: 1
                    } else if (diemGiaoRegex != null) {
                        orderCount = diemGiaoRegex.groupValues[1].toIntOrNull() ?: 1
                        if (checkText.contains("8 don") || checkText.contains("giao 8") || checkText.contains("ghep 8") || checkText.contains("chum 8")) orderCount = 8
                        else if (checkText.contains("7 don") || checkText.contains("giao 7") || checkText.contains("ghep 7") || checkText.contains("chum 7")) orderCount = 7
                        else if (checkText.contains("6 don") || checkText.contains("giao 6") || checkText.contains("ghep 6") || checkText.contains("chum 6")) orderCount = 6
                        else if (checkText.contains("5 don") || checkText.contains("giao 5") || checkText.contains("ghep 5") || checkText.contains("chum 5")) orderCount = 5
                        else if (checkText.contains("4 don") || checkText.contains("giao 4") || checkText.contains("ghep 4") || checkText.contains("chum 4")) orderCount = 4
                        else if (checkText.contains("3 don") || checkText.contains("giao 3") || checkText.contains("ghep 3") || checkText.contains("chum 3")) orderCount = 3
                        else if (checkText.contains("2 don") || checkText.contains("giao 2") || checkText.contains("ghep 2") || checkText.contains("chum 2")) orderCount = 2
                    }

                    detectedOrderCount = orderCount
                    // Map orderCount -> card index (0-5), 6+ gộp vào card 5
                    val cardIndex = if (orderCount >= 6) 5 else (orderCount - 1).coerceIn(0, 5)

                    val card = htCards[cardIndex]

                    val districtFilterSource = when {
                        useHtGlobalDistricts -> "global"
                        card.useDistricts -> "card"
                        else -> "off"
                    }
                    val activeDistricts: List<String> = when {
                        useHtGlobalDistricts -> htGlobalDistricts
                        card.useDistricts -> card.districts
                        else -> emptyList()
                    }.map { removeAccents(it).replace(Regex("\\s+"), " ").trim().lowercase() }
                        .filter { it.isNotEmpty() }

                    val passesTienChuyen = isTienChuyenMatch
                    val effectiveMinPrice = if (passesTienChuyen) 0.0 else card.minPrice
                    val effectiveMaxKm = if (passesTienChuyen) 100.0 else card.maxKm
                    val effectiveMinKm = if (passesTienChuyen) 0.0 else card.minKm

                    decisionContext = "card=${cardIndex + 1} enabled=${card.enabled} minPriceK=${card.minPrice} minKm=${card.minKm} maxKm=${card.maxKm} ratioOn=${card.enableProfitRatio} minPricePerKm=${card.minPricePerKm} districtFilter=$districtFilterSource districtCount=${activeDistricts.size} tcMatch=$passesTienChuyen"

                    val isCodOrder = isCodOnlyOrder(finalCardText, allText, rootNode)

                    if (!passesTienChuyen && !card.enabled) {

                        isNgon = false

                        swipeNow = true

                        resultMsg = "📦 HT ${orderCount}đơn: TẮT → BỎ ❌"

                    } else if (finalPrice < effectiveMinPrice) {

                        isNgon = false

                        swipeNow = true

                        resultMsg = "📦 HT ${orderCount}đơn: ${finalPrice}k < ${effectiveMinPrice.toInt()}k → BỎ ❌"

                    } else if (effectiveMinKm > 0 && finalDistance < effectiveMinKm) {

                        isNgon = false

                        swipeNow = true

                        resultMsg = "📦 HT ${orderCount}đơn: ${finalDistance}km < ${effectiveMinKm.toInt()}km → BỎ ❌"

                    } else if (finalDistance > effectiveMaxKm) {

                        isNgon = false

                        swipeNow = true

                        resultMsg = "📦 HT ${orderCount}đơn: ${finalDistance}km > ${effectiveMaxKm.toInt()}km → BỎ ❌"

                    } else if (!passesTienChuyen && card.enableProfitRatio && card.minPricePerKm > 0.0 && (finalPrice / finalDistance) < card.minPricePerKm) {

                        isNgon = false

                        swipeNow = true

                        val ppk = String.format("%.1f", finalPrice / finalDistance)

                        resultMsg = "📦 HT ${orderCount}đơn: $ppk k/km < ${card.minPricePerKm} k/km → BỎ ❌"

                    } else if (isDepositOrder(finalCardText, allText, rootNode)) {

                        isNgon = false

                        swipeNow = true

                        resultMsg = "📦 HT ${orderCount}đơn: ĐƠN KÝ QUỸ → BỎ ❌"

                    } else if (card.rejectCod && isCodOrder) {

                        isNgon = false

                        swipeNow = true

                        resultMsg = "📦 HT ${orderCount}đơn: COD (Thu tiền) → BỎ ❌"

                    } else {

                        // Qua hết bộ lọc cơ bản → kiểm tra blacklist per-card

                        var blacklisted = false

                        if (!passesTienChuyen && card.blacklist.isNotBlank()) {

                            val keywords = card.blacklist.split(",").map { removeAccents(it.trim()).lowercase() }.filter { it.isNotEmpty() }

                            val cleanText = removeAccents(finalCardText).lowercase()

                            for (kw in keywords) {

                                if (cleanText.contains(kw)) {

                                    isNgon = false

                                    swipeNow = true

                                    resultMsg = "📦 HT ${orderCount}đơn: BLACKLIST '$kw' → BỎ ❌"

                                    blacklisted = true

                                    break

                                }

                            }

                        }

                        

                        // Chỉ áp dụng danh sách quận nếu công tắc lọc tương ứng đang bật (bỏ qua nếu là tiện chuyến).
                        if (!passesTienChuyen && !blacklisted && activeDistricts.isNotEmpty()) {

                            val cleanCardFull = removeAccents(finalCardText).replace(Regex("\\s+"), " ").lowercase()

                            // Trích xuất phần text điểm giao

                            var dropOffText = ""

                            val dropKeywords = listOf("diem ket thuc", "ket thuc", "giao hang", "giao den", "diem giao", "giao:", "giao", "nguoi nhan")

                            var startIdx = -1

                            for (kw in dropKeywords) {

                                val idx = cleanCardFull.lastIndexOf(kw)

                                if (idx != -1) { startIdx = idx; break }

                            }

                            if (startIdx == -1) {

                                val layIdx = Math.max(cleanCardFull.lastIndexOf("lay hang"), cleanCardFull.lastIndexOf("bat dau"))

                                val giaoIdx = cleanCardFull.lastIndexOf("giao")

                                if (giaoIdx > layIdx && giaoIdx != -1) startIdx = giaoIdx

                            }

                            if (startIdx != -1) dropOffText = cleanCardFull.substring(startIdx) else dropOffText = cleanCardFull

                            

                            val targetDistricts = activeDistricts

                            var foundDistrict = false

                            for (d in targetDistricts) {

                                if (dropOffText.contains(d)) { foundDistrict = true; break }

                                // Check landmarks

                                val landmarks = landmarksMap[d]

                                if (landmarks != null) {

                                    for (lm in landmarks) {

                                        if (dropOffText.contains(lm)) { foundDistrict = true; break }

                                    }

                                }

                                if (foundDistrict) break

                            }

                            if (!foundDistrict) {

                                isNgon = false

                                swipeNow = true

                                resultMsg = "📦 HT ${orderCount}đơn: NGOÀI QUẬN → BỎ ❌"

                                blacklisted = true

                            }

                        }

                        

                        if (!blacklisted) {

                            isNgon = true

                            resultMsg = if (passesTienChuyen) "🚲 TIỆN CHUYẾN: ${finalPrice}k/${finalDistance}km ✅" else "📦 HT ${orderCount}đơn: ${finalPrice}k/${finalDistance}km ✅"

                        }

                    }

                }

            } else if (currentMode == "FOOD") {

                if (finalDistance != null && finalDistance <= maxFoodDistance) {

                    isNgon = true

                } else {

                    swipeNow = true

                }

                resultMsg = "🍔 ${finalDistance}km (≤$maxFoodDistance) ${if(isNgon) "✅" else "❌"}"

                

                if (isNgon) {

                    val codAmount = extractCodAmount(finalCardText) ?: 0.0

                    val codInK = codAmount / 1000.0

                    

                    if (codAmount == 0.0) {

                        if (!acceptZeroCod) {

                            isNgon = false

                            swipeNow = true

                            resultMsg += " | LỌC COD: BỎ ĐƠN 0đ"

                        }

                    } else {

                        if (useFoodCodFilter && codInK > maxFoodCod) {

                            isNgon = false

                            swipeNow = true

                            resultMsg += " | LỌC COD: BỎ (${String.format("%.1f", codInK)}k > ${maxFoodCod}k)"

                        } else if (useFoodCodFilter) {

                            resultMsg += " | COD: ${String.format("%.1f", codInK)}k"

                        }

                    }

                }

            }

            

            // LỌC THEO KHU VỰC (CHỈ FOOD - HoaToc đã xử lý per-card)

            if (isNgon && useFoodDistrictFilter && foodDistricts.isNotEmpty() && currentMode == "FOOD") {

                val cleanCardText = removeAccents(finalCardText).replace(Regex("\\s+"), " ").lowercase()

                

                var dropOffText = ""

                val dropKeywords = listOf("diem ket thuc", "ket thuc", "giao hang", "giao den", "diem giao", "giao:", "giao", "nguoi nhan")

                var startIndex = -1

                for (kw in dropKeywords) {

                    val idx = cleanCardText.lastIndexOf(kw)

                    if (idx != -1) { startIndex = idx; break }

                }

                if (startIndex == -1) {

                    val layIndex = Math.max(cleanCardText.lastIndexOf("lay hang"), cleanCardText.lastIndexOf("bat dau"))

                    val giaoIndex = cleanCardText.lastIndexOf("giao")

                    if (giaoIndex > layIndex && giaoIndex != -1) startIndex = giaoIndex

                }

                if (startIndex != -1) dropOffText = cleanCardText.substring(startIndex) else dropOffText = ""



                var foundDistrict = false

                for (district in foodDistricts) {

                    val rawName = removeAccents(district).replace(Regex("\\s+"), " ").lowercase()

                    val aliases = mutableListOf<String>()

                    aliases.add(rawName)

                    

                    if (rawName.startsWith("quan ") || rawName.matches(Regex("^q\\s*\\.?\\s*\\d+$"))) {

                        val numOrName = if (rawName.startsWith("quan ")) rawName.substring(5).trim() else rawName.replace(Regex("[^0-9]"), "")

                        val normalizedQuan = "quan $numOrName"

                        aliases.add(normalizedQuan)

                        aliases.add("q$numOrName")

                        aliases.add("q $numOrName")

                        aliases.add("q.$numOrName")

                        aliases.add("q. $numOrName")

                        landmarksMap[normalizedQuan]?.let { aliases.addAll(it) }

                    } else {

                        landmarksMap[rawName]?.let { aliases.addAll(it) }

                    }

                    

                    for (alias in aliases) {

                        if (dropOffText.contains(alias)) { foundDistrict = true; break }

                    }

                    if (foundDistrict) break

                }

                

                if (!foundDistrict) {

                    if (dropOffText.isEmpty()) {

                        isNgon = false

                        swipeNow = false

                        resultMsg += " | LỖI: CHƯA HIỆN ĐỊA CHỈ GIAO"

                    } else {

                        isNgon = false

                        swipeNow = true

                        resultMsg += " | LỌC KHU VỰC: BỎ"

                    }

                }

            }

            

            // LỌC THEO BLACKLIST TỪ KHÓA (CHỈ FOOD - HoaToc đã xử lý per-card)

            if (isNgon && currentMode == "FOOD") {

                if (blacklistFood.isNotBlank()) {

                    val keywords = blacklistFood.split(",").map { removeAccents(it.trim()).lowercase() }.filter { it.isNotEmpty() }

                    val cleanCardText = removeAccents(finalCardText).lowercase()

                    for (kw in keywords) {

                        if (cleanCardText.contains(kw)) {

                            isNgon = false

                            swipeNow = true

                            resultMsg += " | BLACKLIST NÉ: $kw"

                            break

                        }

                    }

                }

            }

            // Chỉ nhận đơn nếu địa chỉ giao geocode được và nằm trong vùng quanh điểm ghim.
            // Geocoding chạy nền; khi chưa có kết quả thì giữ nguyên đơn và chờ vòng quét sau.
            if (isNgon && usePinnedDeliveryRadius) {
                val pinLat = pinnedLatitude
                val pinLon = pinnedLongitude
                if (pinLat == null || pinLon == null || !pinLat.isFinite() || !pinLon.isFinite() ||
                    pinLat !in -90.0..90.0 || pinLon !in -180.0..180.0
                ) {
                    isNgon = false
                    holdForPinnedAddress = true
                    resultMsg = "📍 CHƯA CÓ TỌA ĐỘ ĐIỂM GHIM → GIỮ ĐƠN"
                } else {
                    val dropOffAddress = extractDropOffAddress(finalCardText)
                    if (dropOffAddress.isNullOrBlank()) {
                        isNgon = false
                        holdForPinnedAddress = true
                        resultMsg = "📍 CHƯA ĐỌC ĐƯỢC ĐỊA CHỈ GIAO → GIỮ ĐƠN"
                    } else {
                        val lookup = DeliveryRadiusGeocoder.lookupOrderAddress(this, dropOffAddress)
                        when (lookup.state) {
                            DeliveryRadiusGeocoder.LookupState.PENDING -> {
                                isNgon = false
                                holdForPinnedAddress = true
                                resultMsg = "📍 ĐANG XÁC ĐỊNH ĐIỂM GIAO → GIỮ ĐƠN"
                            }
                            DeliveryRadiusGeocoder.LookupState.NOT_FOUND -> {
                                isNgon = false
                                swipeNow = true
                                resultMsg = "📍 KHÔNG XÁC ĐỊNH ĐƯỢC ĐIỂM GIAO → BỎ ❌"
                            }
                            DeliveryRadiusGeocoder.LookupState.FOUND -> {
                                val dropOff = lookup.point!!
                                val pin = DeliveryRadiusGeocoder.Point(pinLat, pinLon, "điểm ghim")
                                val distanceFromPin = DeliveryRadiusGeocoder.distanceKm(pin, dropOff)
                                decisionContext += " pinnedRadiusKm=$pinnedRadiusKm pinnedDistanceKm=${String.format(java.util.Locale.US, "%.2f", distanceFromPin)}"
                                if (distanceFromPin > pinnedRadiusKm) {
                                    isNgon = false
                                    swipeNow = true
                                    resultMsg = "📍 ĐIỂM GIAO CÁCH ${String.format(java.util.Locale.US, "%.1f", distanceFromPin)}km > ${pinnedRadiusKm}km → BỎ"
                                } else {
                                    resultMsg += " | 📍 ${String.format(java.util.Locale.US, "%.1f", distanceFromPin)}km/${pinnedRadiusKm}km"
                                }
                            }
                        }
                    }
                }
            }

            val priceForLog = finalPrice?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "n/a"
            val distanceForLog = finalDistance?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "n/a"
            val decisionReason = (resultMsg.ifBlank { if (isNgon) "PASS" else "REJECT" })
                .replace(Regex("\\s+"), " ")
            val countdownRegex = Regex("\\b\\d+\\s*(s|giay|phut)\\b", RegexOption.IGNORE_CASE)
            val priceKmRegex = Regex("\\b\\d+([.,]\\d+)?\\s*(k|km|d|vnd|vnđ)\\b", RegexOption.IGNORE_CASE)
            val staticSnippet = removeAccents(finalCardText).replace(countdownRegex, "").replace(priceKmRegex, "").replace(Regex("\\s+"), " ").trim().take(30)
            val decisionKey = "$currentMode|$priceForLog|$distanceForLog|$staticSnippet"
            logDecisionOnce(
                decisionKey,
                "ORDER_DECISION mode=$currentMode priceK=$priceForLog distanceKm=$distanceForLog eligible=$isNgon $decisionContext reason=$decisionReason key=$decisionKey"
            )


            if (!selectBestOrderEnabled) {
                if (isNgon) {
                    lastRejectedFingerprint = ""
                    rejectedRepeatCount = 0
                    isWaitingToSwipe = false
                    performAcceptOrder(rootNode, targetRect, savedAcceptNode, decisionKey, finalCardText, allText, finalPrice, finalDistance, swipeFingerprint)
                } else {
                    try { savedAcceptNode?.recycle() } catch (_: Exception) {}
                    savedAcceptNode = null
                    isWaitingToSwipe = false
                    if (holdForPinnedAddress) {
                        pausePollingUntil = System.currentTimeMillis() + 750L
                    } else {
                        if (decisionKey == lastRejectedFingerprint) {
                            rejectedRepeatCount++
                        } else {
                            lastRejectedFingerprint = decisionKey
                            rejectedRepeatCount = 1
                        }

                        // Nếu thẻ không đạt yêu cầu này đã vuốt >= 2 lần mà vẫn còn trên màn hình
                        // -> Đã đến cuối danh sách đơn (hết đơn để vuốt)!
                        // -> TỰ ĐỘNG BẤM LOAD (TẢI LẠI TRANG) NGAY LẬP TỨC ĐỂ LẤY MẺ ĐƠN MỚI!
                        if (rejectedRepeatCount >= 2) {
                            Log.i("HK_BENCHMARK", "END_OF_DECK_REACHED in normal mode (card repeated $rejectedRepeatCount times). Auto tapping LOAD!")
                            lastRejectedFingerprint = ""
                            rejectedRepeatCount = 0
                            resetBestOrderState()
                            clickTaiLaiTrangOrTapRefresh(rootNode)
                            pausePollingUntil = System.currentTimeMillis() + 400L
                            return
                        }

                        // Đơn dở → Lướt ngay bằng cử chỉ Target 1 -> Target 2 (ngón tay thật như bản 03-10)
                        val totalTurnMs = if (currentScanStartTime > 0L) android.os.SystemClock.uptimeMillis() - currentScanStartTime else 0L
                        Log.i("HK_BENCHMARK", "DECISION_SKIP_SWIPE totalTurnMs=${totalTurnMs}ms price=$finalPrice dist=$finalDistance repeat=$rejectedRepeatCount")

                        val swipeDuration = swipeByTargets()
                        val retryDelayMs = if (swipeDuration > 0L) {
                            lastSwipedCardFingerprint = swipeFingerprint
                            lastSwipedCardExpiresAt = System.currentTimeMillis() + swipeDuration + 20L
                            swipeDuration + SWIPE_SETTLE_DELAY_MS
                        } else {
                            accessibilityReadIntervalMs().coerceAtLeast(15L)
                        }
                        pausePollingUntil = System.currentTimeMillis() + retryDelayMs
                    }
                }
            } else {
                // ===== TÍNH NĂNG: QUAY LẠI CHỌN ĐƠN NGON NHẤT =====
                if (holdForPinnedAddress) {
                    pausePollingUntil = System.currentTimeMillis() + 750L
                    return
                }

                val currentFingerprint = decisionKey

                if (bestOrderState == BestOrderState.REVERSING) {
                    val p = finalPrice ?: 0.0
                    val d = finalDistance ?: 0.0
                    val cardPoints = extractShopeePoints(finalCardText)

                    val priceMatch = Math.abs(p - bestOrderTargetPrice) < 0.20
                    val distMatch = Math.abs(d - bestOrderTargetDistance) < 0.20
                    val countMatch = (detectedOrderCount == bestOrderTargetCount || bestOrderTargetCount == 0 || detectedOrderCount == 0)
                    val pointsMatch = (bestOrderTargetPoints == 0 || cardPoints == bestOrderTargetPoints || cardPoints == 0)
                    val fingerprintMatch = (currentFingerprint == bestOrderTargetFingerprint)

                    val isTargetMatch = (priceMatch && distMatch && countMatch) || fingerprintMatch

                    if (isTargetMatch) {
                        Log.i("HK_BENCHMARK", "BEST_ORDER_TARGET_REACHED price=$p dist=$d count=$detectedOrderCount pts=$cardPoints in $bestOrderReverseAttempts reverse swipes! ACCEPTING NOW!")
                        // Không clear scannedDeck lúc này để dự phòng tìm đơn ngon thứ 2 nếu hụt đơn
                        bestOrderState = BestOrderState.IDLE
                        if (bestOrderAttemptCount == 0) bestOrderAttemptCount = 1

                        performAcceptOrder(rootNode, targetRect, savedAcceptNode, decisionKey, finalCardText, allText, finalPrice, finalDistance, swipeFingerprint)
                        return
                    } else {
                        bestOrderReverseAttempts++
                        if (bestOrderReverseAttempts > 10) {
                            Log.w("HK_BENCHMARK", "BEST_ORDER_REVERSE_TIMEOUT after 10 reverse swipes. Đơn đã bị lấy hoặc văng trang. Reset và bấm Load!")
                            resetBestOrderState()
                            clickTaiLaiTrangOrTapRefresh(rootNode)
                            return
                        } else {
                            val swipeDuration = dispatchReverseSwipe()
                            val retryDelayMs = if (swipeDuration > 0L) {
                                lastSwipedCardFingerprint = swipeFingerprint
                                lastSwipedCardExpiresAt = System.currentTimeMillis() + swipeDuration + 20L
                                swipeDuration + SWIPE_SETTLE_DELAY_MS
                            } else {
                                accessibilityReadIntervalMs().coerceAtLeast(15L)
                            }
                            pausePollingUntil = System.currentTimeMillis() + retryDelayMs
                            return
                        }
                    }
                }

                if (bestOrderState == BestOrderState.IDLE) {
                    bestOrderState = BestOrderState.SCANNING
                    scannedDeck.clear()
                    seenFingerprints.clear()
                    scanSingleCardRetryCount = 0
                }

                val alreadySeen = seenFingerprints.contains(currentFingerprint)
                if (!alreadySeen) {
                    seenFingerprints.add(currentFingerprint)
                    scanSingleCardRetryCount = 0

                    val p = finalPrice ?: 0.0
                    val d = finalDistance ?: 0.0
                    val ratio = if (d > 0.0) p / d else 0.0
                    val score = if (isTienChuyenMatch) ratio + 10000.0 else ratio
                    val cardPoints = extractShopeePoints(finalCardText)

                    scannedDeck.add(
                        ScannedOrder(
                            fingerprint = currentFingerprint,
                            price = p,
                            distance = d,
                            isTienChuyen = isTienChuyenMatch,
                            isEligible = isNgon,
                            score = score,
                            cardText = finalCardText,
                            orderCount = detectedOrderCount,
                            points = cardPoints
                        )
                    )
                    Log.i("HK_BENCHMARK", "BEST_ORDER_SCANNED_CARD #${scannedDeck.size}: p=$p d=$d ratio=${String.format(java.util.Locale.US, "%.2f", ratio)} orderCount=$detectedOrderCount pts=$cardPoints isTc=$isTienChuyenMatch eligible=$isNgon key=$currentFingerprint")
                } else {
                    scanSingleCardRetryCount++
                }

                // Nếu gặp vòng lặp lướt quá nhiều lần trên cùng 1 thẻ (>= 3 lần) hoặc đã quét quá 20 thẻ -> Coi như đã hết trang
                if (scanSingleCardRetryCount >= 3 || scannedDeck.size >= 20) {
                    Log.i("HK_BENCHMARK", "BEST_ORDER_DECK_LIMIT: Reached deck limit or loop (retryCount=$scanSingleCardRetryCount, scanned=${scannedDeck.size}). Choosing best order now!")
                    val eligibleOrders = getSortedEligibleOrders()
                    if (eligibleOrders.isEmpty()) {
                        Log.i("HK_BENCHMARK", "BEST_ORDER_COMPLETE: No eligible orders in deck (${scannedDeck.size} cards). Resetting and pressing Load!")
                        resetBestOrderState()
                        clickTaiLaiTrangOrTapRefresh(rootNode)
                        return
                    }

                    val bestOrder = eligibleOrders.first()
                    val ratio = if (bestOrder.distance > 0.0) bestOrder.price / bestOrder.distance else 0.0
                    Log.i("HK_BENCHMARK", "BEST_ORDER_CHOSEN winner=${bestOrder.fingerprint} count=${bestOrder.orderCount} pts=${bestOrder.points} price=${bestOrder.price} dist=${bestOrder.distance} ratio=${String.format(java.util.Locale.US, "%.2f", ratio)} isTc=${bestOrder.isTienChuyen} totalScanned=${scannedDeck.size}")

                    val pNow = finalPrice ?: 0.0
                    val dNow = finalDistance ?: 0.0
                    val ptsNow = extractShopeePoints(finalCardText)
                    val priceMatch = Math.abs(pNow - bestOrder.price) < 0.20
                    val distMatch = Math.abs(dNow - bestOrder.distance) < 0.20
                    val countMatch = (detectedOrderCount == bestOrder.orderCount || bestOrder.orderCount == 0 || detectedOrderCount == 0)
                    val pointsMatch = (bestOrder.points == 0 || ptsNow == bestOrder.points || ptsNow == 0)
                    val fingerprintMatch = (currentFingerprint == bestOrder.fingerprint)

                    if ((priceMatch && distMatch && countMatch) || fingerprintMatch) {
                        Log.i("HK_BENCHMARK", "BEST_ORDER_WINNER_ALREADY_ON_SCREEN! ACCEPTING IMMEDIATELY!")
                        // Không clear scannedDeck lúc này để dự phòng tìm đơn ngon thứ 2 nếu hụt đơn
                        bestOrderState = BestOrderState.IDLE
                        bestOrderAttemptCount = 1
                        bestOrderTargetFingerprint = bestOrder.fingerprint
                        bestOrderTargetPrice = bestOrder.price
                        bestOrderTargetDistance = bestOrder.distance
                        bestOrderTargetPoints = bestOrder.points
                        bestOrderTargetCount = bestOrder.orderCount

                        performAcceptOrder(rootNode, targetRect, savedAcceptNode, decisionKey, finalCardText, allText, finalPrice, finalDistance, swipeFingerprint)
                        return
                    } else {
                        Log.i("HK_BENCHMARK", "BEST_ORDER_START_REVERSING target=${bestOrder.fingerprint} price=${bestOrder.price} dist=${bestOrder.distance} pts=${bestOrder.points} from current=$currentFingerprint")
                        bestOrderState = BestOrderState.REVERSING
                        bestOrderTargetFingerprint = bestOrder.fingerprint
                        bestOrderTargetPrice = bestOrder.price
                        bestOrderTargetDistance = bestOrder.distance
                        bestOrderTargetPoints = bestOrder.points
                        bestOrderTargetCount = bestOrder.orderCount
                        bestOrderReverseAttempts = 0
                        bestOrderAttemptCount = 1

                        val swipeDuration = dispatchReverseSwipe()
                        val retryDelayMs = if (swipeDuration > 0L) {
                            lastSwipedCardFingerprint = swipeFingerprint
                            lastSwipedCardExpiresAt = System.currentTimeMillis() + swipeDuration + 20L
                            swipeDuration + SWIPE_SETTLE_DELAY_MS
                        } else {
                            accessibilityReadIntervalMs().coerceAtLeast(15L)
                        }
                        pausePollingUntil = System.currentTimeMillis() + retryDelayMs
                        return
                    }
                }

                // Tiếp tục lướt tới các thẻ tiếp theo cho tới trang cuối ("Không còn đơn hàng nào")
                val swipeDuration = swipeByTargets()
                val retryDelayMs = if (swipeDuration > 0L) {
                    lastSwipedCardFingerprint = swipeFingerprint
                    lastSwipedCardExpiresAt = System.currentTimeMillis() + swipeDuration + 20L
                    swipeDuration + SWIPE_SETTLE_DELAY_MS
                } else {
                    accessibilityReadIntervalMs().coerceAtLeast(15L)
                }
                pausePollingUntil = System.currentTimeMillis() + retryDelayMs
                return
            }

            emptyScreenStartTime = 0L // Reset thời gian trống vì đã thấy đơn

        } else {
            try { savedAcceptNode?.recycle() } catch (_: Exception) {}
            savedAcceptNode = null
            isWaitingToSwipe = false

            val lowerFullText = removeAccents(currentAllText.lowercase())
            val isStateB = lowerFullText.contains("free-pick") || lowerFullText.contains("khong co don") || lowerFullText.contains("hien dang khong co don")
            val isStateA = lowerFullText.contains("khong con don") || lowerFullText.contains("tai lai trang") || (lowerFullText.contains("tai lai") && !lowerFullText.contains("bi quyet"))

            if (isStateB) {
                // Trạng thái B: Khu vực hoàn toàn không có đơn free-pick
                // TUYỆT ĐỐI KHÔNG CLICK VÀO LINK "Xem bí quyết...", CHỈ BẤM HỒNG TÂM NÚT LOAD
                Log.i("HK_BENCHMARK", "EMPTY_STATE_B_FULLTEXT: Khong co don free-pick -> Chi bam nut Load Target")
                resetBestOrderState()
                tapRefreshTarget(force = false)
                pausePollingUntil = System.currentTimeMillis() + 400L
            } else if (isStateA) {
                // Trạng thái A: Đã vuốt hết các đơn trong danh sách
                Log.i("HK_BENCHMARK", "EMPTY_STATE_A_FULLTEXT: Khong con don hang nao")
                if (selectBestOrderEnabled) {
                    handleEndOfDeckOrEmptyScreen(rootNode)
                } else {
                    resetBestOrderState()
                    clickTaiLaiTrangOrTapRefresh(rootNode)
                }
                pausePollingUntil = System.currentTimeMillis() + 400L
            } else if (selectBestOrderEnabled && bestOrderState == BestOrderState.REVERSING) {
                handleEndOfDeckOrEmptyScreen(rootNode)
            } else {
                // Màn hình trống hoàn toàn không có đơn (idle) -> Tự động bấm Load định kỳ mỗi 500ms
                val now = System.currentTimeMillis()
                if (emptyScreenStartTime == 0L) {
                    emptyScreenStartTime = now
                } else if (now - emptyScreenStartTime >= 500L && now - lastRefreshTime >= 500L) {
                    Log.i("HK_BENCHMARK", "EMPTY_SCREEN_IDLE (500ms khong co don) -> Tu dong bam nut Load")
                    emptyScreenStartTime = now
                    tapRefreshTarget(force = false)
                    pausePollingUntil = now + 400L
                }
            }
        }
    }

    private fun getSortedEligibleOrders(): List<ScannedOrder> {
        val eligible = scannedDeck.filter { it.isEligible && !it.isFailed }
        return eligible.sortedWith(
            compareByDescending<ScannedOrder> { it.isTienChuyen }
                .thenByDescending { it.orderCount }
                .thenByDescending { it.points }
                .thenByDescending { if (it.distance > 0.0) it.price / it.distance else 0.0 }
                .thenByDescending { it.price }
        )
    }

    private fun resetBestOrderState() {
        bestOrderState = BestOrderState.IDLE
        scannedDeck.clear()
        seenFingerprints.clear()
        bestOrderTargetFingerprint = null
        bestOrderTargetPrice = 0.0
        bestOrderTargetDistance = 0.0
        bestOrderTargetPoints = 0
        bestOrderTargetCount = 0
        bestOrderReverseAttempts = 0
        scanSingleCardRetryCount = 0
        bestOrderAttemptCount = 0
    }

    private fun handleEndOfDeckOrEmptyScreen(rootNode: AccessibilityNodeInfo? = null) {
        if (selectBestOrderEnabled && bestOrderState == BestOrderState.SCANNING && scannedDeck.isNotEmpty()) {
            // ĐÃ LƯỚT TỚI TRANG CUỐI CÙNG ("Không còn đơn hàng nào. Vui lòng tải lại trang để xem đơn mới.")!
            Log.i("HK_BENCHMARK", "BEST_ORDER_REACHED_LAST_PAGE: Đã lướt tới trang cuối! Tổng số thẻ đã quét: ${scannedDeck.size}")

            val eligibleOrders = getSortedEligibleOrders()
            if (eligibleOrders.isEmpty()) {
                Log.i("HK_BENCHMARK", "BEST_ORDER_END_OF_DECK: Không có đơn nào đạt tiêu chuẩn trong ${scannedDeck.size} thẻ đã quét. Bấm tải lại trang!")
                resetBestOrderState()
                clickTaiLaiTrangOrTapRefresh(rootNode)
            } else {
                // SO SÁNH RIÊNG THEO TỪNG CẤP ĐƠN (1-1, 2-2, 3-3, 4-4, 5-5...):
                // 1. Tiện chuyến luôn ưu tiên số 1 (nếu có)
                // 2. Ưu tiên chùm nhiều đơn (8 > 7 > 6 > 5 > 4 > 3 > 2 > 1)
                // 3. Trong cùng nhóm chùm đơn (1-1, 2-2, 3-3, 4-4, 5-5): ĐIỂM THƯỞNG CAO HƠN THÌ NHẬN (it.points)
                // 4. Tỉ lệ Cước/Km cao hơn thắng (price / distance)
                // 5. Nếu bằng: ĐÚNG GIÁ SET VÀ GIÁ CAO HƠN THÌ NHẬN (it.price)
                val bestOrder = eligibleOrders.first()
                val ratio = if (bestOrder.distance > 0.0) bestOrder.price / bestOrder.distance else 0.0
                Log.i("HK_BENCHMARK", "BEST_ORDER_CHOSEN winner=${bestOrder.fingerprint} count=${bestOrder.orderCount} pts=${bestOrder.points} price=${bestOrder.price} dist=${bestOrder.distance} ratio=${String.format(java.util.Locale.US, "%.2f", ratio)} isTc=${bestOrder.isTienChuyen} totalScanned=${scannedDeck.size}")

                Log.i("HK_BENCHMARK", "BEST_ORDER_START_REVERSING from LAST PAGE to target price=${bestOrder.price} dist=${bestOrder.distance} pts=${bestOrder.points} count=${bestOrder.orderCount}")
                bestOrderState = BestOrderState.REVERSING
                bestOrderTargetFingerprint = bestOrder.fingerprint
                bestOrderTargetPrice = bestOrder.price
                bestOrderTargetDistance = bestOrder.distance
                bestOrderTargetPoints = bestOrder.points
                bestOrderTargetCount = bestOrder.orderCount
                bestOrderReverseAttempts = 0
                bestOrderAttemptCount = 1

                val swipeDuration = dispatchReverseSwipe()
                val retryDelayMs = if (swipeDuration > 0L) {
                    swipeDuration + SWIPE_SETTLE_DELAY_MS
                } else {
                    accessibilityReadIntervalMs().coerceAtLeast(15L)
                }
                pausePollingUntil = System.currentTimeMillis() + retryDelayMs
            }
        } else if (selectBestOrderEnabled && bestOrderState == BestOrderState.REVERSING) {
            bestOrderReverseAttempts++
            if (bestOrderReverseAttempts > 10) {
                Log.w("HK_BENCHMARK", "BEST_ORDER_REVERSE_TIMEOUT from empty screen after 10 reverse swipes. Resetting and refreshing.")
                resetBestOrderState()
                clickTaiLaiTrangOrTapRefresh(rootNode)
            } else {
                val swipeDuration = dispatchReverseSwipe()
                val retryDelayMs = if (swipeDuration > 0L) {
                    swipeDuration + SWIPE_SETTLE_DELAY_MS
                } else {
                    accessibilityReadIntervalMs().coerceAtLeast(15L)
                }
                pausePollingUntil = System.currentTimeMillis() + retryDelayMs
            }
        } else {
            resetBestOrderState()
            clickTaiLaiTrangOrTapRefresh(rootNode)
        }
    }

    private fun clickTaiLaiTrangOrTapRefresh(rootNode: AccessibilityNodeInfo?) {
        var clicked = false
        if (rootNode != null) {
            val searchLabels = listOf("Tải lại trang", "Tải lại", "tai lai trang", "tai lai")
            for (lbl in searchLabels) {
                val nodes = try { rootNode.findAccessibilityNodeInfosByText(lbl) } catch (_: Exception) { null }
                if (!nodes.isNullOrEmpty()) {
                    for (n in nodes) {
                        val txt = (n.text?.toString() ?: n.contentDescription?.toString()).orEmpty()
                        val lower = removeAccents(txt.lowercase())
                        // TUYỆT ĐỐI KHÔNG CLICK VÀO "Xem bí quyết..." hay link web
                        if (lower.contains("bi quyet") || lower.contains("ti le")) {
                            try { n.recycle() } catch (_: Exception) {}
                            continue
                        }
                        if (lower.contains("tai lai")) {
                            val clickable = getClickableNode(n) ?: n
                            clicked = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            if (!clicked) {
                                val bounds = android.graphics.Rect()
                                n.getBoundsInScreen(bounds)
                                if (!bounds.isEmpty && bounds.centerX() > 0 && bounds.centerY() > 0) {
                                    showTouchIndicator(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                                    val path = Path().apply { moveTo(bounds.centerX().toFloat(), bounds.centerY().toFloat()) }
                                    val gesture = GestureDescription.Builder()
                                        .addStroke(GestureDescription.StrokeDescription(path, 0, 40)).build()
                                    clicked = dispatchGesture(gesture, null, null)
                                }
                            }
                            Log.i("HK_BENCHMARK", "CLICK_TAI_LAI_TRANG success=$clicked")
                        }
                        try { n.recycle() } catch (_: Exception) {}
                        if (clicked) break
                    }
                }
                if (clicked) break
            }
        }
        if (clicked) {
            lastOrderDetectedTime = System.currentTimeMillis() + 6000L
            lastSwipedCardFingerprint = ""
            lastSwipedCardExpiresAt = 0L
            emptyScreenStartTime = 0L
            lastRejectedFingerprint = ""
            rejectedRepeatCount = 0
            pausePollingUntil = System.currentTimeMillis() + 350L
            resetFreePickConfirmation()
            lastAcceptedTime = 0L
        }
        // LUÔN LUÔN BẤM HỒNG TÂM LOAD (targetRefresh - nút tròn 2 mũi tên xoay mép trái)
        tapRefreshTarget(force = false)
    }

    private fun tapRefreshTarget(force: Boolean = false) {
        if (!isDriverAppForeground()) return

        // Rate-limit Load while continuing to scan for newly loaded orders.
        val now = System.currentTimeMillis()
        if (!force && now - lastRefreshTime < 400L) return
        lastRefreshTime = now
        resetFreePickConfirmation()
        lastAcceptedTime = 0L

        val x: Float
        val y: Float
        val target = targetRefresh
        if (target != null) {
            x = target.getCenterX()
            y = target.getCenterY()
        } else {
            val prefs = getSharedPreferences("AutoPickPrefs", Context.MODE_PRIVATE)
            val metrics = resources.displayMetrics
            x = prefs.getInt("x_ref_v2", (metrics.widthPixels * 0.15f).toInt()).toFloat()
            y = prefs.getInt("y_ref_v2", (metrics.heightPixels * 0.45f).toInt()).toFloat()
        }

        showTouchIndicator(x, y)
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 40)).build()

        // ===== SAU KHI BẤM LOAD: NẾU CÓ ĐƠN LÀ LỰA LIỀN =====
        // 1. Kích hoạt chế độ tác chiến 1ms siêu tốc trong 6 giây tiếp theo
        lastOrderDetectedTime = now + 6000L
        // 2. Xóa sạch cờ ghi nhớ đơn cũ để lập tức chấp nhận và phân tích thẻ đơn mới vừa load
        lastSwipedCardFingerprint = ""
        lastSwipedCardExpiresAt = 0L
        emptyScreenStartTime = 0L
        lastRejectedFingerprint = ""
        rejectedRepeatCount = 0
        pausePollingUntil = now + 350L

        // 3. Quét ngay khi cử chỉ bấm nút Load vừa hoàn tất (20ms)
        dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (isRunning && currentState == AppState.RUNNING) {
                    doAccessibilityRead()
                }
            }
        }, null)
    }



    private fun swipeByTargets(): Long {
        if (!verifyAppIntegrity(this)) return 0L
        if (!isDriverAppForeground()) return 0L
        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels.toFloat()
        val screenHeight = metrics.heightPixels.toFloat()

        val now = android.os.SystemClock.uptimeMillis()
        val interval = if (lastSwipeTimeUptimeMs > 0L) now - lastSwipeTimeUptimeMs else 0L
        lastSwipeTimeUptimeMs = now
        swipeCount++
        lastOrderDetectedTime = System.currentTimeMillis() + 5000L

        val startX: Float
        val startY: Float
        val endX: Float
        val endY: Float

        val arrow = targetSwipeArrow
        if (arrow != null) {
            startX = arrow.getStartX()
            startY = arrow.getStartY()
            endX = arrow.getEndX()
            endY = arrow.getEndY()
        } else {
            val prefs = getSharedPreferences("AutoPickPrefs", Context.MODE_PRIVATE)
            val fallbackStartX = prefs.getInt("x_sw1_v2", (screenWidth * 0.85f).toInt()).toFloat()
            val fallbackEndX = prefs.getInt("x_sw2_v2", (screenWidth * 0.15f).toInt()).toFloat()
            val fallbackY = prefs.getInt("y_sw1_v2", (screenHeight * 0.65f).toInt()).toFloat()
            startX = fallbackStartX
            startY = fallbackY
            endX = fallbackEndX
            endY = fallbackY
        }
        
        showTouchIndicator(startX, startY)
        showTouchIndicator(endX, endY)

        // ===== CƠ CHẾ FLING BOOSTER NGUYÊN BẢN 03-10 =====
        // Khuếch đại véc-tơ vuốt tạo trớn cực mạnh kéo dài ra mép màn hình
        var deltaX = endX - startX
        var deltaY = endY - startY
        val distance = kotlin.math.hypot(deltaX.toDouble(), deltaY.toDouble()).toFloat()

        if (distance < 10f) {
            deltaX = if (startX > screenWidth / 2f) -screenWidth * 0.75f else screenWidth * 0.75f
            deltaY = 0f
        } else if (distance < 400f) {
            val scale = 500f / distance
            deltaX *= scale
            deltaY *= scale
        } else {
            deltaX *= 2f
            deltaY *= 2f
        }

        val adjustedEndX = (startX + deltaX).coerceIn(0f, screenWidth)
        val adjustedEndY = (startY + deltaY).coerceIn(0f, screenHeight)

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(adjustedEndX, adjustedEndY)
        }

        // Tốc độ vuốt cử chỉ: Cho phép từ 1ms đến 500ms theo đúng cài đặt trên app!
        val swipeDuration = swipeDurationMs.coerceIn(1L, 500L)
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, swipeDuration)).build()

        val dispatched = dispatchGesture(gesture, null, null)
        Log.i("HK_BENCHMARK", "DISPATCH_SWIPE #$swipeCount interval=${interval}ms duration=${swipeDuration}ms speedLevel=$speedLevel queued=$dispatched start=($startX,$startY) end=($adjustedEndX,$adjustedEndY)")
        return if (dispatched) swipeDuration else 0L
    }

    private fun dispatchReverseSwipe(): Long {
        if (!verifyAppIntegrity(this)) return 0L
        if (!isDriverAppForeground()) return 0L
        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels.toFloat()
        val screenHeight = metrics.heightPixels.toFloat()

        val now = android.os.SystemClock.uptimeMillis()
        val interval = if (lastSwipeTimeUptimeMs > 0L) now - lastSwipeTimeUptimeMs else 0L
        lastSwipeTimeUptimeMs = now
        lastOrderDetectedTime = System.currentTimeMillis() + 5000L

        val startX: Float
        val startY: Float
        val endX: Float
        val endY: Float

        val arrow = targetSwipeArrow
        if (arrow != null) {
            startX = arrow.getEndX()
            startY = arrow.getEndY()
            endX = arrow.getStartX()
            endY = arrow.getStartY()
        } else {
            val prefs = getSharedPreferences("AutoPickPrefs", Context.MODE_PRIVATE)
            val fallbackStartX = prefs.getInt("x_sw2_v2", (screenWidth * 0.15f).toInt()).toFloat()
            val fallbackEndX = prefs.getInt("x_sw1_v2", (screenWidth * 0.85f).toInt()).toFloat()
            val fallbackY = prefs.getInt("y_sw1_v2", (screenHeight * 0.65f).toInt()).toFloat()
            startX = fallbackStartX
            startY = fallbackY
            endX = fallbackEndX
            endY = fallbackY
        }

        showTouchIndicator(startX, startY)
        showTouchIndicator(endX, endY)

        var deltaX = endX - startX
        var deltaY = endY - startY
        val distance = kotlin.math.hypot(deltaX.toDouble(), deltaY.toDouble()).toFloat()

        if (distance < 10f) {
            deltaX = if (startX < screenWidth / 2f) screenWidth * 0.75f else -screenWidth * 0.75f
            deltaY = 0f
        } else if (distance < 400f) {
            val scale = 500f / distance
            deltaX *= scale
            deltaY *= scale
        } else {
            deltaX *= 2f
            deltaY *= 2f
        }

        val adjustedEndX = (startX + deltaX).coerceIn(0f, screenWidth)
        val adjustedEndY = (startY + deltaY).coerceIn(0f, screenHeight)

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(adjustedEndX, adjustedEndY)
        }

        val swipeDuration = swipeDurationMs.coerceIn(1L, 500L)
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, swipeDuration)).build()

        val dispatched = dispatchGesture(gesture, null, null)
        Log.i("HK_BENCHMARK", "DISPATCH_REVERSE_SWIPE interval=${interval}ms duration=${swipeDuration}ms speedLevel=$speedLevel queued=$dispatched start=($startX,$startY) end=($adjustedEndX,$adjustedEndY)")
        return if (dispatched) swipeDuration else 0L
    }

    private fun performAcceptOrder(
        rootNode: AccessibilityNodeInfo?,
        targetRect: android.graphics.Rect?,
        savedAcceptNodeParam: AccessibilityNodeInfo?,
        decisionKey: String,
        finalCardText: String,
        allText: String,
        finalPrice: Double?,
        finalDistance: Double?,
        swipeFingerprint: String
    ) {
        var savedAcceptNode = savedAcceptNodeParam
        val now = System.currentTimeMillis()

        if (!verifyAppIntegrity(this)) {
            Log.e("HK_BENCHMARK", "ABORT_ACCEPT: Integrity check failed!")
            return
        }

        // Tuyệt đối không bấm nhận nếu thẻ đơn là đơn ký quỹ / nạp tiền (thay nút nhận đơn bằng thanh cam)
        if (isDepositOrder(finalCardText) || isDepositOrder(allText)) {
            Log.w("HK_BENCHMARK", "ABORT_ACCEPT: Phat hien don ky quy / can nap tien! Bo qua, khong click thanh cam.")
            try { savedAcceptNode?.recycle() } catch (_: Exception) {}
            savedAcceptNode = null
            val swipeDuration = swipeByTargets()
            val retryDelayMs = if (swipeDuration > 0L) {
                lastSwipedCardFingerprint = swipeFingerprint
                lastSwipedCardExpiresAt = System.currentTimeMillis() + swipeDuration + 20L
                swipeDuration + SWIPE_SETTLE_DELAY_MS
            } else {
                accessibilityReadIntervalMs().coerceAtLeast(15L)
            }
            pausePollingUntil = System.currentTimeMillis() + retryDelayMs
            return
        }

        // Khóa không cho bấm nhận đơn mới trong 3.5 giây để chờ chốt đơn và Load xong!
        // Chỉ áp dụng khi KHÔNG đang chờ popup xác nhận
        if (!awaitingPopupConfirmation && now - lastAcceptedTime < 3500L) {
            return
        }

        var delayMs = 0L
        if (maxRandomDelayMs > 0) {
            val minRandom = (maxRandomDelayMs / 2).toLong()
            val maxRandom = maxRandomDelayMs.toLong()
            delayMs += minRandom + (Math.random() * (maxRandom - minRandom + 1)).toLong()
        }

        val totalTurnMs = if (currentScanStartTime > 0L) android.os.SystemClock.uptimeMillis() - currentScanStartTime else 0L
        Log.i("HK_BENCHMARK", "DECISION_ACCEPT_ORDER totalTurnMs=${totalTurnMs}ms price=$finalPrice dist=$finalDistance")
        lastSwipedCardFingerprint = ""
        lastSwipedCardExpiresAt = 0L

        // Khóa chống bấm lặp cùng một đơn trong vòng 5 giây: Lướt bỏ để không kẹt màn hình
        if ((recentlyAcceptedKeys[decisionKey] ?: 0L) > now) {
            Log.i("HK_BENCHMARK", "SUPPRESSED_REPEAT_ACCEPT key=$decisionKey -> Lướt bỏ chuyển thẻ")
            try { savedAcceptNode?.recycle() } catch (_: Exception) {}
            savedAcceptNode = null
            val swipeDuration = swipeByTargets()
            val retryDelayMs = if (swipeDuration > 0L) {
                lastSwipedCardFingerprint = swipeFingerprint
                lastSwipedCardExpiresAt = System.currentTimeMillis() + swipeDuration + 20L
                swipeDuration + SWIPE_SETTLE_DELAY_MS
            } else {
                accessibilityReadIntervalMs().coerceAtLeast(15L)
            }
            pausePollingUntil = System.currentTimeMillis() + retryDelayMs
            return
        }

        // BƯỚC 1: BẤM NÚT "NHẬN ĐƠN" TRÊN THẺ ĐƠN
        var acceptNodeToClick: AccessibilityNodeInfo? = savedAcceptNode

        if (acceptNodeToClick == null || !acceptNodeToClick.refresh() || !isRealAcceptButton(acceptNodeToClick)) {
            acceptNodeToClick = null
            val searchLabels = listOf("Nhận đơn", "NHẬN ĐƠN", "Lấy đơn", "Nhận")
            for (l in searchLabels) {
                val foundNodes = rootNode?.findAccessibilityNodeInfosByText(l)
                if (!foundNodes.isNullOrEmpty()) {
                    acceptNodeToClick = foundNodes.firstOrNull { isRealAcceptButton(it) }
                    for (fn in foundNodes) {
                        if (fn !== acceptNodeToClick) {
                            try { fn.recycle() } catch (_: Exception) {}
                        }
                    }
                    if (acceptNodeToClick != null) break
                }
            }
        }

        var acceptSuccess = false
        if (acceptNodeToClick != null) {
            val clickable = getClickableNode(acceptNodeToClick) ?: acceptNodeToClick
            if (delayMs > 0) {
                android.os.SystemClock.sleep(delayMs)
            }

            val bounds = android.graphics.Rect()
            clickable.getBoundsInScreen(bounds)
            if (bounds.isEmpty) acceptNodeToClick.getBoundsInScreen(bounds)

            val actionClicked = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)

            var gestureTapped = false
            if (!bounds.isEmpty && bounds.centerX() > 0 && bounds.centerY() > 0) {
                showTouchIndicator(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                val path = Path().apply { moveTo(bounds.centerX().toFloat(), bounds.centerY().toFloat()) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build()
                dispatchGesture(gesture, null, null)
                gestureTapped = true
            }

            if (actionClicked || gestureTapped) {
                acceptSuccess = true
            }

            if (clickable !== acceptNodeToClick) {
                try { clickable.recycle() } catch (_: Exception) {}
            }
        } else if (targetRect != null && !targetRect.isEmpty) {
            val rx = targetRect.centerX().toFloat()
            val ry = targetRect.centerY().toFloat()
            showTouchIndicator(rx, ry)
            val path = Path().apply { moveTo(rx, ry) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build()
            dispatchGesture(gesture, null, null)
            acceptSuccess = true
            Log.i("HK_BENCHMARK", "ACCEPT_CLICK_FALLBACK_TARGETRECT at ($rx, $ry)")
        } else {
            val metrics = resources.displayMetrics
            val fx = metrics.widthPixels * 0.5f
            val fy = metrics.heightPixels * 0.83f
            showTouchIndicator(fx, fy)
            val path = Path().apply { moveTo(fx, fy) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build()
            dispatchGesture(gesture, null, null)
            acceptSuccess = true
            Log.i("HK_BENCHMARK", "ACCEPT_CLICK_FALLBACK_DEFAULT_BOTTOM at ($fx, $fy)")
        }

        try { savedAcceptNode?.recycle() } catch (_: Exception) {}
        savedAcceptNode = null

        if (acceptSuccess) {
            awaitingPopupConfirmation = true
            firstAcceptClickTime = System.currentTimeMillis()
            pendingDecisionKey = decisionKey
            lastAttemptedCardText = finalCardText
            flashScreenSuccess()
            playAlertSound()
            Log.i("HK_BENCHMARK", "FIRST_CLICK_ACCEPT_DONE mode=$pickMode key=$decisionKey. Chờ bấm popup lần 2...")
            logDecisionOnce("click_ok|$decisionKey", "ORDER_CLICK stage=first_card_click mode=$pickMode key=$decisionKey")

            handler.postDelayed({
                if (isRunning && currentState == AppState.RUNNING) {
                    doAccessibilityRead()
                }
            }, 60L)
            pausePollingUntil = System.currentTimeMillis() + 50L
        } else {
            pausePollingUntil = System.currentTimeMillis() + 300L
        }
    }

    private fun tryFastScrollForward(root: AccessibilityNodeInfo): Boolean {
        fun tryScroll(node: AccessibilityNodeInfo): Boolean {
            try {
                if (node.isScrollable || node.actionList.any { 
                    it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ||
                    (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q && 
                        (it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_RIGHT.id || it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_DOWN.id))
                }) {
                    if (node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        if (node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_RIGHT.id)) return true
                        if (node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_DOWN.id)) return true
                    }
                }
            } catch (_: Exception) {}
            return false
        }

        try {
            if (tryScroll(root)) return true
            for (i in 0 until root.childCount) {
                val child = root.getChild(i) ?: continue
                try {
                    if (tryScroll(child)) return true
                    for (j in 0 until child.childCount) {
                        val grandChild = child.getChild(j) ?: continue
                        try {
                            if (tryScroll(grandChild)) return true
                        } finally {
                            grandChild.recycle()
                        }
                    }
                } finally {
                    child.recycle()
                }
            }
        } catch (_: Exception) {}
        return false
    }

    private fun playAlertSound() {

        try {

            val mediaPlayer = android.media.MediaPlayer.create(this, com.example.hkpickclone.R.raw.alert)

            mediaPlayer.setOnCompletionListener { mp ->

                try { mp.release() } catch(e:Exception){}

            }

            mediaPlayer.start()

            

            // Buộc dừng âm thanh và giải phóng bộ nhớ sau đúng 3 giây

            handler.postDelayed({

                try {

                    if (mediaPlayer.isPlaying) {

                        mediaPlayer.stop()

                    }

                    mediaPlayer.release()

                } catch (e: Exception) {}

            }, 3000)

            

        } catch (e: Exception) {
        }

    }



    private fun showTouchIndicator(x: Float, y: Float) {

        return // Ẩn hiệu ứng bấm

        handler.post {

            try {

                val indicator = View(this)

                val drawable = android.graphics.drawable.GradientDrawable()

                drawable.shape = android.graphics.drawable.GradientDrawable.OVAL

                drawable.setColor(Color.parseColor("#80FF0000"))

                drawable.setStroke(4, Color.RED)

                indicator.background = drawable



                val size = 80

                val params = WindowManager.LayoutParams(

                    size, size,

                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,

                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,

                    PixelFormat.TRANSLUCENT

                )

                params.gravity = Gravity.TOP or Gravity.START

                params.x = (x - size / 2).toInt()

                params.y = (y - size / 2).toInt()



                windowManager?.addView(indicator, params)

                indicator.animate().alpha(0f).scaleX(1.5f).scaleY(1.5f).setDuration(400)

                    .withEndAction { try { windowManager?.removeView(indicator) } catch (e: Exception) {} }.start()

            } catch (e: Exception) {}

        }

    }



    private fun flashScreenSuccess() {

        handler.post {

            try {

                val flashView = View(this)

                flashView.setBackgroundColor(Color.parseColor("#8000FF00")) // Màu xanh lá cây bán trong suốt

                val params = WindowManager.LayoutParams(

                    WindowManager.LayoutParams.MATCH_PARENT,

                    WindowManager.LayoutParams.MATCH_PARENT,

                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,

                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,

                    PixelFormat.TRANSLUCENT

                )

                windowManager?.addView(flashView, params)

                

                // Hiệu ứng chớp nháy

                val animator = android.animation.ObjectAnimator.ofFloat(flashView, "alpha", 0f, 1f, 0f, 1f, 0f, 1f, 0f)

                animator.duration = 2000 // Chớp trong 2 giây

                animator.start()

                

                handler.postDelayed({

                    try { windowManager?.removeView(flashView) } catch (e: Exception) {}

                }, 2100)

            } catch (e: Exception) {}

        }

    }



    private fun getClickableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {

        if (node.isClickable) return node

        var parent = node.parent

        while (parent != null) {

            if (parent.isClickable) return parent

            val nextParent = parent.parent
            parent.recycle()
            parent = nextParent

        }

        return null

    }

    private fun clickSuccessDialogOkButton(rootNode: AccessibilityNodeInfo?): Boolean {
        val candidateRoots = mutableListOf<AccessibilityNodeInfo>()
        rootNode?.let { candidateRoots.add(it) }
        val activeRoot = rootInActiveWindow
        if (activeRoot != null && activeRoot !== rootNode) {
            candidateRoots.add(activeRoot)
        }
        try {
            for (w in windows) {
                val r = w.root ?: continue
                if (!candidateRoots.contains(r)) candidateRoots.add(r)
            }
        } catch (_: Exception) {}

        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

        var clicked = false

        // 1. Kiểm tra ID nút chuẩn AlertDialog và ShopeeFood
        val positiveIds = listOf(
            "android:id/button1", 
            "vn.foody.delivery:id/button1", 
            "vn.foody.delivery:id/btn_ok", 
            "vn.foody.delivery:id/btnOk", 
            "vn.foody.delivery:id/btn_confirm", 
            "vn.foody.delivery:id/btnConfirm"
        )
        for (root in candidateRoots) {
            for (resId in positiveIds) {
                val idNodes = try { root.findAccessibilityNodeInfosByViewId(resId) } catch (_: Exception) { null }
                if (!idNodes.isNullOrEmpty()) {
                    for (node in idNodes) {
                        val bounds = android.graphics.Rect()
                        node.getBoundsInScreen(bounds)
                        if (!bounds.isEmpty && bounds.centerX() > 0 && bounds.centerY() > 0) {
                            val clickable = getClickableNode(node) ?: node
                            val actionClicked = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            showTouchIndicator(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                            val path = Path().apply { moveTo(bounds.centerX().toFloat(), bounds.centerY().toFloat()) }
                            val gesture = GestureDescription.Builder()
                                .addStroke(GestureDescription.StrokeDescription(path, 0, 40)).build()
                            dispatchGesture(gesture, null, null)
                            if (clickable !== node) { try { clickable.recycle() } catch (_: Exception) {} }
                            try { node.recycle() } catch (_: Exception) {}
                            Log.i("HK_BENCHMARK", "SUCCESS_POPUP_OK_CLICKED by ID=$resId bounds=$bounds actionSuccess=$actionClicked")
                            clicked = true
                            break
                        }
                        try { node.recycle() } catch (_: Exception) {}
                    }
                }
                if (clicked) break
            }
            if (clicked) break
        }

        // 2. Tìm theo text "OK", "ok", "Đồng ý"
        if (!clicked) {
            val okLabels = listOf("OK", "ok", "Ok", "Đồng ý", "ĐỒNG Ý", "Xác nhận", "Xong")
            for (root in candidateRoots) {
                for (label in okLabels) {
                    val nodes = root.findAccessibilityNodeInfosByText(label) ?: continue
                    for (node in nodes) {
                        val bounds = android.graphics.Rect()
                        node.getBoundsInScreen(bounds)
                        if (bounds.isEmpty || bounds.width() <= 0 || bounds.height() <= 0) {
                            try { node.recycle() } catch (_: Exception) {}
                            continue
                        }
                        val nodeText = (node.text?.toString() ?: node.contentDescription?.toString())?.trim() ?: ""
                        if (nodeText.equals("OK", ignoreCase = true) || nodeText.equals("Đồng ý", ignoreCase = true) || nodeText.equals("Xác nhận", ignoreCase = true)) {
                            val clickable = getClickableNode(node) ?: node
                            val actionClicked = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            showTouchIndicator(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                            val path = Path().apply { moveTo(bounds.centerX().toFloat(), bounds.centerY().toFloat()) }
                            val gesture = GestureDescription.Builder()
                                .addStroke(GestureDescription.StrokeDescription(path, 0, 40)).build()
                            dispatchGesture(gesture, null, null)
                            if (clickable !== node) { try { clickable.recycle() } catch (_: Exception) {} }
                            try { node.recycle() } catch (_: Exception) {}
                            Log.i("HK_BENCHMARK", "SUCCESS_POPUP_OK_CLICKED by text=$nodeText bounds=$bounds actionSuccess=$actionClicked")
                            clicked = true
                            break
                        }
                        try { node.recycle() } catch (_: Exception) {}
                    }
                    if (clicked) break
                }
                if (clicked) break
            }
        }

        // 3. Fallback cử chỉ chạm chính xác tâm nút OK (50% width, 58.1% height)
        val okX = screenWidth * 0.50f
        val okY = screenHeight * 0.581f
        showTouchIndicator(okX, okY)
        val path = Path().apply { moveTo(okX, okY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 40)).build()
        val dispatched = dispatchGesture(gesture, null, null)
        Log.i("HK_BENCHMARK", "SUCCESS_POPUP_OK_FALLBACK_TAP at ($okX, $okY) dispatched=$dispatched")

        // 4. Nhấn thêm 1 nhịp sau 250ms để bảo đảm popup biến mất
        handler.postDelayed({
            try {
                val curRoot = rootInActiveWindow
                val curText = if (curRoot != null) removeAccents(extractAllText(curRoot).lowercase()) else ""
                if (curText.contains("thanh cong") || curText.contains("lay don free-pick")) {
                    val retryPath = Path().apply { moveTo(okX, okY) }
                    val retryGesture = GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(retryPath, 0, 40)).build()
                    dispatchGesture(retryGesture, null, null)
                    Log.i("HK_BENCHMARK", "SUCCESS_POPUP_OK_RETRY_TAP at ($okX, $okY)")
                }
                curRoot?.recycle()
            } catch (_: Exception) {}
        }, 250L)

        return true
    }

    private fun clickOrderConfirmationPopup(rootNode: AccessibilityNodeInfo?): Boolean {
        val candidateRoots = mutableListOf<AccessibilityNodeInfo>()
        rootNode?.let { candidateRoots.add(it) }
        val activeRoot = rootInActiveWindow
        if (activeRoot != null && activeRoot !== rootNode) {
            candidateRoots.add(activeRoot)
        }
        try {
            for (w in windows) {
                val r = w.root ?: continue
                if (!candidateRoots.contains(r)) candidateRoots.add(r)
            }
        } catch (_: Exception) {}

        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

        // 1. Kiểm tra ID nút xác nhận chuẩn của AlertDialog (android:id/button1 hoặc vn.foody.delivery:id/button1)
        val positiveIds = listOf("android:id/button1", "vn.foody.delivery:id/button1", "vn.foody.delivery:id/btn_confirm", "vn.foody.delivery:id/btnConfirm")
        for (root in candidateRoots) {
            for (resId in positiveIds) {
                val idNodes = try { root.findAccessibilityNodeInfosByViewId(resId) } catch (_: Exception) { null }
                if (!idNodes.isNullOrEmpty()) {
                    for (node in idNodes) {
                        val bounds = android.graphics.Rect()
                        node.getBoundsInScreen(bounds)
                        if (!bounds.isEmpty && bounds.centerX() > 0 && bounds.centerY() > 0) {
                            val clickable = getClickableNode(node) ?: node
                            val actionClicked = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            showTouchIndicator(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                            val path = Path().apply { moveTo(bounds.centerX().toFloat(), bounds.centerY().toFloat()) }
                            val gesture = GestureDescription.Builder()
                                .addStroke(GestureDescription.StrokeDescription(path, 0, 40)).build()
                            dispatchGesture(gesture, null, null)
                            if (clickable !== node) { try { clickable.recycle() } catch (_: Exception) {} }
                            try { node.recycle() } catch (_: Exception) {}
                            Log.i("HK_BENCHMARK", "POPUP_CONFIRMATION_CLICKED by ID=$resId at bounds=$bounds actionSuccess=$actionClicked")
                            return true
                        }
                        try { node.recycle() } catch (_: Exception) {}
                    }
                }
            }
        }

        // 2. Tìm theo text: Ưu tiên tuyệt đối các nút nằm trong vùng Dialog (ở giữa màn hình, từ 35% đến 78% chiều cao)
        val acceptLabels = listOf("Nhận đơn", "NHẬN ĐƠN", "Xác nhận", "XÁC NHẬN", "Đồng ý", "ĐỒNG Ý", "Lấy đơn", "LẤY ĐƠN", "Nhận")
        val candidateButtons = mutableListOf<Pair<AccessibilityNodeInfo, android.graphics.Rect>>()

        for (root in candidateRoots) {
            for (label in acceptLabels) {
                val nodes = root.findAccessibilityNodeInfosByText(label) ?: continue
                for (node in nodes) {
                    val bounds = android.graphics.Rect()
                    node.getBoundsInScreen(bounds)
                    if (bounds.isEmpty || bounds.width() <= 0 || bounds.height() <= 0) {
                        try { node.recycle() } catch (_: Exception) {}
                        continue
                    }
                    val nodeText = (node.text?.toString() ?: node.contentDescription?.toString())?.trim() ?: ""
                    val normalized = removeAccents(nodeText).lowercase()
                    if (normalized.contains("tu choi") || normalized.contains("huy") || 
                        (normalized.contains("dong") && !normalized.contains("dong y")) || 
                        normalized.contains("khong") || normalized.contains("bi quyet") || 
                        normalized.contains("ti le")) {
                        try { node.recycle() } catch (_: Exception) {}
                        continue
                    }

                    candidateButtons.add(Pair(node, bounds))
                }
            }
        }

        if (candidateButtons.isNotEmpty()) {
            // Sắp xếp: Nút nằm trong vùng Dialog (Y từ 35% đến 78% màn hình) lên đầu tiên!
            // Tránh bấm nhầm nút "Nhận đơn" của thẻ đơn bị mờ ở đáy màn hình (>80% height)
            val sortedButtons = candidateButtons.sortedBy { (_, bounds) ->
                val isInDialogZone = bounds.centerY() in (screenHeight * 0.35f).toInt()..(screenHeight * 0.78f).toInt()
                if (isInDialogZone) 0 else 1
            }

            for ((node, bounds) in sortedButtons) {
                val clickable = getClickableNode(node) ?: node
                var clicked = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)

                if (!bounds.isEmpty && bounds.centerX() > 0 && bounds.centerY() > 0) {
                    showTouchIndicator(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                    val path = Path().apply { moveTo(bounds.centerX().toFloat(), bounds.centerY().toFloat()) }
                    val gesture = GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0, 40)).build()
                    dispatchGesture(gesture, null, null)
                    clicked = true
                }

                if (clickable !== node) {
                    try { clickable.recycle() } catch (_: Exception) {}
                }
                try { node.recycle() } catch (_: Exception) {}

                if (clicked) {
                    Log.i("HK_BENCHMARK", "POPUP_CONFIRMATION_CLICKED by text at bounds=$bounds")
                    return true
                }
            }
        }



        // 4. Fallback cuối cùng: Chạm trực tiếp vào vị trí nút [Nhận đơn] của popup trên ShopeeFood (72% ngang, 56.5% dọc)
        // CHỈ thực hiện khi hệ thống đang trong trạng thái chờ popup xác nhận của đơn hợp lệ
        if (awaitingPopupConfirmation) {
            val defaultDialogX = screenWidth * 0.72f
            val defaultDialogY = screenHeight * 0.565f
            showTouchIndicator(defaultDialogX, defaultDialogY)
            val fallbackPath = Path().apply { moveTo(defaultDialogX, defaultDialogY) }
            val fallbackGesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(fallbackPath, 0, 40)).build()
            val dispatched = dispatchGesture(fallbackGesture, null, null)
            Log.i("HK_BENCHMARK", "POPUP_FALLBACK_GEOMETRIC_TAP at ($defaultDialogX, $defaultDialogY) dispatched=$dispatched")
            return dispatched
        }
        return false
    }

    private fun clickFreePickConfirmation(rootNode: AccessibilityNodeInfo?, useGesture: Boolean = false): Boolean {
        return clickOrderConfirmationPopup(rootNode)
    }

    private fun dismissMissedOrderDialog(rootNode: AccessibilityNodeInfo?): Boolean {
        val activeRoot = rootNode ?: rootInActiveWindow ?: return false
        val ownsActiveRoot = activeRoot !== rootNode
        val buttonTexts = listOf("Xác nhận", "Đã hiểu", "Xac nhan", "Da hieu", "Đồng ý", "Dong y", "OK", "Đóng", "Dong")
        var clickTarget: AccessibilityNodeInfo? = null
        var selectedButton: AccessibilityNodeInfo? = null
        val foundNodes = mutableListOf<AccessibilityNodeInfo>()

        try {
            if (!isDriverAppPackage(activeRoot.packageName?.toString()) || !isDriverAppForeground()) return false

            for (btnText in buttonTexts) {
                val nodes = activeRoot.findAccessibilityNodeInfosByText(btnText)
                if (!nodes.isNullOrEmpty()) {
                    foundNodes.addAll(nodes)
                    selectedButton = nodes.firstOrNull {
                        it.isVisibleToUser && it.isEnabled
                    }
                    if (selectedButton != null) break
                }
            }

            if (selectedButton != null) {
                clickTarget = getClickableNode(selectedButton)
                if (clickTarget != null && clickTarget.isEnabled) {
                    val clicked = tryClick(clickTarget, "dismiss_dialog_${selectedButton.text}")
                    if (clicked) return true
                }

                val bounds = android.graphics.Rect()
                selectedButton.getBoundsInScreen(bounds)
                if (!bounds.isEmpty) {
                    if (dryRun) {
                        Log.i("HK_BENCHMARK", "DRY_RUN would_gesture tag=dismiss_dialog_gesture")
                        return true
                    }
                    val path = Path().apply { moveTo(bounds.centerX().toFloat(), bounds.centerY().toFloat()) }
                    val gesture = GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build()
                    return dispatchGesture(gesture, null, null)
                }
            }

            // Fallback: nếu không tìm thấy text node nhưng dialog đang xuất hiện trên màn hình
            val screenW = resources.displayMetrics.widthPixels
            val screenH = resources.displayMetrics.heightPixels
            val fallbackX = screenW * 0.50f
            val fallbackY = screenH * 0.58f
            Log.i("HK_BENCHMARK", "DISMISS_DIALOG_COORDINATE_FALLBACK at ($fallbackX, $fallbackY)")
            if (dryRun) return true
            val path = Path().apply { moveTo(fallbackX, fallbackY) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build()
            return dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            Log.e("HK_BENCHMARK", "Error in dismissMissedOrderDialog: ${e.message}")
            return false
        } finally {
            if (clickTarget != null && clickTarget !== selectedButton) {
                try { clickTarget.recycle() } catch (_: Exception) {}
            }
            foundNodes.forEach { try { it.recycle() } catch (_: Exception) {} }
            if (ownsActiveRoot) {
                try { activeRoot.recycle() } catch (_: Exception) {}
            }
        }
    }

    private fun clickAcknowledgeButton(rootNode: AccessibilityNodeInfo?): Boolean {
        return dismissMissedOrderDialog(rootNode)
    }



    private fun extractAllText(node: AccessibilityNodeInfo, nodeCountRef: IntArray? = null): String {
        nodeCountRef?.let { it[0]++ }
        val sb = java.lang.StringBuilder()

        if (node.text != null) sb.append(node.text.toString()).append(" ")

        if (node.contentDescription != null) sb.append(node.contentDescription.toString()).append(" ")

        for (i in 0 until node.childCount) {

            val child = node.getChild(i)

            if (child != null) {

                sb.append(extractAllText(child, nodeCountRef))

                child.recycle()

            }

        }

        return sb.toString()

    }

    private fun extractDropOffAddress(cardText: String): String? {
        val normalized = removeAccents(cardText).lowercase(java.util.Locale.ROOT)
        val markers = listOf(
            "diem ket thuc:",
            "diem ket thuc",
            "ket thuc:",
            "ket thuc",
            "diem giao hang",
            "diem giao",
            "giao den",
            "giao hang",
            "giao:",
            "giao",
            "nguoi nhan"
        )
        val markerMatch = markers
            .mapNotNull { marker -> normalized.lastIndexOf(marker).takeIf { it >= 0 }?.let { it to marker } }
            .maxByOrNull { it.first } ?: return null
        val start = markerMatch.first + markerMatch.second.length
        if (start >= cardText.length) return null

        var address = cardText.substring(start).trimStart(' ', ':', '-', '\n', '\r')
        val endMarkers = listOf(" nhan don", " asap", " tong thu", " diem bat dau", " lay hang")
        val normalizedAddress = removeAccents(address).lowercase(java.util.Locale.ROOT)
        val end = endMarkers.mapNotNull { normalizedAddress.indexOf(it).takeIf { index -> index >= 0 } }.minOrNull()
        if (end != null) address = address.substring(0, end)

        return address.replace(Regex("\\s+"), " ").trim().take(240).takeIf { it.length >= 5 }
    }



    private fun extractCodAmount(text: String): Double? {

        // Tìm chữ "Thu:" (hoặc "thu:"), theo sau là số tiền, có thể có chữ 'đ'

        val regex = Regex("thu:\\s*([0-9.,\\s]+)(đ|d|k|vnđ|vnd)?", RegexOption.IGNORE_CASE)

        val match = regex.find(text) ?: return null

        var numStr = match.groupValues[1]

        // Loại bỏ mọi ký tự không phải số (bỏ dấu phẩy, dấu chấm, khoảng trắng)

        numStr = numStr.replace(Regex("[^0-9]"), "")

        if (numStr.isEmpty()) return null

        

        // Trả về số tiền gốc (ví dụ: 122630.0). Nếu cần so sánh với Slider (k), ta sẽ chia 1000 sau.

        return numStr.toDouble()

    }

    private fun isDepositOrder(cardText: String, fullText: String = "", rootNode: AccessibilityNodeInfo? = null): Boolean {
        if (cardText.isBlank()) return false
        val clean = removeAccents(cardText).replace(Regex("\\s+"), " ").lowercase()
        // Tuyệt đối loại bỏ phần tiêu đề "Ví ký quỹ" / "Số dư ví" trên thanh trạng thái Shopee
        val safe = clean.replace(Regex("(?i)vi\\s*k[y|i]\\s*quy"), "")
            .replace(Regex("(?i)so\\s*du\\s*vi"), "")
            .replace(Regex("(?i)tai\\s*khoan"), "")

        // 1. Nhận diện chính xác thanh cam ở đáy thẻ: "Bạn cần nạp thêm tiền ký quỹ để nhận đơn hàng này."
        if (safe.contains("nap them tien ky quy") || safe.contains("nap them tien ki quy") ||
            safe.contains("can nap them tien ky quy") || safe.contains("can nap them tien ki quy") ||
            safe.contains("can nap them") || safe.contains("nap them tien") || safe.contains("can nap tien") ||
            safe.contains("ban can nap them")) {
            return true
        }

        // 2. Chỉ kiểm tra từ khóa ký quỹ chính xác của đơn hàng (không kiểm tra từ trơ 'ky quy')
        return safe.contains("don ky quy") || safe.contains("don ki quy") ||
               safe.contains("can ky quy") || safe.contains("can ki quy") ||
               safe.contains("yeu cau ky quy") || safe.contains("yeu cau ki quy") ||
               safe.contains("tien ky quy") || safe.contains("tien ki quy") ||
               safe.contains("khong du ky quy") || safe.contains("khong du ki quy") ||
               safe.contains("so du khong du") || safe.contains("so du k du") ||
               safe.contains("ky quy:") || safe.contains("ki quy:")
    }

    private fun isCodOnlyOrder(cardText: String, fullText: String = "", rootNode: AccessibilityNodeInfo? = null): Boolean {
        if (cardText.isBlank()) return false
        val clean = removeAccents(cardText).replace(Regex("\\s+"), " ").lowercase()

        // 1. Nhận diện nhãn đơn COD trên thẻ: "1 đơn COD", "2 đơn COD", "đơn COD"...
        if (clean.contains("don cod") || clean.contains("don hang cod")) {
            return true
        }

        // 2. Kiểm tra từ khóa Ứng tiền / Thu hộ / Tạm ứng
        if (clean.contains("ung tien") || clean.contains("tam ung") || 
            clean.contains("tien ung") || clean.contains("thu ho")) {
            return true
        }

        // 3. Kiểm tra mục Thu tiền (loại trừ thu 0đ, thu 0k)
        if (clean.contains("thu tien") || clean.contains("thu:")) {
            val isZeroThu = clean.contains("thu: 0") || clean.contains("thu: 0k") || 
                            clean.contains("thu: 0d") || clean.contains("thu 0d") ||
                            clean.contains("thu: 0 vnd") || clean.contains("thu 0 vnd")
            if (!isZeroThu) return true
        }

        // 4. Kiểm tra từ khóa COD (trừ trường hợp "0 cod", "khong cod", "khong thu cod")
        val hasCod = clean.contains("cod")
        if (hasCod) {
            val isZeroCod = clean.contains("0 cod") || 
                            clean.contains("khong cod") || 
                            clean.contains("khong thu cod") ||
                            clean.contains("cod: 0") ||
                            clean.contains("cod 0") ||
                            clean.contains("0d cod") ||
                            clean.contains("0k cod")
            if (!isZeroCod) return true
        }

        // 5. Kiểm tra mục Thu tiền: "Thu: ***" hoặc "Thu: [số > 0]"
        if (clean.contains("thu: ***") || clean.contains("thu:***") || clean.contains("thu ***") ||
            clean.contains("thu : ***") || clean.contains("thu :***")) {
            return true
        }

        val codAmount = extractCodAmount(cardText) ?: 0.0
        if (codAmount > 0.0) {
            return true
        }

        return false
    }

    private fun isCodOrDepositOrder(cardText: String, fullText: String, rootNode: AccessibilityNodeInfo? = null): Boolean {
        return isDepositOrder(cardText, fullText, rootNode) || isCodOnlyOrder(cardText, fullText, rootNode)
    }



    private fun extractPrice(text: String): Double? {
        var cleanText = text.replace("\n", " ").replace("\r", " ")
        cleanText = cleanText.replace(Regex("(\\d)\\s*[,.]\\s*(\\d)"), "$1.$2")

        // Ưu tiên quét phần đầu thẻ trước thông tin lấy hàng / điểm bắt đầu / ứng trả COD
        val headerText = cleanText.split(Regex("Lấy hàng|Lay hang|Điểm bắt đầu|Diem bat dau|Trả:|Tra:|Thu:", RegexOption.IGNORE_CASE)).firstOrNull() ?: cleanText
        val headerPrice = extractPriceInternal(headerText)
        if (headerPrice != null && headerPrice > 0.0) return headerPrice

        return extractPriceInternal(cleanText)
    }

    private fun extractPriceInternal(text: String): Double? {
        // Cực kỳ quan trọng:
        // 1. k(?!\s*[mM]) để tránh bắt nhầm "20 km" thành "20k"
        // 2. (?![a-zA-Z\u00C0-\u1EF9\u0110\u0111]) để tránh bắt nhầm ký tự đầu của từ tiếng Việt:
        //    Ví dụ: "8 Điểm" -> không bị bắt nhầm thành "8 đ" (0.008k)
        //           "8401 Đang hoạt động" -> không bị bắt nhầm thành "8401 đ" (8.401k)
        //           "45 Đặng Văn Ngữ" -> không bị bắt nhầm thành "45 đ"
        // 3. Bổ sung ký hiệu ₫ (\u20ab) chuẩn tiền tệ
        val regex = Regex("(\\d+([.,]\\d+)?)\\s*(đ|₫|k(?!\\s*[mM])|vnđ|vnd)(?![a-zA-Z\\u00C0-\\u1EF9\\u0110\\u0111])(\\s*\\+\\s*(\\d+([.,]\\d+)?)\\s*(đ|₫|k(?!\\s*[mM])|vnđ|vnd)(?![a-zA-Z\\u00C0-\\u1EF9\\u0110\\u0111]))?", RegexOption.IGNORE_CASE)
        val matches = regex.findAll(text)

        var bestTotal = 0.0
        for (match in matches) {
            var currentTotal = 0.0
            fun parseGroup(numStr: String, unitStr: String): Double {
                var n = numStr
                val u = unitStr.lowercase()
                if (u == "k") {
                    n = n.replace(",", ".")
                } else {
                    n = n.replace(",", "").replace(".", "")
                }
                var p = n.toDoubleOrNull() ?: 0.0
                if (u == "đ" || u == "₫" || u == "vnđ" || u == "vnd") {
                    p /= 1000.0 // Quy đổi về k
                }
                return p
            }
            currentTotal += parseGroup(match.groupValues[1], match.groupValues[3])
            if (match.groupValues[4].isNotEmpty()) {
                currentTotal += parseGroup(match.groupValues[5], match.groupValues[7])
            }
            if (currentTotal > bestTotal) {
                bestTotal = currentTotal
            }
        }
        return if (bestTotal > 0) bestTotal else null
    }



    private fun extractDistance(text: String): Double? {

        var cleanText = text.replace("\n", " ").replace("\r", " ")

        cleanText = cleanText.replace(Regex("(\\d)\\s*[,.]\\s*(\\d)"), "$1.$2")

        val regex = Regex("(\\d+[,.]\\d+|\\d+)\\s*km", RegexOption.IGNORE_CASE)

        val match = regex.find(cleanText)

        if (match != null) {

            return match.groupValues[1].replace(",", ".").toDoubleOrNull()

        }

        return null

    }

    private fun extractShopeePoints(text: String): Int {
        val clean = removeAccents(text).lowercase()
        val match = Regex("\\b(\\d+)\\s*(?:diem|điem|điểm)\\b").find(clean)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }


    private fun updateFloatingButtonIcon() {
        val greenBg = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.parseColor("#4CAF50"))
            cornerRadius = 20f
        }
        val redBg = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.parseColor("#F44336"))
            cornerRadius = 20f
        }
        if (isAutoRunEnabled) {
            btnStart?.text = "⏸ DỪNG LẠI"
            btnStart?.background = redBg
        } else {
            btnStart?.text = "▶ BẮT ĐẦU"
            btnStart?.background = greenBg
        }
    }

    private fun android.content.SharedPreferences.getDouble(key: String, defaultValue: Double): Double {
        if (!contains(key)) return defaultValue
        return try {
            val strVal = getString(key, "")
            if (strVal != null && strVal.isNotEmpty()) {
                strVal.toDoubleOrNull() ?: defaultValue
            } else {
                defaultValue
            }
        } catch (e: Exception) {
            try {
                getFloat(key, defaultValue.toFloat()).toDouble()
            } catch (e2: Exception) {
                try {
                    val bits = getLong(key, java.lang.Double.doubleToRawLongBits(defaultValue))
                    java.lang.Double.longBitsToDouble(bits)
                } catch (e3: Exception) {
                    defaultValue
                }
            }
        }
    }
}






