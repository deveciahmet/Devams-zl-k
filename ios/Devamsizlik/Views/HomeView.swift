import SwiftUI

struct HomeView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        let snap = model.snap
        let threshold = snap.notify.warnThreshold
        let sorted = snap.stats.sorted { $0.remaining != $1.remaining ? $0.remaining < $1.remaining : $0.code < $1.code }
        let exhausted = sorted.filter(\.exhausted)
        let unanswered = snap.unansweredPast.count
        let week = snap.currentWeek

        List {
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    Text(verbatim: week < 1 ? "Dönem henüz başlamadı" : week > snap.weeks ? "Dönem bitti" : "Hafta \(week) / \(snap.weeks)")
                        .font(.title2.bold())
                    ProgressView(value: Double(min(max(week, 0), snap.weeks)), total: Double(max(snap.weeks, 1)))
                    Text(verbatim: "Varsayılan devam şartı: %\(snap.requiredPercent) (en fazla %\(100 - snap.requiredPercent) devamsızlık)")
                        .font(.caption).foregroundStyle(.secondary)
                    if let next = snap.nextAsk {
                        let part = next.count > 1 ? " (\(next.index). saat)" : ""
                        Text(verbatim: "Sonraki soru: \(snap.title(next.code))\(part) • \(dayShortName(next.session.slot.day)) " +
                             "\(next.time.day.short) \(next.time.time.text)")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
                .padding(.vertical, 4)
            }

            if !model.notificationsEnabled {
                Section {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Bildirimler kapalı").font(.headline)
                            Text("Ders sonlarında \"derse girdin mi?\" sorusu gelmez.").font(.caption)
                        }
                        Spacer()
                        Button("Aç") {
                            if let url = URL(string: UIApplication.openNotificationSettingsURLString) {
                                UIApplication.shared.open(url)
                            }
                        }
                        .buttonStyle(.borderedProminent)
                    }
                }
                .listRowBackground(Color.riskRed.opacity(0.15))
            }

            if snap.slots.isEmpty {
                Section {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Ders programın boş").font(.headline)
                        Text("Programını bir fotoğraftan aktarabilir ya da Ayarlar'dan elle ekleyebilirsin.").font(.caption)
                    }
                    NavigationLink(value: Route.importer) { Label("Programı fotoğraftan ekle", systemImage: "camera.viewfinder") }
                }
            }

            if unanswered > 0 {
                Section {
                    NavigationLink(value: Route.fill) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(verbatim: "\(unanswered) geçmiş yoklama işaretlenmemiş").font(.headline)
                            Text("Önceki haftaları doldurmadan istatistikler eksik olur. Doldurmak için dokun.")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                        .padding(.vertical, 2)
                    }
                }
                .listRowBackground(Color.riskOrange.opacity(0.15))
            }

            if !exhausted.isEmpty {
                Section {
                    VStack(alignment: .leading, spacing: 6) {
                        Label("DİKKAT! Devamsızlık hakkın kalmadı", systemImage: "exclamationmark.triangle.fill")
                            .font(.headline)
                        ForEach(exhausted) { s in
                            Text(verbatim: s.remaining < 0
                                 ? "• \(s.title): sınırı \(-s.remaining) \(s.unit) AŞTIN (\(s.absent)/\(s.allowed))"
                                 : "• \(s.title): hakkın bitti (\(s.absent)/\(s.allowed)) — bir daha kaçırma!")
                                .font(.subheadline.weight(.semibold))
                        }
                    }
                    .foregroundColor(.white)
                    .padding(.vertical, 4)
                }
                .listRowBackground(Color.riskRed)
            }

            if !sorted.isEmpty {
                Section(header: Text("Dersler")) {
                    ForEach(sorted) { s in
                        NavigationLink(value: Route.course(s.code)) {
                            CourseCardView(s: s, threshold: threshold)
                        }
                        .listRowBackground(s.exhausted ? Color.riskRed.opacity(0.10) : nil)
                    }
                }
            }
        }
        .navigationTitle("Devamsızlık")
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                NavigationLink(value: Route.settings) { Image(systemName: "gearshape") }
                    .accessibilityLabel("Ayarlar")
            }
        }
    }
}
