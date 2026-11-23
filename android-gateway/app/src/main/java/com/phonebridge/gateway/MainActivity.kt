package com.phonebridge.gateway

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.phonebridge.gateway.bluetooth.BLEPeripheralServer

class MainActivity : AppCompatActivity() {

    private lateinit var statusTextView: TextView
    private lateinit var btnToggleServer: Button
    private var bleServer: BLEPeripheralServer? = null

    companion object {
        private const val PERMISSION_REQUEST_CODE = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        statusTextView = TextView(this).apply {
            text = "وضعیت: در انتظار اعطای مجوزها و راه‌اندازی سرور"
            textSize = 18f
            setPadding(0, 0, 0, 32)
        }

        btnToggleServer = Button(this).apply {
            text = "شروع سرور بلوتوث"
            setOnClickListener {
                startServer()
            }
        }

        val btnSetDefaultDialer = Button(this).apply {
            text = "تنظیم به عنوان سرویس تماس پیش‌فرض (اختیاری)"
            setOnClickListener {
                requestDefaultDialerRole()
            }
        }

        layout.addView(statusTextView)
        layout.addView(btnToggleServer)
        layout.addView(btnSetDefaultDialer)
        setContentView(layout)

        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECORD_AUDIO
        )

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
            // در صورتی که همه دسترسی‌ها از قبل داده شده باشد، سرور را اتوماتیک فعال کن
            startServer()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            startServer()
        }
    }

    private fun requestDefaultDialerRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(android.app.role.RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(android.app.role.RoleManager.ROLE_DIALER)) {
                val intent = roleManager.createRequestRoleIntent(android.app.role.RoleManager.ROLE_DIALER)
                startActivity(intent)
            }
        } else {
            val telecomManager = getSystemService(TelecomManager::class.java)
            if (telecomManager != null && telecomManager.defaultDialerPackage != packageName) {
                val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                    putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
                }
                startActivity(intent)
            }
        }
    }

    private fun startServer() {
        if (bleServer == null) {
            bleServer = BLEPeripheralServer(this)
            bleServer?.startServer()
        }
        statusTextView.text = "وضعیت: سرور بلوتوث فعال و آماده اتصال آیفون ۱۷ است."
        btnToggleServer.isEnabled = false
    }
}
