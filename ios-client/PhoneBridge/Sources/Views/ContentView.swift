import SwiftUI

struct ContentView: View {
    @EnvironmentObject var bleClient: BLEClient
    @EnvironmentObject var callManager: CallManager

    @State private var dialedNumber: String = ""
    @State private var selectedTab: Int = 0

    var body: some View {
        TabView(selection: $selectedTab) {
            // تب ۱: صفحه شماره‌گیر حرفه‌ای (Keypad)
            NavigationStack {
                VStack(spacing: 12) {
                    // نوار وضعیت فشرده اتصال در بالای صفحه
                    statusBarHeader

                    Spacer()

                    // نمایشگر شماره گرفته شده
                    HStack {
                        Spacer()
                        Text(dialedNumber.isEmpty ? " " : dialedNumber)
                            .font(.system(size: 38, weight: .regular, design: .rounded))
                            .lineLimit(1)
                            .minimumScaleFactor(0.6)
                            .padding(.horizontal)
                        
                        if !dialedNumber.isEmpty {
                            Button(action: {
                                if !dialedNumber.isEmpty {
                                    dialedNumber.removeLast()
                                }
                            }) {
                                Image(systemName: "delete.left.fill")
                                    .font(.title2)
                                    .foregroundColor(.secondary)
                            }
                            .padding(.trailing, 24)
                        } else {
                            Spacer().frame(width: 44)
                        }
                    }
                    .frame(height: 60)

                    Spacer()

                    // کلیدهای شماره‌گیر (Keypad Grid)
                    VStack(spacing: 16) {
                        HStack(spacing: 28) {
                            keypadButton("1", subtitle: "")
                            keypadButton("2", subtitle: "A B C")
                            keypadButton("3", subtitle: "D E F")
                        }
                        HStack(spacing: 28) {
                            keypadButton("4", subtitle: "G H I")
                            keypadButton("5", subtitle: "J K L")
                            keypadButton("6", subtitle: "M N O")
                        }
                        HStack(spacing: 28) {
                            keypadButton("7", subtitle: "P Q R S")
                            keypadButton("8", subtitle: "T U V")
                            keypadButton("9", subtitle: "W X Y Z")
                        }
                        HStack(spacing: 28) {
                            keypadButton("*", subtitle: "")
                            keypadButton("0", subtitle: "+")
                            keypadButton("#", subtitle: "")
                        }

                        // دکمه سبز بزرگ تماس
                        HStack {
                            Spacer()
                            Button(action: {
                                makeCall()
                            }) {
                                ZStack {
                                    Circle()
                                        .fill(bleClient.isConnected ? Color.green : Color.gray)
                                        .frame(width: 76, height: 76)
                                        .shadow(color: (bleClient.isConnected ? Color.green : Color.clear).opacity(0.4), radius: 8, x: 0, y: 4)

                                    Image(systemName: "phone.fill")
                                        .font(.system(size: 32))
                                        .foregroundColor(.white)
                                }
                            }
                            .disabled(!bleClient.isConnected || dialedNumber.isEmpty)
                            Spacer()
                        }
                        .padding(.top, 8)
                    }

                    Spacer(minLength: 20)
                }
                .navigationTitle("PhoneBridge")
                .navigationBarTitleDisplayMode(.inline)
            }
            .tabItem {
                Label("شماره‌گیر", systemImage: "circle.grid.3x3.fill")
            }
            .tag(0)

            // تب ۲: لاگ‌ها و وضعیت اتصال (Activity & Status)
            NavigationStack {
                VStack(spacing: 16) {
                    // کارت بزرگ وضعیت اتصال
                    connectionCard

                    // لیست زنده رویدادها
                    VStack(alignment: .leading, spacing: 8) {
                        Text("گزارش تماس‌ها و پیامک‌ها")
                            .font(.headline)
                            .padding(.horizontal, 4)

                        if bleClient.logs.isEmpty {
                            VStack(spacing: 12) {
                                Spacer()
                                Image(systemName: "clock")
                                    .font(.system(size: 40))
                                    .foregroundColor(.secondary)
                                Text("رویدادی ثبت نشده")
                                    .font(.headline)
                                Text("تماس‌ها و پیامک‌های دریافتی اینجا ثبت می‌شوند.")
                                    .font(.subheadline)
                                    .foregroundColor(.secondary)
                                Spacer()
                            }
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                        } else {
                            List(bleClient.logs, id: \.self) { log in
                                Text(log)
                                    .font(.system(size: 13, design: .monospaced))
                                    .foregroundColor(.primary)
                            }
                            .listStyle(.insetGrouped)
                        }
                    }

                    Spacer()
                }
                .padding()
                .navigationTitle("گزارش رویدادها")
            }
            .tabItem {
                Label("رویدادها", systemImage: "list.bullet.rectangle.portrait")
            }
            .tag(1)
        }
    }

