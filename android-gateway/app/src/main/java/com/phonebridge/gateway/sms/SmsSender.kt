package com.phonebridge.gateway.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.phonebridge.gateway.util.Logger
import com.phonebridge.gateway.util.PhoneNumberValidator
import com.phonebridge.gateway.util.RateLimiter

/**
 * SMS Sender با قابلیت validation, rate limiting و multi-part support
 * این کلاس مستقل از کد موجود است و فقط افزودنی است
 */
class SmsSender(private val context: Context) {
    
    private val smsManager: SmsManager = SmsManager.getDefault()
    private val rateLimiter = RateLimiter(maxActionsPerMinute = 10) // Max 10 SMS/min
    
    companion object {
        private const val MAX_MESSAGE_LENGTH = 1000
        private const val SINGLE_SMS_LENGTH = 160
    }
    
    /**
     * ارسال SMS با validation کامل
     * @param phoneNumber شماره مقصد
     * @param message متن پیام
     * @param callback نتیجه ارسال (success, error message)
     */
    fun sendSms(phoneNumber: String, message: String, callback: (Boolean, String?) -> Unit) {
        // Step 1: Validation - Phone Number
        val normalizedNumber = PhoneNumberValidator.normalize(phoneNumber)
        if (!PhoneNumberValidator.isValid(normalizedNumber)) {
            val error = "Invalid phone number format"
            Logger.log(Logger.LogLevel.WARNING, Logger.Category.SMS, "SMS send failed: $error")
            callback(false, error)
            return
        }
        
        // Step 2: Validation - Message
        if (message.isBlank()) {
            val error = "Message cannot be empty"
            Logger.log(Logger.LogLevel.WARNING, Logger.Category.SMS, "SMS send failed: $error")
            callback(false, error)
            return
        }
        
        if (message.length > MAX_MESSAGE_LENGTH) {
            val error = "Message too long (max $MAX_MESSAGE_LENGTH characters)"
            Logger.log(Logger.LogLevel.WARNING, Logger.Category.SMS, "SMS send failed: $error")
            callback(false, error)
            return
        }
        
        // Step 3: Rate Limiting
        if (!rateLimiter.allowAction()) {
            val error = "Rate limit exceeded (10 SMS/min)"
            Logger.log(Logger.LogLevel.WARNING, Logger.Category.SMS, "SMS send failed: $error")
            callback(false, error)
            return
        }
        
        // Step 4: Permission Check
        if (!hasSmsPermission()) {
            val error = "SMS permission not granted"
            Logger.log(Logger.LogLevel.ERROR, Logger.Category.SMS, "SMS send failed: $error")
            callback(false, error)
            return
        }
        
        // Step 5: Send SMS
        try {
            if (message.length > SINGLE_SMS_LENGTH) {
                // Multi-part SMS
                sendMultiPartSms(normalizedNumber, message, callback)
            } else {
                // Single SMS
                sendSingleSms(normalizedNumber, message, callback)
            }
        } catch (e: Exception) {
            val error = "SMS send exception: ${e.message}"
            Logger.log(Logger.LogLevel.ERROR, Logger.Category.SMS, error)
            callback(false, error)
        }
    }
    
    /**
     * ارسال SMS تک قسمتی
     */
    private fun sendSingleSms(phoneNumber: String, message: String, callback: (Boolean, String?) -> Unit) {
        try {
            smsManager.sendTextMessage(
                phoneNumber,
                null,
                message,
                null,  // sentIntent - می‌توان برای tracking اضافه کرد
                null   // deliveryIntent
            )
            
            Logger.log(
                Logger.LogLevel.INFO,
                Logger.Category.SMS,
                "SMS sent to $phoneNumber (${message.length} chars)"
            )
            
            callback(true, null)
            
        } catch (e: Exception) {
            Logger.log(
                Logger.LogLevel.ERROR,
                Logger.Category.SMS,
                "Failed to send SMS: ${e.message}"
            )
            callback(false, e.message)
        }
    }
    
    /**
     * ارسال SMS چند قسمتی (بیش از 160 کاراکتر)
     */
    private fun sendMultiPartSms(phoneNumber: String, message: String, callback: (Boolean, String?) -> Unit) {
        try {
            val parts = smsManager.divideMessage(message)
            
            Logger.log(
                Logger.LogLevel.INFO,
                Logger.Category.SMS,
                "Sending multi-part SMS to $phoneNumber (${parts.size} parts, ${message.length} chars)"
            )
            
            smsManager.sendMultipartTextMessage(
                phoneNumber,
                null,
                parts,
                null,  // sentIntents
                null   // deliveryIntents
            )
            
            Logger.log(
                Logger.LogLevel.INFO,
                Logger.Category.SMS,
                "Multi-part SMS sent successfully"
            )
            
            callback(true, null)
            
        } catch (e: Exception) {
            Logger.log(
                Logger.LogLevel.ERROR,
                Logger.Category.SMS,
                "Failed to send multi-part SMS: ${e.message}"
            )
            callback(false, e.message)
        }
    }
    
    /**
     * بررسی دسترسی SEND_SMS
     */
    private fun hasSmsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * در��افت تعداد SMS های باقیمانده تا رسیدن به rate limit
     */
    fun getRemainingQuota(): Int {
        return rateLimiter.remainingActions()
    }
}
