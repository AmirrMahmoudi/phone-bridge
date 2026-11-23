package com.phonebridge.gateway.audio

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import kotlin.concurrent.thread

/**
 * مدیر کانال L2CAP برای انتقال صدا از اندروید به آیفون
 * این کلاس سوکت سرور L2CAP را ایجاد کرده و PSM دینامیک را از سیستم دریافت می‌کند
 */
class L2CAPChannelManager(private val bluetoothAdapter: BluetoothAdapter) {

    companion object {
        private const val TAG = "L2CAPChannelManager"
    }

    private var serverSocket: BluetoothServerSocket? = null
    var connectedSocket: BluetoothSocket? = null
    private var isListening = false
    var currentPsm: Int = 0
        private set

    var onConnected: ((BluetoothSocket) -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    /**
     * شروع گوش دادن به اتصالات L2CAP و دریافت PSM اختصاصی از سیستم
     */
    fun startListening() {
        if (isListening) {
            Log.w(TAG, "L2CAP server already listening")
            return
        }

        try {
            // ایجاد سوکت L2CAP با PSM دینامیک (سیستم خودکار PSM اختصاص می‌دهد)
            serverSocket = bluetoothAdapter.listenUsingL2capChannel()
            currentPsm = serverSocket?.psm ?: 0

            if (currentPsm == 0) {
                Log.e(TAG, "Failed to get PSM from L2CAP server socket")
                return
            }

            isListening = true
            Log.i(TAG, "L2CAP server listening on PSM $currentPsm")

            // پذیرش اتصالات در یک Thread جداگانه
            thread(name = "L2CAP-Accept-Thread") {
                acceptConnections()
            }

        } catch (e: IOException) {
            Log.e(TAG, "Error starting L2CAP server", e)
            isListening = false
        }
    }

    /**
     * حلقه پذیرش اتصالات ورودی
     */
    private fun acceptConnections() {
        while (isListening) {
            try {
                Log.d(TAG, "Waiting for L2CAP client connection...")
                val socket = serverSocket?.accept()

                if (socket != null) {
                    Log.i(TAG, "L2CAP client connected from ${socket.remoteDevice?.address}")
                    connectedSocket = socket
                    onConnected?.invoke(socket)
                }
            } catch (e: IOException) {
                if (isListening) {
                    Log.e(TAG, "Error accepting L2CAP connection, retrying...", e)
                    // تلاش مجدد در صورت خطا
                    Thread.sleep(1000)
                } else {
                    Log.i(TAG, "L2CAP server stopped")
                    break
                }
            }
        }
    }

    /**
     * بستن کانال L2CAP
     */
    fun stopListening() {
        isListening = false
        try {
            connectedSocket?.close()
            connectedSocket = null
            serverSocket?.close()
            serverSocket = null
            Log.i(TAG, "L2CAP server stopped and sockets closed")
            onDisconnected?.invoke()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing L2CAP sockets", e)
        }
    }
}
