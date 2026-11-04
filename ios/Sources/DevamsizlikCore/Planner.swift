import Foundation

/// Bir bildirimin içeriği ve zamanı. iOS katmanı bunu UNNotificationRequest'e çevirir.
public struct PlannedNotification: Hashable {
    public enum Kind: String {
        case ask, remind, preclass, weekly, warning, notice
    }

    public let id: String
    public let kind: Kind
    public let title: String
    public let body: String
    /// nil = hemen göster
    public let time: DateTime?
    /// Evet / Hayır / Ders yapılmadı tuşları bu yoklamaya cevap verir.
    public let pollKey: String?

    public var hasAnswerButtons: Bool { pollKey != nil }

    public init(id: String, kind: Kind, title: String, body: String, time: DateTime?, pollKey: String?) {
        self.id = id
        self.kind = kind
        self.title = title
        self.body = body
        self.time = time
        self.pollKey = pollKey
    }
}

/// iOS'ta uygulama istediği an çalışamadığı için bütün bildirimler önceden zamanlanır.
/// iOS en fazla 64 bekleyen bildirime izin verir; plan en yakın olaylarla sınırlanır ve
/// uygulama her açıldığında / her cevapta yenilenir.
public enum Planner {
    public static let maxPending = 60
    public static let answerCategory = "YOKLAMA"

    public static func courseTitle(_ code: String, _ cfg: CourseConfig) -> String {
        cfg.name.isEmpty ? code : "\(cfg.name) (\(code))"
    }

    public static func question(_ poll: Poll, _ cfg: CourseConfig) -> String {
        let t = courseTitle(poll.code, cfg)
        return poll.count > 1 ? "\(t) dersinin \(poll.index). saatine girdin mi?" : "\(t) dersine girdin mi?"
    }

    public static func detail(_ poll: Poll) -> String {
        let s = poll.session
        var d = "\(dayLongName(s.slot.day)) \(s.slot.start.text)–\(s.slot.end.text) • Hafta \(s.week)"
        if poll.count > 1 { d += " • \(poll.index). yoklama" }
        return d
    }

    public static func ask(_ poll: Poll, _ cfg: CourseConfig, reminderAfter: Int? = nil) -> PlannedNotification {
        let q = question(poll, cfg)
        if let r = reminderAfter {
            return PlannedNotification(id: "remind|\(poll.key)", kind: .remind, title: "Hatırlatma: \(q)",
                                       body: detail(poll), time: poll.time.adding(minutes: r), pollKey: poll.key)
        }
        return PlannedNotification(id: "ask|\(poll.key)", kind: .ask, title: q, body: detail(poll),
                                   time: poll.time, pollKey: poll.key)
    }

    /// "Hayır" dendikten sonra kalan hak eşiğe indiyse hemen gösterilecek uyarı.
    public static func warning(_ st: CourseStats) -> PlannedNotification {
        let title: String
        let body: String
        if st.remaining < 0 {
            title = "⛔ \(st.title): devamsızlık sınırını aştın!"
            body = "\(st.absent) devamsızlık yaptın, sınır \(st.allowed)."
        } else if st.remaining == 0 {
            title = "⛔ \(st.title): devamsızlık hakkın bitti!"
            body = "Bir kez daha girmezsen devamsızlıktan kalırsın. (\(st.absent)/\(st.allowed))"
        } else {
            title = "⚠ \(st.title): son \(st.remaining) devamsızlık hakkın kaldı"
            body = "\(st.absent)/\(st.allowed) devamsızlık yaptın. Dikkat et!"
        }
        return PlannedNotification(id: "warn|\(st.code)", kind: .warning, title: title, body: body, time: nil, pollKey: nil)
    }

    /// Hakkı azalan dersin başlamasından önce "kaçırma" hatırlatması.
    public static func preclass(_ session: Session, _ st: CourseStats, at time: DateTime) -> PlannedNotification {
        let hours = "\(session.slot.start.text)–\(session.slot.end.text)"
        let body: String
        if st.remaining < 0 {
            body = "Devamsızlık sınırını zaten aştın (\(st.absent)/\(st.allowed))."
        } else if st.remaining == 0 {
            body = "Devamsızlık hakkın bitti! Bu derse girmezsen devamsızlıktan kalırsın."
        } else {
            body = "Son \(st.remaining) devamsızlık hakkın kaldı, kaçırma!"
        }
        return PlannedNotification(id: "pre|\(session.key)", kind: .preclass, title: "\(st.title) dersin yaklaşıyor (\(hours))",
                                   body: body, time: time, pollKey: nil)
    }

