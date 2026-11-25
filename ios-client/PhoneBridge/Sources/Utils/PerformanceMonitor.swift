import Foundation
import UIKit

/**
 * مانیتورینگ performance و آمار عملکرد برنامه
 * 
 * Note: برای production، استفاده از Firebase Performance Monitoring توصیه می‌شود
 */
public class PerformanceMonitor {
    public static let shared = PerformanceMonitor()
    
    // MARK: - Metrics
    
    private var metrics: [String: PerformanceMetric] = [:]
    private var traces: [String: TraceData] = [:]
    
    // App lifecycle metrics
    @Published public var appLaunchTime: TimeInterval = 0
    @Published public var memoryUsage: UInt64 = 0
    @Published public var cpuUsage: Double = 0
    
    // Connection metrics
    @Published public var bleConnectionCount: Int = 0
    @Published public var l2capConnectionCount: Int = 0
    @Published public var averageReconnectTime: TimeInterval = 0
    
    // Audio metrics
    @Published public var totalAudioPacketsSent: Int = 0
    @Published public var totalAudioPacketsReceived: Int = 0
    @Published public var audioPacketsDropped: Int = 0
    @Published public var averageAudioLatency: TimeInterval = 0
    
    // Call metrics
    @Published public var totalCallsHandled: Int = 0
    @Published public var missedCallsCount: Int = 0
    @Published public var averageCallDuration: TimeInterval = 0
    
    private let monitorTimer: Timer
    
    // MARK: - Initialization
    
    private init() {
        // Monitor every 5 seconds
        monitorTimer = Timer.scheduledTimer(withTimeInterval: 5.0, repeats: true) { [weak self] _ in
            self?.updateSystemMetrics()
        }
        
        Logger.i("Performance Monitor initialized", category: .performance)
    }
    
    // MARK: - Trace Management
    
    /**
     * شروع یک trace برای اندازه‌گیری عملیات
     */
    public func startTrace(_ name: String) {
        traces[name] = TraceData(startTime: Date())
        Logger.v("Started trace: \(name)", category: .performance)
    }
    
    /**
     * پایان trace و ثبت مدت زمان
     */
    public func stopTrace(_ name: String) {
        guard let trace = traces[name] else {
            Logger.w("No trace found for: \(name)", category: .performance)
            return
        }
        
        let duration = Date().timeIntervalSince(trace.startTime)
        recordMetric(name, value: duration, unit: .seconds)
        traces.removeValue(forKey: name)
        
        Logger.v("Stopped trace: \(name) - Duration: \(String(format: "%.3f", duration))s", category: .performance)
    }
    
    /**
     * اندازه‌گیری مدت زمان اجرای یک closure
     */
    public func measure<T>(_ name: String, operation: () throws -> T) rethrows -> T {
        startTrace(name)
        defer { stopTrace(name) }
        return try operation()
    }
    
    // MARK: - Metrics Recording
    
    /**
     * ثبت یک metric
     */
    public func recordMetric(_ name: String, value: Double, unit: MetricUnit = .milliseconds) {
        if var existingMetric = metrics[name] {
            existingMetric.addValue(value)
            metrics[name] = existingMetric
        } else {
            metrics[name] = PerformanceMetric(name: name, unit: unit, value: value)
        }
        
        Logger.v("Recorded metric: \(name) = \(value) \(unit.rawValue)", category: .performance)
    }
    
    /**
     * افزایش یک counter
     */
    public func incrementCounter(_ name: String, by amount: Int = 1) {
        if var existingMetric = metrics[name] {
            existingMetric.addValue(Double(amount))
            metrics[name] = existingMetric
        } else {
            metrics[name] = PerformanceMetric(name: name, unit: .count, value: Double(amount))
        }
    }
    
    // MARK: - System Metrics
    
