package com.phonebridge.gateway.util

/**
 * Validator برای شماره تلفن
 * پشتیبانی از فرمت‌های بین‌المللی و محلی
 */
object PhoneNumberValidator {
    
    // فرمت معتبر: 10-15 رقم، با یا بدون +
    // مثال: +989123456789, 09123456789, +12125551234
    private val PHONE_REGEX = Regex("^\\+?[0-9]{10,15}$")
    
    /**
     * بررسی اعتبار شماره تلفن
     * @param phoneNumber شماره تلفن برای بررسی
     * @return true اگر معتبر باشد
     */
    fun isValid(phoneNumber: String): Boolean {
        if (phoneNumber.isBlank()) {
            return false
        }
        
        return PHONE_REGEX.matches(phoneNumber.trim())
    }
    
    /**
     * Normalize کردن شماره تلفن (حذف فاصله، خط تیره و...)
     * @param phoneNumber شماره خام
     * @return شماره normalize شده
     */
    fun normalize(phoneNumber: String): String {
        return phoneNumber
            .trim()
            .replace(" ", "")
            .replace("-", "")
            .replace("(", "")
            .replace(")", "")
    }
    
    /**
     * بررسی اینکه شماره ایرانی است یا نه
     */
    fun isIranianNumber(phoneNumber: String): Boolean {
        val normalized = normalize(phoneNumber)
        return normalized.startsWith("+98") || normalized.startsWith("0")
    }
}
