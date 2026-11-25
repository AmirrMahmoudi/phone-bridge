package com.phonebridge.gateway

import android.Manifest
import android.app.role.RoleManager
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
import com.phonebridge.gateway.util.BatteryOptimizationHelper
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
        requestDefaultDialerRole()
        checkBatteryOptimization()
        startDeviceStatusUpdates()
    }

    private fun requestDefaultDialerRole() {
        val roleManager = getSystemService(RoleManager::class.java) ?: return
        if (roleManager.isRoleAvailable(RoleManager.ROLE_DIALER) &&
            !roleManager.isRoleHeld(RoleManager.ROLE_DIALER)
        ) {
            val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
            @Suppress("DEPRECATION")
            startActivityForResult(intent, 102)
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
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
        val serviceIntent = Intent(this, PhoneBridgeForegroundService::class.java).apply {
            action = PhoneBridgeForegroundService.ACTION_START
            putExtra(PhoneBridgeForegroundService.EXTRA_MESSAGE, "سرور PhoneBridge فعال است")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        viewModel.setBleActive(true)
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
     * بررسی و درخواست معافیت از بهینه‌سازی باتری با UI بهتر
     */
    private fun checkBatteryOptimization() {
        val dozeStatus = BatteryOptimizationHelper.getDozeStatus(this)
        
        // فقط در صورتی که OPTIMIZED باشد، دیالوگ نشان بده
        if (dozeStatus == BatteryOptimizationHelper.DozeStatus.OPTIMIZED) {
            AlertDialog.Builder(this)
                .setTitle("⚠️ هشدار مهم")
                .setMessage(
                    "PhoneBridge در حال حاضر تحت بهینه‌سازی باتری است.\n\n" +
                    "این ممکن است باعث شود:\n" +
                    "• تماس‌ها و پیامک‌ها با تاخیر دریافت شوند\n" +
                    "• اتصال بلوتوث در پس‌زمینه قطع شود\n" +
                    "• عملکرد برنامه ناپایدار شود\n\n" +
                    "برای عملکرد بهینه، لطفاً این برنامه را از بهینه‌سازی باتری معاف کنید."
                )
                .setPositiveButton("باز کردن تنظیمات") { _, _ ->
                    BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(this)
                }
                .setNegativeButton("فعلاً نه", null)
                .setCancelable(false)
                .show()
        }
    }
}
