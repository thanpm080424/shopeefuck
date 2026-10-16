package com.hkpick.phoneinstaller

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import io.github.rhythmcache.adb.AdbClient
import io.github.rhythmcache.adb.FileKeyProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class MainActivity : Activity() {
    companion object {
        private const val ACTION_USB_PERMISSION = "com.hkpick.phoneinstaller.USB_PERMISSION"
        private const val DRIVER_PACKAGE = "com.samsung.health.addon"
        private const val DRIVER_CERT_ASSET = "driver.cert.sha256"
    }

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var usbManager: UsbManager
    private lateinit var statusText: TextView
    private lateinit var versionText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var runButton: Button

    @Volatile private var busy = false
    private var runAfterUsbPermission = false
    private var latestDevice: UsbDevice? = null
    private var receiverRegistered = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = usbDeviceFrom(intent) ?: latestDevice
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null) {
                        latestDevice = device
                        setStatus("Đã cấp quyền USB. Đang kết nối ADB với điện thoại khách…", Tone.BLUE)
                        if (runAfterUsbPermission) connectInstallAndOpen(device)
                    } else {
                        runAfterUsbPermission = false
                        setStatus("Chưa được cấp quyền USB. Trên điện thoại đang chạy bộ cài, chạm Cho phép trong hộp thoại vừa hiện rồi bấm RUN.", Tone.ORANGE)
                    }
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val detached = usbDeviceFrom(intent)
                    if (detached?.deviceId == latestDevice?.deviceId) {
                        latestDevice = null
                        runAfterUsbPermission = false
                        setStatus("Cáp vừa bị ngắt. Nối lại cáp dữ liệu/OTG, mở khóa máy khách rồi bấm RUN.", Tone.ORANGE)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        window.statusBarColor = Color.rgb(243, 246, 251)
        window.navigationBarColor = Color.rgb(243, 246, 251)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        createScreen()
        registerUsbReceiver()
        getSharedPreferences("secure_firebase_session", MODE_PRIVATE).edit().clear().apply()
        File(cacheDir, "driver-download.apk").delete()
        setStatus("Sẵn sàng. Chuẩn bị điện thoại khách theo các bước bên dưới, nối cáp rồi bấm RUN.", Tone.BLUE)
    }

    override fun onResume() {
        super.onResume()
        if (!busy) refreshConnectionHint()
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            runCatching { unregisterReceiver(usbReceiver) }
            receiverRegistered = false
        }
        ioScope.cancel()
        super.onDestroy()
    }

    private fun createScreen() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(243, 246, 251))
        }

        val scroll = ScrollView(this).apply { isFillViewport = false }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(14))
        }
        scroll.addView(content)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.rgb(22, 38, 60), 22)
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        header.addView(label("HKPICK  /  CÀI NHANH", 12, Color.rgb(142, 183, 255), true))
        header.addView(label("Cài app tài xế\nbằng điện thoại", 25, Color.WHITE, true).apply {
            setPadding(0, dp(8), 0, dp(6))
        })
        header.addView(label("Điện thoại này làm máy cài. Cắm máy khách qua cáp OTG, xác nhận quyền rồi RUN để cài và mở app.", 14, Color.rgb(222, 231, 242), false))
        versionText = label("APK tải từ kho riêng sau khi xác minh license", 13, Color.rgb(207, 220, 239), true).apply {
            setPadding(0, dp(14), 0, 0)
        }
        header.addView(versionText)
        content.addView(header, margin(bottom = 14))

        val licenseCard = card(Color.WHITE)
        licenseCard.addView(label("CẤP KEY THEO MÃ MÁY", 13, Color.rgb(37, 99, 235), true))
        licenseCard.addView(label(
            "Bộ cài đọc mã Android ID của điện thoại khách qua USB. Nếu máy chưa có license, gửi mã đó cho quản trị viên cấp key trong HK Admin Pro rồi bấm RUN lại.",
            14, Color.rgb(51, 65, 85), false,
        ).apply { setPadding(0, dp(8), 0, 0); setLineSpacing(dp(2).toFloat(), 1f) })
        content.addView(licenseCard, margin(bottom = 14))

        val helpCard = card(Color.WHITE)
        helpCard.addView(label("LÀM TRÊN ĐIỆN THOẠI KHÁCH", 13, Color.rgb(37, 99, 235), true))
        addStep(helpCard, "1", "Mở khóa Tùy chọn nhà phát triển", "Vào Cài đặt → Giới thiệu điện thoại → Thông tin phần mềm → chạm Số hiệu bản tạo 7 lần. Nếu máy đã có Tùy chọn nhà phát triển thì bỏ qua bước này.")
        addStep(helpCard, "2", "Bật Gỡ lỗi USB", "Quay lại Cài đặt → Tùy chọn nhà phát triển → bật Gỡ lỗi USB. Giữ màn hình sáng và mở khóa điện thoại khách.")
        addStep(helpCard, "3", "Cắm hai điện thoại", "Dùng cáp có truyền dữ liệu và đầu OTG phù hợp. Nếu điện thoại chạy bộ cài hỏi quyền dùng thiết bị USB, chọn Cho phép.")
        addStep(helpCard, "4", "Xác nhận trên máy khách", "Khi máy khách hiện “Cho phép gỡ lỗi USB?”, kiểm tra đây là điện thoại của bạn rồi chạm Cho phép. Chỉ chọn “Luôn cho phép” nếu bạn sẽ tiếp tục dùng chính điện thoại này làm máy cài.")
        helpCard.addView(label("Nếu là Xiaomi/HyperOS và máy báo thiếu quyền, bật thêm “Gỡ lỗi USB (Cài đặt bảo mật)” trong Tùy chọn nhà phát triển.", 13, Color.rgb(91, 103, 122), false).apply {
            setPadding(0, dp(8), 0, 0)
        })
        content.addView(helpCard, margin(bottom = 14))

        val statusCard = card(Color.WHITE)
        statusCard.addView(label("TRẠNG THÁI", 12, Color.rgb(107, 119, 138), true))
        statusText = label("Đang kiểm tra…", 14, Color.rgb(33, 48, 68), false).apply {
            setPadding(0, dp(8), 0, dp(4))
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        statusCard.addView(statusText)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(Color.rgb(37, 99, 235))
            progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(226, 232, 240))
            visibility = View.GONE
        }
        statusCard.addView(progressBar, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(8) })
        content.addView(statusCard, margin(bottom = 10))

        val note = label("Bảo mật: ADB cho phép máy cài thực hiện thao tác quản trị trên máy khách. Chỉ xác nhận điện thoại của bạn; cài xong hãy tắt Gỡ lỗi USB hoặc thu hồi quyền gỡ lỗi nếu không dùng nữa.", 12, Color.rgb(91, 103, 122), false).apply {
            setPadding(dp(4), dp(2), dp(4), dp(8))
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        content.addView(note)

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(16))
            setBackgroundColor(Color.rgb(243, 246, 251))
        }
        runButton = actionButton("▶  RUN  •  CÀI VÀ MỞ", Color.rgb(37, 99, 235), Color.WHITE)
        runButton.setOnClickListener { onRunPressed() }
        footer.addView(runButton, LinearLayout.LayoutParams(-1, dp(56)))
        page.addView(footer)

        setContentView(page)
    }

    private fun addStep(parent: LinearLayout, number: String, title: String, detail: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, dp(15), 0, 0)
        }
        val badge = TextView(this).apply {
            text = number
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(37, 99, 235), 100)
        }
        row.addView(badge, LinearLayout.LayoutParams(dp(28), dp(28)))
        val words = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), 0, 0, 0)
        }
        words.addView(label(title, 15, Color.rgb(28, 41, 60), true))
        words.addView(label(detail, 13, Color.rgb(91, 103, 122), false).apply {
            setPadding(0, dp(3), 0, 0)
            setLineSpacing(dp(2).toFloat(), 1f)
        })
        row.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        parent.addView(row)
    }

    private fun card(color: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(color, 18)
        setPadding(dp(16), dp(16), dp(16), dp(16))
    }

    private fun label(text: String, size: Int, color: Int, bold: Boolean): TextView = TextView(this).apply {
        this.text = text
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun actionButton(text: String, backgroundColor: Int, foregroundColor: Int): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(foregroundColor)
        background = rounded(backgroundColor, 15)
        stateListAnimator = null
    }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun margin(bottom: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(bottom) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun refreshConnectionHint() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
            setStatus("Điện thoại đang chạy bộ cài chưa báo hỗ trợ USB Host/OTG. Hãy dùng điện thoại khác có OTG để làm máy cài.", Tone.ORANGE)
            return
        }
        val device = findAdbDevice()
        if (device == null) {
            setStatus("Chưa thấy máy khách ở chế độ ADB. Bật Gỡ lỗi USB trên máy khách, mở khóa màn hình, rồi cắm lại cáp dữ liệu/OTG.", Tone.BLUE)
        } else {
            latestDevice = device
            val title = device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "điện thoại khách"
            setStatus("Đã thấy $title qua USB. Kiểm tra hướng dẫn trên, sau đó bấm RUN.", Tone.GREEN)
        }
    }

    private fun onRunPressed() {
        if (busy) return
        runAfterUsbPermission = true
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
            runAfterUsbPermission = false
            setStatus("Máy này chưa hỗ trợ USB Host/OTG nên không thể làm máy cài. Dùng điện thoại có OTG rồi cài bộ cài lên đó.", Tone.ORANGE)
            return
        }

        val device = findAdbDevice()
        if (device == null) {
            runAfterUsbPermission = false
            setStatus("Chưa tìm thấy ADB trên máy khách. Mở máy khách → Cài đặt → Tùy chọn nhà phát triển → bật Gỡ lỗi USB; giữ máy mở khóa và cắm lại cáp dữ liệu/OTG.", Tone.ORANGE)
            return
        }
        latestDevice = device
        if (!usbManager.hasPermission(device)) {
            setStatus("Điện thoại đang chạy bộ cài cần quyền USB. Hãy nhìn màn hình điện thoại này và chạm Cho phép nếu hộp thoại hiện ra.", Tone.BLUE)
            requestUsbPermission(device)
            return
        }
        connectInstallAndOpen(device)
    }

    private fun findAdbDevice(): UsbDevice? = usbManager.deviceList.values.firstOrNull {
        UsbPacketTransport.findAdbInterface(it) != null
    }

    private fun requestUsbPermission(device: UsbDevice) {
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName).apply {
            putExtra(UsbManager.EXTRA_DEVICE, device)
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
        val pending = PendingIntent.getBroadcast(this, device.deviceId, intent, flags)
        usbManager.requestPermission(device, pending)
    }

    private fun connectInstallAndOpen(device: UsbDevice) {
        if (busy) return
        busy = true
        runButton.isEnabled = false
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        setStatus("Đang bắt tay ADB. Nhìn điện thoại khách và chạm Cho phép nếu hiện “Cho phép gỡ lỗi USB?”.", Tone.BLUE)

        ioScope.launch {
            var client: AdbClient? = null
            var transport: UsbPacketTransport? = null
            var temporaryApk: File? = null
            try {
                val iface = UsbPacketTransport.findAdbInterface(device)
                    ?: throw IllegalStateException("Chưa thấy giao diện ADB. Bật Gỡ lỗi USB trên máy khách rồi rút cắm lại cáp.")
                val endpoints = UsbPacketTransport.findAdbEndpoints(iface)
                    ?: throw IllegalStateException("Cổng USB của máy khách không mở được dữ liệu ADB. Thử cáp dữ liệu khác.")
                val connection = usbManager.openDevice(device)
                    ?: throw IllegalStateException("Chưa được cấp quyền USB trên điện thoại chạy bộ cài.")
                if (!connection.claimInterface(iface, true)) {
                    connection.close()
                    throw IllegalStateException("Không mở được kết nối USB. Rút cáp, mở khóa máy khách rồi cắm lại.")
                }
                transport = UsbPacketTransport(connection, iface, endpoints.first, endpoints.second)
                val keyDir = File(noBackupFilesDir, "adb-host")
                val keyProvider = FileKeyProvider(
                    keyFile = File(keyDir, "adbkey"),
                    pubKeyFile = File(keyDir, "adbkey.pub"),
                    identityComment = Build.MODEL.replace(' ', '_') + "@HKPickInstaller",
                )

                client = AdbClient.connect(
                    transport = transport,
                    keyProvider = keyProvider,
                    handshakeTimeoutMs = 60_000,
                )

                val sdkText = client.shell("getprop ro.build.version.sdk").stdoutText.trim()
                val sdk = sdkText.toIntOrNull() ?: 0
                val modelText = client.shell("getprop ro.product.model").stdoutText.trim()
                val model = modelText.ifBlank { "điện thoại khách" }
                setStatus("Đã xác nhận $model. Đang kiểm tra và cài bản tài xế…", Tone.BLUE)

                val targetDeviceId = client.shell("settings get secure android_id").stdoutText.trim().lowercase()
                if (targetDeviceId.isBlank() || targetDeviceId == "null") {
                    throw IllegalStateException("Không đọc được Android ID của điện thoại khách qua ADB.")
                }
                setStatus("Đang kiểm tra key cho mã máy $targetDeviceId…", Tone.BLUE)
                val release = try {
                    issueDriverApk(targetDeviceId)
                } catch (error: Throwable) {
                    val detail = error.message.orEmpty()
                    if (detail.contains("license", ignoreCase = true) ||
                        detail.contains("permission-denied", ignoreCase = true) ||
                        detail.contains("chưa được cấp", ignoreCase = true)
                    ) {
                        throw IllegalStateException("Máy chưa được cấp key. Gửi mã máy $targetDeviceId cho quản trị viên, chờ cấp license rồi bấm RUN lại.", error)
                    }
                    throw error
                }
                runOnUiThread { versionText.text = "Bản tải xuống: ${release.versionName} (${release.versionCode})" }
                setStatus("License hợp lệ. Đang tải APK riêng tư về bộ cài…", Tone.BLUE)
                temporaryApk = downloadDriverApk(release)
                val driverInfo = validateDriverApk(
                    temporaryApk!!,
                    expectedHash = release.sha256,
                    expectedSigner = release.signerSha256,
                )
                val installedVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    driverInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    driverInfo.versionCode.toLong()
                }
                if (installedVersionCode != release.versionCode) {
                    throw IllegalStateException("Mã phiên bản APK tải xuống không khớp bản phát hành máy chủ.")
                }
                val requiredApi = driverInfo.applicationInfo?.minSdkVersion ?: 30
                if (sdk < maxOf(requiredApi, release.minimumApi)) {
                    throw IllegalStateException("$model chạy Android API $sdk. Bản tài xế này yêu cầu API $requiredApi trở lên.")
                }

                var shownPercent = -1
                runOnUiThread {
                    progressBar.isIndeterminate = false
                    progressBar.progress = 0
                }
                client.install.install(temporaryApk!!, flags = listOf("-r")).collect { progress ->
                    val percent = progress.percentage.toInt().coerceIn(0, 100)
                    if (percent != shownPercent) {
                        shownPercent = percent
                        runOnUiThread { progressBar.progress = percent }
                        setStatus("Đang cài lên $model… $percent%", Tone.BLUE)
                    }
                }

                setStatus("Đã cài xong. Đang mở app tài xế trên máy khách…", Tone.BLUE)
                val launchResult = client.shell("monkey -p $DRIVER_PACKAGE 1")
                val launchText = (launchResult.stdoutText + " " + launchResult.stderrText).trim()
                if (!launchResult.isSuccess || launchText.contains("No activities found", ignoreCase = true)) {
                    setStatus("Cài thành công lên $model, nhưng Android chưa tự mở app. Mở app tài xế trên máy khách bằng biểu tượng app. Sau đó tắt Gỡ lỗi USB nếu đã cài xong.", Tone.ORANGE)
                } else {
                    setStatus("Hoàn tất: đã cài và mở app tài xế trên $model. Sau khi xong, tắt Gỡ lỗi USB hoặc thu hồi quyền máy cài trên máy khách.", Tone.GREEN)
                }
            } catch (error: Throwable) {
                setStatus(readableError(error), Tone.ORANGE)
            } finally {
                runCatching { client?.close() }
                if (client == null) runCatching { transport?.close() }
                runCatching { temporaryApk?.delete() }
                runOnUiThread {
                    busy = false
                    runAfterUsbPermission = false
                    runButton.isEnabled = true
                    progressBar.visibility = View.GONE
                    progressBar.isIndeterminate = false
                }
            }
        }
    }

    private fun readableError(error: Throwable): String {
        val messages = generateSequence(error) { it.cause }
            .mapNotNull { it.message?.takeIf(String::isNotBlank) }
            .toList()
        val detail = messages.joinToString(" — ")
        val lower = detail.lowercase()
        return when {
            "signature" in lower || "update_incompatible" in lower || "inconsistent certificates" in lower ->
                "Cài không được vì app trên máy khách đang có chữ ký khác. Bộ cài không tự gỡ app cũ để tránh mất dữ liệu. Kiểm tra bản đã cài trước khi quyết định gỡ."
            "unauthorized" in lower || "authentication" in lower || "auth" in lower || "timed out" in lower || "timeout" in lower ->
                "ADB chưa được máy khách tin cậy. Mở khóa máy khách, bật Gỡ lỗi USB, chạm Cho phép trong hộp thoại ADB rồi thử lại. Chi tiết: $detail"
            "usb" in lower || "device" in lower || "interface" in lower ->
                "Kết nối USB chưa ổn. Kiểm tra OTG, dùng cáp có truyền dữ liệu, bật Gỡ lỗi USB và giữ máy khách mở khóa. Chi tiết: $detail"
            else -> "Chưa cài xong. Kiểm tra hướng dẫn, giữ máy khách mở khóa và thử lại. Chi tiết: ${detail.ifBlank { error.javaClass.simpleName }}"
        }
    }

    private data class DriverRelease(
        val downloadUrl: String,
        val sha256: String,
        val signerSha256: String,
        val versionName: String,
        val versionCode: Long,
        val minimumApi: Int,
        val sizeBytes: Long,
    )


    private fun issueDriverApk(deviceId: String): DriverRelease {
        val response = postJson(
            "https://asia-southeast1-${BuildConfig.FIREBASE_PROJECT_ID}.cloudfunctions.net/issueDriverApk",
            JSONObject().put("data", JSONObject().put("deviceId", deviceId)),
        )
        val data = response.optJSONObject("result") ?: response.optJSONObject("data")
            ?: throw IllegalStateException("Máy chủ trả về thông tin bản tải không hợp lệ.")
        val downloadUrl = data.optString("downloadUrl")
        val sha256 = data.optString("sha256").lowercase()
        val signerSha256 = data.optString("signerSha256").lowercase()
        val versionCode = data.optLong("versionCode")
        if (!downloadUrl.startsWith("https://") ||
            !sha256.matches(Regex("[a-f0-9]{64}")) ||
            !signerSha256.matches(Regex("[a-f0-9]{64}")) || versionCode <= 0L
        ) {
            throw IllegalStateException("Máy chủ trả về metadata APK thiếu hoặc không hợp lệ.")
        }
        return DriverRelease(
            downloadUrl = downloadUrl,
            sha256 = sha256,
            signerSha256 = signerSha256,
            versionName = data.optString("versionName"),
            versionCode = versionCode,
            minimumApi = data.optInt("minimumApi", 30),
            sizeBytes = data.optLong("sizeBytes"),
        )
    }

    private fun downloadDriverApk(release: DriverRelease): File {
        val downloadHost = runCatching { URL(release.downloadUrl).host.lowercase() }.getOrDefault("")
        if (downloadHost !in setOf("storage.googleapis.com", "firebasestorage.googleapis.com")) {
            throw IllegalStateException("Máy chủ trả về địa chỉ tải APK không thuộc Firebase Storage.")
        }
        val file = File(cacheDir, "driver-download.apk")
        if (cacheDir.usableSpace < release.sizeBytes + 8L * 1024L * 1024L) {
            throw IllegalStateException("Máy chạy bộ cài không đủ dung lượng trống để tải APK tài xế.")
        }
        val connection = URL(release.downloadUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 90_000
        connection.instanceFollowRedirects = true
        try {
            if (connection.responseCode !in 200..299) throw IllegalStateException("Không tải được APK từ kho lưu trữ.")
            val finalHost = connection.url.host.lowercase()
            if (finalHost !in setOf("storage.googleapis.com", "firebasestorage.googleapis.com")) {
                throw IllegalStateException("Địa chỉ chuyển hướng tải APK không thuộc Firebase Storage.")
            }
            connection.inputStream.use { input -> FileOutputStream(file).use { output -> input.copyTo(output, 128 * 1024) } }
        } finally { connection.disconnect() }
        if (file.length() < 1024 * 1024 || file.length() > 300L * 1024L * 1024L) {
            file.delete()
            throw IllegalStateException("Kích thước APK tải xuống không hợp lệ.")
        }
        if (release.sizeBytes > 0 && file.length() != release.sizeBytes) {
            file.delete()
            throw IllegalStateException("Dung lượng APK không khớp thông tin máy chủ.")
        }
        return file
    }

    private fun postJson(url: String, body: JSONObject): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 20_000
        connection.readTimeout = 45_000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        val response = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
        if (status !in 200..299) {
            val message = response.optJSONObject("error")?.optString("message")
                ?: response.optString("message")
            throw IllegalStateException(message?.takeIf(String::isNotBlank) ?: "Máy chủ từ chối yêu cầu (HTTP $status).")
        }
        return response
    }


    private fun validateDriverApk(file: File, expectedHash: String, expectedSigner: String): PackageInfo {
        if (!file.isFile || file.length() == 0L) throw IllegalArgumentException("Không tìm thấy APK tải xuống.")
        val actualHash = sha256(file).lowercase()
        if (!expectedHash.matches(Regex("[a-f0-9]{64}")) || actualHash != expectedHash) {
            throw IllegalArgumentException("SHA-256 APK không khớp dữ liệu máy chủ. Không cài file này.")
        }
        val pinnedSigner = readAssetText(DRIVER_CERT_ASSET).trim().lowercase()
        if (!expectedSigner.matches(Regex("[a-f0-9]{64}")) || expectedSigner != pinnedSigner) {
            throw IllegalArgumentException("Máy chủ trả về chữ ký APK không khớp bản cài đã phát hành.")
        }
        val info = readArchiveInfo(file)
            ?: throw IllegalArgumentException("File tải xuống không phải APK Android hợp lệ.")
        if (info.packageName != DRIVER_PACKAGE) {
            throw IllegalArgumentException("Sai ứng dụng: package ${info.packageName}, cần $DRIVER_PACKAGE.")
        }
        if (signerDigests(info) != setOf(pinnedSigner)) {
            throw IllegalArgumentException("Chữ ký APK tài xế không hợp lệ. Không cài file này.")
        }
        return info
    }

    private fun readArchiveInfo(file: File): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageArchiveInfo(file.absolutePath, flags)
        }
    }

    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        return signatures.orEmpty().map { sha256Bytes(it.toByteArray()) }.toSet()
    }

    private fun readAssetText(name: String): String = assets.open(name).bufferedReader().use { it.readText() }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256Bytes(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun setStatus(message: String, tone: Tone) {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                statusText.text = message
                val color = when (tone) {
                    Tone.BLUE -> Color.rgb(37, 99, 235)
                    Tone.GREEN -> Color.rgb(22, 145, 90)
                    Tone.ORANGE -> Color.rgb(190, 105, 15)
                }
                statusText.setTextColor(color)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun usbDeviceFrom(intent: Intent): UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

    private enum class Tone { BLUE, GREEN, ORANGE }
}
