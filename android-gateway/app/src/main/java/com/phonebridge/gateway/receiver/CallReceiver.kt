package com.phonebridge.gateway.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import com.phonebridge.gateway.bluetooth.BLEPeripheralServer
import org.json.JSONObject
import java.util.UUID

/**
 * دریافت‌کننده سیستمی رویدادهای تماس تلفنی (زنگ خوردن و قطع شدن)
 * این کامپوننت حتی در صورتی که PhoneBridge شماره‌گیر پیش‌فرض نباشد نیز زنگ خوردن را به آیفون ارسال می‌کند.
 */
class CallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CallReceiver"
        private var lastState = TelephonyManager.CALL_STATE_IDLE
        private var incomingNumber: String? = null
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

            if (state != lastState) {
                when (state) {
                    TelephonyManager.CALL_STATE_RINGING -> {
                        incomingNumber = if (!number.isNullOrEmpty()) number else "ناشناس"
                        Log.i(TAG, "Incoming GSM call ringing from: $incomingNumber")

                        val payload = JSONObject().apply {
                            put("type", "CALL_INCOMING")
                            put("callId", UUID.randomUUID().toString())
                            put("callerNumber", incomingNumber)
                            put("callerName", incomingNumber)
                            put("timestamp", System.currentTimeMillis() / 1000)
                        }
                        BLEPeripheralServer.instance?.sendNotification(payload.toString())
                    }

                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        Log.i(TAG, "Call in progress (answered)")
                    }

                    TelephonyManager.CALL_STATE_IDLE -> {
                        if (lastState == TelephonyManager.CALL_STATE_RINGING || lastState == TelephonyManager.CALL_STATE_OFFHOOK) {
                            Log.i(TAG, "Call finished / disconnected")
                            val payload = JSONObject().apply {
                                put("type", "CALL_ENDED")
                                put("reason", "DISCONNECTED")
                            }
                            BLEPeripheralServer.instance?.sendNotification(payload.toString())
                        }
                        incomingNumber = null
                    }
                }
                lastState = state
            }
        }
    }
}
