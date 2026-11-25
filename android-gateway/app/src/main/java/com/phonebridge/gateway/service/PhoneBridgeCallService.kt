package com.phonebridge.gateway.service

import android.telecom.Call
import android.telecom.InCallService
import android.util.Log
import com.phonebridge.gateway.bluetooth.BLEPeripheralServer
import org.json.JSONObject
import java.util.UUID

/**
 * سرویس تلفنی پیشرفته برای رصد وضعیت تماس‌های ورودی و خروجی گوشی اندروید
 */
class PhoneBridgeCallService : InCallService() {

    companion object {
        private const val TAG = "PhoneBridgeCallService"
        var currentCall: Call? = null

        fun answerCurrentCall() {
            currentCall?.answer(0)
            Log.d(TAG, "Call answered via PhoneBridge.")
        }

        fun disconnectCurrentCall() {
            currentCall?.disconnect()
            Log.d(TAG, "Call disconnected via PhoneBridge.")
        }
    }

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call?, state: Int) {
            super.onStateChanged(call, state)
            when (state) {
                Call.STATE_RINGING -> {
                    val handle = call?.details?.handle?.schemeSpecificPart ?: "Unknown"
                    val callerName = call?.details?.callerDisplayName ?: ""
                    Log.d(TAG, "Incoming call ringing from: $handle")

                    val payload = JSONObject().apply {
                        put("type", "CALL_INCOMING")
                        put("callId", UUID.randomUUID().toString())
                        put("callerNumber", handle)
                        put("callerName", callerName)
                        put("timestamp", System.currentTimeMillis() / 1000)
                    }
                    BLEPeripheralServer.instance?.sendNotification(payload.toString())
                    
                    // به‌روزرسانی ViewModel
                    com.phonebridge.gateway.MainActivity.instance?.runOnUiThread {
                        com.phonebridge.gateway.MainActivity.instance?.let { activity ->
                            val viewModelStore = activity.viewModelStore
                            val viewModel = androidx.lifecycle.ViewModelProvider(viewModelStore, androidx.lifecycle.ViewModelProvider.NewInstanceFactory())
                                .get(com.phonebridge.gateway.viewmodel.PhoneBridgeViewModel::class.java)
                            viewModel.setActiveCall(
                                com.phonebridge.gateway.viewmodel.CallState(
                                    callId = UUID.randomUUID().toString(),
                                    callerNumber = handle,
                                    callerName = callerName.ifEmpty { null },
                                    startTime = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                }

                Call.STATE_ACTIVE -> {
                    Log.d(TAG, "Call is now active")
                    // شروع استریم صوت روی L2CAP برای تماس‌های ورودی و خروجی
                    BLEPeripheralServer.instance?.startAudioStreaming()
                    val activePayload = JSONObject().apply {
                        put("type", "CALL_ACTIVE")
                    }
                    BLEPeripheralServer.instance?.sendNotification(activePayload.toString())

                    // به‌روزرسانی وضعیت صوت
                    com.phonebridge.gateway.MainActivity.instance?.runOnUiThread {
                        com.phonebridge.gateway.MainActivity.instance?.let { activity ->
                            val viewModelStore = activity.viewModelStore
                            val viewModel = androidx.lifecycle.ViewModelProvider(viewModelStore, androidx.lifecycle.ViewModelProvider.NewInstanceFactory())
                                .get(com.phonebridge.gateway.viewmodel.PhoneBridgeViewModel::class.java)
                            viewModel.setAudioStreaming(true)
                        }
                    }
                }

                Call.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Call disconnected.")
                    BLEPeripheralServer.instance?.stopAudioStreaming()
                    val payload = JSONObject().apply {
                        put("type", "CALL_ENDED")
                        put("reason", "DISCONNECTED")
                    }
                    BLEPeripheralServer.instance?.sendNotification(payload.toString())
                    currentCall = null
                    
                    // به‌روزرسانی ViewModel
                    com.phonebridge.gateway.MainActivity.instance?.runOnUiThread {
                        com.phonebridge.gateway.MainActivity.instance?.let { activity ->
                            val viewModelStore = activity.viewModelStore
                            val viewModel = androidx.lifecycle.ViewModelProvider(viewModelStore, androidx.lifecycle.ViewModelProvider.NewInstanceFactory())
                                .get(com.phonebridge.gateway.viewmodel.PhoneBridgeViewModel::class.java)
                            viewModel.endCall()
                        }
                    }
                }
            }
        }
    }

    override fun onCallAdded(call: Call?) {
        super.onCallAdded(call)
        Log.d(TAG, "onCallAdded")
        currentCall = call
        call?.registerCallback(callCallback)
        
        // در صورتی که در همین لحظه در وضعیت زنگ خوردن باشد
        if (call?.state == Call.STATE_RINGING) {
            val handle = call.details?.handle?.schemeSpecificPart ?: "Unknown"
            val callerName = call.details?.callerDisplayName ?: ""
            val payload = JSONObject().apply {
                put("type", "CALL_INCOMING")
                put("callId", UUID.randomUUID().toString())
                put("callerNumber", handle)
                put("callerName", callerName)
                put("timestamp", System.currentTimeMillis() / 1000)
            }
            BLEPeripheralServer.instance?.sendNotification(payload.toString())
        }
    }

    override fun onCallRemoved(call: Call?) {
        super.onCallRemoved(call)
        Log.d(TAG, "onCallRemoved")
        call?.unregisterCallback(callCallback)
        if (currentCall == call) {
            currentCall = null
        }
    }
}
