import SwiftUI

/// صفحه شماره‌گیری برای تماس خروجی (فیچر جدید)
struct DialerView: View {
    @EnvironmentObject var bleClient: BLEClient
    @State private var phoneNumber: String = ""
    @State private var isDialing: Bool = false
    @State private var resultMessage: String? = nil
    @State private var showResult: Bool = false
    @State private var isSuccess: Bool = false
    
    // دکمه‌های عددی شماره‌گیر
    private let dialPadButtons = [
        ["1", "2", "3"],
        ["4", "5", "6"],
        ["7", "8", "9"],
        ["*", "0", "#"]
    ]
    
    var body: some View {
        VStack(spacing: 20) {
            // Header
            Text("☎️ شماره‌گیری")
                .font(.largeTitle)
                .fontWeight(.bold)
                .padding(.top, 20)
            
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
            
            // نمایش شماره تلفن
            HStack {
                Text(formattedPhoneNumber)
                    .font(.system(size: 32, weight: .medium, design: .rounded))
                    .frame(maxWidth: .infinity)
                    .padding()
                    .background(Color(.systemGray6))
                    .cornerRadius(12)
                
                // دکمه پاک کردن
                if !phoneNumber.isEmpty {
                    Button(action: deleteLastDigit) {
                        Image(systemName: "delete.left.fill")
                            .font(.title2)
                            .foregroundColor(.red)
                            .padding()
                    }
                }
            }
            .padding(.horizontal)
            
            // صفحه کلید عددی
            VStack(spacing: 15) {
                ForEach(dialPadButtons, id: \.self) { row in
                    HStack(spacing: 15) {
                        ForEach(row, id: \.self) { button in
                            DialPadButton(digit: button) {
                                addDigit(button)
                            }
                        }
                    }
                }
            }
            .padding(.horizontal)
            
            Spacer()
            
            // نمایش نتیجه
            if showResult, let message = resultMessage {
                HStack {
                    Image(systemName: isSuccess ? "checkmark.circle.fill" : "xmark.circle.fill")
                        .foregroundColor(isSuccess ? .green : .red)
                    Text(message)
                        .font(.subheadline)
                }
                .padding()
                .background(isSuccess ? Color.green.opacity(0.2) : Color.red.opacity(0.2))
                .cornerRadius(10)
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
            
            // دکمه تماس
            Button(action: initiateCall) {
                HStack {
                    if isDialing {
                        ProgressView()
                            .progressViewStyle(CircularProgressViewStyle(tint: .white))
                    } else {
                        Image(systemName: "phone.fill")
                        Text("تماس")
                            .fontWeight(.semibold)
                    }
                }
                .frame(maxWidth: .infinity)
                .frame(height: 56)
                .background(canDial ? Color.green : Color.gray)
                .foregroundColor(.white)
                .cornerRadius(16)
            }
            .disabled(!canDial || isDialing)
            .padding(.horizontal)
            .padding(.bottom, 20)
        }
        .background(Color(.systemBackground))
    }
    
    // MARK: - Computed Properties
    
    /// آیا می‌توان تماس گرفت؟
    private var canDial: Bool {
        return bleClient.isConnected && 
               !phoneNumber.isEmpty && 
               isValidPhoneNumber(phoneNumber) &&
               !isDialing
    }
    
    /// فرمت زیبای شماره تلفن
    private var formattedPhoneNumber: String {
        if phoneNumber.isEmpty {
            return "شماره را وارد کنید"
        }
        return phoneNumber
    }
    
    // MARK: - Functions
    
    /// افزودن رقم به شماره
    private func addDigit(_ digit: String) {
        if phoneNumber.count < 15 { // حداکثر 15 رقم
            phoneNumber += digit
            // Haptic feedback
            let generator = UIImpactFeedbackGenerator(style: .light)
            generator.impactOccurred()
        }
    }
    
    /// حذف آخرین رقم
    private func deleteLastDigit() {
        if !phoneNumber.isEmpty {
            phoneNumber.removeLast()
            // Haptic feedback
            let generator = UIImpactFeedbackGenerator(style: .medium)
            generator.impactOccurred()
        }
    }
    
    /// شروع تماس
    private func initiateCall() {
        guard canDial else { return }
        
        isDialing = true
        showResult = false
        
        // Haptic feedback
        let generator = UINotificationFeedbackGenerator()
        generator.notificationOccurred(.success)
        
        bleClient.initiateCall(phoneNumber: phoneNumber) { success, callId, error in
            DispatchQueue.main.async {
                isDialing = false
                isSuccess = success
                
                if success {
                    resultMessage = "✅ تماس با موفقیت برقرار شد"
                    // پاک کردن شماره پس از موفقیت
                    DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                        phoneNumber = ""
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
    
    /// بررسی اعتبار شماره تلفن
    private func isValidPhoneNumber(_ number: String) -> Bool {
        // حداقل 10 رقم
        return number.count >= 10 && number.count <= 15
    }
}

// MARK: - Dial Pad Button Component

struct DialPadButton: View {
    let digit: String
    let action: () -> Void
    
    var body: some View {
        Button(action: action) {
            Text(digit)
                .font(.system(size: 28, weight: .medium, design: .rounded))
                .frame(width: 80, height: 80)
                .background(Color(.systemGray5))
                .foregroundColor(.primary)
                .cornerRadius(40)
        }
        .buttonStyle(DialPadButtonStyle())
    }
}

/// استایل دکمه صفحه کلید با افکت فشردن
struct DialPadButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.95 : 1.0)
            .opacity(configuration.isPressed ? 0.7 : 1.0)
            .animation(.easeInOut(duration: 0.1), value: configuration.isPressed)
    }
}

// MARK: - Preview

struct DialerView_Previews: PreviewProvider {
    static var previews: some View {
        DialerView()
            .environmentObject(BLEClient())
    }
}
