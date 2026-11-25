package com.phonebridge.gateway.bluetooth

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.ParcelUuid
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import com.phonebridge.gateway.service.PhoneBridgeCallService
import com.phonebridge.gateway.sms.SmsSender
import com.phonebridge.gateway.dialer.CallInitiator
import org.json.JSONObject
import java.util.Collections
import java.util.UUID

/**
 * سرور بلوتوث LE که روی گوشی اندروید اجرا می‌شود و آیفون به عنوان کلاینت به آن وصل می‌شود.
 */
class BLEPeripheralServer(private val context: Context) {

    companion object {
        private const val TAG = "BLEPeripheralServer"
        var instance: BLEPeripheralServer? = null

        val SERVICE_UUID: UUID = UUID.fromString("0000A001-0000-1000-8000-00805F9B34FB")
        val COMMAND_CHAR_UUID: UUID = UUID.fromString("0000A002-0000-1000-8000-00805F9B34FB")
        val EVENT_CHAR_UUID: UUID = UUID.fromString("0000A003-0000-1000-8000-00805F9B34FB")
        val AUDIO_PSM_CHAR_UUID: UUID = UUID.fromString("0000A004-0000-1000-8000-00805F9B34FB")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var gattServer: BluetoothGattServer? = null
    private var eventCharacteristic: BluetoothGattCharacteristic? = null
    private var psmCharacteristic: BluetoothGattCharacteristic? = null

    // نگهداری لیست امن تمامی آیفون‌های متصل شده
    private val connectedDevices = Collections.synchronizedSet(mutableSetOf<BluetoothDevice>())
    
    // ردیابی دلیل disconnect برای هر دستگاه
    private data class DisconnectContext(
        val device: BluetoothDevice,
        val reason: DisconnectReason,
        val timestamp: Long
    )
    
    enum class DisconnectReason {
        USER_INITIATED,       // کاربر disconnect کرد
        OUT_OF_RANGE,        // دستگاه از محدوده خارج شد
        CONNECTION_TIMEOUT,   // Timeout
        PERIPHERAL_ERROR,     // خطای سمت peripheral
        UNKNOWN              // نامشخص
    }
    
    private val recentDisconnects = mutableMapOf<String, DisconnectContext>()
    
    // مدیریت کانال L2CAP برای انتقال صوت
    private var l2capChannelManager: com.phonebridge.gateway.audio.L2CAPChannelManager? = null
    private var audioBridgeManager: com.phonebridge.gateway.audio.AudioBridgeManager? = null
    private var heartbeatHandler: android.os.Handler? = null
    private var heartbeatRunnable: Runnable? = null
    
    // مدیر تاریخچه تماس
    private var callHistoryManager: com.phonebridge.gateway.data.CallHistoryManager? = null
    
    // مدیر ارسال SMS (فیچر جدید)
    private val smsSender: SmsSender by lazy { SmsSender(context) }
    
    // مدیر تماس خروجی (فیچر جدید)
    private val callInitiator: CallInitiator by lazy { CallInitiator(context) }
    
    // Advertising callback برای restart
    private var currentAdvertiseCallback: AdvertiseCallback? = null

    init {
        instance = this
        bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
    }

    fun startServer() {
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Log.e(TAG, "Bluetooth not enabled or not available.")
            return
        }

        setupGattServer()
        startAdvertising()
        
        // راه‌اندازی AudioBridgeManager
        audioBridgeManager = com.phonebridge.gateway.audio.AudioBridgeManager(context)
        
        // راه‌اندازی CallHistoryManager
        callHistoryManager = com.phonebridge.gateway.data.CallHistoryManager(context)
        
        // راه‌اندازی کانال L2CAP برای انتقال صوت
        l2capChannelManager = com.phonebridge.gateway.audio.L2CAPChannelManager(bluetoothAdapter!!).apply {
            onConnected = { socket ->
                Log.i(TAG, "L2CAP audio channel connected")
            }
            onDisconnected = {
                Log.i(TAG, "L2CAP audio channel disconnected")
                audioBridgeManager?.stopStreaming()
                // تلاش مجدد برای L2CAP در پس‌زمینه
                retryL2CAPConnection()
            }
            startListening()
            
            // به‌روزرسانی PSM با تاخیر کوتاه برای اطمینان از ready بودن
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (currentPsm > 0) {
                    updatePsmCharacteristic(currentPsm)
                    Log.i(TAG, "✅ PSM characteristic updated with value: $currentPsm")
                    
                    // ارسال event به iOS که PSM آماده است
                    val payload = JSONObject().apply {
                        put("type", "L2CAP_READY")
                        put("psm", currentPsm)
                    }
                    sendNotification(payload.toString())
                }
            }, 500)
        }
        
        // شروع ارسال heartbeat
        startHeartbeat()
    }
    
    /**
     * تلاش مجدد برای برقراری L2CAP connection
     */
    private fun retryL2CAPConnection() {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (connectedDevices.isEmpty()) return@postDelayed
            
            Log.i(TAG, "🔄 Retrying L2CAP connection...")
            try {
                l2capChannelManager?.startListening()
                Log.i(TAG, "✅ L2CAP listener restarted")
            } catch (e: Exception) {
                Log.e(TAG, "❌ L2CAP retry failed: ${e.message}")
            }
        }, 2000) // تلاش مجدد بعد از ۲ ثانیه
    }
    
    /**
     * شروع ارسال heartbeat هر ۳۰ ثانیه
     */
    private fun startHeartbeat() {
        heartbeatHandler = android.os.Handler(android.os.Looper.getMainLooper())
        heartbeatRunnable = object : Runnable {
            override fun run() {
                if (connectedDevices.isNotEmpty()) {
                    Log.d(TAG, "Sending heartbeat to connected devices")
                    sendDeviceStatus()
                }
                heartbeatHandler?.postDelayed(this, 30000) // هر ۳۰ ثانیه
            }
        }
        heartbeatHandler?.postDelayed(heartbeatRunnable!!, 30000)
        Log.i(TAG, "Heartbeat started (every 30 seconds)")
    }
    
    /**
     * توقف heartbeat
     */
    private fun stopHeartbeat() {
        heartbeatRunnable?.let { heartbeatHandler?.removeCallbacks(it) }
        heartbeatHandler = null
        heartbeatRunnable = null
        Log.i(TAG, "Heartbeat stopped")
    }

    private fun setupGattServer() {
        val gattCallback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
                super.onConnectionStateChange(device, status, newState)
                if (device != null) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        Log.i(TAG, "✅ iPhone connected: ${device.address}")
                        connectedDevices.add(device)
                        
                        // پاک کردن recent disconnect context
                        recentDisconnects.remove(device.address)
                        
                        sendDeviceStatus(device)
                        
                        // به‌روزرسانی ViewModel
                        com.phonebridge.gateway.MainActivity.instance?.runOnUiThread {
                            (context as? com.phonebridge.gateway.MainActivity)?.let { activity ->
                                val viewModelStore = activity.viewModelStore
                                val viewModel = androidx.lifecycle.ViewModelProvider(viewModelStore, androidx.lifecycle.ViewModelProvider.NewInstanceFactory())
                                    .get(com.phonebridge.gateway.viewmodel.PhoneBridgeViewModel::class.java)
                                viewModel.setConnectedDevice(device.address)
                            }
                        }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        // تشخیص دلیل disconnect
                        val disconnectReason = classifyDisconnectReason(status)
                        
                        Log.i(TAG, "❌ iPhone disconnected: ${device.address} (status=$status, reason=$disconnectReason)")
                        
                        // ذخیره context برای reconnection
                        recentDisconnects[device.address] = DisconnectContext(
                            device = device,
                            reason = disconnectReason,
                            timestamp = System.currentTimeMillis()
                        )
                        
                        connectedDevices.remove(device)
                        
                        // به‌روزرسانی ViewModel
                        com.phonebridge.gateway.MainActivity.instance?.runOnUiThread {
                            (context as? com.phonebridge.gateway.MainActivity)?.let { activity ->
                                val viewModelStore = activity.viewModelStore
                                val viewModel = androidx.lifecycle.ViewModelProvider(viewModelStore, androidx.lifecycle.ViewModelProvider.NewInstanceFactory())
                                    .get(com.phonebridge.gateway.viewmodel.PhoneBridgeViewModel::class.java)
                                if (connectedDevices.isEmpty()) {
                                    viewModel.setConnectedDevice(null)
                                }
                            }
                        }
                        
                        // شروع مجدد advertising اگر هیچ دستگاهی متصل نیست
                        restartAdvertising(disconnectReason)
                    }
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice?,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic?,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?
            ) {
                super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }

                if (device != null) {
                    connectedDevices.add(device)
                }

                if (characteristic?.uuid == COMMAND_CHAR_UUID && value != null) {
                    try {
                        val jsonStr = String(value, Charsets.UTF_8)
                        val json = JSONObject(jsonStr)
                        handleIncomingCommand(json)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing incoming command", e)
                    }
                }
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice?,
                requestId: Int,
                descriptor: BluetoothGattDescriptor?,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?
            ) {
                super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value)
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
                if (device != null) {
                    connectedDevices.add(device)
                    Log.i(TAG, "Notification subscription enabled by device: ${device.address}")
                    sendDeviceStatus(device)
                }
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice?,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic?
            ) {
                super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
                
                // پاسخ به درخواست خواندن PSM
                if (characteristic?.uuid == AUDIO_PSM_CHAR_UUID) {
                    val psm = l2capChannelManager?.currentPsm ?: 0
                    // تبدیل PSM به فرمت 2-بایتی little-endian
                    val psmBytes = ByteArray(2)
                    psmBytes[0] = (psm and 0xFF).toByte()
                    psmBytes[1] = ((psm shr 8) and 0xFF).toByte()
                    
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, psmBytes)
                    Log.i(TAG, "PSM read request handled: $psm")
                } else {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }
            }
        }

        gattServer = bluetoothManager?.openGattServer(context, gattCallback)
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        // کاراکتریستیک دریافت دستورات از آیفون (Write)
        val commandChar = BluetoothGattCharacteristic(
            COMMAND_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(commandChar)

        // کاراکتریستیک ارسال رویدادها و نوتیفیکیشن‌ها به آیفون (Notify)
        eventCharacteristic = BluetoothGattCharacteristic(
            EVENT_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val configDescriptor = BluetoothGattDescriptor(
            CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ
        )
        eventCharacteristic?.addDescriptor(configDescriptor)
        service.addCharacteristic(eventCharacteristic)

        // کاراکتریستیک PSM برای کانال L2CAP (Read)
        psmCharacteristic = BluetoothGattCharacteristic(
            AUDIO_PSM_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        service.addCharacteristic(psmCharacteristic)

        gattServer?.addService(service)
        Log.i(TAG, "GATT Server configured and service added.")
    }

    private fun startAdvertising() {
        val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.e(TAG, "BLE Advertiser not available")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0) // بدون timeout
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        
        // Scan response برای اطلاعات بیشتر
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.i(TAG, "✅ BLE Advertisement started successfully")
                Log.i(TAG, "   Service UUID: $SERVICE_UUID")
                Log.i(TAG, "   Device Name: ${bluetoothAdapter?.name}")
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "❌ BLE Advertisement failed with code: $errorCode")
                when (errorCode) {
                    ADVERTISE_FAILED_DATA_TOO_LARGE -> Log.e(TAG, "   Reason: Data too large")
                    ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> Log.e(TAG, "   Reason: Too many advertisers")
                    ADVERTISE_FAILED_ALREADY_STARTED -> Log.e(TAG, "   Reason: Already started")
                    ADVERTISE_FAILED_INTERNAL_ERROR -> Log.e(TAG, "   Reason: Internal error")
                    ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> Log.e(TAG, "   Reason: Feature unsupported")
                }
                
                // تلاش مجدد بعد از 3 ثانیه
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    startAdvertising()
                }, 3000)
            }
        }

        currentAdvertiseCallback = callback
        advertiser.startAdvertising(settings, data, scanResponse, callback)
    }
    
    /**
     * شروع مجدد advertising بعد از disconnect با context-aware strategy
     */
    private fun restartAdvertising(disconnectReason: DisconnectReason = DisconnectReason.UNKNOWN) {
        // تاخیر متناسب با دلیل disconnect
        val delay = when (disconnectReason) {
            DisconnectReason.OUT_OF_RANGE -> 500L   // سریع‌تر restart کن
            DisconnectReason.CONNECTION_TIMEOUT -> 1000L
            DisconnectReason.PERIPHERAL_ERROR -> 2000L  // کمی بیشتر صبر کن
            else -> 1000L
        }
        
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (connectedDevices.isEmpty() && bluetoothAdapter?.isEnabled == true) {
                Log.i(TAG, "🔄 Restarting BLE advertising (reason: $disconnectReason, delay: ${delay}ms)...")
                
                // توقف advertising قبلی
                currentAdvertiseCallback?.let { callback ->
                    bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(callback)
                }
                
                // کمی تاخیر قبل از شروع مجدد
                Thread.sleep(100)
                startAdvertising()
            }
        }, delay)
    }
    
    /**
     * تشخیص دلیل disconnect بر اساس status code
     */
    private fun classifyDisconnectReason(status: Int): DisconnectReason {
        return when (status) {
            BluetoothGatt.GATT_SUCCESS -> DisconnectReason.USER_INITIATED // 0: Normal disconnect
            8 -> DisconnectReason.CONNECTION_TIMEOUT  // Connection timeout
            19 -> DisconnectReason.OUT_OF_RANGE      // Connection terminated by peer (usually out of range)
            22 -> DisconnectReason.OUT_OF_RANGE      // LMP response timeout (device went away)
            62 -> DisconnectReason.CONNECTION_TIMEOUT // GATT connection timeout
            133 -> DisconnectReason.PERIPHERAL_ERROR // Generic GATT error
            else -> DisconnectReason.UNKNOWN
        }
    }

    /**
     * ارسال پیام نوتیفیکیشن به تمام آیفون‌های متصل شده
     */
    fun sendNotification(jsonString: String) {
        val char = eventCharacteristic ?: return
        val bytes = jsonString.toByteArray(Charsets.UTF_8)

        synchronized(connectedDevices) {
            if (connectedDevices.isEmpty()) {
                Log.w(TAG, "Cannot send notification: no device connected in registry.")
                return
            }
            for (device in connectedDevices) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gattServer?.notifyCharacteristicChanged(device, char, false, bytes)
                    } else {
                        @Suppress("DEPRECATION")
                        char.value = bytes
                        @Suppress("DEPRECATION")
                        gattServer?.notifyCharacteristicChanged(device, char, false)
                    }
                    Log.d(TAG, "Notification sent to ${device.address}: $jsonString")
                } catch (e: Exception) {
                    Log.e(TAG, "Error notifying device ${device.address}", e)
                }
            }
        }
    }

    /**
     * ارسال درصد باتری و نام اپراتور سیم‌کارت به آیفون
     */
    fun sendDeviceStatus(targetDevice: BluetoothDevice? = null) {
        try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val batteryLevel = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 80

            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val carrierName = telephonyManager?.networkOperatorName?.ifEmpty { "سیم‌کارت فعال" } ?: "سیم‌کارت فعال"

            val payload = JSONObject().apply {
                put("type", "DEVICE_STATUS")
                put("batteryLevel", batteryLevel)
                put("simCarrier", carrierName)
            }
            sendNotification(payload.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching device status", e)
        }
    }

    /**
     * پردازش دستورات دریافتی از آیفون
     */
    private fun handleIncomingCommand(json: JSONObject) {
        val cmd = json.optString("cmd")
        Log.i(TAG, "Received command from iPhone: $cmd")
        when (cmd) {
            "ANSWER_CALL" -> {
                answerCall()
            }
            "HANGUP_CALL" -> {
                hangupCall()
            }
            "SET_MUTE" -> {
                val muted = json.optBoolean("muted", false)
                audioBridgeManager?.setMute(muted)
            }
            "SEND_SMS" -> {
                val phoneNumber = json.optString("phoneNumber")
                val message = json.optString("message")
                sendSmsMessage(phoneNumber, message)
            }
            "DIAL_CALL" -> {
                val phoneNumber = json.optString("phoneNumber")
                dialCall(phoneNumber)
            }
            "REQUEST_CALL_HISTORY" -> {
                sendCallHistory()
            }
        }
    }
    
    /**
     * ارسال تاریخچه تماس به iOS به صورت chunk
     */
    private fun sendCallHistory() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                val history = callHistoryManager?.getRecentCallHistory(100) ?: emptyList()
                
                if (history.isEmpty()) {
                    Log.w(TAG, "No call history available")
                    val payload = JSONObject().apply {
                        put("type", "CALL_HISTORY_RESPONSE")
                        put("status", "EMPTY")
                    }
                    sendNotification(payload.toString())
                    return@post
                }
                
                // ارسال آمار کلی
                val stats = callHistoryManager?.getCallHistoryStats()
                val statsPayload = JSONObject().apply {
                    put("type", "CALL_HISTORY_STATS")
                    put("totalCalls", stats?.totalCalls ?: 0)
                    put("incomingCalls", stats?.incomingCalls ?: 0)
                    put("outgoingCalls", stats?.outgoingCalls ?: 0)
                    put("missedCalls", stats?.missedCalls ?: 0)
                }
                sendNotification(statsPayload.toString())
                
                // تقسیم به chunk و ارسال تدریجی
                val chunks = callHistoryManager?.splitIntoChunks(history) ?: emptyList()
                Log.i(TAG, "Sending ${chunks.size} chunks of call history...")
                
                chunks.forEachIndexed { index, chunk ->
                    // تاخیر 200ms بین هر chunk
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        val chunkJson = callHistoryManager?.chunkToJSON(chunk) ?: ""
                        sendNotification(chunkJson)
                        Log.d(TAG, "Sent chunk ${chunk.chunkIndex + 1}/${chunk.totalChunks}")
                        
                        // بعد از آخرین chunk، بفرست که تموم شد
                        if (index == chunks.size - 1) {
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                val completePayload = JSONObject().apply {
                                    put("type", "CALL_HISTORY_COMPLETE")
                                    put("totalItems", history.size)
                                }
                                sendNotification(completePayload.toString())
                                Log.i(TAG, "✅ Call history sync complete")
                            }, 200)
                        }
                    }, index * 200L)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error sending call history", e)
                val payload = JSONObject().apply {
                    put("type", "CALL_HISTORY_ERROR")
                    put("error", e.localizedMessage ?: "Unknown error")
                }
                sendNotification(payload.toString())
            }
        }
    }

    /**
     * ارسال SMS با استفاده از SmsSender (با validation و rate limiting)
     * این تابع refactor شده است و از کلاس جدید SmsSender استفاده می‌کند
     */
    private fun sendSmsMessage(phoneNumber: String, message: String) {
        Log.i(TAG, "Sending SMS via SmsSender to: $phoneNumber")
        
        smsSender.sendSms(phoneNumber, message) { success, error ->
            val payload = JSONObject().apply {
                put("type", "SMS_SEND_RESULT")
                put("success", success)
                if (success) {
                    put("messageId", "sms_${System.currentTimeMillis()}")
                } else {
                    put("error", error ?: "Unknown error")
                }
                put("timestamp", System.currentTimeMillis() / 1000)
            }
            sendNotification(payload.toString())
            
            if (success) {
                Log.i(TAG, "✅ SMS sent successfully to $phoneNumber")
            } else {
                Log.e(TAG, "❌ SMS send failed: $error")
            }
        }
    }
    
    /**
     * شروع تماس خروجی با استفاده از CallInitiator (با validation و rate limiting)
     * این تابع کاملاً جدید است (فیچر جدید)
     */
    private fun dialCall(phoneNumber: String) {
        Log.i(TAG, "Initiating outgoing call via CallInitiator to: $phoneNumber")
        
        callInitiator.initiateCall(phoneNumber) { success, callId, error ->
            val payload = JSONObject().apply {
                put("type", "DIAL_RESULT")
                put("success", success)
                if (success) {
                    put("callId", callId)
                } else {
                    put("error", error ?: "Unknown error")
                }
                put("timestamp", System.currentTimeMillis() / 1000)
            }
            sendNotification(payload.toString())
            
            if (success) {
                Log.i(TAG, "✅ Call initiated successfully to $phoneNumber (ID: $callId)")
            } else {
                Log.e(TAG, "❌ Call initiation failed: $error")
            }
        }
    }

    private fun answerCall() {
        com.phonebridge.gateway.service.PhoneBridgeForegroundService.instance?.setAnsweredFromIphone(true)
        
        // بی‌صدا کردن زنگ اندروید تا تداخل نشود
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val prevRingerMode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        audioManager?.ringerMode = AudioManager.RINGER_MODE_SILENT
        
        if (PhoneBridgeCallService.currentCall != null) {
            PhoneBridgeCallService.answerCurrentCall()
            Log.i(TAG, "Call answered via InCallService.")
            
            // شروع استریم صوت بعد از پاسخ به تماس
            startAudioStreaming()
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                if (context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    telecom?.acceptRingingCall()
                    Log.i(TAG, "Call answered via TelecomManager.acceptRingingCall()")
                    startAudioStreaming()
                }
            }
        }
        
        // بازگرداندن حالت زنگ بعد از ۱ ثانیه (تماس وصل شده)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            audioManager?.ringerMode = prevRingerMode
        }, 1000)
    }

    private fun hangupCall() {
        // توقف استریم صوت
        stopAudioStreaming()
        
        if (PhoneBridgeCallService.currentCall != null) {
            PhoneBridgeCallService.disconnectCurrentCall()
            Log.i(TAG, "Call disconnected via InCallService.")
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                if (context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    telecom?.endCall()
                    Log.i(TAG, "Call disconnected via TelecomManager.endCall()")
                }
            }
        }
    }



    /**
     * شروع استریم صوت از طریق کانال L2CAP
     */
    private fun startAudioStreaming() {
        val channelManager = l2capChannelManager
        val audioManager = audioBridgeManager
        
        if (channelManager == null || audioManager == null) {
            Log.e(TAG, "Cannot start audio streaming: managers not initialized")
            return
        }
        
        // بررسی اینکه سوکت L2CAP متصل است
        val socket = channelManager.connectedSocket
        if (socket != null && socket.isConnected) {
            Log.i(TAG, "Starting audio streaming via L2CAP")
            audioManager.startStreaming(socket)
        } else {
            Log.w(TAG, "L2CAP socket not connected, waiting for connection...")
            // تنظیم callback برای شروع streaming بعد از اتصال
            channelManager.onConnected = { connectedSocket ->
                Log.i(TAG, "L2CAP connected, starting audio streaming")
                audioManager.startStreaming(connectedSocket)
            }
        }
    }

    /**
     * توقف استریم صوت
     */
    private fun stopAudioStreaming() {
        audioBridgeManager?.stopStreaming()
        Log.i(TAG, "Audio streaming stopped")
    }

    /**
     * به‌روزرسانی مقدار کاراکتریستیک PSM
     */
    private fun updatePsmCharacteristic(psm: Int) {
        val psmBytes = ByteArray(2)
        psmBytes[0] = (psm and 0xFF).toByte()
        psmBytes[1] = ((psm shr 8) and 0xFF).toByte()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // برای API 33+
            psmCharacteristic?.setValue(psmBytes)
        } else {
            @Suppress("DEPRECATION")
            psmCharacteristic?.value = psmBytes
        }
    }

    /**
     * توقف سرور و بستن تمام اتصالات
     */
    fun stopServer() {
        stopHeartbeat()
        l2capChannelManager?.stopListening()
        l2capChannelManager = null
        gattServer?.close()
        gattServer = null
        connectedDevices.clear()
        Log.i(TAG, "BLE Server stopped")
    }
}
