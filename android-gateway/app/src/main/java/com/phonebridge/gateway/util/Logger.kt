package com.phonebridge.gateway.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * سیستم جامع logging برای PhoneBridge
 */
object Logger {
    
    // MARK: - Log Levels
    
    enum class LogLevel(val priority: Int, val emoji: String) {
        VERBOSE(Log.VERBOSE, "💬"),
        DEBUG(Log.DEBUG, "🐛"),
        INFO(Log.INFO, "ℹ️"),
        WARNING(Log.WARN, "⚠️"),
        ERROR(Log.ERROR, "❌"),
        CRITICAL(Log.ASSERT, "🔥");
        
        companion object {
            fun fromPriority(priority: Int): LogLevel {
                return values().find { it.priority == priority } ?: DEBUG
            }
        }
    }
    
    // MARK: - Log Categories
    
    enum class Category(val tag: String) {
        BLUETOOTH("Bluetooth"),
        AUDIO("Audio"),
        CALL("Call"),
        SMS("SMS"),
        NETWORK("Network"),
        UI("UI"),
        STORAGE("Storage"),
        SYSTEM("System"),
        PERFORMANCE("Performance")
    }
    
    // MARK: - Properties
    
    private const val TAG = "PhoneBridge"
    private const val MAX_STORED_LOGS = 1000
    private val logEntries = mutableListOf<LogEntry>()
    private val mutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO)
    
    var minimumLogLevel: LogLevel = LogLevel.DEBUG
    var enableFileLogging: Boolean = true
    var enableConsoleLogging: Boolean = true
    
    private var context: Context? = null
    
    fun initialize(context: Context) {
        this.context = context.applicationContext
        loadLogsFromDisk()
    }
    
    // MARK: - Logging Methods
    
    fun v(message: String, category: Category = Category.SYSTEM) {
        log(message, LogLevel.VERBOSE, category)
    }
    
    fun d(message: String, category: Category = Category.SYSTEM) {
        log(message, LogLevel.DEBUG, category)
    }
    
    fun i(message: String, category: Category = Category.SYSTEM) {
        log(message, LogLevel.INFO, category)
    }
    
    fun w(message: String, category: Category = Category.SYSTEM) {
        log(message, LogLevel.WARNING, category)
    }
    
    fun e(message: String, category: Category = Category.SYSTEM, throwable: Throwable? = null) {
        var fullMessage = message
        if (throwable != null) {
            fullMessage += " | Exception: ${throwable.message}"
        }
        log(fullMessage, LogLevel.ERROR, category, throwable)
    }
    
    fun c(message: String, category: Category = Category.SYSTEM) {
        log(message, LogLevel.CRITICAL, category)
    }
    
    // MARK: - Core Logging
    
    private fun log(
        message: String,
        level: LogLevel,
        category: Category,
        throwable: Throwable? = null
    ) {
        if (level.priority < minimumLogLevel.priority) return
        
        val stackTrace = Thread.currentThread().stackTrace
        val caller = stackTrace.getOrNull(5) // تلاش برای یافتن caller واقعی
        
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            category = category,
            message = message,
            fileName = caller?.fileName ?: "Unknown",
            methodName = caller?.methodName ?: "Unknown",
            lineNumber = caller?.lineNumber ?: 0,
            throwable = throwable
        )
        
        scope.launch {
            mutex.withLock {
                // Store in memory
                logEntries.add(entry)
                if (logEntries.size > MAX_STORED_LOGS) {
                    logEntries.removeAt(0)
                }
                
                // Console logging
                if (enableConsoleLogging) {
                    logToConsole(entry)
                }
                
                // File logging (every 10 logs or critical)
                if (enableFileLogging && (logEntries.size % 10 == 0 || level == LogLevel.CRITICAL)) {
                    saveLogsToDisk()
                }
            }
        }
    }
    
    private fun logToConsole(entry: LogEntry) {
        val tag = "${TAG}:${entry.category.tag}"
        when (entry.level) {
            LogLevel.VERBOSE -> Log.v(tag, entry.formattedMessage, entry.throwable)
            LogLevel.DEBUG -> Log.d(tag, entry.formattedMessage, entry.throwable)
            LogLevel.INFO -> Log.i(tag, entry.formattedMessage, entry.throwable)
            LogLevel.WARNING -> Log.w(tag, entry.formattedMessage, entry.throwable)
            LogLevel.ERROR -> Log.e(tag, entry.formattedMessage, entry.throwable)
            LogLevel.CRITICAL -> Log.wtf(tag, entry.formattedMessage, entry.throwable)
        }
    }
    
    // MARK: - Persistence
    
    private fun getLogsFile(): File? {
        return context?.filesDir?.let { File(it, "phonebridge_logs.json") }
    }
    
    private fun saveLogsToDisk() {
        val file = getLogsFile() ?: return
        
        try {
            val jsonArray = JSONArray()
            logEntries.forEach { entry ->
                jsonArray.put(entry.toJSON())
            }
            
            file.writeText(jsonArray.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save logs", e)
        }
    }
    
    private fun loadLogsFromDisk() {
        val file = getLogsFile() ?: return
        if (!file.exists()) return
        
        try {
            val jsonString = file.readText()
            val jsonArray = JSONArray(jsonString)
            
            logEntries.clear()
            for (i in 0 until jsonArray.length()) {
                val jsonObject = jsonArray.getJSONObject(i)
                logEntries.add(LogEntry.fromJSON(jsonObject))
            }
            
            Log.i(TAG, "📂 Loaded ${logEntries.size} log entries from disk")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load logs", e)
        }
    }
    
    // MARK: - Export & Analysis
    
    fun exportLogs(): String {
        return logEntries.joinToString("\n") { it.formattedMessage }
    }
    
    fun exportLogsToFile(): File? {
        val context = context ?: return null
        val file = File(context.cacheDir, "phonebridge_logs_${System.currentTimeMillis()}.txt")
        
        return try {
            file.writeText(exportLogs())
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export logs", e)
            null
        }
    }
    
    fun getRecentLogs(count: Int = 100): List<LogEntry> {
        return logEntries.takeLast(count)
    }
    
    fun getLogsByLevel(level: LogLevel): List<LogEntry> {
        return logEntries.filter { it.level == level }
    }
    
    fun getLogsByCategory(category: Category): List<LogEntry> {
        return logEntries.filter { it.category == category }
    }
    
    fun getErrorSummary(): ErrorSummary {
        val errors = logEntries.filter { it.level == LogLevel.ERROR || it.level == LogLevel.CRITICAL }
        val criticalCount = errors.count { it.level == LogLevel.CRITICAL }
        val errorCount = errors.count { it.level == LogLevel.ERROR }
        val warningCount = logEntries.count { it.level == LogLevel.WARNING }
        
        return ErrorSummary(
            totalErrors = errorCount,
            criticalErrors = criticalCount,
            warnings = warningCount,
            recentErrors = errors.takeLast(10)
        )
    }
    
    fun clearLogs() {
        scope.launch {
            mutex.withLock {
                logEntries.clear()
                getLogsFile()?.delete()
                Log.i(TAG, "🗑️ Logs cleared")
            }
        }
    }
}

