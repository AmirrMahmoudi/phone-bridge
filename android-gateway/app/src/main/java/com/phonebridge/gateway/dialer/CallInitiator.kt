package com.phonebridge.gateway.dialer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.phonebridge.gateway.util.Logger
import com.phonebridge.gateway.util.PhoneNumberValidator
import com.phonebridge.gateway.util.RateLimiter

/**
 * Call Initiator برای شروع تماس خروجی
 * این کلاس مستقل از کد موجود است و فقط افزودنی است
 */
class CallInitiator(private val context: Context) {
    
    private val rateLimiter = RateLimiter(maxActionsPerMinute = 5) // Max 5 calls/min
    
    /**
     * شروع تماس خروجی با validation کامل
     * @param phoneNumber شماره مقصد
     * @param callback نتیجه (success, callId/error)
     */
    fun initiateCall(phoneNumber: String, callback: (Boolean, String?, String?) -> Unit) {
        // Step 1: Validation - Phone Number
        val normalizedNumber = PhoneNumberValidator.normalize(phoneNumber)
        if (!PhoneNumberValidator.isValid(normalizedNumber)) {
            val error = "Invalid phone number format"
            Logger.log(Logger.LogLevel.WARNING, Logger.Category.CALL, "Call initiation failed: $error")
            callback(false, null, error)
            return
        }
        
        // Step 2: Rate Limiting
        if (!rateLimiter.allowAction()) {
            val error = "Rate limit exceeded (5 calls/min)"
            Logger.log(Logger.LogLevel.WARNING, Logger.Category.CALL, "Call initiation failed: $error")
            callback(false, null, error)
            return
        }
        
        // Step 3: Permission Check
        if (!hasCallPermission()) {
            val error = "CALL_PHONE permission not granted"
            Logger.log(Logger.LogLevel.ERROR, Logger.Category.CALL, "Call initiation failed: $error")
            callback(false, null, error)
            return
        }
        
        // Step 4: SIM Check
        if (!isSimAvailable()) {
            val error = "SIM card not available"
            Logger.log(Logger.LogLevel.ERROR, Logger.Category.CALL, "Call initiation failed: $error")
            callback(false, null, error)
            return
        }
        
        // Step 5: Start Call
        try {
            startCall(normalizedNumber)
            
            // Generate call ID
            val callId = "call_${System.currentTimeMillis()}"
            
            Logger.log(
                Logger.LogLevel.INFO,
                Logger.Category.CALL,
                "Call initiated to $normalizedNumber (ID: $callId)"
            )
            
            callback(true, callId, null)
            
        } catch (e: SecurityException) {
            val error = "Security exception: ${e.message}"
            Logger.log(Logger.LogLevel.ERROR, Logger.Category.CALL, error)
            callback(false, null, error)
        } catch (e: Exception) {
            val error = "Call exception: ${e.message}"
            Logger.log(Logger.LogLevel.ERROR, Logger.Category.CALL, error)
            callback(false, null, error)
        }
    }
    
    /**
     * شروع تماس با Intent
     */
    private fun startCall(phoneNumber: String) {
        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$phoneNumber")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        
        context.startActivity(intent)
    }
    
    /**
     * بررسی دسترسی CALL_PHONE
     */
    private fun hasCallPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * بررسی در دسترس بودن SIM card
     */
    private fun isSimAvailable(): Boolean {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        return telephonyManager?.simState == TelephonyManager.SIM_STATE_READY
    }
    
    /**
     * دریافت تعداد تماس‌های باقیمانده تا رسیدن به rate limit
     */
    fun getRemainingQuota(): Int {
        return rateLimiter.remainingActions()
    }
}
