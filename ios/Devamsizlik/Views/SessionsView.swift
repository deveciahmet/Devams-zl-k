import SwiftUI

/// courseCode == nil → "Geçmiş dersleri doldur" (bütün derslerin geçmiş yoklamaları)
/// courseCode != nil → o dersin bütün dönemi (geçmiş + gelecek)
struct SessionsView: View {
    let courseCode: String?
    @EnvironmentObject var model: AppModel
    @State private var onlyUnanswered: Bool
    @State private var confirmAll = false

    init(courseCode: String?) {
        self.courseCode = courseCode
        _onlyUnanswered = State(initialValue: courseCode == nil)
    }

    var body: some View {
        let snap = model.snap
        let scope = snap.polls.filter { p in
            if let code = courseCode { return p.code == code }
            return p.time <= snap.now
        }
        let unanswered = scope.filter { $0.time <= snap.now && snap.status($0.key) == nil }
        let weeks = groupByWeek(onlyUnanswered ? unanswered : scope, semesterStart: snap.semesterStart)

        List {
            if let code = courseCode, let st = snap.stat(code) {
                Section {
                    CourseCardView(s: st, threshold: snap.notify.warnThreshold)
                    Text(verbatim: ruleSummary(st, snap.config(code), defaultRequired: snap.requiredPercent))
                        .font(.caption).foregroundStyle(.secondary)
                    NavigationLink(value: Route.courseEdit(code)) {
                        Text("Ders adı, devam şartı ve yoklama sayısını değiştir")
                    }
                }
            }
            Section {
                Toggle("Sadece işaretlenmemişler", isOn: $onlyUnanswered)
                if !unanswered.isEmpty {
                    Button {
                        confirmAll = true
                    } label: {
                        Text(verbatim: "İşaretlenmemiş \(unanswered.count) yoklamanın hepsini \"Girdim\" yap")
                    }
                    Text("İpucu: Önce hepsini \"Girdim\" yap, sonra sadece girmediklerini \"Girmedim\" olarak değiştir.")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            if weeks.isEmpty {
                Section {
                    Text(verbatim: onlyUnanswered ? "İşaretlenmemiş geçmiş ders yok 🎉" : "Ders yok").foregroundStyle(.secondary)
                }
            }
            ForEach(weeks) { w in
                Section(header: Text(verbatim: w.title)) {
                    ForEach(w.sessions) { g in
                        SessionRow(group: g, snap: snap, showCode: courseCode == nil) { keys, st in
                            model.setStatuses(keys, st)
                        }
                    }
                }
            }
        }
        .navigationTitle(courseCode.map { snap.title($0) } ?? "Geçmiş dersleri doldur")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                if let code = courseCode {
                    Button {
                        model.path.append(.courseEdit(code))
                    } label: {
                        Image(systemName: "pencil")
                    }
                    .accessibilityLabel("Ders ayarları")
                }
            }
        }
        .alert("Emin misin?", isPresented: $confirmAll) {
            Button("Evet") { model.setStatuses(unanswered.map(\.key), .attended) }
            Button("Vazgeç", role: .cancel) {}
        } message: {
            Text(verbatim: "İşaretlenmemiş \(unanswered.count) geçmiş yoklama \"Girdim\" olarak kaydedilecek. " +
                 "Sonradan tek tek değiştirebilirsin.")
        }
    }
}

private struct SessionRow: View {
    let group: SessionGroup
    let snap: Snapshot
    let showCode: Bool
    let onSet: ([String], Status?) -> Void

    var body: some View {
        let s = group.session
        let head = (showCode ? "\(snap.title(s.slot.code))  •  " : "") +
            "\(dayShortName(s.slot.day)) \(s.date.short)  \(s.slot.start.text)–\(s.slot.end.text)"
        VStack(alignment: .leading, spacing: 6) {
            Text(verbatim: head).font(showCode ? .subheadline.weight(.semibold) : .subheadline)
            if group.polls.allSatisfy({ $0.time > snap.now }) {
                // Henüz yapılmamış ders: sadece "yapılmayacak" (tatil, iptal) olarak işaretlenebilir
                let allCancelled = group.polls.allSatisfy { snap.status($0.key) == .cancelled }
                HStack(spacing: 8) {
                    StatusButton(label: "Ders yapılmadı", selected: allCancelled, color: .gray) {
                        onSet(group.polls.map(\.key), allCancelled ? nil : .cancelled)
                    }
                    if !allCancelled {
                        Text("Henüz yapılmadı").font(.caption).foregroundStyle(.secondary)
                    }
                }
            } else {
                ForEach(group.polls) { p in
                    PollRow(poll: p, status: snap.status(p.key), past: p.time <= snap.now) { st in onSet([p.key], st) }
                }
            }
        }
        .padding(.vertical, 2)
    }
}

private struct PollRow: View {
    let poll: Poll
    let status: Status?
    let past: Bool
    let onSet: (Status?) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if poll.count > 1 {
                Text(verbatim: "\(poll.index). saat · \(poll.time.time.text)").font(.caption.weight(.semibold))
            }
            HStack(spacing: 6) {
                if past {
                    StatusButton(label: "Girdim", selected: status == .attended, color: .riskGreen) { toggle(.attended) }
                    StatusButton(label: "Girmedim", selected: status == .absent, color: .riskRed) { toggle(.absent) }
                }
                StatusButton(label: "Yapılmadı", selected: status == .cancelled, color: .gray) { toggle(.cancelled) }
                if !past && status == nil {
                    Text("Henüz sorulmadı").font(.caption).foregroundStyle(.secondary)
                }
            }
        }
    }

    private func toggle(_ s: Status) { onSet(status == s ? nil : s) }
}
