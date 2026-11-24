import Foundation

/**
 * مدل‌های تاریخچه تماس
 */

// MARK: - Call History Item

public struct CallHistoryItem: Identifiable, Codable {
    public let id: Int64
    public let phoneNumber: String
    public let contactName: String?
    public let callType: CallType
    public let timestamp: Int64 // Unix timestamp in milliseconds
    public let duration: Int64   // Duration in seconds
    public let formattedDate: String
    public let formattedDuration: String
    
    enum CodingKeys: String, CodingKey {
        case id
        case phoneNumber
        case contactName
        case callType
        case timestamp
        case duration
        case formattedDate
        case formattedDuration
    }
    
    public var date: Date {
        return Date(timeIntervalSince1970: Double(timestamp) / 1000.0)
    }
    
    public var typeIcon: String {
        switch callType {
        case .incoming: return "phone.arrow.down.left"
        case .outgoing: return "phone.arrow.up.right"
        case .missed: return "phone.down"
        case .rejected: return "phone.down.fill"
        case .unknown: return "phone"
        }
    }
    
    public var typeColor: String {
        switch callType {
        case .incoming: return "green"
        case .outgoing: return "blue"
        case .missed: return "red"
        case .rejected: return "orange"
        case .unknown: return "gray"
        }
    }
    
    public var displayName: String {
        return contactName ?? phoneNumber
    }
}

// MARK: - Call Type

public enum CallType: String, Codable {
    case incoming = "INCOMING"
    case outgoing = "OUTGOING"
    case missed = "MISSED"
    case rejected = "REJECTED"
    case unknown = "UNKNOWN"
    
    public var localizedDescription: String {
        switch self {
        case .incoming: return "تماس ورودی"
        case .outgoing: return "تماس خروجی"
        case .missed: return "بی‌پاسخ"
        case .rejected: return "رد شده"
        case .unknown: return "نامشخص"
        }
    }
}

// MARK: - Call History Stats

public struct CallHistoryStats: Codable {
    public let totalCalls: Int
    public let incomingCalls: Int
    public let outgoingCalls: Int
    public let missedCalls: Int
    
    enum CodingKeys: String, CodingKey {
        case totalCalls
        case incomingCalls
        case outgoingCalls
        case missedCalls
    }
    
    public var missedCallsPercentage: Double {
        return totalCalls > 0 ? (Double(missedCalls) / Double(totalCalls)) * 100.0 : 0.0
    }
    
    public var answeredCallsPercentage: Double {
        let answered = incomingCalls + outgoingCalls - missedCalls
        return totalCalls > 0 ? (Double(answered) / Double(totalCalls)) * 100.0 : 0.0
    }
}

// MARK: - Call History Chunk

public struct CallHistoryChunk: Codable {
    public let chunkIndex: Int
    public let totalChunks: Int
    public let items: [CallHistoryItem]
    
    enum CodingKeys: String, CodingKey {
        case chunkIndex
        case totalChunks
        case items
    }
}

// MARK: - Sync Status

public enum CallHistorySyncStatus {
    case idle
    case syncing(progress: Double) // 0.0 to 1.0
    case completed(itemCount: Int)
    case failed(error: String)
    
    public var description: String {
        switch self {
        case .idle:
            return "در انتظار"
        case .syncing(let progress):
            return "در حال دریافت... \(Int(progress * 100))%"
        case .completed(let count):
            return "دریافت شد (\(count) تماس)"
        case .failed(let error):
            return "خطا: \(error)"
        }
    }
    
    public var isSyncing: Bool {
        if case .syncing = self {
            return true
        }
        return false
    }
}
