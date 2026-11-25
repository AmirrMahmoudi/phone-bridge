import SwiftUI

/// مدل داده برای هر رویداد در لیست فعالیت‌ها
struct ActivityItem: Identifiable {
    let id = UUID()
    let icon: String
    let title: String
    let subtitle: String
    let timestamp: Date
    let fullBody: String? // برای ذخیره متن کامل پیامک
    let sender: String? // برای ذخیره شماره/نام فرستنده SMS
}

/// مدیریت لیست رویدادهای اپلیکیشن (تماس‌ها، پیامک‌ها، تغییرات اتصال)
class ActivityLog: ObservableObject {
    @Published var items: [ActivityItem] = []
    
    /// افزودن رویداد جدید با محدودیت 50 آیتم
    func add(icon: String, title: String, subtitle: String = "", fullBody: String? = nil, sender: String? = nil) {
        let newItem = ActivityItem(icon: icon, title: title, subtitle: subtitle, timestamp: Date(), fullBody: fullBody, sender: sender)
        DispatchQueue.main.async {
            self.items.insert(newItem, at: 0)
            if self.items.count > 50 {
                self.items = Array(self.items.prefix(50))
            }
        }
    }
}

struct ContentView: View {
    @EnvironmentObject var bleClient: BLEClient
    @EnvironmentObject var callManager: CallManager
    @StateObject private var activityLog = ActivityLog()
    @State private var showSettings = false

    var body: some View {
        TabView {
            NavigationStack {
                VStack(spacing: 0) {
                    // کارت وضعیت اتصال در بالای صفحه
                    ConnectionStatusCard()
                        .padding()
                    
                    // نمایش وضعیت تماس فعال در صورت وجود
                    if callManager.activeCallUUID != nil {
                        ActiveCallIndicator()
                            .padding(.horizontal)
                            .padding(.bottom, 8)
                    }
                    
                    // لیست رویدادها
                    ActivityFeed(activityLog: activityLog)
                }
                .navigationTitle("PhoneBridge")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .navigationBarTrailing) {
                        Button(action: { showSettings = true }) {
                            Image(systemName: "gearshape")
                        }
                    }
                }
                .sheet(isPresented: $showSettings) {
                    SettingsSheet()
                }
                .onAppear {
                    setupActivityLogging()
                }
            }
            .tabItem {
                Label("وضعیت", systemImage: "antenna.radiowaves.left.and.right")
            }

            DialerView()
                .tabItem {
                    Label("شماره‌گیر", systemImage: "phone.fill")
                }

            SmsComposerView()
                .tabItem {
                    Label("پیامک", systemImage: "message.fill")
                }

            CallHistoryView()
                .tabItem {
                    Label("تاریخچه", systemImage: "clock.fill")
                }
        }
    }
    
    /// راه‌اندازی لاگینگ خودکار رویدادها از BLEClient
    private func setupActivityLogging() {
        bleClient.onActivityEvent = { icon, title, subtitle, fullBody, sender in
            activityLog.add(icon: icon, title: title, subtitle: subtitle, fullBody: fullBody, sender: sender)
        }
    }
}

/// کارت نمایش وضعیت اتصال با جزئیات دستگاه
struct ConnectionStatusCard: View {
    @EnvironmentObject var bleClient: BLEClient
    
