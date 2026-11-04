import SwiftUI

extension Color {
    static let riskRed = Color(red: 0.776, green: 0.157, blue: 0.157)
    static let riskOrange = Color(red: 0.937, green: 0.424, blue: 0.0)
    static let riskGreen = Color(red: 0.180, green: 0.490, blue: 0.196)
}

func riskColor(_ s: CourseStats, _ threshold: Int) -> Color {
    if s.remaining <= 0 { return .riskRed }
    if s.remaining <= max(1, threshold) { return .riskOrange }
    return .riskGreen
}

extension Calendar {
    static var gregorianLocal: Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = .current
        return c
    }
}

extension Day {
    /// DatePicker için.
    var date: Date {
        Calendar.gregorianLocal.date(from: DateComponents(year: year, month: month, day: day, hour: 12)) ?? Date()
    }

    init(_ date: Date) {
        let c = Calendar.gregorianLocal.dateComponents([.year, .month, .day], from: date)
        self.init(c.year ?? 2000, c.month ?? 1, c.day ?? 1)
    }
}

extension HM {
    /// DatePicker (.hourAndMinute) için.
    var date: Date {
        Calendar.gregorianLocal.date(from: DateComponents(year: 2026, month: 1, day: 1, hour: hour, minute: minute)) ?? Date()
    }

    init(_ date: Date) {
        let c = Calendar.gregorianLocal.dateComponents([.hour, .minute], from: date)
        self.init(c.hour ?? 0, c.minute ?? 0)
    }
}

/// Girdim / Girmedim / Yapılmadı düğmesi. Liste satırında birden fazla düğme olduğu için .borderless.
struct StatusButton: View {
    let label: String
    let selected: Bool
    let color: Color
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(verbatim: label)
                .font(.subheadline.weight(.medium))
                .lineLimit(1)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .foregroundColor(selected ? .white : .primary)
                .background(Capsule().fill(selected ? color : Color.clear))
                .overlay(Capsule().stroke(selected ? color : Color.secondary.opacity(0.5), lineWidth: 1))
        }
        .buttonStyle(.borderless)
    }
}

struct CourseCardView: View {
    let s: CourseStats
    let threshold: Int

    var body: some View {
        let c = riskColor(s, threshold)
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: s.title).font(.title3.bold()).lineLimit(2)
                    if !s.name.isEmpty {
                        Text(verbatim: s.code).font(.caption).foregroundStyle(.secondary)
                    }
                }
                Spacer()
                Text(verbatim: pct(s.absentPercent)).font(.title3.bold()).foregroundColor(c)
            }
            ProgressView(value: progress).tint(c)
            Text(verbatim: "Devamsızlık: \(s.absent) / \(s.allowed) hak  •  Kalan hak: \(max(0, s.remaining))")
                .font(.subheadline.weight(.semibold))
                .foregroundColor(c)
            Text(verbatim: details).font(.caption).foregroundStyle(.secondary)
            if let w = warning {
                Text(verbatim: w.text).font(.subheadline.bold()).foregroundColor(w.color)
            }
        }
        .padding(.vertical, 4)
    }

    private var progress: Double {
        s.allowed <= 0 ? (s.absent > 0 ? 1 : 0) : min(Double(s.absent) / Double(s.allowed), 1)
    }

    private var details: String {
        var d = "Girdin:\u{00A0}\(s.attended)  •  Kalan\u{00A0}\(s.unit):\u{00A0}\(s.upcoming)  •  Toplam:\u{00A0}\(s.total)"
        if s.unanswered > 0 { d += "  •  İşaretsiz:\u{00A0}\(s.unanswered)" }
        if s.cancelled > 0 { d += "  •  Yapılmadı:\u{00A0}\(s.cancelled)" }
        if s.pollsPerSession > 1 { d += "\nHer derste \(s.pollsPerSession) yoklama alınıyor" }
        return d
    }

    private var warning: (text: String, color: Color)? {
        if s.remaining < 0 { return ("⚠ Devamsızlık sınırını \(-s.remaining) \(s.unit) aştın!", .riskRed) }
        if s.remaining == 0 { return ("⚠ Hakkın bitti! Bir kez daha girmezsen devamsızlıktan kalırsın.", .riskRed) }
        if s.remaining <= max(1, threshold) { return ("Son \(s.remaining) hakkın kaldı.", .riskOrange) }
        return nil
    }
}

/// Sayı girilen alan (yalnızca rakam).
struct NumberField: View {
    let title: String
    @Binding var text: String

    var body: some View {
        LabeledContent(title) {
            TextField("", text: $text)
                .keyboardType(.numberPad)
                .multilineTextAlignment(.trailing)
                .onChange(of: text) { v in
                    let f = String(v.filter { $0.isASCII && $0.isNumber }.prefix(3))
                    if f != v { text = f }
                }
        }
    }
}

struct SlotDraft: Hashable {
    var code = ""
    var name = ""
    var day = 1
    var start = HM(8, 30)
    var end = HM(10, 29)
}

/// Ders saati ekleme / düzenleme sayfası.
struct SlotEditor: View {
    let title: String
    let showName: Bool
    let onSave: (SlotDraft) -> Void
    @State private var draft: SlotDraft
    @Environment(\.dismiss) private var dismiss

    init(title: String, draft: SlotDraft, showName: Bool, onSave: @escaping (SlotDraft) -> Void) {
        self.title = title
        self.showName = showName
        self.onSave = onSave
        _draft = State(initialValue: draft)
    }

    private var valid: Bool {
        !draft.code.trimmingCharacters(in: .whitespaces).isEmpty && draft.end > draft.start
    }

    var body: some View {
        NavigationStack {
            Form {
                LabeledContent("Ders kodu") {
                    TextField("MAK 335", text: $draft.code)
                        .multilineTextAlignment(.trailing)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                }
                if showName {
                    LabeledContent("Ders adı") {
                        TextField("isteğe bağlı", text: $draft.name).multilineTextAlignment(.trailing)
                    }
                }
                Picker("Gün", selection: $draft.day) {
                    ForEach(1...7, id: \.self) { d in Text(verbatim: dayLongName(d)).tag(d) }
                }
                DatePicker("Başlangıç", selection: Binding(get: { draft.start.date }, set: { draft.start = HM($0) }),
                           displayedComponents: .hourAndMinute)
                DatePicker("Bitiş", selection: Binding(get: { draft.end.date }, set: { draft.end = HM($0) }),
                           displayedComponents: .hourAndMinute)
                if draft.end <= draft.start {
                    Text("Bitiş saati başlangıçtan sonra olmalı.").foregroundColor(.riskRed)
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Vazgeç") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Kaydet") {
                        var d = draft
                        d.code = d.code.trimmingCharacters(in: .whitespaces)
                        d.name = d.name.trimmingCharacters(in: .whitespaces)
                        onSave(d)
                        dismiss()
                    }
                    .disabled(!valid)
                }
            }
        }
    }
}
