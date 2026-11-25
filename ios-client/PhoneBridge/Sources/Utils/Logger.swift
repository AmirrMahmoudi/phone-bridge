import Foundation
import OSLog

/**
 * سیستم جامع logging برای PhoneBridge
 * شامل: سطوح مختلف، دسته‌بندی، persistence، و export
 */
public class Logger {
    public static let shared = Logger()
    
    // MARK: - Log Levels
    
    public enum LogLevel: Int, Comparable {
        case verbose = 0
        case debug = 1
        case info = 2
        case warning = 3
        case error = 4
        case critical = 5
        
        public static func < (lhs: LogLevel, rhs: LogLevel) -> Bool {
            return lhs.rawValue < rhs.rawValue
        }
        
        var emoji: String {
            switch self {
            case .verbose: return "💬"
            case .debug: return "🐛"
            case .info: return "ℹ️"
            case .warning: return "⚠️"
            case .error: return "❌"
            case .critical: return "🔥"
            }
        }
        
        var osLogType: OSLogType {
            switch self {
            case .verbose, .debug: return .debug
            case .info: return .info
            case .warning, .error: return .error
            case .critical: return .fault
            }
        }
    }
    
    // MARK: - Log Categories
    
    public enum Category: String {
        case bluetooth = "Bluetooth"
        case audio = "Audio"
        case call = "Call"
        case sms = "SMS"
        case network = "Network"
        case ui = "UI"
        case storage = "Storage"
        case system = "System"
        case performance = "Performance"
    }
    
    // MARK: - Properties
    
    private let osLog: OSLog
    private var logEntries: [LogEntry] = []
    private let maxStoredLogs = 1000
    private let logQueue = DispatchQueue(label: "com.phonebridge.logger", qos: .utility)
    
    public var minimumLogLevel: LogLevel = .debug
    public var enableFileLogging: Bool = true
    public var enableConsoleLogging: Bool = true
    
    // MARK: - Initialization
    
    private init() {
        self.osLog = OSLog(subsystem: "com.phonebridge", category: "app")
        loadLogsFromDisk()
    }
    
    // MARK: - Logging Methods
    
    public func verbose(_ message: String, category: Category = .system, file: String = #file, function: String = #function, line: Int = #line) {
        log(message, level: .verbose, category: category, file: file, function: function, line: line)
    }
    
    public func debug(_ message: String, category: Category = .system, file: String = #file, function: String = #function, line: Int = #line) {
        log(message, level: .debug, category: category, file: file, function: function, line: line)
    }
    
    public func info(_ message: String, category: Category = .system, file: String = #file, function: String = #function, line: Int = #line) {
        log(message, level: .info, category: category, file: file, function: function, line: line)
    }
    
    public func warning(_ message: String, category: Category = .system, file: String = #file, function: String = #function, line: Int = #line) {
        log(message, level: .warning, category: category, file: file, function: function, line: line)
    }
    
    public func error(_ message: String, category: Category = .system, error: Error? = nil, file: String = #file, function: String = #function, line: Int = #line) {
        var fullMessage = message
        if let error = error {
            fullMessage += " | Error: \(error.localizedDescription)"
        }
        log(fullMessage, level: .error, category: category, file: file, function: function, line: line)
    }
    
    public func critical(_ message: String, category: Category = .system, file: String = #file, function: String = #function, line: Int = #line) {
        log(message, level: .critical, category: category, file: file, function: function, line: line)
    }
    
    // MARK: - Core Logging
    
    private func log(_ message: String, level: LogLevel, category: Category, file: String, function: String, line: Int) {
        guard level >= minimumLogLevel else { return }
        
        let fileName = URL(fileURLWithPath: file).lastPathComponent
        let entry = LogEntry(
            timestamp: Date(),
            level: level,
            category: category,
            message: message,
            file: fileName,
            function: function,
            line: line
        )
        
        logQueue.async { [weak self] in
            guard let self = self else { return }
            
            // Store in memory
            self.logEntries.append(entry)
            if self.logEntries.count > self.maxStoredLogs {
                self.logEntries.removeFirst()
            }
            
            // Console logging
            if self.enableConsoleLogging {
                self.logToConsole(entry)
            }
            
            // OS Log
            os_log("%{public}@", log: self.osLog, type: level.osLogType, entry.formattedMessage)
            
            // File logging (every 10 logs or critical)
            if self.enableFileLogging && (self.logEntries.count % 10 == 0 || level == .critical) {
                self.saveLogsToDisk()
            }
        }
    }
    
    private func logToConsole(_ entry: LogEntry) {
        print(entry.formattedMessage)
    }
    
    // MARK: - Persistence
    
    private var logsFileURL: URL {
        let documentsPath = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return documentsPath.appendingPathComponent("phonebridge_logs.json")
    }
    
    private func saveLogsToDisk() {
        do {
            let encoder = JSONEncoder()
            encoder.dateEncodingStrategy = .iso8601
            let data = try encoder.encode(logEntries)
            try data.write(to: logsFileURL, options: .atomic)
        } catch {
            print("Failed to save logs: \(error)")
        }
    }
    