    var body: some View {
        VStack(spacing: 16) {
            HStack(spacing: 16) {
                // دایره وضعیت اتصال
                ZStack {
                    Circle()
                        .fill(bleClient.isConnected ? Color.green.opacity(0.2) : Color.red.opacity(0.2))
                        .frame(width: 50, height: 50)
                    
                    Circle()
                        .fill(bleClient.isConnected ? Color.green : Color.red)
                        .frame(width: 20, height: 20)
                }
                
                VStack(alignment: .leading, spacing: 4) {
                    Text(bleClient.isConnected ? "متصل" : "قطع ارتباط")
                        .font(.headline)
                        .foregroundColor(.primary)
                    
                    Text(bleClient.deviceName)
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                    
                    // نمایش کیفیت اتصال
                    if bleClient.isConnected {
                        HStack(spacing: 4) {
                            Image(systemName: bleClient.connectionQuality.icon)
                                .foregroundColor(qualityColor(bleClient.connectionQuality))
                                .font(.caption)
                            Text(bleClient.connectionQuality.description)
                                .font(.caption)
                                .foregroundColor(qualityColor(bleClient.connectionQuality))
                            Text("(\(bleClient.rssi) dBm)")
                                .font(.caption2)
                                .foregroundColor(.secondary)
                        }
                    }
                }
                
                Spacer()
            }
            
            if bleClient.isConnected {
                Divider()
                
                // اطلاعات دستگاه: اپراتور، باتری، سیگنال
                HStack(spacing: 20) {
                    HStack(spacing: 6) {
                        Image(systemName: "antenna.radiowaves.left.and.right")
                            .foregroundColor(.blue)
                        Text(bleClient.simCarrier)
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    
                    HStack(spacing: 6) {
                        Image(systemName: batteryIcon(level: bleClient.batteryLevel))
                            .foregroundColor(batteryColor(level: bleClient.batteryLevel))
                        Text("\(bleClient.batteryLevel)%")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    
                    HStack(spacing: 6) {
                        Image(systemName: "cellularbars")
                            .foregroundColor(.green)
                        Text("قوی")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    
                    Spacer()
                }
            }
        }
        .padding()
        .background(
            RoundedRectangle(cornerRadius: 16)
                .fill(Color(uiColor: .secondarySystemBackground))
                .shadow(color: Color.black.opacity(0.05), radius: 8, x: 0, y: 2)
        )
    }
    
    private func batteryIcon(level: Int) -> String {
        if level > 75 { return "battery.100" }
        if level > 50 { return "battery.75" }
        if level > 25 { return "battery.50" }
        if level > 10 { return "battery.25" }
        return "battery.0"
    }
    
    private func batteryColor(level: Int) -> Color {
        if level > 20 { return .green }
        return .red
    }
    
    private func qualityColor(_ quality: BLEClient.ConnectionQuality) -> Color {
        switch quality {
        case .excellent, .good: return .green
        case .fair: return .orange
        case .poor: return .red
        case .unknown: return .gray
        }
    }
}

/// نمایشگر تماس فعال با تایمر شمارنده زمان
struct ActiveCallIndicator: View {
    @EnvironmentObject var callManager: CallManager
    @State private var callDuration: TimeInterval = 0
    @State private var timer: Timer?
    
    var body: some View {
        HStack(spacing: 12) {
            // آیکون تماس در حال انجام
            ZStack {
                Circle()
                    .fill(Color.green.opacity(0.2))
                    .frame(width: 44, height: 44)
                
                Image(systemName: "phone.fill")
                    .foregroundColor(.green)
                    .font(.system(size: 18))
            }
            
            VStack(alignment: .leading, spacing: 2) {
                Text(callManager.callerName.isEmpty ? "در حال تماس..." : callManager.callerName)
                    .font(.headline)
                    .foregroundColor(.primary)
                
                Text(formatDuration(callDuration))
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                    .monospacedDigit()
            }
            
            Spacer()
            
            // انیمیشن پالس برای نشان دادن تماس فعال
            Circle()
                .fill(Color.green)
                .frame(width: 8, height: 8)
                .overlay(
                    Circle()
                        .stroke(Color.green, lineWidth: 2)
                        .scaleEffect(1.5)
                        .opacity(0.5)
                )
        }
        .padding()
        .background(
            RoundedRectangle(cornerRadius: 12)
                .fill(Color.green.opacity(0.1))
                .overlay(
                    RoundedRectangle(cornerRadius: 12)
                        .strokeBorder(Color.green.opacity(0.3), lineWidth: 1)
                )
        )
        .onAppear {
            startTimer()
        }
        .onDisappear {
            stopTimer()
        }
    }
    
    private func startTimer() {
        callDuration = 0
        timer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { _ in
            callDuration += 1
        }
    }
    
    private func stopTimer() {
        timer?.invalidate()
        timer = nil
    }
    
    private func formatDuration(_ duration: TimeInterval) -> String {
        let hours = Int(duration) / 3600
        let minutes = (Int(duration) % 3600) / 60
        let seconds = Int(duration) % 60
        
        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            return String(format: "%02d:%02d", minutes, seconds)
        }
    }
}

/// لیست رویدادهای اخیر (تماس‌ها، پیامک‌ها، تغییرات اتصال)
struct ActivityFeed: View {
    @EnvironmentObject var bleClient: BLEClient
    @ObservedObject var activityLog: ActivityLog
    
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("رویدادهای اخیر")
                .font(.headline)
                .padding(.horizontal)
                .padding(.top, 8)
            
            if activityLog.items.isEmpty && bleClient.logs.isEmpty {
                VStack(spacing: 16) {
                    Spacer()
                    
                    Image(systemName: "clock.arrow.circlepath")
                        .font(.system(size: 50))
                        .foregroundColor(.secondary)
                    
                    VStack(spacing: 4) {
                        Text("رویدادی ثبت نشده")
                            .font(.headline)
                            .foregroundColor(.primary)
                        
                        Text("تماس‌ها و پیامک‌های دریافتی اینجا نمایش داده می‌شوند")
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 32)
                    }
                    
                    Spacer()
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    LazyVStack(spacing: 0) {
                        // نمایش رویدادهای جدید از ActivityLog
                        ForEach(activityLog.items) { item in
                            ActivityItemRow(item: item)
                            Divider()
                                .padding(.leading, 60)
                        }
                        
                        // نمایش لاگ‌های قدیمی از BLEClient
                        ForEach(Array(bleClient.logs.enumerated()), id: \.offset) { index, log in
                            LegacyLogRow(log: log)
                            if index < bleClient.logs.count - 1 {
                                Divider()
                                    .padding(.leading, 60)
                            }
                        }
                    }
                }
            }
        }
    }
}

