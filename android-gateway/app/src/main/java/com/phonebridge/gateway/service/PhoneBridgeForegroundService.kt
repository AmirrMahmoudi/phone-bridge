package com.phonebridge.gateway.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.phonebridge.gateway.MainActivity
import com.phonebridge.gateway.bluetooth.BLEPeripheralServer
import org.json.JSONObject
import java.util.UUID

/**
 * سرویس Foreground دائمی برای حفظ سرور BLE و شنود تماس‌های ورودی در حالت پس‌زمینه
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
        var bleServer: BLEPeripheralServer? = null
    }
    
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastCallState = TelephonyManager.CALL_STATE_IDLE
    private var incomingNumber: String? = null
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        acquireWakeLock()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                bleServer?.stopServer()
                bleServer = null
                releaseWakeLock()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val message = intent?.getStringExtra(EXTRA_MESSAGE) ?: "PhoneBridge فعال است و به آیفون متصل می‌باشد"
                startForegroundWithNotification(message)
                initBleServer()
                setupCallListener()
            }
        }
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        instance = null
    }

    /**
     * راه‌اندازی سرور بلوتوث داخل سرویس دائمی
     */
    private fun initBleServer() {
        if (bleServer == null) {
            bleServer = BLEPeripheralServer(applicationContext)
            bleServer?.startServer()
            Log.i(TAG, "BLEPeripheralServer initialized inside Foreground Service")
        }
    }

    /**
     * ثبت شنونده تماس‌ها برای اطلاع فوری به آیفون
     */
    private fun setupCallListener() {
        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyManager.registerTelephonyCallback(
                    mainExecutor,
                    object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                        override fun onCallStateChanged(state: Int) {
                            handleCallStateChange(state)
                        }
                    }
                )
            } else {
                @Suppress("DEPRECATION")
                telephonyManager.listen(
                    object : PhoneStateListener() {
                        @Deprecated("Deprecated in Java")
                        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                            handleCallStateChange(state, phoneNumber)
                        }
                    },
                    PhoneStateListener.LISTEN_CALL_STATE
                )
            }
            Log.i(TAG, "Telephony call state listener registered successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error registering Telephony listener", e)
        }
    }

    private var incomingCallerName: String? = null
    private var currentCallId: String? = null

    fun handleIncomingNumber(number: String, contactName: String? = null) {
        this.incomingNumber = number
        val name = contactName ?: com.phonebridge.gateway.util.ContactHelper.getContactName(this, number) ?: number
        this.incomingCallerName = name
        if (currentCallId == null) {
            currentCallId = UUID.randomUUID().toString()
        }
        broadcastIncomingCall(number, name)
    }

    fun handleCallStateChange(state: Int, phoneNumber: String? = null) {
        if (state == lastCallState) return
        Log.i(TAG, "Call state changed: $state, number: $phoneNumber")
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                if (currentCallId == null) {
                    currentCallId = UUID.randomUUID().toString()
                }
                val number = phoneNumber ?: incomingNumber
                if (!number.isNullOrEmpty()) {
                    val name = incomingCallerName ?: com.phonebridge.gateway.util.ContactHelper.getContactName(this, number) ?: number
                    broadcastIncomingCall(number, name)
                } else {
                    // تاخیر کوتاه ۲۰۰ میلی‌ثانیه‌ای تا اینتنت حاوی شماره از CallReceiver برسد
                    serviceScope.launch {
                        delay(250)
                        if (lastCallState == TelephonyManager.CALL_STATE_RINGING && incomingNumber == null) {
                            broadcastIncomingCall("ناشناس", "تماس ورودی")
                        }
                    }
                }
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                Log.i(TAG, "Call in progress / answered")
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (lastCallState == TelephonyManager.CALL_STATE_RINGING || lastCallState == TelephonyManager.CALL_STATE_OFFHOOK) {
                    val payload = JSONObject().apply {
                        put("type", "CALL_ENDED")
                        put("reason", "DISCONNECTED")
                        put("callId", currentCallId ?: "")
                    }
                    BLEPeripheralServer.instance?.sendNotification(payload.toString())
                    Log.i(TAG, "CALL_ENDED notification sent to iPhone")
                }
                incomingNumber = null
                incomingCallerName = null
                currentCallId = null
            }
        }
        lastCallState = state
    }

    private fun broadcastIncomingCall(number: String, callerName: String = number) {
        val payload = JSONObject().apply {
            put("type", "CALL_INCOMING")
            put("callId", currentCallId ?: UUID.randomUUID().toString().also { currentCallId = it })
            put("callerNumber", number)
            put("callerName", callerName)
            put("timestamp", System.currentTimeMillis() / 1000)
        }
        BLEPeripheralServer.instance?.sendNotification(payload.toString())
        Log.i(TAG, "CALL_INCOMING notification sent to iPhone: $number (Name: $callerName)")
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneBridge::WakeLock")
            wakeLock?.acquire(24 * 60 * 60 * 1000L)
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock error", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock release error", e)
        }
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