    // MARK: - دکمه‌های شماره‌گیر
    private func keypadButton(_ title: String, subtitle: String) -> some View {
        Button(action: {
            dialedNumber.append(title)
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
        }) {
            VStack(spacing: 0) {
                Text(title)
                    .font(.system(size: 32, weight: .regular))
                    .foregroundColor(.primary)
                if !subtitle.isEmpty {
                    Text(subtitle)
                        .font(.system(size: 10, weight: .medium))
                        .foregroundColor(.secondary)
                }
            }
            .frame(width: 76, height: 76)
            .background(Circle().fill(Color(uiColor: .secondarySystemFill)))
        }
        .simultaneousGesture(
            LongPressGesture(minimumDuration: 0.6).onEnded { _ in
                if title == "0" {
                    if !dialedNumber.isEmpty && dialedNumber.last == "0" {
                        dialedNumber.removeLast()
                    }
                    dialedNumber.append("+")
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                }
            }
        )
    }

    // MARK: - نوار وضعیت فشرده بالای صفحه شماره‌گیر
    private var statusBarHeader: some View {
        HStack(spacing: 12) {
            Circle()
                .fill(bleClient.isConnected ? Color.green : Color.red)
                .frame(width: 10, height: 10)

            Text(bleClient.isConnected ? bleClient.deviceName : "در حال جستجوی سامسونگ...")
                .font(.footnote)
                .fontWeight(.medium)
                .foregroundColor(.secondary)

            Spacer()

            if bleClient.isConnected {
                HStack(spacing: 6) {
                    Image(systemName: "simcard")
                    Text(bleClient.simCarrier)
                }
                .font(.caption)
                .foregroundColor(.secondary)

                HStack(spacing: 4) {
                    Image(systemName: "battery.75")
                    Text("\(bleClient.batteryLevel)%")
                }
                .font(.caption)
                .foregroundColor(.secondary)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(Capsule().fill(Color(uiColor: .tertiarySystemFill)))
        .padding(.horizontal)
    }

    // MARK: - کارت بزرگ وضعیت اتصال
    private var connectionCard: some View {
        VStack(spacing: 12) {
            HStack {
                Image(systemName: bleClient.isConnected ? "antenna.radiowaves.left.and.right" : "antenna.radiowaves.left.and.right.slash")
                    .font(.system(size: 36))
                    .foregroundColor(bleClient.isConnected ? .green : .red)

                VStack(alignment: .leading, spacing: 4) {
                    Text(bleClient.isConnected ? "متصل به دستگاه اندروید" : "ارتباط قطع است")
                        .font(.headline)
                    Text(bleClient.deviceName)
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                }
                Spacer()
            }

            Divider()

            HStack(spacing: 24) {
                Label("\(bleClient.batteryLevel)%", systemImage: "battery.75")
                    .font(.subheadline)
                Label(bleClient.simCarrier, systemImage: "simcard")
                    .font(.subheadline)
                Spacer()

                Button(bleClient.isConnected ? "قطع اتصال" : "اتصال مجدد") {
                    if bleClient.isConnected {
                        bleClient.disconnect()
                    } else {
                        bleClient.startScanning()
                    }
                }
                .buttonStyle(.borderedProminent)
                .tint(bleClient.isConnected ? .red : .blue)
            }
        }
        .padding()
        .background(RoundedRectangle(cornerRadius: 16).fill(Color(uiColor: .secondarySystemBackground)))
    }

    // MARK: - انجام تماس
    private func makeCall() {
        guard !dialedNumber.isEmpty, bleClient.isConnected else { return }
        bleClient.dial(number: dialedNumber)
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
    }
}