/// نمایش یک رویداد در لیست
struct ActivityItemRow: View {
    let item: ActivityItem
    @State private var showSMSDetail = false
    
    var body: some View {
        Button(action: {
            if item.icon == "message" && item.fullBody != nil {
                showSMSDetail = true
            }
        }) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: item.icon)
                    .font(.system(size: 24))
                    .foregroundColor(.blue)
                    .frame(width: 36, height: 36)
                
                VStack(alignment: .leading, spacing: 4) {
                    Text(item.title)
                        .font(.body)
                        .foregroundColor(.primary)
                    
                    if !item.subtitle.isEmpty {
                        Text(item.subtitle)
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    
                    Text(item.timestamp, style: .relative)
                        .font(.caption2)
                        .foregroundColor(.secondary)
                }
                
                Spacer()
                
                if item.icon == "message" {
                    Image(systemName: "chevron.right")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }
            .padding(.vertical, 12)
            .padding(.horizontal)
        }
        .buttonStyle(PlainButtonStyle())
        .sheet(isPresented: $showSMSDetail) {
            if let body = item.fullBody, let sender = item.sender {
                SMSDetailView(sender: sender, messageBody: body, timestamp: item.timestamp)
            }
        }
    }
}

/// نمایش لاگ‌های قدیمی از سیستم BLEClient
struct LegacyLogRow: View {
    let log: String
    
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "text.alignleft")
                .font(.system(size: 20))
                .foregroundColor(.secondary)
                .frame(width: 36, height: 36)
            
            Text(log)
                .font(.system(size: 13, design: .monospaced))
                .foregroundColor(.primary)
                .lineLimit(nil)
            
            Spacer()
        }
        .padding(.vertical, 12)
        .padding(.horizontal)
    }
}

/// صفحه تنظیمات با دکمه‌های کنترل اتصال
struct SettingsSheet: View {
    @EnvironmentObject var bleClient: BLEClient
    @Environment(\.dismiss) var dismiss
    
    var body: some View {
        NavigationStack {
            Form {
                Section("اتصال بلوتوث") {
                    HStack {
                        Text("وضعیت")
                        Spacer()
                        if bleClient.isReconnecting {
                            HStack(spacing: 4) {
                                ProgressView()
                                    .scaleEffect(0.8)
                                Text("در حال اتصال مجدد...")
                                    .foregroundColor(.orange)
                            }
                        } else {
                            Text(bleClient.isConnected ? "متصل" : "قطع")
                                .foregroundColor(bleClient.isConnected ? .green : .red)
                        }
                    }
                    
                    if bleClient.isConnected {
                        Button(role: .destructive) {
                            bleClient.disconnect()
                        } label: {
                            Label("قطع اتصال", systemImage: "wifi.slash")
                        }
                    } else {
                        Button {
                            bleClient.startScanning()
                        } label: {
                            Label("اتصال مجدد", systemImage: "arrow.clockwise")
                        }
                    }
                }
                
                Section("تنظیمات صوت") {
                    HStack {
                        Text("کیفیت صدا")
                        Spacer()
                        Text("بالا")
                            .foregroundColor(.secondary)
                    }
                    
                    HStack {
                        Text("کاهش نویز")
                        Spacer()
                        Text("فعال")
                            .foregroundColor(.secondary)
                    }
                    
                    Text("تنظیمات صوتی در نسخه‌های بعدی فعال خواهد شد")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                
                Section("درباره") {
                    HStack {
                        Text("نسخه")
                        Spacer()
                        Text("1.0.0")
                            .foregroundColor(.secondary)
                    }
                }
            }
            .navigationTitle("تنظیمات")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("بستن") {
                        dismiss()
                    }
                }
            }
        }
    }
}

