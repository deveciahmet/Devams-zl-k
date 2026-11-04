import SwiftUI
import UIKit

private struct ImportEdit: Identifiable {
    let id = UUID()
    /// nil = yeni ders saati
    let index: Int?
    let draft: SlotDraft
}

/// Programı fotoğraftan ekleme:
/// 1) Kullanıcı hazır metni kopyalayıp fotoğrafla birlikte bir yapay zekâya gönderir.
/// 2) Yapay zekânın JSON cevabını buraya yapıştırır.
/// 3) Uygulama cevabı çözer, kontrol listesi gösterir, onaylanınca programa kaydeder.
struct ImportView: View {
    @EnvironmentObject var model: AppModel
    @State private var answer: String
    @State private var result: ParseResult?
    @State private var entries: [ImportedSlot]
    @State private var replace = true
    @State private var expanded = false
    @State private var copied = false
    @State private var editing: ImportEdit?
    @State private var savedMessage: String?

    init(initialText: String? = nil) {
        let text = initialText ?? ""
        let r = text.isEmpty ? nil : ScheduleImport.parse(text)
        _answer = State(initialValue: text)
        _result = State(initialValue: r)
        _entries = State(initialValue: r?.entries ?? [])
    }

    var body: some View {
        Form {
            Section(header: Text("1. Bu metni kopyala"),
                    footer: Text("ChatGPT, Gemini, Claude gibi bir yapay zekâ uygulamasını aç. Ders programının fotoğrafını " +
                                 "ya da ekran görüntüsünü ekle, bu metni yapıştır ve gönder.")) {
                Text(verbatim: importPrompt)
                    .font(.system(.caption, design: .monospaced))
                    .lineLimit(expanded ? nil : 6)
                    .onTapGesture { expanded.toggle() }
                HStack(spacing: 10) {
                    Button(copied ? "Kopyalandı ✓" : "Kopyala") {
                        UIPasteboard.general.string = importPrompt
                        copied = true
                    }
                    .buttonStyle(.borderedProminent)
                    ShareLink("Paylaş", item: importPrompt)
                        .buttonStyle(.bordered)
                }
                .lineLimit(1)
                Button(expanded ? "Metni daralt" : "Metnin tamamını göster") { expanded.toggle() }
                    .buttonStyle(.borderless)
            }

            Section(header: Text("2. Yapay zekânın cevabını buraya yapıştır")) {
                TextEditor(text: $answer)
                    .font(.system(.caption, design: .monospaced))
                    .frame(minHeight: 110)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                HStack(spacing: 10) {
                    Button {
                        if let s = UIPasteboard.general.string { answer = s }
                    } label: {
                        Label("Panodan yapıştır", systemImage: "doc.on.clipboard")
                    }
                    .buttonStyle(.bordered)
                    Button("Çözümle") { parse() }
                        .buttonStyle(.borderedProminent)
                        .disabled(answer.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }

            if let r = result {
                if let e = r.error {
                    Section {
                        Text(verbatim: e).foregroundColor(.riskRed)
                    }
                }
                if !r.warnings.isEmpty {
                    Section(header: Text("Bazı kayıtlar alınamadı")) {
                        ForEach(r.warnings, id: \.self) { w in
                            Text(verbatim: "• " + w).font(.caption)
                        }
                    }
                }
                if r.error == nil {
                    Section(header: Text(verbatim: "3. Kontrol et (\(entries.count) ders saati)"),
                            footer: Text("Yanlış okunan bir şey varsa dokunup düzelt ya da sola kaydırıp sil.")) {
                        ForEach(Array(entries.enumerated()), id: \.offset) { i, e in
                            Button {
                                editing = ImportEdit(index: i, draft: SlotDraft(code: e.code, name: e.name, day: e.day,
                                                                              start: e.start, end: e.end))
                            } label: {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(verbatim: e.code + (e.name.isEmpty ? "" : " · \(e.name)"))
                                        .foregroundColor(.primary)
                                    Text(verbatim: "\(dayLongName(e.day))  \(e.start.text)–\(e.end.text)")
                                        .font(.caption).foregroundColor(.secondary)
                                }
                            }
                        }
                        .onDelete { entries.remove(atOffsets: $0) }
                        Button {
                            editing = ImportEdit(index: nil, draft: SlotDraft())
                        } label: {
                            Label("Ders saati ekle", systemImage: "plus")
                        }
                    }
                    Section(footer: Text(replace
                                         ? "Eski program silinir. Aynı ders, gün ve saatteki derslerin yoklama geçmişi korunur."
                                         : "Yeni ders saatleri mevcut programın yanına eklenir; aynı olanlar tekrar eklenmez.")) {
                        Picker("Kaydetme şekli", selection: $replace) {
                            Text("Mevcut programın yerine koy").tag(true)
                            Text("Mevcut programa ekle").tag(false)
                        }
                        .pickerStyle(.inline)
                        .labelsHidden()
                        Button {
                            let n = model.importSlots(entries, replace: replace)
                            savedMessage = "\(n) ders saati kaydedildi. Yoklama sayısı ve devam şartı için: Ayarlar → Dersler"
                        } label: {
                            Text("Programa kaydet").font(.headline).frame(maxWidth: .infinity)
                        }
                        .disabled(entries.isEmpty)
                    }
                }
            }
        }
        .navigationTitle("Programı fotoğraftan ekle")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $editing) { item in
            SlotEditor(title: item.index == nil ? "Ders saati ekle" : "Ders saatini düzenle",
                       draft: item.draft, showName: true) { d in
                let slot = ImportedSlot(code: normalizeCode(d.code), name: d.name, day: d.day, start: d.start, end: d.end)
                if let i = item.index, i < entries.count { entries[i] = slot } else { entries.append(slot) }
            }
        }
        .alert(savedMessage ?? "", isPresented: Binding(get: { savedMessage != nil }, set: { if !$0 { savedMessage = nil } })) {
            Button("Tamam") {
                savedMessage = nil
                if !model.path.isEmpty { model.path.removeLast() }
            }
        }
    }

    private func parse() {
        let r = ScheduleImport.parse(answer)
        result = r
        entries = r.entries
    }
}
