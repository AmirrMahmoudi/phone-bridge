package com.phonebridge.gateway.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Helper برای مدیریت battery optimization و doze mode
 */
object BatteryOptimizationHelper {
    
    private const val TAG = "BatteryOptHelper"
    
    /**
     * بررسی اینکه آیا app از battery optimization معاف است
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true // قبل از M این مشکل نیست
        }
        
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    }
    
    /**
     * باز کردن صفحه درخواست معافیت
     */
    fun requestIgnoreBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true
        }
        
        return try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error opening battery optimization settings", e)
            
            // Fallback: باز کردن لیست کلی
            try {
                val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                context.startActivity(fallbackIntent)
                true
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback also failed", e2)
                false
            }
        }
    }
    
    /**
     * دریافت وضعیت Doze Mode
     */
    fun getDozeStatus(context: Context): DozeStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return DozeStatus.NOT_APPLICABLE
        }
        
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return DozeStatus.UNKNOWN
        
        val isIgnoring = powerManager.isIgnoringBatteryOptimizations(context.packageName)
        
        return when {
            isIgnoring -> DozeStatus.EXEMPTED
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && powerManager.isDeviceIdleMode -> 
                DozeStatus.IN_DOZE_MODE
            else -> DozeStatus.OPTIMIZED
        }
    }
    
    /**
     * دریافت توضیحات وضعیت فعلی
     */
    fun getStatusDescription(status: DozeStatus): String {
        return when (status) {
            DozeStatus.EXEMPTED -> "✅ معاف از بهینه‌سازی - عملکرد کامل"
            DozeStatus.OPTIMIZED -> "⚠️ تحت بهینه‌سازی - ممکن است در پس‌زمینه متوقف شود"
            DozeStatus.IN_DOZE_MODE -> "🔴 در حالت Doze - عملکرد محدود شده"
            DozeStatus.NOT_APPLICABLE -> "✅ نسخه قدیمی اندروید - بدون محدودیت"
            DozeStatus.UNKNOWN -> "❓ نامشخص"
        }
    }
    
    /**
     * راهنمای قدم به قدم برای معافیت
     */
    fun getStepByStepGuide(): List<String> {
        return listOf(
            "۱. دکمه «باز کردن تنظیمات» را بزنید",
            "۲. Phone Bridge را در لیست پیدا کنید",
            "۳. گزینه «Don't optimize» یا «معاف» را انتخاب کنید",
            "۴. تنظیمات را ذخیره کرده و به برنامه برگردید",
            "۵. برنامه را مجدداً باز کنید تا تغییرات اعمال شود"
        )
    }
    
    enum class DozeStatus {
        EXEMPTED,         // معاف از optimization
        OPTIMIZED,        // تحت optimization (خطرناک!)
        IN_DOZE_MODE,     // الان در حالت Doze
        NOT_APPLICABLE,   // برای این نسخه اندروید مهم نیست
        UNKNOWN           // نامشخص
    }
}
