import SwiftUI

/**
 * نمایش تاریخچه تماس‌های sync شده از Android
 */
struct CallHistoryView: View {
    @StateObject private var historyManager = CallHistoryManager.shared
    @State private var searchText: String = ""
    @State private var selectedFilter: CallType? = nil
    
    private var filteredHistory: [CallHistoryItem] {
        var items = historyManager.callHistory
        
        // فیلتر بر اساس نوع
        if let filter = selectedFilter {
            items = items.filter { $0.callType == filter }
        }
        
        // جستجو
        if !searchText.isEmpty {
            items = items.filter { item in
                item.phoneNumber.contains(searchText) ||
                (item.contactName?.contains(searchText) ?? false)
            }
        }
        
        return items
    }
    
    var body: some View {
        VStack(spacing: 0) {
            // Header با آمار
            if let stats = historyManager.stats {
                statsHeader(stats)
            }
            
            // Search bar
            HStack {
                Image(systemName: "magnifyingglass")
                    .foregroundColor(.gray)
                TextField("جستجو...", text: $searchText)
                    .textFieldStyle(RoundedBorderTextFieldStyle())
            }
            .padding()
            
            // Filter chips
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 12) {
                    FilterChip(title: "همه", isSelected: selectedFilter == nil) {
                        selectedFilter = nil
                    }
                    FilterChip(title: "ورودی", isSelected: selectedFilter == .incoming) {
                        selectedFilter = .incoming
                    }
                    FilterChip(title: "خروجی", isSelected: selectedFilter == .outgoing) {
                        selectedFilter = .outgoing
                    }
                    FilterChip(title: "بی‌پاسخ", isSelected: selectedFilter == .missed) {
                        selectedFilter = .missed
                    }
                }
                .padding(.horizontal)
            }
            .padding(.bottom)
            
            // Status banner
            if historyManager.syncStatus.isSyncing {
                HStack {
                    ProgressView()
                    Text(historyManager.syncStatus.description)
                        .font(.caption)
                }
                .padding()
                .frame(maxWidth: .infinity)
                .background(Color.blue.opacity(0.1))
            }
            
            // List
            if filteredHistory.isEmpty {
                emptyState
            } else {
                List(filteredHistory) { item in
                    CallHistoryRow(item: item)
                }
                .listStyle(PlainListStyle())
            }
        }
        .navigationTitle("تاریخچه تماس‌ها")
        .navigationBarTitleDisplayMode(.large)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button(action: {
                    historyManager.requestCallHistory()
                }) {
                    Image(systemName: "arrow.clockwise")
                }
                .disabled(historyManager.syncStatus.isSyncing)
            }
        }
    }
    
    // MARK: - Stats Header
    
    @ViewBuilder
    private func statsHeader(_ stats: CallHistoryStats) -> some View {
        VStack(spacing: 8) {
            HStack(spacing: 20) {
                StatBadge(icon: "phone.arrow.down.left", value: "\(stats.incomingCalls)", label: "ورودی", color: .green)
                StatBadge(icon: "phone.arrow.up.right", value: "\(stats.outgoingCalls)", label: "خروجی", color: .blue)
                StatBadge(icon: "phone.down", value: "\(stats.missedCalls)", label: "بی‌پاسخ", color: .red)
            }
            .padding(.vertical, 12)
            
            Text("مجموع: \(stats.totalCalls) تماس")
                .font(.caption)
                .foregroundColor(.gray)
        }
        .padding()
        .background(Color(.systemGray6))
    }
    
    // MARK: - Empty State
    
    private var emptyState: some View {
        VStack(spacing: 16) {
            Image(systemName: "phone.down.circle")
                .font(.system(size: 60))
                .foregroundColor(.gray)
            
            Text("هیچ تماسی یافت نشد")
                .font(.title3)
                .fontWeight(.semibold)
            
            Text("برای دریافت تاریخچه، دکمه بارگذاری را بزنید")
                .font(.caption)
                .foregroundColor(.gray)
                .multilineTextAlignment(.center)
            
            Button(action: {
                historyManager.requestCallHistory()
            }) {
                HStack {
                    Image(systemName: "arrow.down.circle")
                    Text("بارگذاری تاریخچه")
                }
                .padding()
                .background(Color.blue)
                .foregroundColor(.white)
                .cornerRadius(10)
            }
            .disabled(historyManager.syncStatus.isSyncing)
        }
        .padding()
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// MARK: - Call History Row

struct CallHistoryRow: View {
    let item: CallHistoryItem
    
    var body: some View {
        HStack(spacing: 12) {
            // Icon
            Circle()
                .fill(iconColor.opacity(0.2))
                .frame(width: 44, height: 44)
                .overlay(
                    Image(systemName: item.typeIcon)
                        .foregroundColor(iconColor)
                )
            
            // Info
            VStack(alignment: .leading, spacing: 4) {
                Text(item.displayName)
                    .font(.headline)
                
                HStack(spacing: 8) {
                    Text(item.callType.localizedDescription)
                        .font(.caption)
                        .foregroundColor(.gray)
                    
                    Text("•")
                        .foregroundColor(.gray)
                    
                    Text(item.formattedDuration)
                        .font(.caption)
                        .foregroundColor(.gray)
                }
            }
            
            Spacer()
            
            // Date
            VStack(alignment: .trailing, spacing: 4) {
                Text(formatDate(item.date))
                    .font(.caption)
                    .foregroundColor(.gray)
                
                Text(formatTime(item.date))
                    .font(.caption2)
                    .foregroundColor(.gray)
            }
        }
        .padding(.vertical, 4)
    }
    
    private var iconColor: Color {
        switch item.callType {
        case .incoming: return .green
        case .outgoing: return .blue
        case .missed: return .red
        case .rejected: return .orange
        case .unknown: return .gray
        }
    }
    
    private func formatDate(_ date: Date) -> String {
        let formatter = DateFormatter()
        
        if Calendar.current.isDateInToday(date) {
            return "امروز"
        } else if Calendar.current.isDateInYesterday(date) {
            return "دیروز"
        } else {
            formatter.dateFormat = "d MMM"
            return formatter.string(from: date)
        }
    }
    
    private func formatTime(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm"
        return formatter.string(from: date)
    }
}

// MARK: - Filter Chip

struct FilterChip: View {
    let title: String
    let isSelected: Bool
    let action: () -> Void
    
    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.caption)
                .fontWeight(isSelected ? .semibold : .regular)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(isSelected ? Color.blue : Color(.systemGray5))
                .foregroundColor(isSelected ? .white : .primary)
                .cornerRadius(16)
        }
    }
}

// MARK: - Stat Badge

struct StatBadge: View {
    let icon: String
    let value: String
    let label: String
    let color: Color
    
    var body: some View {
        VStack(spacing: 4) {
            Image(systemName: icon)
                .foregroundColor(color)
            Text(value)
                .font(.title2)
                .fontWeight(.bold)
            Text(label)
                .font(.caption2)
                .foregroundColor(.gray)
        }
        .frame(maxWidth: .infinity)
    }
}

// MARK: - Preview

struct CallHistoryView_Previews: PreviewProvider {
    static var previews: some View {
        NavigationView {
            CallHistoryView()
        }
    }
}
