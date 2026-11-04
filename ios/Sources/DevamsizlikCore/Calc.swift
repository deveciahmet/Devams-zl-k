import Foundation

// Android sürümündeki Core.kt'nin birebir karşılığı.

/// "mak335" → "MAK 335". Ders koduna benzemeyen metinlere (ör. ders adı) dokunmaz, sadece boşlukları düzeltir.
public func normalizeCode(_ raw: String) -> String {
    let s = raw.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
    let chars = Array(s)
    func isDigit(_ c: Character) -> Bool { c.isASCII && c.isNumber }
    var i = 0
    while i < chars.count, chars[i].isLetter { i += 1 }
    let letters = i
    guard (2...5).contains(letters) else { return s }
    var j = i
    while j < chars.count, chars[j] == " " { j += 1 }
    var k = j
    while k < chars.count, isDigit(chars[k]) { k += 1 }
    guard (2...4).contains(k - j) else { return s }
    var l = k
    while l < chars.count, chars[l].isLetter { l += 1 }
    guard l == chars.count, l - k <= 2 else { return s }
    return (String(chars[0..<letters]) + " " + String(chars[j..<chars.count])).uppercased()
}

/// Verilen gün dönemin kaçıncı haftası (1 tabanlı; dönem öncesi ≤ 0).
public func weekNumber(_ semesterStart: Day, _ date: Day) -> Int {
    let d = semesterStart.monday.days(until: date)
    let q = d >= 0 ? d / 7 : -((-d + 6) / 7)
    return q + 1
}

/// Yoklama saatleri. k yoklama varsa 1., 2., … yoklama dersin başlamasından 1, 2, … saat sonra,
/// sonuncusu ders bitiminde sorulur. Ders bunun için kısaysa yoklamalar eşit aralıklarla dağıtılır.
public func pollTimes(_ start: HM, _ end: HM, _ count: Int) -> [HM] {
    let k = min(max(count, 1), maxPolls)
    if k == 1 { return [end] }
    let dur = end.minutes - start.minutes
    let hourly = (k - 1) * 60 < dur
    var out = (1..<k).map { i in HM(minutes: start.minutes + (hourly ? i * 60 : i * dur / k)) }
    out.append(end)
    return out
}

public func generateSessions(_ semesterStart: Day, _ weeks: Int, _ slots: [Slot]) -> [Session] {
    let monday = semesterStart.monday
    var out: [(Session, Int)] = []
    var n = 0
    for w in 0..<max(weeks, 0) {
        let m = monday.adding(weeks: w)
        for s in slots {
            out.append((Session(slot: s, date: m.adding(days: s.day - 1), week: w + 1), n))
            n += 1
        }
    }
    out.sort { a, b in a.0.start != b.0.start ? a.0.start < b.0.start : a.1 < b.1 }
    return out.map(\.0)
}

public func buildPolls(_ sessions: [Session], _ configs: [String: CourseConfig]) -> [Poll] {
    var out: [Poll] = []
    out.reserveCapacity(sessions.count)
    for s in sessions {
        let k = min(max(configs[s.slot.code]?.polls ?? 1, 1), maxPolls)
        for (i, t) in pollTimes(s.slot.start, s.slot.end, k).enumerated() {
            out.append(Poll(session: s, index: i + 1, count: k, time: DateTime(s.date, t)))
        }
    }
    return out
}

/// Devam şartı %p ise en fazla ⌊toplam × (100 − p) / 100⌋ devamsızlık yapılabilir.
public func allowedAbsences(_ total: Int, _ cfg: CourseConfig, _ defaultRequired: Int) -> Int {
    let v: Int
    switch cfg.mode {
    case .standard: v = total * (100 - min(max(defaultRequired, 0), 100)) / 100
    case .percent: v = total * (100 - min(max(cfg.requiredPercent, 0), 100)) / 100
    case .count: v = cfg.allowedCount
    }
    return max(v, 0)
}

public func computeStats(
    _ polls: [Poll],
    status: (String) -> Status?,
    configs: [String: CourseConfig],
    defaultRequired: Int,
    now: DateTime
) -> [CourseStats] {
    var order: [String] = []
    var groups: [String: [Poll]] = [:]
    for p in polls {
        if groups[p.code] == nil { order.append(p.code) }
        groups[p.code, default: []].append(p)
    }
    return order.map { code in
        let list = groups[code] ?? []
        var att = 0, abs = 0, can = 0, una = 0, up = 0
        for p in list {
            switch status(p.key) {
            case .attended?: att += 1
            case .absent?: abs += 1
            case .cancelled?: can += 1
            case nil: if p.time > now { up += 1 } else { una += 1 }
            }
        }
        let cfg = configs[code] ?? CourseConfig()
        let k = list.first?.count ?? 1
        let sessionCount = Set(list.map(\.session.key)).count
        let total = cfg.totalSessions.map { $0 * k } ?? (list.count - can)
        return CourseStats(
            code: code, name: cfg.name, pollsPerSession: k, sessionCount: sessionCount,
            total: total, manualTotal: cfg.totalSessions != nil,
            attended: att, absent: abs, cancelled: can, unanswered: una, upcoming: up,
            allowed: allowedAbsences(total, cfg, defaultRequired)
        )
    }
}

