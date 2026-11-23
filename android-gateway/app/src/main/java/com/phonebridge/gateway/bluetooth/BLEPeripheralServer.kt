package com.phonebridge.gateway.bluetooth

import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.phonebridge.gateway.service.PhoneBridgeCallService
import org.json.JSONObject
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
    }

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var gattServer: BluetoothGattServer? = null
    private var eventCharacteristic: BluetoothGattCharacteristic? = null
    private var connectedDevice: BluetoothDevice? = null

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
    }

    private fun setupGattServer() {
        val gattCallback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
                super.onConnectionStateChange(device, status, newState)
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "iPhone connected: ${device?.address}")
                    connectedDevice = device
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "iPhone disconnected: ${device?.address}")
                    if (connectedDevice == device) {
                        connectedDevice = null
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
            UUID.fromString("00002902-0000-1000-8000-00805F9B34FB"),
            BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ
        )
        eventCharacteristic?.addDescriptor(configDescriptor)
        service.addCharacteristic(eventCharacteristic)

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

    fun sendNotification(jsonString: String) {
        val device = connectedDevice
        val char = eventCharacteristic
        if (device != null && char != null) {
            char.value = jsonString.toByteArray(Charsets.UTF_8)
            gattServer?.notifyCharacteristicChanged(device, char, false)
            Log.d(TAG, "Notification sent to iPhone: $jsonString")
        } else {
            Log.w(TAG, "Cannot send notification: no device connected.")
        }
    }

    private fun handleIncomingCommand(json: JSONObject) {
        val cmd = json.optString("cmd")
        Log.d(TAG, "Received command from iPhone: $cmd")
        when (cmd) {
            "ANSWER_CALL" -> {
                PhoneBridgeCallService.answerCurrentCall()
            }
            "HANGUP_CALL" -> {
                PhoneBridgeCallService.disconnectCurrentCall()
            }
        }
    }
}
