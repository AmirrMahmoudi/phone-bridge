import SwiftUI

struct ContentView: View {
    @EnvironmentObject var bleClient: BLEClient
    @EnvironmentObject var callManager: CallManager

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                // کارت وضعیت اتصال
                VStack(spacing: 12) {
                    HStack {
                        Image(systemName: bleClient.isConnected ? "antenna.radiowaves.left.and.right" : "antenna.radiowaves.left.and.right.slash")
                            .font(.system(size: 32))
                            .foregroundColor(bleClient.isConnected ? .green : .red)
                        
                        VStack(alignment: .leading, spacing: 4) {
                            Text(bleClient.isConnected ? "متصل به دستگاه اندروید" : "در حال جستجو...")
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

                // لاگ رویدادها
                VStack(alignment: .leading, spacing: 8) {
                    Text("گزارش رویدادها (Realtime Logs)")
                        .font(.headline)
                        .padding(.horizontal, 4)

                    List(bleClient.logs, id: \.self) { log in
                        Text(log)
                            .font(.system(size: 13, design: .monospaced))
                            .foregroundColor(.primary)
                    }
                    .listStyle(.plain)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                }

                Spacer()
            }
            .padding()
            .navigationTitle("PhoneBridge")
        }
    }
}