    private func loadLogsFromDisk() {
        guard FileManager.default.fileExists(atPath: logsFileURL.path) else { return }
        
        do {
            let data = try Data(contentsOf: logsFileURL)
            let decoder = JSONDecoder()
            decoder.dateDecodingStrategy = .iso8601
            logEntries = try decoder.decode([LogEntry].self, from: data)
            print("📂 Loaded \(logEntries.count) log entries from disk")
        } catch {
            print("Failed to load logs: \(error)")
        }
    }
    
    // MARK: - Export & Analysis
    
    public func exportLogs() -> String {
        return logEntries.map { $0.formattedMessage }.joined(separator: "\n")
    }
    
    public func exportLogsToFile() -> URL? {
        let tempDir = FileManager.default.temporaryDirectory
        let exportURL = tempDir.appendingPathComponent("phonebridge_logs_\(Int(Date().timeIntervalSince1970)).txt")
        
        do {
            let logsText = exportLogs()
            try logsText.write(to: exportURL, atomically: true, encoding: .utf8)
            return exportURL
        } catch {
            print("Failed to export logs: \(error)")
            return nil
        }
    }
    
    public func getRecentLogs(count: Int = 100) -> [LogEntry] {
        return Array(logEntries.suffix(count))
    }
    
    public func getLogsByLevel(_ level: LogLevel) -> [LogEntry] {
        return logEntries.filter { $0.level == level }
    }
    
    public func getLogsByCategory(_ category: Category) -> [LogEntry] {
        return logEntries.filter { $0.category == category }
    }
    
    public func getErrorSummary() -> ErrorSummary {
        let errors = logEntries.filter { $0.level == .error || $0.level == .critical }
        let criticalCount = errors.filter { $0.level == .critical }.count
        let errorCount = errors.filter { $0.level == .error }.count
        let warningCount = logEntries.filter { $0.level == .warning }.count
        
        return ErrorSummary(
            totalErrors: errorCount,
            criticalErrors: criticalCount,
            warnings: warningCount,
            recentErrors: Array(errors.suffix(10))
        )
    }
    
    public func clearLogs() {
        logQueue.async { [weak self] in
            self?.logEntries.removeAll()
            try? FileManager.default.removeItem(at: self!.logsFileURL)
            print("🗑️ Logs cleared")
        }
    }
}

// MARK: - Log Entry

public struct LogEntry: Codable {
    public let timestamp: Date
    public let level: Logger.LogLevel
    public let category: Logger.Category
    public let message: String
    public let file: String
    public let function: String
    public let line: Int
    
    // Manual Codable implementation
    enum CodingKeys: String, CodingKey {
        case timestamp, level, category, message, file, function, line
    }
    
    public init(timestamp: Date, level: Logger.LogLevel, category: Logger.Category, message: String, file: String, function: String, line: Int) {
        self.timestamp = timestamp
        self.level = level
        self.category = category
        self.message = message
        self.file = file
        self.function = function
        self.line = line
    }
    
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        timestamp = try container.decode(Date.self, forKey: .timestamp)
        let levelRawValue = try container.decode(Int.self, forKey: .level)
        level = Logger.LogLevel(rawValue: levelRawValue) ?? .info
        let categoryRawValue = try container.decode(String.self, forKey: .category)
        category = Logger.Category(rawValue: categoryRawValue) ?? .system
        message = try container.decode(String.self, forKey: .message)
        file = try container.decode(String.self, forKey: .file)
        function = try container.decode(String.self, forKey: .function)
        line = try container.decode(Int.self, forKey: .line)
    }
    
    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(timestamp, forKey: .timestamp)
        try container.encode(level.rawValue, forKey: .level)
        try container.encode(category.rawValue, forKey: .category)
        try container.encode(message, forKey: .message)
        try container.encode(file, forKey: .file)
        try container.encode(function, forKey: .function)
        try container.encode(line, forKey: .line)
    }
    
    public var formattedMessage: String {
        let dateFormatter = DateFormatter()
        dateFormatter.dateFormat = "HH:mm:ss.SSS"
        let timeString = dateFormatter.string(from: timestamp)
        
        return "\(level.emoji) [\(timeString)] [\(category.rawValue)] \(message) (\(file):\(line))"
    }
}

// MARK: - Error Summary

public struct ErrorSummary {
    public let totalErrors: Int
    public let criticalErrors: Int
    public let warnings: Int
    public let recentErrors: [LogEntry]
    
    public var hasIssues: Bool {
        return totalErrors > 0 || criticalErrors > 0
    }
}

// MARK: - Convenience Extensions

extension Logger {
    public static func v(_ message: String, category: Category = .system) {
        shared.verbose(message, category: category)
    }
    
    public static func d(_ message: String, category: Category = .system) {
        shared.debug(message, category: category)
    }
    
    public static func i(_ message: String, category: Category = .system) {
        shared.info(message, category: category)
    }
    
    public static func w(_ message: String, category: Category = .system) {
        shared.warning(message, category: category)
    }
    
    public static func e(_ message: String, category: Category = .system, error: Error? = nil) {
        shared.error(message, category: category, error: error)
    }
    
    public static func c(_ message: String, category: Category = .system) {
        shared.critical(message, category: category)
    }
}