    /// Haftalık özet. İçerik zamanlanırken hesaplanır; her cevapta plan yenilendiği için güncel kalır.
    public static func weekly(_ snap: Snapshot, at t: DateTime) -> PlannedNotification? {
        let sum = summarize(snap.polls, status: snap.status, from: t.adding(days: -7), to: t)
        guard sum.total > 0 else { return nil }
        let week = weekNumber(snap.semesterStart, t.day.adding(days: -1))
        let title = (1...max(snap.weeks, 1)).contains(week) ? "Haftalık özet • \(week). hafta" : "Haftalık özet"
        var first = "Son 7 gün • Girdin: \(sum.attended) • Girmedin: \(sum.absent)"
        if sum.cancelled > 0 { first += " • Yapılmadı: \(sum.cancelled)" }
        if sum.unanswered > 0 { first += " • İşaretsiz: \(sum.unanswered)" }
        var lines = [first]
        if !sum.absentByCourse.isEmpty {
            lines.append("Devamsızlık: " + sum.absentByCourse.map { "\(snap.title($0.code)) (\($0.count))" }.joined(separator: ", "))
        }
        for st in snap.stats.filter({ $0.remaining <= snap.notify.warnThreshold }).sorted(by: { $0.remaining < $1.remaining }) {
            if st.remaining < 0 {
                lines.append("⛔ \(st.title): sınırı aştın (\(st.absent)/\(st.allowed))")
            } else if st.remaining == 0 {
                lines.append("⛔ \(st.title): hakkın bitti")
            } else {
                lines.append("⚠ \(st.title): son \(st.remaining) hak")
            }
        }
        let unanswered = snap.polls.filter { $0.time <= t && snap.status($0.key) == nil }.count
        if unanswered > 0 { lines.append("İşaretlenmemiş \(unanswered) yoklama var, doldurmak için dokun.") }
        return PlannedNotification(id: "weekly|\(t)", kind: .weekly, title: title, body: lines.joined(separator: "\n"),
                                   time: t, pollKey: nil)
    }

    public static func riskyCodes(_ snap: Snapshot) -> Set<String> {
        Set(snap.stats.filter { $0.remaining <= snap.notify.warnThreshold }.map(\.code))
    }

    public static func events(_ snap: Snapshot) -> [Event] {
        computeEvents(snap.polls, status: snap.status, riskyCodes: riskyCodes(snap), settings: snap.notify,
                      semesterStart: snap.semesterStart, weeks: snap.weeks)
    }

    /// Şu andan sonraki bildirimler (en yakın [maxPending] tanesi). Plan kesildiyse sonuna
    /// "uygulamayı aç" hatırlatması eklenir, böylece bildirimler sessizce durmaz.
    public static func plan(_ snap: Snapshot) -> [PlannedNotification] {
        var polls: [String: Poll] = [:]
        for p in snap.polls { polls[p.key] = p }
        var sessions: [String: Session] = [:]
        for s in snap.sessions { sessions[s.key] = s }

        let future = events(snap).enumerated()
            .filter { $0.element.time > snap.now }
            .sorted { a, b in a.element.time != b.element.time ? a.element.time < b.element.time : a.offset < b.offset }
            .map(\.element)

        var out: [PlannedNotification] = []
        var truncated = false
        for ev in future {
            let n: PlannedNotification?
            switch ev.type {
            case .ask:
                n = polls[ev.key].map { ask($0, snap.config($0.code)) }
            case .remind:
                n = polls[ev.key].map { ask($0, snap.config($0.code), reminderAfter: snap.notify.reminderMinutes) }
            case .preclass:
                n = sessions[ev.key].flatMap { s in snap.stat(s.slot.code).map { preclass(s, $0, at: ev.time) } }
            case .weekly:
                n = weekly(snap, at: ev.time)
            }
            guard let note = n else { continue }
            if out.count == maxPending {
                truncated = true
                break
            }
            out.append(note)
        }
        if truncated, let last = out.last?.time {
            out.append(PlannedNotification(
                id: "notice", kind: .notice, title: "Bildirimler duracak",
                body: "Yeni soruların gelmeye devam etmesi için Devamsızlık uygulamasını bir kez aç.",
                time: last.adding(minutes: 1), pollKey: nil))
        }
        return out
    }
}
