package com.phonebridge.gateway

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.phonebridge.gateway.bluetooth.BLEPeripheralServer
import com.phonebridge.gateway.service.PhoneBridgeForegroundService
import com.phonebridge.gateway.ui.screens.MainScreen
import com.phonebridge.gateway.ui.theme.PhoneBridgeTheme
import com.phonebridge.gateway.viewmodel.PhoneBridgeViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * اکتیویتی اصلی اپلیکیشن Phone Bridge با Jetpack Compose
 */
class MainActivity : ComponentActivity() {

    private val viewModel: PhoneBridgeViewModel by viewModels()
    private var bleServer: BLEPeripheralServer? = null
    private var audioManager: AudioManager? = null

    companion object {
        private const val PERMISSION_REQUEST_CODE = 101
        var instance: MainActivity? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        
        setContent {
            PhoneBridgeTheme {
                MainScreen(
                    viewModel = viewModel,
                    onToggleSpeaker = { toggleSpeaker() }
                )
            }
        }

        checkAndRequestPermissions()
        checkBatteryOptimization()
        startDeviceStatusUpdates()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            startServer()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            startServer()
        }
    }

    private fun startServer() {
        if (bleServer == null) {
            bleServer = BLEPeripheralServer(this)
            bleServer?.startServer()
            viewModel.setBleActive(true)
            
            // شروع سرویس Foreground
            val serviceIntent = Intent(this, PhoneBridgeForegroundService::class.java).apply {
                action = PhoneBridgeForegroundService.ACTION_START
                putExtra(PhoneBridgeForegroundService.EXTRA_MESSAGE, "سرور BLE فعال است")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
    }

    /**
     * تغییر وضعیت اسپیکر
     */
    private fun toggleSpeaker() {
        val currentSpeaker = viewModel.speakerEnabled.value
        val newSpeaker = !currentSpeaker
        
        audioManager?.let { am ->
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = newSpeaker
            viewModel.setSpeakerEnabled(newSpeaker)
        }
    }

    /**
     * به‌روزرسانی دوره‌ای وضعیت دستگاه
     */
    private fun startDeviceStatusUpdates() {
        lifecycleScope.launch {
            while (true) {
                updateDeviceStatus()
                delay(5000) // هر 5 ثانیه یک‌بار
            }
        }
    }

    private fun updateDeviceStatus() {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val batteryLevel = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0

        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val carrierName = telephonyManager?.networkOperatorName ?: ""

        viewModel.updateDeviceStatus(batteryLevel, 0, carrierName)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
    
    /**
     * بررسی و درخواست معافیت از بهینه‌سازی باتری
     */
    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            val packageName = packageName
            
            if (powerManager?.isIgnoringBatteryOptimizations(packageName) == false) {
                // نمایش دیالوگ درخواست معافیت
                AlertDialog.Builder(this)
                    .setTitle("درخواست معافیت از بهینه‌سازی باتری")
                    .setMessage("برای اطمینان از عملکرد مداوم PhoneBridge در پس‌زمینه، لطفاً این برنامه را از بهینه‌سازی باتری معاف کنید.")
                    .setPositiveButton("تنظیمات") { _, _ ->
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:$packageName")
                            }
                            startActivity(intent)
                        } catch (e: Exception) {
                            android.util.Log.e("MainActivity", "Error opening battery optimization settings", e)
                        }
                    }
                    .setNegativeButton("بعداً", null)
                    .show()
            }
        }
    }
}
