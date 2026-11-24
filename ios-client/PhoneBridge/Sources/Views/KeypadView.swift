import SwiftUI
import UIKit

/// ساختار داده هر کلید در صفحه شماره‌گیر
struct KeypadButtonData {
    let digit: String
    let letters: String
}

/// صفحه شماره‌گیر بومی مشابه رابط کاربری استاندارد آیفون
struct KeypadView: View {
    @EnvironmentObject var bleClient: BLEClient
    @EnvironmentObject var callManager: CallManager
    
    @State private var dialedNumber: String = ""
    private let hapticFeedback = UIImpactFeedbackGenerator(style: .light)
    
    let buttons: [[KeypadButtonData]] = [
        [KeypadButtonData(digit: "1", letters: ""), KeypadButtonData(digit: "2", letters: "ABC"), KeypadButtonData(digit: "3", letters: "DEF")],
        [KeypadButtonData(digit: "4", letters: "GHI"), KeypadButtonData(digit: "5", letters: "JKL"), KeypadButtonData(digit: "6", letters: "MNO")],
        [KeypadButtonData(digit: "7", letters: "PQRS"), KeypadButtonData(digit: "8", letters: "TUV"), KeypadButtonData(digit: "9", letters: "WXYZ")],
        [KeypadButtonData(digit: "*", letters: ""), KeypadButtonData(digit: "0", letters: "+"), KeypadButtonData(digit: "#", letters: "")]
    ]
    
    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                Spacer()
                
                // نمایش شماره تایپ‌شده
                Text(dialedNumber.isEmpty ? " " : dialedNumber)
                    .font(.system(size: dialedNumber.count > 12 ? 28 : 36, weight: .regular, design: .rounded))
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                    .padding(.horizontal, 24)
                    .frame(height: 50)
                
                Spacer()
                
                // شبکه کلیدهای شماره‌گیر (3x4)
                VStack(spacing: 16) {
                    ForEach(buttons, id: \.first?.digit) { row in
                        HStack(spacing: 24) {
                            ForEach(row, id: \.digit) { btn in
                                KeypadCircleButton(digit: btn.digit, letters: btn.letters) {
                                    appendDigit(btn.digit)
                                } onLongPress: {
                                    if btn.digit == "0" {
                                        appendDigit("+")
                                    }
                                }
                            }
                        }
                    }
                }
                
                Spacer()
                
                // ردیف پایین: دکمه تماس سبز و دکمه پاک‌کردن
                HStack(spacing: 24) {
                    // فضای خالی تراز سمت چپ
                    Circle()
                        .fill(Color.clear)
                        .frame(width: 75, height: 75)
                    
                    // دکمه تماس سبز
                    Button(action: makeCall) {
                        ZStack {
                            Circle()
                                .fill(dialedNumber.isEmpty ? Color.green.opacity(0.6) : Color.green)
                                .frame(width: 75, height: 75)
                                .shadow(color: Color.green.opacity(0.4), radius: 8, x: 0, y: 4)
                            
                            Image(systemName: "phone.fill")
                                .font(.system(size: 32, weight: .bold))
                                .foregroundColor(.white)
                        }
                    }
                    .disabled(dialedNumber.isEmpty || !bleClient.isConnected)
                    
                    // دکمه پاک‌کردن (Backspace)
                    Group {
                        if !dialedNumber.isEmpty {
                            Button(action: deleteDigit) {
                                ZStack {
                                    Circle()
                                        .fill(Color(.systemGray6))
                                        .frame(width: 75, height: 75)
                                    
                                    Image(systemName: "delete.backward.fill")
                                        .font(.system(size: 24, weight: .semibold))
                                        .foregroundColor(.primary)
                                }
                            }
                            .simultaneousGesture(
                                LongPressGesture(minimumDuration: 0.6).onEnded { _ in
                                    clearAll()
                                }
                            )
                        } else {
                            Circle()
                                .fill(Color.clear)
                                .frame(width: 75, height: 75)
                        }
                    }
                }
                .padding(.bottom, 30)
            }
            .navigationTitle("شماره‌گیر")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
    
    private func appendDigit(_ digit: String) {
        hapticFeedback.impactOccurred()
        dialedNumber.append(digit)
    }
    
    private func deleteDigit() {
        hapticFeedback.impactOccurred()
        if !dialedNumber.isEmpty {
            dialedNumber.removeLast()
        }
    }
    
    private func clearAll() {
        UINotificationFeedbackGenerator().notificationOccurred(.warning)
        dialedNumber.removeAll()
    }
    
    private func makeCall() {
        guard !dialedNumber.isEmpty else { return }
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        bleClient.dial(phoneNumber: dialedNumber)
    }
}

/// دکمه دایره‌ای با طراحی ارگونومیک شبیه iOS Phone
struct KeypadCircleButton: View {
    let digit: String
    let letters: String
    let action: () -> Void
    var onLongPress: (() -> Void)? = nil
    
    var body: some View {
        Button(action: action) {
            ZStack {
                Circle()
                    .fill(Color(.systemGray5))
                    .frame(width: 75, height: 75)
                
                VStack(spacing: 2) {
                    Text(digit)
                        .font(.system(size: 32, weight: .regular, design: .rounded))
                        .foregroundColor(.primary)
                    
                    if !letters.isEmpty {
                        Text(letters)
                            .font(.system(size: 10, weight: .bold))
                            .foregroundColor(.secondary)
                    }
                }
            }
        }
        .simultaneousGesture(
            LongPressGesture(minimumDuration: 0.5).onEnded { _ in
                onLongPress?()
            }
        )
    }
}
