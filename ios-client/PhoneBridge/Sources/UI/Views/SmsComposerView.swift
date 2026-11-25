import SwiftUI
import UIKit

/// صفحه ارسال پیامک (فیچر جدید)
struct SmsComposerView: View {
    @EnvironmentObject var bleClient: BLEClient
    @State private var phoneNumber: String = ""
    @State private var message: String = ""
    @State private var isSending: Bool = false
    @State private var resultMessage: String? = nil
    @State private var showResult: Bool = false
    @State private var isSuccess: Bool = false
    @FocusState private var focusedField: Field?
    
    enum Field {
        case phoneNumber
        case message
    }
    
    var body: some View {
        VStack(spacing: 0) {
            // Header
            Text("💬 ارسال پیامک")
                .font(.largeTitle)
                .fontWeight(.bold)
                .padding(.top, 20)
                .padding(.bottom, 10)
            
            // نمایش وضعیت اتصال
            if !bleClient.isConnected {
                HStack {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundColor(.orange)
                    Text("اتصال به گوشی Android برقرار نیست")
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                }
                .padding()
            }
            
            ScrollView {
                VStack(spacing: 20) {
                    // فیلد شماره تلفن
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Image(systemName: "person.circle.fill")
                                .foregroundColor(.blue)
                            Text("شماره تلفن")
                                .font(.headline)
                        }
                        
                        TextField("مثال: 09123456789", text: $phoneNumber)
                            .keyboardType(.phonePad)
                            .textFieldStyle(RoundedBorderTextFieldStyle())
                            .focused($focusedField, equals: .phoneNumber)
                            .padding(.horizontal)
                            .frame(height: 44)
                            .background(Color(.systemGray6))
                            .cornerRadius(10)
                        
                        // Validation indicator
                        if !phoneNumber.isEmpty {
                            HStack {
                                Image(systemName: isValidPhoneNumber(phoneNumber) ? "checkmark.circle.fill" : "xmark.circle.fill")
                                    .foregroundColor(isValidPhoneNumber(phoneNumber) ? .green : .red)
                                Text(isValidPhoneNumber(phoneNumber) ? "شماره معتبر است" : "شماره نامعتبر است (10-15 رقم)")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                            .padding(.horizontal)
                        }
                    }
                    .padding(.horizontal)
                    
                    // فیلد متن پیام
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Image(systemName: "text.bubble.fill")
                                .foregroundColor(.green)
                            Text("متن پیام")
                                .font(.headline)
                            Spacer()
                            Text("\(message.count)/1000")
                                .font(.caption)
                                .foregroundColor(message.count > 1000 ? .red : .secondary)
                        }
                        
                        ZStack(alignment: .topLeading) {
                            if message.isEmpty {
                                Text("متن پیام خود را بنویسید...")
                                    .foregroundColor(.secondary)
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 12)
                            }
                            
                            TextEditor(text: $message)
                                .focused($focusedField, equals: .message)
                                .frame(height: 200)
                                .padding(4)
                                .background(Color(.systemGray6))
                                .cornerRadius(10)
                                .overlay(
                                    RoundedRectangle(cornerRadius: 10)
                                        .stroke(message.count > 1000 ? Color.red : Color.clear, lineWidth: 2)
                                )
                        }
                        
                        // شمارنده کاراکتر با هشدار
                        if message.count > 1000 {
                            HStack {
                                Image(systemName: "exclamationmark.triangle.fill")
                                    .foregroundColor(.red)
                                Text("پیام خیلی طولانی است! حداکثر 1000 کاراکتر")
                                    .font(.caption)
                                    .foregroundColor(.red)
                            }
                            .padding(.horizontal)
                        } else if message.count > 160 {
                            HStack {
                                Image(systemName: "info.circle.fill")
                                    .foregroundColor(.orange)
                                Text("این پیام به صورت چند قسمتی ارسال می‌شود")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                            .padding(.horizontal)
                        }
                    }
                    .padding(.horizontal)
                    
                    // دکمه‌های سریع (Quick Actions)
                    VStack(alignment: .leading, spacing: 10) {
                        Text("پیام‌های آماده:")
                            .font(.caption)
                            .foregroundColor(.secondary)
                            .padding(.horizontal)
                        
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 10) {
                                QuickMessageButton(text: "الان مشغولم، بعداً تماس می‌گیرم") {
                                    message = "الان مشغولم، بعداً تماس می‌گیرم"
                                }
                                QuickMessageButton(text: "لطفاً پیامک بفرستید") {
                                    message = "لطفاً پیامک بفرستید"
                                }
                                QuickMessageButton(text: "در راهم، 10 دقیقه دیگر می‌رسم") {
                                    message = "در راهم، 10 دقیقه دیگر می‌رسم"
                                }
                            }
                            .padding(.horizontal)
                        }
                    }
                    .padding(.vertical, 10)
                    
                    // نمایش نتیجه
                    if showResult, let resultMsg = resultMessage {
                        HStack {
                            Image(systemName: isSuccess ? "checkmark.circle.fill" : "xmark.circle.fill")
                                .foregroundColor(isSuccess ? .green : .red)
                            Text(resultMsg)
                                .font(.subheadline)
                        }
                        .padding()
                        .frame(maxWidth: .infinity)
                        .background(isSuccess ? Color.green.opacity(0.2) : Color.red.opacity(0.2))
                        .cornerRadius(10)
                        .padding(.horizontal)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                    }
                }
                .padding(.vertical)
            }
            
            // دکمه‌های عملیات
            HStack(spacing: 15) {
                // دکمه پاک کردن
                Button(action: clearFields) {
                    HStack {
                        Image(systemName: "trash")
                        Text("پاک کردن")
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: 50)
                    .background(Color(.systemGray5))
                    .foregroundColor(.primary)
                    .cornerRadius(12)
                }
                .disabled(phoneNumber.isEmpty && message.isEmpty)
                
                // دکمه ارسال
                Button(action: sendSms) {
                    HStack {
                        if isSending {
                            ProgressView()
                                .progressViewStyle(CircularProgressViewStyle(tint: .white))
                        } else {
                            Image(systemName: "paperplane.fill")
                            Text("ارسال")
                                .fontWeight(.semibold)
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: 50)
                    .background(canSend ? Color.blue : Color.gray)
                    .foregroundColor(.white)
                    .cornerRadius(12)
                }
                .disabled(!canSend || isSending)
            }
            .padding()
            .background(Color(.systemBackground))
        }
        .background(Color(.systemBackground))
        .onTapGesture {
            // بستن کیبورد با کلیک روی background
            focusedField = nil
        }
    }
    
    // MARK: - Computed Properties
    
    /// آیا می‌توان پیامک ارسال کرد؟
    private var canSend: Bool {
        return bleClient.isConnected &&
               !phoneNumber.isEmpty &&
               isValidPhoneNumber(phoneNumber) &&
               !message.isEmpty &&
               message.count <= 1000 &&
               !isSending
    }
    
    // MARK: - Functions
    
    /// بررسی اعتبار شماره تلفن
    private func isValidPhoneNumber(_ number: String) -> Bool {
        let phoneRegex = "^\\+?[0-9]{10,15}$"
        let phonePredicate = NSPredicate(format: "SELF MATCHES %@", phoneRegex)
        return phonePredicate.evaluate(with: number)
    }
    
    /// ارسال پیامک
    private func sendSms() {
        guard canSend else { return }
        
        isSending = true
        showResult = false
        focusedField = nil // بستن کیبورد
        
        // Haptic feedback
        let generator = UINotificationFeedbackGenerator()
        generator.notificationOccurred(.success)
        
        bleClient.sendSMS(phoneNumber: phoneNumber, message: message) { success, error in
            DispatchQueue.main.async {
                isSending = false
                isSuccess = success
                
                if success {
                    resultMessage = "✅ پیامک با موفقیت ارسال شد"
                    // پاک کردن فیلدها پس از موفقیت
                    DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                        clearFields()
                        withAnimation {
                            showResult = false
                        }
                    }
                } else {
                    resultMessage = "❌ خطا: \(error ?? "Unknown error")"
                }
                
                withAnimation {
                    showResult = true
                }
                
                // پنهان کردن پیام پس از 5 ثانیه
                DispatchQueue.main.asyncAfter(deadline: .now() + 5) {
                    withAnimation {
                        showResult = false
                    }
                }
            }
        }
    }
    
    /// پاک کردن فیلدها
    private func clearFields() {
        phoneNumber = ""
        message = ""
        focusedField = nil
        
        // Haptic feedback
        let generator = UIImpactFeedbackGenerator(style: .medium)
        generator.impactOccurred()
    }
}

// MARK: - Quick Message Button Component

struct QuickMessageButton: View {
    let text: String
    let action: () -> Void
    
    var body: some View {
        Button(action: action) {
            Text(text)
                .font(.caption)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(Color.blue.opacity(0.1))
                .foregroundColor(.blue)
                .cornerRadius(8)
        }
    }
}

// MARK: - Preview

struct SmsComposerView_Previews: PreviewProvider {
    static var previews: some View {
        SmsComposerView()
            .environmentObject(BLEClient())
    }
}
