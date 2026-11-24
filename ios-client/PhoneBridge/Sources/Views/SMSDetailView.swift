import SwiftUI

/// صفحه نمایش جزئیات کامل پیامک
struct SMSDetailView: View {
    let sender: String
    let body: String
    let timestamp: Date
    
    @Environment(\.dismiss) var dismiss
    @State private var showCopyConfirmation = false
    
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    // اطلاعات فرستنده
                    VStack(alignment: .leading, spacing: 8) {
                        Text("فرستنده")
                            .font(.caption)
                            .foregroundColor(.secondary)
                        
                        Text(sender)
                            .font(.title2)
                            .fontWeight(.semibold)
                    }
                    .padding()
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color(.systemGray6))
                    .cornerRadius(12)
                    
                    // زمان دریافت
                    VStack(alignment: .leading, spacing: 8) {
                        Text("زمان دریافت")
                            .font(.caption)
                            .foregroundColor(.secondary)
                        
                        Text(formatDate(timestamp))
                            .font(.body)
                    }
                    .padding()
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color(.systemGray6))
                    .cornerRadius(12)
                    
                    // متن پیامک
                    VStack(alignment: .leading, spacing: 8) {
                        Text("متن پیام")
                            .font(.caption)
                            .foregroundColor(.secondary)
                        
                        Text(body)
                            .font(.body)
                            .textSelection(.enabled)
                            .lineLimit(nil)
                    }
                    .padding()
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color(.systemGray6))
                    .cornerRadius(12)
                    
                    // دکمه کپی
                    Button(action: copyMessage) {
                        HStack {
                            Image(systemName: "doc.on.doc")
                            Text("کپی متن پیام")
                        }
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(Color.blue)
                        .foregroundColor(.white)
                        .cornerRadius(12)
                    }
                    
                    if showCopyConfirmation {
                        HStack {
                            Image(systemName: "checkmark.circle.fill")
                                .foregroundColor(.green)
                            Text("متن پیام کپی شد")
                                .font(.subheadline)
                        }
                        .padding()
                        .frame(maxWidth: .infinity)
                        .background(Color.green.opacity(0.1))
                        .cornerRadius(8)
                    }
                }
                .padding()
            }
            .navigationTitle("جزئیات پیامک")
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
    
    /// کپی کردن متن پیام به کلیپ‌بورد
    private func copyMessage() {
        UIPasteboard.general.string = body
        showCopyConfirmation = true
        
        // پنهان کردن پیام تایید بعد از 2 ثانیه
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
            showCopyConfirmation = false
        }
    }
    
    /// فرمت کردن تاریخ به فرمت قابل خواندن
    private func formatDate(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .medium
        formatter.locale = Locale(identifier: "fa_IR")
        return formatter.string(from: date)
    }
}

#Preview {
    SMSDetailView(
        sender: "09123456789",
        body: "این یک پیام تستی است که برای نمایش در حالت پیش‌نمایش استفاده می‌شود.",
        timestamp: Date()
    )
}