    private func updateSystemMetrics() {
        // Memory usage
        var info = mach_task_basic_info()
        var count = mach_msg_type_number_t(MemoryLayout<mach_task_basic_info>.size)/4
        
        let kerr: kern_return_t = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: 1) {
                task_info(mach_task_self_, task_flavor_t(MACH_TASK_BASIC_INFO), $0, &count)
            }
        }
        
        if kerr == KERN_SUCCESS {
            memoryUsage = info.resident_size
        }
        
        // CPU usage (simplified)
        var threadsList: thread_act_array_t?
        var threadsCount = mach_msg_type_number_t(0)
        let threadsResult = task_threads(mach_task_self_, &threadsList, &threadsCount)
        
        if threadsResult == KERN_SUCCESS, let threads = threadsList {
            var totalCPU: Double = 0
            
            for i in 0..<Int(threadsCount) {
                var threadInfo = thread_basic_info()
                var threadInfoCount = mach_msg_type_number_t(THREAD_INFO_MAX)
                
                let infoResult = withUnsafeMutablePointer(to: &threadInfo) {
                    $0.withMemoryRebound(to: integer_t.self, capacity: 1) {
                        thread_info(threads[i], thread_flavor_t(THREAD_BASIC_INFO), $0, &threadInfoCount)
                    }
                }
                
                if infoResult == KERN_SUCCESS {
                    let cpuUsage = Double(threadInfo.cpu_usage) / Double(TH_USAGE_SCALE)
                    totalCPU += cpuUsage * 100.0
                }
            }
            
            cpuUsage = totalCPU
            vm_deallocate(mach_task_self_, vm_address_t(bitPattern: threads), vm_size_t(Int(threadsCount) * MemoryLayout<thread_t>.stride))
        }
    }
    
    // MARK: - Reporting
    
    /**
     * دریافت خلاصه عملکرد
     */
    public func getPerformanceSummary() -> PerformanceSummary {
        return PerformanceSummary(
            appLaunchTime: appLaunchTime,
            memoryUsageMB: Double(memoryUsage) / 1024.0 / 1024.0,
            cpuUsagePercent: cpuUsage,
            bleConnectionCount: bleConnectionCount,
            l2capConnectionCount: l2capConnectionCount,
            totalCallsHandled: totalCallsHandled,
            missedCallsCount: missedCallsCount,
            audioPacketsSent: totalAudioPacketsSent,
            audioPacketsReceived: totalAudioPacketsReceived,
            audioPacketsDropped: audioPacketsDropped,
            metrics: Array(metrics.values)
        )
    }
    
    /**
     * Export metrics به JSON
     */
    public func exportMetrics() -> String {
        var json: [String: Any] = [:]
        
        json["appLaunchTime"] = appLaunchTime
        json["memoryUsageMB"] = Double(memoryUsage) / 1024.0 / 1024.0
        json["cpuUsagePercent"] = cpuUsage
        json["bleConnectionCount"] = bleConnectionCount
        json["l2capConnectionCount"] = l2capConnectionCount
        json["totalCallsHandled"] = totalCallsHandled
        json["audioPacketsSent"] = totalAudioPacketsSent
        json["audioPacketsReceived"] = totalAudioPacketsReceived
        
        var metricsArray: [[String: Any]] = []
        for metric in metrics.values {
            metricsArray.append([
                "name": metric.name,
                "count": metric.count,
                "sum": metric.sum,
                "average": metric.average,
                "min": metric.min,
                "max": metric.max,
                "unit": metric.unit.rawValue
            ])
        }
        json["metrics"] = metricsArray
        
        if let jsonData = try? JSONSerialization.data(withJSONObject: json, options: .prettyPrinted),
           let jsonString = String(data: jsonData, encoding: .utf8) {
            return jsonString
        }
        
        return "{}"
    }
    
    /**
     * پاک کردن تمام metrics
     */
    public func reset() {
        metrics.removeAll()
        traces.removeAll()
        
        bleConnectionCount = 0
        l2capConnectionCount = 0
        totalCallsHandled = 0
        missedCallsCount = 0
        totalAudioPacketsSent = 0
        totalAudioPacketsReceived = 0
        audioPacketsDropped = 0
        
        Logger.i("Performance metrics reset", category: .performance)
    }
}

// MARK: - Data Structures

private struct TraceData {
    let startTime: Date
}

public struct PerformanceMetric {
    let name: String
    let unit: MetricUnit
    var count: Int = 0
    var sum: Double = 0
    var min: Double = Double.greatestFiniteMagnitude
    var max: Double = -Double.greatestFiniteMagnitude
    
    init(name: String, unit: MetricUnit, value: Double? = nil) {
        self.name = name
        self.unit = unit
        if let value = value {
            self.addValue(value)
        }
    }
    
    mutating func addValue(_ value: Double) {
        count += 1
        sum += value
        min = Swift.min(min, value)
        max = Swift.max(max, value)
    }
    
    var average: Double {
        return count > 0 ? sum / Double(count) : 0
    }
}

public enum MetricUnit: String {
    case milliseconds = "ms"
    case seconds = "s"
    case bytes = "bytes"
    case count = "count"
    case percent = "%"
}

public struct PerformanceSummary {
    public let appLaunchTime: TimeInterval
    public let memoryUsageMB: Double
    public let cpuUsagePercent: Double
    public let bleConnectionCount: Int
    public let l2capConnectionCount: Int
    public let totalCallsHandled: Int
    public let missedCallsCount: Int
    public let audioPacketsSent: Int
    public let audioPacketsReceived: Int
    public let audioPacketsDropped: Int
    public let metrics: [PerformanceMetric]
    
    public var audioPacketLossRate: Double {
        let total = audioPacketsSent + audioPacketsReceived
        return total > 0 ? (Double(audioPacketsDropped) / Double(total)) * 100.0 : 0.0
    }
    
    public var missedCallRate: Double {
        return totalCallsHandled > 0 ? (Double(missedCallsCount) / Double(totalCallsHandled)) * 100.0 : 0.0
    }
}