/// (from, to] aralığındaki yoklamaların özeti.
public func summarize(_ polls: [Poll], status: (String) -> Status?, from: DateTime, to: DateTime) -> WeekSummary {
    var total = 0, att = 0, abs = 0, can = 0, una = 0
    var order: [String] = []
    var byCourse: [String: Int] = [:]
    for p in polls where p.time > from && p.time <= to {
        total += 1
        switch status(p.key) {
        case .attended?: att += 1
        case .absent?:
            abs += 1
            if byCourse[p.code] == nil { order.append(p.code) }
            byCourse[p.code, default: 0] += 1
        case .cancelled?: can += 1
        case nil: una += 1
        }
    }
    return WeekSummary(total: total, attended: att, absent: abs, cancelled: can, unanswered: una,
                       absentByCourse: order.map { ($0, byCourse[$0] ?? 0) })
}

/// Zamanlanacak bütün bildirimler:
/// - ask: her cevaplanmamış yoklamanın saatinde soru
/// - remind: cevap gelmezse belirli süre sonra bir kez daha
/// - preclass: hakkı azalan (riskli) derslerden önce "kaçırma" hatırlatması
/// - weekly: haftalık özet
public func computeEvents(
    _ polls: [Poll],
    status: (String) -> Status?,
    riskyCodes: Set<String>,
    settings ns: NotifySettings,
    semesterStart: Day,
    weeks: Int
) -> [Event] {
    var out: [Event] = []
    for p in polls where status(p.key) == nil {
        out.append(Event(time: p.time, type: .ask, key: p.key))
        if ns.reminderMinutes > 0 {
            out.append(Event(time: p.time.adding(minutes: ns.reminderMinutes), type: .remind, key: p.key))
        }
    }
    if ns.warnEnabled && ns.preclassMinutes > 0 && !riskyCodes.isEmpty {
        for p in polls where p.index == 1 && riskyCodes.contains(p.code) && status(p.key) != .cancelled {
            out.append(Event(time: p.session.start.adding(minutes: -ns.preclassMinutes), type: .preclass, key: p.session.key))
        }
    }
    if ns.weeklyEnabled {
        let monday = semesterStart.monday
        for w in 0...max(weeks, 0) {
            let date = monday.adding(weeks: w).adding(days: min(max(ns.weeklyDay, 1), 7) - 1)
            out.append(Event(time: DateTime(date, ns.weeklyTime), type: .weekly, key: "weekly"))
        }
    }
    return out
}

/// Yoklamaları hafta → ders olarak gruplar (sıra korunur).
public func groupByWeek(_ polls: [Poll], semesterStart: Day) -> [WeekGroup] {
    var weeks: [WeekGroup] = []
    var currentWeek: Int?
    var sessions: [SessionGroup] = []
    var currentSession: Session?
    var sessionPolls: [Poll] = []

    func flushSession() {
        if let s = currentSession { sessions.append(SessionGroup(session: s, polls: sessionPolls)) }
        currentSession = nil
        sessionPolls = []
    }
    func flushWeek() {
        flushSession()
        if let w = currentWeek {
            weeks.append(WeekGroup(week: w, monday: semesterStart.monday.adding(weeks: w - 1), sessions: sessions))
        }
        sessions = []
    }
    for p in polls {
        if p.session.week != currentWeek {
            flushWeek()
            currentWeek = p.session.week
        }
        if p.session.key != currentSession?.key {
            flushSession()
            currentSession = p.session
        }
        sessionPolls.append(p)
    }
    flushWeek()
    return weeks
}

/// "Devam şartı %70 (varsayılan) • Dönemde 13 ders • Hak: 3"
public func ruleSummary(_ s: CourseStats, _ cfg: CourseConfig, defaultRequired: Int) -> String {
    let rule: String
    switch cfg.mode {
    case .standard: rule = "Devam şartı %\(defaultRequired) (varsayılan)"
    case .percent: rule = "Devam şartı %\(cfg.requiredPercent)"
    case .count: rule = "Gelmeme hakkı: \(cfg.allowedCount) \(s.unit)"
    }
    let sessions = s.manualTotal ? "Dönemde \(cfg.totalSessions ?? 0) ders (elle)" : "Dönemde \(s.sessionCount) ders"
    let polls = s.pollsPerSession > 1 ? " × \(s.pollsPerSession) yoklama" : ""
    return "\(rule) • \(sessions)\(polls) • Hak:\u{00A0}\(s.allowed)"
}
