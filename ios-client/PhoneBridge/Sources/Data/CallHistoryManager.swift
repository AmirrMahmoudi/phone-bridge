import Foundation
import Combine

/**
 * مدیریت تاریخچه تماس‌های sync شده از Android
 */
public class CallHistoryManager: ObservableObject {
    public static let shared = CallHistoryManager()
    
    // MARK: - Published Properties
    
    @Published public var callHistory: [CallHistoryItem] = []
    @Published public var stats: CallHistoryStats?
    @Published public var syncStatus: CallHistorySyncStatus = .idle
    
    // MARK: - Private Properties
    
    private var receivedChunks: [Int: CallHistoryChunk] = [:]
    private var expectedChunks: Int = 0
    private let storageKey = "savedCallHistory"
    private let statsKey = "savedCallHistoryStats"
    
    // MARK: - Initialization
    
    private init() {
        loadFromStorage()
    }
    
    // MARK: - Request History
    
    /**
     * درخواست تاریخچه تماس از Android
     */
    public func requestCallHistory() {
        guard let bleClient = BLEClient.shared as BLEClient? else {
            syncStatus = .failed(error: "BLE not connected")
            return
        }
        
        // Reset state
        receivedChunks.removeAll()
        expectedChunks = 0
        syncStatus = .syncing(progress: 0.0)
        
        // ارسال درخواست
        bleClient.sendCommand(["cmd": "REQUEST_CALL_HISTORY"])
        bleClient.log("📞 Requesting call history from Android...")
    }
    
    // MARK: - Process Incoming Data
    
    /**
     * پردازش داده‌های دریافتی از Android
     */
    public func processIncomingData(_ json: [String: Any]) {
        guard let type = json["type"] as? String else { return }
        
        switch type {
        case "CALL_HISTORY_STATS":
            handleStats(json)
            
        case "CALL_HISTORY_CHUNK":
            handleChunk(json)
            
        case "CALL_HISTORY_COMPLETE":
            handleComplete(json)
            
        case "CALL_HISTORY_RESPONSE":
            if let status = json["status"] as? String, status == "EMPTY" {
                DispatchQueue.main.async {
                    self.syncStatus = .completed(itemCount: 0)
                    self.callHistory = []
                }
            }
            
        case "CALL_HISTORY_ERROR":
            let error = json["error"] as? String ?? "Unknown error"
            DispatchQueue.main.async {
                self.syncStatus = .failed(error: error)
            }
            
        default:
            break
        }
    }
    
    /**
     * پردازش آمار کلی
     */
    private func handleStats(_ json: [String: Any]) {
        do {
            let jsonData = try JSONSerialization.data(withJSONObject: json)
            let stats = try JSONDecoder().decode(CallHistoryStats.self, from: jsonData)
            
            DispatchQueue.main.async {
                self.stats = stats
                print("[CallHistory] 📊 Stats received: \(stats.totalCalls) total calls")
            }
        } catch {
            print("[CallHistory] Error decoding stats: \(error)")
        }
    }
    
    /**
     * پردازش chunk دریافتی
     */
    private func handleChunk(_ json: [String: Any]) {
        do {
            let jsonData = try JSONSerialization.data(withJSONObject: json)
            let chunk = try JSONDecoder().decode(CallHistoryChunk.self, from: jsonData)
            
            receivedChunks[chunk.chunkIndex] = chunk
            expectedChunks = chunk.totalChunks
            
            // محاسبه progress
            let progress = Double(receivedChunks.count) / Double(expectedChunks)
            
            DispatchQueue.main.async {
                self.syncStatus = .syncing(progress: progress)
                print("[CallHistory] 📦 Received chunk \(chunk.chunkIndex + 1)/\(chunk.totalChunks) (\(chunk.items.count) items)")
            }
            
        } catch {
            print("[CallHistory] Error decoding chunk: \(error)")
        }
    }
    
    /**
     * پردازش پایان sync
     */
    private func handleComplete(_ json: [String: Any]) {
        // ترکیب تمام chunk ها
        let sortedChunks = receivedChunks.keys.sorted()
        var allItems: [CallHistoryItem] = []
        
        for index in sortedChunks {
            if let chunk = receivedChunks[index] {
                allItems.append(contentsOf: chunk.items)
            }
        }
        
        DispatchQueue.main.async {
            self.callHistory = allItems
            self.syncStatus = .completed(itemCount: allItems.count)
            print("[CallHistory] ✅ Sync complete: \(allItems.count) items")
            
            // ذخیره در storage
            self.saveToStorage()
        }
        
        // پاک کردن chunk های موقت
        receivedChunks.removeAll()
        expectedChunks = 0
    }
    
    // MARK: - Storage
    
    /**
     * ذخیره تاریخچه در UserDefaults
     */
    private func saveToStorage() {
        do {
            let encoder = JSONEncoder()
            let data = try encoder.encode(callHistory)
            UserDefaults.standard.set(data, forKey: storageKey)
            
            if let stats = stats {
                let statsData = try encoder.encode(stats)
                UserDefaults.standard.set(statsData, forKey: statsKey)
            }
            
            print("[CallHistory] 💾 Saved to storage")
        } catch {
            print("[CallHistory] Error saving to storage: \(error)")
        }
    }
    
    /**
     * بارگذاری تاریخچه از UserDefaults
     */
    private func loadFromStorage() {
        do {
            let decoder = JSONDecoder()
            
            if let data = UserDefaults.standard.data(forKey: storageKey) {
                callHistory = try decoder.decode([CallHistoryItem].self, from: data)
                print("[CallHistory] 📂 Loaded \(callHistory.count) items from storage")
            }
            
            if let statsData = UserDefaults.standard.data(forKey: statsKey) {
                stats = try decoder.decode(CallHistoryStats.self, from: statsData)
                print("[CallHistory] 📊 Loaded stats from storage")
            }
            
        } catch {
            print("[CallHistory] Error loading from storage: \(error)")
        }
    }
    
    // MARK: - Filtering & Search
    
    /**
     * فیلتر کردن بر اساس نوع تماس
     */
    public func filterByType(_ type: CallType) -> [CallHistoryItem] {
        return callHistory.filter { $0.callType == type }
    }
    
    /**
     * جستجو در تاریخچه
     */
    public func search(query: String) -> [CallHistoryItem] {
        guard !query.isEmpty else { return callHistory }
        
        let lowercaseQuery = query.lowercased()
        return callHistory.filter { item in
            item.phoneNumber.lowercased().contains(lowercaseQuery) ||
            (item.contactName?.lowercased().contains(lowercaseQuery) ?? false)
        }
    }
    
    /**
     * دریافت تماس‌های اخیر (محدود)
     */
    public func getRecent(limit: Int = 20) -> [CallHistoryItem] {
        return Array(callHistory.prefix(limit))
    }
    
    /**
     * دریافت تماس‌های بی‌پاسخ
     */
    public var missedCalls: [CallHistoryItem] {
        return filterByType(.missed)
    }
    
    /**
     * پاک کردن تاریخچه
     */
    public func clearHistory() {
        callHistory.removeAll()
        stats = nil
        syncStatus = .idle
        
        UserDefaults.standard.removeObject(forKey: storageKey)
        UserDefaults.standard.removeObject(forKey: statsKey)
        
        print("[CallHistory] 🗑️ History cleared")
    }
}
