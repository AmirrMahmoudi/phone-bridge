package com.phonebridge.gateway.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CallLog
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * مدیریت تاریخچه تماس‌ها و sync به iOS
 */
class CallHistoryManager(private val context: Context) {
    
    companion object {
        private const val TAG = "CallHistoryManager"
        private const val MAX_HISTORY_ITEMS = 100 // محدود کردن به 100 تماس اخیر
        private const val CHUNK_SIZE = 10 // ارسال 10 تایی
    }
    
    /**
     * خواندن تاریخچه تماس‌های اخیر از Call Log
     */
    fun getRecentCallHistory(limit: Int = MAX_HISTORY_ITEMS): List<CallHistoryItem> {
        // بررسی permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) 
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_CALL_LOG permission not granted")
            return emptyList()
        }
        
        val callHistoryItems = mutableListOf<CallHistoryItem>()
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION
        )
        
        val sortOrder = "${CallLog.Calls.DATE} DESC"
        
        try {
            val cursor: Cursor? = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )
            
            cursor?.use {
                val idIndex = it.getColumnIndex(CallLog.Calls._ID)
                val numberIndex = it.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIndex = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val typeIndex = it.getColumnIndex(CallLog.Calls.TYPE)
                val dateIndex = it.getColumnIndex(CallLog.Calls.DATE)
                val durationIndex = it.getColumnIndex(CallLog.Calls.DURATION)
                
                var count = 0
                while (it.moveToNext() && count < limit) {
                    val id = if (idIndex >= 0) it.getLong(idIndex) else 0L
                    val number = if (numberIndex >= 0) it.getString(numberIndex) ?: "Unknown" else "Unknown"
                    val name = if (nameIndex >= 0) it.getString(nameIndex) else null
                    val type = if (typeIndex >= 0) it.getInt(typeIndex) else CallLog.Calls.INCOMING_TYPE
                    val date = if (dateIndex >= 0) it.getLong(dateIndex) else 0L
                    val duration = if (durationIndex >= 0) it.getLong(durationIndex) else 0L
                    
                    val callType = when (type) {
                        CallLog.Calls.INCOMING_TYPE -> CallType.INCOMING
                        CallLog.Calls.OUTGOING_TYPE -> CallType.OUTGOING
                        CallLog.Calls.MISSED_TYPE -> CallType.MISSED
                        CallLog.Calls.REJECTED_TYPE -> CallType.REJECTED
                        else -> CallType.UNKNOWN
                    }
                    
                    callHistoryItems.add(
                        CallHistoryItem(
                            id = id,
                            phoneNumber = number,
                            contactName = name,
                            callType = callType,
                            timestamp = date,
                            duration = duration
                        )
                    )
                    count++
                }
            }
            
            Log.i(TAG, "Retrieved ${callHistoryItems.size} call history items")
        } catch (e: Exception) {
            Log.e(TAG, "Error reading call history", e)
        }
        
        return callHistoryItems
    }
    
    /**
     * تبدیل تاریخچه تماس به JSON برای ارسال به iOS
     */
    fun convertToJSON(items: List<CallHistoryItem>): String {
        val jsonArray = JSONArray()
        
        for (item in items) {
            val jsonObject = JSONObject().apply {
                put("id", item.id)
                put("phoneNumber", item.phoneNumber)
                put("contactName", item.contactName ?: "")
                put("callType", item.callType.name)
                put("timestamp", item.timestamp)
                put("duration", item.duration)
                put("formattedDate", formatTimestamp(item.timestamp))
                put("formattedDuration", formatDuration(item.duration))
            }
            jsonArray.put(jsonObject)
        }
        
        val result = JSONObject().apply {
            put("type", "CALL_HISTORY")
            put("items", jsonArray)
            put("totalCount", items.size)
        }
        
        return result.toString()
    }
    
    /**
     * تقسیم تاریخچه به chunk های کوچکتر برای ارسال BLE
     */
    fun splitIntoChunks(items: List<CallHistoryItem>): List<CallHistoryChunk> {
        val chunks = mutableListOf<CallHistoryChunk>()
        val totalChunks = (items.size + CHUNK_SIZE - 1) / CHUNK_SIZE // round up
        
        for (chunkIndex in 0 until totalChunks) {
            val startIndex = chunkIndex * CHUNK_SIZE
            val endIndex = minOf(startIndex + CHUNK_SIZE, items.size)
            val chunkItems = items.subList(startIndex, endIndex)
            
            chunks.add(
                CallHistoryChunk(
                    chunkIndex = chunkIndex,
                    totalChunks = totalChunks,
                    items = chunkItems
                )
            )
        }
        
        Log.i(TAG, "Split ${items.size} items into ${chunks.size} chunks")
        return chunks
    }
    
    /**
     * تبدیل chunk به JSON
     */
    fun chunkToJSON(chunk: CallHistoryChunk): String {
        val itemsArray = JSONArray()
        for (item in chunk.items) {
            val jsonObject = JSONObject().apply {
                put("id", item.id)
                put("phoneNumber", item.phoneNumber)
                put("contactName", item.contactName ?: "")
                put("callType", item.callType.name)
                put("timestamp", item.timestamp)
                put("duration", item.duration)
                put("formattedDate", formatTimestamp(item.timestamp))
                put("formattedDuration", formatDuration(item.duration))
            }
            itemsArray.put(jsonObject)
        }
        
        val result = JSONObject().apply {
            put("type", "CALL_HISTORY_CHUNK")
            put("chunkIndex", chunk.chunkIndex)
            put("totalChunks", chunk.totalChunks)
            put("items", itemsArray)
        }
        
        return result.toString()
    }
    
    /**
     * فرمت کردن timestamp به تاریخ خوانا
     */
    private fun formatTimestamp(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
    
    /**
     * فرمت کردن مدت زمان تماس
     */
    private fun formatDuration(durationSeconds: Long): String {
        if (durationSeconds == 0L) return "0:00"
        
        val hours = durationSeconds / 3600
        val minutes = (durationSeconds % 3600) / 60
        val seconds = durationSeconds % 60
        
        return when {
            hours > 0 -> String.format("%d:%02d:%02d", hours, minutes, seconds)
            else -> String.format("%d:%02d", minutes, seconds)
        }
    }
    
    /**
     * دریافت آمار تاریخچه تماس
     */
    fun getCallHistoryStats(): CallHistoryStats {
        val items = getRecentCallHistory()
        
        val incomingCount = items.count { it.callType == CallType.INCOMING }
        val outgoingCount = items.count { it.callType == CallType.OUTGOING }
        val missedCount = items.count { it.callType == CallType.MISSED }
        val rejectedCount = items.count { it.callType == CallType.REJECTED }
        
        val totalDuration = items.sumOf { it.duration }
        
        return CallHistoryStats(
            totalCalls = items.size,
            incomingCalls = incomingCount,
            outgoingCalls = outgoingCount,
            missedCalls = missedCount,
            rejectedCalls = rejectedCount,
            totalDuration = totalDuration
        )
    }
}

// MARK: - Data Classes

/**
 * آیتم تاریخچه تماس
 */
data class CallHistoryItem(
    val id: Long,
    val phoneNumber: String,
    val contactName: String?,
    val callType: CallType,
    val timestamp: Long, // Unix timestamp in milliseconds
    val duration: Long   // Duration in seconds
)

/**
 * انواع تماس
 */
enum class CallType {
    INCOMING,   // تماس ورودی
    OUTGOING,   // تماس خروجی
    MISSED,     // تماس بی‌پاسخ
    REJECTED,   // تماس رد شده
    UNKNOWN     // نامشخص
}

/**
 * Chunk تاریخچه برای ارسال تدریجی
 */
data class CallHistoryChunk(
    val chunkIndex: Int,
    val totalChunks: Int,
    val items: List<CallHistoryItem>
)

/**
 * آمار تاریخچه تماس
 */
data class CallHistoryStats(
    val totalCalls: Int,
    val incomingCalls: Int,
    val outgoingCalls: Int,
    val missedCalls: Int,
    val rejectedCalls: Int,
    val totalDuration: Long // Total duration in seconds
) {
    val averageDuration: Long
        get() = if (totalCalls > 0) totalDuration / totalCalls else 0
}
