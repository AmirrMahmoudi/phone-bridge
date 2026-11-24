package com.phonebridge.gateway.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import com.phonebridge.gateway.bluetooth.BLEPeripheralServer
import com.phonebridge.gateway.util.ContactHelper
import org.json.JSONObject
import java.util.UUID

/**
 * دریافت‌کننده سیستمی رویدادهای تماس تلفنی (زنگ خوردن و قطع شدن)
 * این کامپوننت شماره تماس‌گیرنده و نام مخاطب را بلافاصله برای آیفون ارسال می‌کند.
 */
class CallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CallReceiver"
        private var lastState = TelephonyManager.CALL_STATE_IDLE
        private var incomingNumber: String? = null
        private var currentCallId: String? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

            Log.d(TAG, "Phone state changed: $stateStr, number: $number")

            val state = when (stateStr) {
                TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
                TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
                TelephonyManager.EXTRA_STATE_IDLE -> TelephonyManager.CALL_STATE_IDLE
                else -> TelephonyManager.CALL_STATE_IDLE
            }

            if (state == TelephonyManager.CALL_STATE_RINGING) {
                // در اندروید اینتنت زنگ خوردن ممکن است دو بار فرستاده شود (یک بار بدون شماره و بار دوم همراه با شماره)
                if (!number.isNullOrEmpty() && number != incomingNumber) {
                    incomingNumber = number
                    val contactName = ContactHelper.getContactName(context, number) ?: number
                    if (currentCallId == null) {
                        currentCallId = UUID.randomUUID().toString()
                    }

                    Log.i(TAG, "Incoming GSM call ringing from: $number (Name: $contactName)")

                    // ارسال شماره به سرویس پس‌زمینه
                    com.phonebridge.gateway.service.PhoneBridgeForegroundService.instance?.handleIncomingNumber(number, contactName)

                    val payload = JSONObject().apply {
                        put("type", "CALL_INCOMING")
                        put("callId", currentCallId)
                        put("callerNumber", number)
                        put("callerName", contactName)
                        put("timestamp", System.currentTimeMillis() / 1000)
                    }
                    BLEPeripheralServer.instance?.sendNotification(payload.toString())
                }
            } else if (state != lastState) {
                when (state) {
                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        Log.i(TAG, "Call in progress (answered)")
                    }

                    TelephonyManager.CALL_STATE_IDLE -> {
                        if (lastState == TelephonyManager.CALL_STATE_RINGING || lastState == TelephonyManager.CALL_STATE_OFFHOOK) {
                            Log.i(TAG, "Call finished / disconnected")
                            val payload = JSONObject().apply {
                                put("type", "CALL_ENDED")
                                put("reason", "DISCONNECTED")
                                put("callId", currentCallId ?: "")
                            }
                            BLEPeripheralServer.instance?.sendNotification(payload.toString())
                        }
                        incomingNumber = null
                        currentCallId = null
                    }
                }
            }
            lastState = state
        }
    }
}
