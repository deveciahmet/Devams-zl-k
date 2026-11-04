import SwiftUI

/// Derse özel ayarlar: ad, kod, dönemdeki ders sayısı, devam şartı / gelmeme hakkı, yoklama sayısı.
struct CourseEditView: View {
    let code: String
    private let original: CourseConfig
    @EnvironmentObject var model: AppModel
    @State private var codeText: String
    @State private var name: String
    @State private var autoTotal: Bool
    @State private var totalText: String
    @State private var mode: LimitMode
    @State private var percentText: String
    @State private var countText: String
    @State private var polls: Int
    @State private var fillNew = true
    @State private var errorText: String?

    init(code: String, snap: Snapshot) {
        self.code = code
        let cfg = snap.config(code)
        original = cfg
        let sessionCount = snap.sessions.filter { $0.slot.code == code }.count
        _codeText = State(initialValue: code)
        _name = State(initialValue: cfg.name)
        _autoTotal = State(initialValue: cfg.totalSessions == nil)
        _totalText = State(initialValue: String(cfg.totalSessions ?? sessionCount))
        _mode = State(initialValue: cfg.mode)
        _percentText = State(initialValue: String(cfg.mode == .percent ? cfg.requiredPercent : snap.requiredPercent))
        _countText = State(initialValue: String(cfg.allowedCount))
        _polls = State(initialValue: cfg.polls)
    }

    private func draft(_ snap: Snapshot) -> CourseConfig {
        CourseConfig(
            name: name.trimmingCharacters(in: .whitespaces),
            totalSessions: autoTotal ? nil : Int(totalText).flatMap { $0 > 0 ? $0 : nil },
            mode: mode,
            requiredPercent: Int(percentText).map { min(max($0, 0), 100) } ?? snap.requiredPercent,
            allowedCount: max(Int(countText) ?? 0, 0),
            polls: polls
        )
    }

