package com.example.hkpickclone

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

class AppLifecycleService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Khởi động service nhưng không dính (không tự mở lại)
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d("AppLifecycle", "Người dùng đã tắt đa nhiệm (Swipe from Recents). Đang tiến hành hủy AutoPickService...")
        
        // Kích hoạt hàm kill service bên trong AutoPickService
        AutoPickService.instance?.killService()
        
        // Tự tắt LifecycleService
        stopSelf()
    }
}
