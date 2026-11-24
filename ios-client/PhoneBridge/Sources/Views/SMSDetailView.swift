import SwiftUI

/// صفحه نمایش جزئیات کامل پیامک و امکان پاسخ مستقیم با سیم‌کارت اندروید
struct SMSDetailView: View {
    let sender: String
    let messageBody: String
    let timestamp: Date
    
    @Environment(\.dismiss) var dismiss
    @EnvironmentObject var bleClient: BLEClient
    
    @State private var replyText: String = ""
    @State private var showCopyConfirmation = false
    @State private var showSentConfirmation = false
    
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
                        
                        Text(messageBody)
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
                        .background(Color(.systemGray5))
                        .foregroundColor(.primary)
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
                    
                    Divider()
                        .padding(.vertical, 8)
                    
                    // بخش پاسخ سریع از طریق سیم‌کارت اندروید
                    VStack(alignment: .leading, spacing: 12) {
                        Text("پاسخ سریع با سیم‌کارت اندروید")
                            .font(.headline)
                        
                        HStack {
                            TextField("متن پیامک پاسخ...", text: $replyText)
                                .padding()
                                .background(Color(.systemGray6))
                                .cornerRadius(10)
                            
                            Button(action: sendReply) {
                                Image(systemName: "arrow.up.circle.fill")
                                    .font(.system(size: 36))
                                    .foregroundColor(replyText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !bleClient.isConnected ? .gray : .blue)
                            }
                            .disabled(replyText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !bleClient.isConnected)
                        }
                        
                        if showSentConfirmation {
                            HStack {
                                Image(systemName: "checkmark.circle.fill")
                                    .foregroundColor(.green)
                                Text("دستور ارسال پیامک به اندروید فرستاده شد")
                                    .font(.caption)
                            }
                            .padding(.top, 4)
                        }
                    }
                    .padding()
                    .background(Color(.systemGray6).opacity(0.5))
                    .cornerRadius(12)
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
    
    private func copyMessage() {
        UIPasteboard.general.string = messageBody
        showCopyConfirmation = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
            showCopyConfirmation = false
        }
    }
    
    private func sendReply() {
        let text = replyText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        bleClient.sendSMS(recipient: sender, message: text)
        replyText = ""
        showSentConfirmation = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 3) {
            showSentConfirmation = false
        }
    }
    
    private func formatDate(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .medium
        formatter.locale = Locale(identifier: "fa_IR")
        return formatter.string(from: date)
    }
}
