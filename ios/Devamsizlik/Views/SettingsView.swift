import SwiftUI

private struct EditTarget: Identifiable {
    let id = UUID()
    let slot: Slot?
    let draft: SlotDraft
}

struct SettingsView: View {
    @EnvironmentObject var model: AppModel
    @State private var editing: EditTarget?
    @State private var confirmReset = false

    private func notify<T>(_ kp: WritableKeyPath<NotifySettings, T>) -> Binding<T> {
        Binding(
            get: { model.snap.notify[keyPath: kp] },
            set: { v in
                var n = model.snap.notify
                n[keyPath: kp] = v
                model.setNotify(n)
            }
        )
    }

    var body: some View {
        let snap = model.snap
        let ns = snap.notify
        let slots = snap.slots.sorted { $0.day != $1.day ? $0.day < $1.day : $0.start < $1.start }

        Form {
            Section(header: Text("Dönem"),
                    footer: Text(verbatim: "Devam şartı: derslerin en az yüzde kaçına girmen gerektiği (İTÜ'de genelde %70). " +
                                 "Bir derse özel farklı şart için aşağıdaki Dersler bölümünü kullan.")) {
                DatePicker("1. hafta başlangıcı",
                           selection: Binding(get: { snap.semesterStart.date }, set: { model.setSemesterStart(Day($0)) }),
                           displayedComponents: .date)
                Stepper(value: Binding(get: { min(max(snap.currentWeek, 1), 30) }, set: { model.setCurrentWeek($0) }), in: 1...30) {
                    Text(verbatim: "Şu anki hafta: \(snap.currentWeek)")
                }
                Stepper(value: Binding(get: { snap.weeks }, set: { model.setWeeks($0) }), in: 1...30) {
                    Text(verbatim: "Toplam hafta: \(snap.weeks)")
                }
                Stepper(value: Binding(get: { snap.requiredPercent }, set: { model.setRequiredPercent($0) }), in: 0...100, step: 5) {
                    Text(verbatim: "Devam şartı: %\(snap.requiredPercent)")
                }
            }

            Section(header: Text("Ders programı"), footer: Text("Silmek için ders saatini sola kaydır.")) {
                NavigationLink(value: Route.importer) {
                    Label("Programı fotoğraftan ekle", systemImage: "camera.viewfinder")
                }
                Button {
                    editing = EditTarget(slot: nil, draft: SlotDraft())
                } label: {
                    Label("Elle ders saati ekle", systemImage: "plus")
                }
                ForEach(slots) { s in
                    Button {
                        editing = EditTarget(slot: s, draft: SlotDraft(code: s.code, name: "", day: s.day, start: s.start, end: s.end))
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(verbatim: snap.title(s.code)).foregroundColor(.primary)
                            Text(verbatim: (snap.config(s.code).name.isEmpty ? "" : "\(s.code) • ") +
                                 "\(dayLongName(s.day))  \(s.start.text)–\(s.end.text)")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    .swipeActions {
                        Button(role: .destructive) {
                            model.removeSlot(id: s.id)
                        } label: {
                            Label("Sil", systemImage: "trash")
                        }
                    }
                }
                Button("Programı varsayılana (OBS) döndür", role: .destructive) { confirmReset = true }
            }

            if !snap.courseCodes.isEmpty {
                Section(header: Text("Dersler"),
                        footer: Text("Ders adı, dönemdeki ders sayısı, devam şartı / gelmeme hakkı ve yoklama sayısı için derse dokun.")) {
                    ForEach(snap.courseCodes, id: \.self) { code in
                        NavigationLink(value: Route.courseEdit(code)) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(verbatim: snap.title(code))
                                if let st = snap.stat(code) {
                                    Text(verbatim: (snap.config(code).name.isEmpty ? "" : "\(code) • ") +
                                         ruleSummary(st, snap.config(code), defaultRequired: snap.requiredPercent))
                                        .font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
            }

            Section(header: Text("Bildirimler"),
                    footer: Text("Evet / Hayır tuşlarını görmek için gelen bildirimi basılı tut ya da aşağı çek. " +
                                 "Tuşa basınca cevap uygulama açılmadan kaydedilir.")) {
                Picker("Cevapsız soruyu tekrar sor", selection: notify(\.reminderMinutes)) {
                    Text("Kapalı").tag(0)
                    Text("15 dk").tag(15)
                    Text("30 dk").tag(30)
                    Text("1 saat").tag(60)
                }
                Toggle("Haftalık özet", isOn: notify(\.weeklyEnabled))
                if ns.weeklyEnabled {
                    Picker("Özet günü", selection: notify(\.weeklyDay)) {
                        ForEach(1...7, id: \.self) { d in Text(verbatim: dayLongName(d)).tag(d) }
                    }
                    DatePicker("Özet saati",
                               selection: Binding(get: { ns.weeklyTime.date }, set: { v in
                                   var n = model.snap.notify
                                   n.weeklyTime = HM(v)
                                   model.setNotify(n)
                               }),
                               displayedComponents: .hourAndMinute)
                }
                Toggle("Erken uyarı", isOn: notify(\.warnEnabled))
                if ns.warnEnabled {
                    Picker("Kaç hak kala uyarsın?", selection: notify(\.warnThreshold)) {
                        Text("1 hak").tag(1)
                        Text("2 hak").tag(2)
                        Text("3 hak").tag(3)
                    }
                    Picker("Riskli dersten önce", selection: notify(\.preclassMinutes)) {
                        Text("Kapalı").tag(0)
                        Text("30 dk").tag(30)
                        Text("1 saat").tag(60)
                        Text("2 saat").tag(120)
                    }
                }
                Button("Test bildirimi gönder") { model.sendTest() }
                Button("Bildirim ayarlarını aç") {
                    if let url = URL(string: UIApplication.openNotificationSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                }
            }

            Section {
                Text(verbatim: "Sürüm \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?")")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Ayarlar")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $editing) { target in
            SlotEditor(title: target.slot == nil ? "Ders saati ekle" : "Ders saatini düzenle",
                       draft: target.draft, showName: false) { d in
                if let old = target.slot { model.updateSlot(old, d) } else { model.addSlot(d) }
            }
        }
        .confirmationDialog("Program sıfırlansın mı?", isPresented: $confirmReset, titleVisibility: .visible) {
            Button("Sıfırla", role: .destructive) { model.resetSlots() }
        } message: {
            Text("Ders programı OBS'deki varsayılan haline döner. Yoklama kayıtların silinmez.")
        }
    }
}
