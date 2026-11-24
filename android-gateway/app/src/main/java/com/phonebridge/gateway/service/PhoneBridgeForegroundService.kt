package com.phonebridge.gateway.service

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.phonebridge.gateway.MainActivity
import com.phonebridge.gateway.R

/**
 * سرویس Foreground برای حفظ سرور BLE در حالت پس‌زمینه
 */
class PhoneBridgeForegroundService : Service() {
    
    companion object {
        private const val TAG = "PhoneBridgeForegroundService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "phone_bridge_service"
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_MESSAGE = "EXTRA_MESSAGE"
        
        var instance: PhoneBridgeForegroundService? = null
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "سرور BLE فعال است"
                startForegroundWithNotification(message)
            }
            ACTION_STOP -> {
                stopForeground(true)
                stopSelf()
            }
            else -> {
                startForegroundWithNotification("سرور BLE فعال است")
            }
        }
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
    
    /**
     * ایجاد کانال نوتیفیکیشن برای اندروید 8 و بالاتر
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Phone Bridge Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "نوتیفیکیشن دائمی برای سرویس Phone Bridge"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }
    
    /**
     * شروع سرویس Foreground با نوتیفیکیشن
     */
    private fun startForegroundWithNotification(message: String) {
        val notification = buildNotification(message)
        startForeground(NOTIFICATION_ID, notification)
    }
    
    /**
     * به‌روزرسانی پیام نوتیفیکیشن
     */
    fun updateNotification(message: String) {
        val notification = buildNotification(message)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }
    
    /**
     * ساخت نوتیفیکیشن
     */
    private fun buildNotification(message: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Phone Bridge")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
