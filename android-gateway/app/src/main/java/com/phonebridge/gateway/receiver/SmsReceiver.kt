package com.phonebridge.gateway.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.provider.ContactsContract
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
            
            // ترکیب تمام قسمت‌های پیامک چندبخشی
            val fullBody = messages.joinToString("") { it.displayMessageBody ?: "" }
            val sender = messages.firstOrNull()?.displayOriginatingAddress ?: "Unknown"
            
            // جستجوی نام از مخاطبین
            val senderName = context?.let { ctx -> lookupContactName(ctx, sender) }
            
            Log.d(TAG, "SMS received from $sender (${senderName ?: "Unknown"}): $fullBody")

            val payload = JSONObject().apply {
                put("type", "SMS_INCOMING")
                put("sender", sender)
                put("senderName", senderName ?: "")
                put("body", fullBody)
                put("timestamp", System.currentTimeMillis() / 1000)
            }
            BLEPeripheralServer.instance?.sendNotification(payload.toString())
            
            // به‌روزرسانی ViewModel
            context?.let { ctx ->
                com.phonebridge.gateway.MainActivity.instance?.runOnUiThread {
                    com.phonebridge.gateway.MainActivity.instance?.let { activity ->
                        val viewModelStore = activity.viewModelStore
                        val viewModel = androidx.lifecycle.ViewModelProvider(viewModelStore, androidx.lifecycle.ViewModelProvider.NewInstanceFactory())
                            .get(com.phonebridge.gateway.viewmodel.PhoneBridgeViewModel::class.java)
                        viewModel.addSmsActivity(sender, fullBody, senderName)
                    }
                }
            }
        }
    }
    
    /**
     * جستجوی نام مخاطب از شماره تلفن
     */
    private fun lookupContactName(context: Context, phoneNumber: String): String? {
        val uri = ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon()
            .appendPath(phoneNumber)
            .build()
        
        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )
            
            if (cursor != null && cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    return cursor.getString(nameIndex)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطا در جستجوی نام مخاطب", e)
        } finally {
            cursor?.close()
        }
        
        return null
    }
}