// MARK: - Log Entry

data class LogEntry(
    val timestamp: Long,
    val level: Logger.LogLevel,
    val category: Logger.Category,
    val message: String,
    val fileName: String,
    val methodName: String,
    val lineNumber: Int,
    val throwable: Throwable? = null
) {
    val formattedMessage: String
        get() {
            val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            val timeString = sdf.format(Date(timestamp))
            return "${level.emoji} [$timeString] [${category.tag}] $message ($fileName:$lineNumber)"
        }
    
    fun toJSON(): JSONObject {
        return JSONObject().apply {
            put("timestamp", timestamp)
            put("level", level.name)
            put("category", category.name)
            put("message", message)
            put("fileName", fileName)
            put("methodName", methodName)
            put("lineNumber", lineNumber)
            if (throwable != null) {
                put("exception", throwable.message)
            }
        }
    }
    
    companion object {
        fun fromJSON(json: JSONObject): LogEntry {
            return LogEntry(
                timestamp = json.getLong("timestamp"),
                level = Logger.LogLevel.valueOf(json.getString("level")),
                category = Logger.Category.valueOf(json.getString("category")),
                message = json.getString("message"),
                fileName = json.getString("fileName"),
                methodName = json.getString("methodName"),
                lineNumber = json.getInt("lineNumber"),
                throwable = null // Не можем восстановить throwable из JSON
            )
        }
    }
}

// MARK: - Error Summary

data class ErrorSummary(
    val totalErrors: Int,
    val criticalErrors: Int,
    val warnings: Int,
    val recentErrors: List<LogEntry>
) {
    val hasIssues: Boolean
        get() = totalErrors > 0 || criticalErrors > 0
}
