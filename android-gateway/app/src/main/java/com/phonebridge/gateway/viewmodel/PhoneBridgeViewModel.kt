package com.phonebridge.gateway.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ViewModel برای مدیریت وضعیت UI اپلیکیشن Phone Bridge
 */
class PhoneBridgeViewModel : ViewModel() {
    
    // وضعیت BLE و اتصال
    private val _bleActive = MutableStateFlow(false)
    val bleActive: StateFlow<Boolean> = _bleActive.asStateFlow()
    
    private val _connectedDevice = MutableStateFlow<String?>(null)
    val connectedDevice: StateFlow<String?> = _connectedDevice.asStateFlow()
    
    // وضعیت صوتی
    private val _audioStreaming = MutableStateFlow(false)
    val audioStreaming: StateFlow<Boolean> = _audioStreaming.asStateFlow()
    
    private val _speakerEnabled = MutableStateFlow(false)
    val speakerEnabled: StateFlow<Boolean> = _speakerEnabled.asStateFlow()
    
    // وضعیت تماس
    private val _activeCall = MutableStateFlow<CallState?>(null)
    val activeCall: StateFlow<CallState?> = _activeCall.asStateFlow()
    
    // لیست فعالیت‌ها
    private val _activityItems = MutableStateFlow<List<ActivityItem>>(emptyList())
    val activityItems: StateFlow<List<ActivityItem>> = _activityItems.asStateFlow()
    
    // وضعیت دستگاه
    private val _batteryLevel = MutableStateFlow(0)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()
    
    private val _signalStrength = MutableStateFlow(0)
    val signalStrength: StateFlow<Int> = _signalStrength.asStateFlow()
    
    private val _carrierName = MutableStateFlow("")
    val carrierName: StateFlow<String> = _carrierName.asStateFlow()
    
    // توابع برای به‌روزرسانی وضعیت BLE
    fun setBleActive(active: Boolean) {
        _bleActive.value = active
        if (active) {
            addActivityItem(ActivityItem(
                type = ActivityType.CONNECTION,
                message = "سرور BLE فعال شد",
                timestamp = System.currentTimeMillis()
            ))
        }
    }
    
    fun setConnectedDevice(deviceAddress: String?) {
        _connectedDevice.value = deviceAddress
        deviceAddress?.let {
            addActivityItem(ActivityItem(
                type = ActivityType.CONNECTION,
                message = "دستگاه متصل شد: $it",
                timestamp = System.currentTimeMillis()
            ))
        }
    }
    
    // توابع برای مدیریت وضعیت صوت
    fun setAudioStreaming(streaming: Boolean) {
        _audioStreaming.value = streaming
    }
    
    fun setSpeakerEnabled(enabled: Boolean) {
        _speakerEnabled.value = enabled
    }
    
    // توابع برای مدیریت تماس
    fun setActiveCall(call: CallState?) {
        _activeCall.value = call
        call?.let {
            addActivityItem(ActivityItem(
                type = ActivityType.CALL,
                message = "تماس ورودی از ${it.callerNumber}",
                timestamp = System.currentTimeMillis()
            ))
        }
    }
    
    fun endCall() {
        _activeCall.value = null
        _audioStreaming.value = false
        addActivityItem(ActivityItem(
            type = ActivityType.CALL,
            message = "تماس قطع شد",
            timestamp = System.currentTimeMillis()
        ))
    }
    
    // توابع برای مدیریت فعالیت‌ها
    fun addActivityItem(item: ActivityItem) {
        _activityItems.value = listOf(item) + _activityItems.value
    }
    
    fun addSmsActivity(sender: String, body: String, senderName: String? = null) {
        addActivityItem(ActivityItem(
            type = ActivityType.SMS,
            message = "پیامک از ${senderName ?: sender}",
            details = body,
            timestamp = System.currentTimeMillis()
        ))
    }
    
    // توابع برای به‌روزرسانی وضعیت دستگاه
    fun updateDeviceStatus(battery: Int, signal: Int, carrier: String) {
        _batteryLevel.value = battery
        _signalStrength.value = signal
        _carrierName.value = carrier
    }
}

/**
 * کلاس داده برای وضعیت تماس فعال
 */
data class CallState(
    val callId: String,
    val callerNumber: String,
    val callerName: String?,
    val startTime: Long
)

/**
 * کلاس داده برای آیتم‌های لیست فعالیت
 */
data class ActivityItem(
    val type: ActivityType,
    val message: String,
    val details: String? = null,
    val timestamp: Long
)

/**
 * انواع فعالیت‌ها
 */
enum class ActivityType {
    CONNECTION,
    CALL,
    SMS
}
