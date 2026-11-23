package com.phonebridge.gateway.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.phonebridge.gateway.bluetooth.BLEPeripheralServer
import org.json.JSONObject

/**
 * دریافت پیامک‌های ورودی و فوروارد به آیفون از طریق بلوتوث
 */
class SmsReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "PhoneBridgeSmsReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            for (sms in messages) {
                val sender = sms.displayOriginatingAddress ?: "Unknown"
                val body = sms.displayMessageBody ?: ""
                Log.d(TAG, "SMS received from $sender: $body")

                val payload = JSONObject().apply {
                    put("type", "SMS_INCOMING")
                    put("sender", sender)
                    put("body", body)
                    put("timestamp", System.currentTimeMillis() / 1000)
                }
                BLEPeripheralServer.instance?.sendNotification(payload.toString())
            }
        }
    }
}