    var body: some View {
        let snap = model.snap
        let courseSlots = snap.slots.filter { $0.code == code }
            .sorted { $0.day != $1.day ? $0.day < $1.day : $0.start < $1.start }
        let sessions = snap.sessions.filter { $0.slot.code == code }
        let d = draft(snap)
        let cfgs = [code: d]
        let preview = computeStats(buildPolls(sessions, cfgs), status: snap.status, configs: cfgs,
                                   defaultRequired: snap.requiredPercent, now: snap.now).first

        Form {
            Section(header: Text("Ders"),
                    footer: Text("Ders adı yazarsan uygulamada ve bildirimlerde kodun yerine ad görünür.")) {
                LabeledContent("Ders kodu") {
                    TextField("MAK 335", text: $codeText)
                        .multilineTextAlignment(.trailing)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                }
                LabeledContent("Ders adı") {
                    TextField("isteğe bağlı", text: $name).multilineTextAlignment(.trailing)
                }
            }

            Section(header: Text("Dönemde kaç kez yapılacak?"),
                    footer: Text(verbatim: autoTotal ? "\(snap.weeks) hafta × haftada \(courseSlots.count) = \(sessions.count) ders"
                                 : "Elle girdiğinde \"Ders yapılmadı\" işaretleri toplamdan ayrıca düşülmez.")) {
                Toggle("Programdan otomatik hesapla", isOn: $autoTotal)
                if !autoTotal {
                    NumberField(title: "Dönemdeki ders sayısı", text: $totalText)
                }
            }

            Section(header: Text("Devam şartı")) {
                Picker("Devam şartı", selection: $mode) {
                    Text("Varsayılan").tag(LimitMode.standard)
                    Text("Yüzde").tag(LimitMode.percent)
                    Text("Gelmeme hakkı").tag(LimitMode.count)
                }
                .pickerStyle(.segmented)
                switch mode {
                case .standard:
                    Text(verbatim: "Ayarlar'daki genel devam şartı (%\(snap.requiredPercent)) kullanılır.")
                        .font(.caption).foregroundStyle(.secondary)
                case .percent:
                    NumberField(title: "Devam şartı (%)", text: $percentText)
                    Text(verbatim: "Derslerin en az yüzde kaçına girmen gerekiyor? Örn. 70 yazarsan en fazla %30 devamsızlık yapabilirsin.")
                        .font(.caption).foregroundStyle(.secondary)
                case .count:
                    NumberField(title: polls > 1 ? "Gelmeme hakkı (yoklama)" : "Gelmeme hakkı (ders)", text: $countText)
                    Text("Hoca \"en fazla 3 kez gelmeyebilirsiniz\" dediyse 3 yaz.")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }

            Section(header: Text("Her derste kaç yoklama alınıyor?")) {
                Picker("Yoklama sayısı", selection: $polls) {
                    ForEach(1...maxPolls, id: \.self) { n in Text(verbatim: "\(n)").tag(n) }
                }
                .pickerStyle(.segmented)
                ForEach(courseSlots) { s in
                    let times = pollTimes(s.start, s.end, polls).map(\.text).joined(separator: ", ")
                    Text(verbatim: "\(dayLongName(s.day)) \(s.start.text)–\(s.end.text) → \(times)" +
                         (polls > 1 ? " saatlerinde sorulur" : " saatinde sorulur"))
                        .font(.caption)
                }
                if polls > 1 {
                    Text(verbatim: "Her yoklama ayrı sorulur ve ayrı sayılır: 1. soru dersin başlamasından 1 saat sonra, son soru " +
                         "ders bitiminde gelir. Bir derse hiç girmezsen \(polls) devamsızlık yazılır.")
                        .font(.caption).foregroundStyle(.secondary)
                    if polls > original.polls {
                        Toggle(isOn: $fillNew) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Geçmiş derslerdeki yeni yoklamaları doldur")
                                Text("Geçmiş haftalarda 1. yoklamaya ne cevap verdiysen yeni yoklamalara da o yazılır")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }

            if let p = preview {
                Section(header: Text("Bu ayarlarla")) {
                    Text(verbatim: "Toplam: \(p.total) \(p.unit)  •  Devamsızlık hakkı: \(p.allowed)")
                    Text(verbatim: "Şu anki devamsızlık: \(p.absent)  •  Kalan hak: \(p.remaining)")
                }
            }

            Section {
                Button {
                    save(snap)
                } label: {
                    Text("Kaydet").font(.headline).frame(maxWidth: .infinity)
                }
            }
        }
        .navigationTitle("Ders ayarları")
        .navigationBarTitleDisplayMode(.inline)
        .alert(errorText ?? "", isPresented: Binding(get: { errorText != nil }, set: { if !$0 { errorText = nil } })) {
            Button("Tamam", role: .cancel) {}
        }
    }

    private func save(_ snap: Snapshot) {
        let newCode = normalizeCode(codeText)
        let d = draft(snap)
        if newCode.isEmpty {
            errorText = "Ders kodu boş olamaz"
            return
        }
        if !autoTotal && d.totalSessions == nil {
            errorText = "Dönemdeki ders sayısını gir"
            return
        }
        if mode == .percent, Int(percentText).map({ (0...100).contains($0) }) != true {
            errorText = "Devam şartı 0–100 arasında olmalı"
            return
        }
        if mode == .count, Int(countText) == nil {
            errorText = "Gelmeme hakkını sayı olarak gir"
            return
        }
        let saved = model.saveCourse(oldCode: code, newCode: newCode, config: d,
                                     fillFromPolls: fillNew && polls > original.polls ? original.polls : nil)
        // Bu ekranı kapat; kod değiştiyse ders ekranı yeni koda geçsin
        var path = model.path
        if !path.isEmpty { path.removeLast() }
        model.path = path.map { $0 == .course(code) ? .course(saved) : $0 }
    }
}
