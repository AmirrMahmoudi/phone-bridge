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
    
    // مدیریت کانال L2CAP برای انتقال صوت
    private var l2capChannelManager: com.phonebridge.gateway.audio.L2CAPChannelManager? = null
    private var audioBridgeManager: com.phonebridge.gateway.audio.AudioBridgeManager? = null
    private var audioBridgeManager: com.phonebridge.gateway.audio.AudioBridgeManager? = null

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
        audioBridgeManager = com.phonebridge.gateway.audio.AudioBridgeManager()
        
        // راه‌اندازی کانال L2CAP برای انتقال صوت
        l2capChannelManager = com.phonebridge.gateway.audio.L2CAPChannelManager(bluetoothAdapter!!).apply {
            onConnected = { socket ->
                Log.i(TAG, "L2CAP audio channel connected")
            }
            onDisconnected = {
                Log.i(TAG, "L2CAP audio channel disconnected")
                audioBridgeManager?.stopStreaming()
            }
            startListening()
            
            // به‌روزرسانی مقدار کاراکتریستیک PSM پس از دریافت PSM از سیستم
            if (currentPsm > 0) {
                updatePsmCharacteristic(currentPsm)
                Log.i(TAG, "PSM characteristic updated with value: $currentPsm")
            }
        }
    }

    private fun setupGattServer() {
        val gattCallback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
                super.onConnectionStateChange(device, status, newState)
                if (device != null) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        Log.i(TAG, "iPhone connected: ${device.address}")
                        connectedDevices.add(device)
                        sendDeviceStatus(device)
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        Log.i(TAG, "iPhone disconnected: ${device.address}")
                        connectedDevices.remove(device)
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
        val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser ?: return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.i(TAG, "BLE Advertisement started successfully.")
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "BLE Advertisement failed with code: $errorCode")
            }
        }

        advertiser.startAdvertising(settings, data, callback)
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
        }
    }

    private fun answerCall() {
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
                    
                    // شروع استریم صوت
                    startAudioStreaming()
                }
            }
        }
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
        l2capChannelManager?.stopListening()
        l2capChannelManager = null
        gattServer?.close()
        gattServer = null
        connectedDevices.clear()
        Log.i(TAG, "BLE Server stopped")
    }
}
