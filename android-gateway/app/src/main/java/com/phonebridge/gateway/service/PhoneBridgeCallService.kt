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
                    // ⚠️ CALL_INCOMING فقط از onCallAdded فرستاده می‌شود — اینجا نه
                    // این از duplicate notification جلوگیری می‌کند
                    val handle = call?.details?.handle?.schemeSpecificPart ?: "Unknown"
                    val callerName = call?.details?.callerDisplayName ?: ""
                    Log.d(TAG, "Incoming call ringing from: $handle (notification sent by onCallAdded)")
                    
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
                    val fg = com.phonebridge.gateway.service.PhoneBridgeForegroundService.instance
                    val shouldBridgeAudio = (fg?.isAnsweredFromIphone() == true) || (fg?.isOutgoingFromIphone() == true)

                    if (shouldBridgeAudio) {
                        Log.d(TAG, "Call is now ACTIVE (iPhone call) — starting audio streaming")
                        BLEPeripheralServer.instance?.startAudioStreaming()

                        val activePayload = JSONObject().apply {
                            put("type", "CALL_ACTIVE")
                        }
                        BLEPeripheralServer.instance?.sendNotification(activePayload.toString())
                        Log.d(TAG, "CALL_ACTIVE sent to iPhone — audio streaming started")
                    } else {
                        Log.d(TAG, "Call is now ACTIVE locally on Android — skipping iPhone audio bridge")
                    }

                    // به‌روزرسانی وضعیت صوت
                    com.phonebridge.gateway.MainActivity.instance?.runOnUiThread {
                        com.phonebridge.gateway.MainActivity.instance?.let { activity ->
                            val viewModelStore = activity.viewModelStore
                            val viewModel = androidx.lifecycle.ViewModelProvider(viewModelStore, androidx.lifecycle.ViewModelProvider.NewInstanceFactory())
                                .get(com.phonebridge.gateway.viewmodel.PhoneBridgeViewModel::class.java)
                            viewModel.setAudioStreaming(shouldBridgeAudio)
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
        Log.d(TAG, "onCallAdded: state=${call?.state}")
        currentCall = call
        call?.registerCallback(callCallback)
        
        // ⚠️ CALL_INCOMING را اینجا نمی‌فرستیم!
        // PhoneBridgeForegroundService.broadcastIncomingCall() این کار را می‌کند
        // و اطلاعات کامل‌تری (شماره، نام مخاطب) از ContactHelper دارد.
        // این جلوگیری از duplicate notification می‌کند.
        Log.d(TAG, "Call registered — CALL_INCOMING will be sent by ForegroundService or CallReceiver")
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
