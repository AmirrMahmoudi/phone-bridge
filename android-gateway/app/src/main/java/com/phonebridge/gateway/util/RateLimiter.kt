package com.phonebridge.gateway.util

/**
 * Rate limiter برای جلوگیری از spam تماس/SMS
 * Thread-safe implementation با synchronized access
 */
class RateLimiter(
    private val maxActionsPerMinute: Int
) {
    private val actionTimestamps = mutableListOf<Long>()
    
    /**
     * بررسی اینکه آیا action جدید مجاز است یا نه
     * @return true اگر مجاز باشد، false اگر rate limit exceeded
     */
    @Synchronized
    fun allowAction(): Boolean {
        val now = System.currentTimeMillis()
        
        // حذف timestamp های قدیمی (بیش از 1 دقیقه)
        actionTimestamps.removeAll { now - it > 60_000 }
        
        return if (actionTimestamps.size < maxActionsPerMinute) {
            actionTimestamps.add(now)
            true
        } else {
            false
        }
    }
    
    /**
     * Reset کردن limiter (برای تست)
     */
    @Synchronized
    fun reset() {
        actionTimestamps.clear()
    }
    
    /**
     * دریافت تعداد action های باقیمانده
     */
    @Synchronized
    fun remainingActions(): Int {
        val now = System.currentTimeMillis()
        actionTimestamps.removeAll { now - it > 60_000 }
        return maxActionsPerMinute - actionTimestamps.size
    }
}
