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
 * شامل persistence برای recovery بدون وقفه در حین تماس
 */
class L2CAPChannelManager(private val bluetoothAdapter: BluetoothAdapter) {

    companion object {
        private const val TAG = "L2CAPChannelManager"
        private const val BUFFER_SIZE = 4096
        private const val MAX_BUFFERED_PACKETS = 50 // حداکثر 50 بسته (حدود 1 ثانیه audio در 50ms frames)
    }

    private var serverSocket: BluetoothServerSocket? = null
    var connectedSocket: BluetoothSocket? = null
    private var isListening = false
    var currentPsm: Int = 0
        private set

    var onConnected: ((BluetoothSocket) -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    
    // Persistence state برای recovery
    private var isInCall: Boolean = false
    private val audioPacketBuffer = java.util.concurrent.LinkedBlockingQueue<ByteArray>(MAX_BUFFERED_PACKETS)
    private var lastConnectionTime: Long = 0
    private var reconnectAttempts: Int = 0
    private val maxReconnectAttempts = 5

    /**
     * شروع گوش دادن به اتصالات L2CAP و دریافت PSM اختصاصی از سیستم
     * با persistence برای recovery بدون وقفه
     */
    fun startListening(duringCall: Boolean = false) {
        if (isListening) {
            Log.w(TAG, "L2CAP server already listening")
            return
        }
        
        isInCall = duringCall

        try {
            // ایجاد سوکت L2CAP (ابتدا insecure برای سازگاری اتصال مستقیم با iOS بدون الزام به PIN جفت‌سازی، سپس امن)
            serverSocket = try {
                bluetoothAdapter.listenUsingInsecureL2capChannel()
            } catch (e: Exception) {
                Log.w(TAG, "listenUsingInsecureL2capChannel not supported, falling back to listenUsingL2capChannel", e)
                bluetoothAdapter.listenUsingL2capChannel()
            }
            currentPsm = serverSocket?.psm ?: 0

            if (currentPsm == 0) {
                Log.e(TAG, "Failed to get PSM from L2CAP server socket")
                return
            }

            isListening = true
            Log.i(TAG, "L2CAP server listening on PSM $currentPsm (inCall=$isInCall, buffered=${audioPacketBuffer.size} packets)")

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
     * علامت‌گذاری اینکه تماس شروع شده (برای persistence)
     */
    fun setCallActive(active: Boolean) {
        val wasInCall = isInCall
        isInCall = active
        
        if (active && !wasInCall) {
            Log.i(TAG, "Call started - L2CAP persistence enabled")
            audioPacketBuffer.clear() // پاک کردن buffer قبلی
        } else if (!active && wasInCall) {
            Log.i(TAG, "Call ended - L2CAP persistence disabled")
            audioPacketBuffer.clear()
        }
    }
    
    /**
     * Buffer کردن یک audio packet برای ارسال موقع reconnect
     */
    fun bufferAudioPacket(packet: ByteArray) {
        if (!isInCall) return
        
        // اگر buffer پر است، قدیمی‌ترین packet رو حذف کن
        if (audioPacketBuffer.remainingCapacity() == 0) {
            audioPacketBuffer.poll() // حذف اولین المان
            Log.w(TAG, "Audio buffer full, dropping oldest packet")
        }
        
        audioPacketBuffer.offer(packet)
    }
    
    /**
     * ارسال تمام buffered packets بعد از reconnect
     */
    private fun flushBufferedPackets(socket: BluetoothSocket) {
        if (audioPacketBuffer.isEmpty()) return
        
        Log.i(TAG, "Flushing ${audioPacketBuffer.size} buffered audio packets...")
        
        try {
            val outputStream = socket.outputStream
            var sentCount = 0
            
            while (audioPacketBuffer.isNotEmpty()) {
                val packet = audioPacketBuffer.poll()
                if (packet != null) {
                    outputStream.write(packet)
                    sentCount++
                }
            }
            
            outputStream.flush()
            Log.i(TAG, "✅ Flushed $sentCount packets successfully")
            
        } catch (e: IOException) {
            Log.e(TAG, "❌ Error flushing buffered packets", e)
            audioPacketBuffer.clear()
        }
    }

    /**
     * حلقه پذیرش اتصالات ورودی با persistence support
     */
    private fun acceptConnections() {
        while (isListening) {
            try {
                Log.d(TAG, "Waiting for L2CAP client connection...")
                val socket = serverSocket?.accept()

                if (socket != null) {
                    val timeSinceLastConnection = System.currentTimeMillis() - lastConnectionTime
                    val isReconnect = lastConnectionTime > 0 && timeSinceLastConnection < 30000 // کمتر از 30 ثانیه
                    
                    Log.i(TAG, "✅ L2CAP client connected from ${socket.remoteDevice?.address} (reconnect=$isReconnect, inCall=$isInCall)")
                    
                    lastConnectionTime = System.currentTimeMillis()
                    connectedSocket = socket
                    reconnectAttempts = 0
                    
                    // اگر reconnect در حین تماس است، buffered packets رو بفرست
                    if (isReconnect && isInCall) {
                        Log.i(TAG, "🔄 Reconnected during call - restoring audio stream...")
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            flushBufferedPackets(socket)
                        }, 100) // کمی تاخیر برای اطمینان از ready بودن socket
                    }
                    
                    onConnected?.invoke(socket)
                }
            } catch (e: IOException) {
                if (isListening) {
                    reconnectAttempts++
                    Log.e(TAG, "Error accepting L2CAP connection (attempt $reconnectAttempts/$maxReconnectAttempts)", e)
                    
                    // اگر در حین تماس بود و به max attempts رسید، هشدار بده
                    if (isInCall && reconnectAttempts >= maxReconnectAttempts) {
                        Log.e(TAG, "❌ Failed to restore L2CAP connection during call after $reconnectAttempts attempts")
                        // پاک کردن buffer چون احتمالاً موفق نمی‌شیم
                        audioPacketBuffer.clear()
                    }
                    
                    // تلاش مجدد در صورت خطا
                    Thread.sleep(if (isInCall) 500 else 1000) // در حین تماس سریع‌تر retry کن
                } else {
                    Log.i(TAG, "L2CAP server stopped")
                    break
                }
            }
        }
    }

    /**
     * بستن کانال L2CAP با حفظ persistence state
     */
    fun stopListening(clearBuffer: Boolean = true) {
        isListening = false
        try {
            connectedSocket?.close()
            connectedSocket = null
            serverSocket?.close()
            serverSocket = null
            
            if (clearBuffer) {
                audioPacketBuffer.clear()
                isInCall = false
            }
            
            Log.i(TAG, "L2CAP server stopped and sockets closed (buffer ${if (clearBuffer) "cleared" else "preserved"})")
            onDisconnected?.invoke()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing L2CAP sockets", e)
        }
    }
}
