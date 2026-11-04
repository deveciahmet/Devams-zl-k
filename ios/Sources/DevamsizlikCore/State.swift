import Foundation

/// Basit anahtar-değer deposu. iOS'ta UserDefaults, testlerde bellekte bir sözlük.
public protocol KeyValueStore: AnyObject {
    func string(forKey key: String) -> String?
    func set(_ value: String?, forKey key: String)
}

public final class MemoryStore: KeyValueStore {
    public private(set) var values: [String: String]
    public init(_ values: [String: String] = [:]) { self.values = values }
    public func string(forKey key: String) -> String? { values[key] }
    public func set(_ value: String?, forKey key: String) { values[key] = value }
}

/// Belirli bir andaki bütün verinin hesaplanmış hali. Ekranlar ve bildirim planı bunun üzerinden çalışır.
public struct Snapshot {
    public let now: DateTime
    public let semesterStart: Day
    public let weeks: Int
    public let requiredPercent: Int
    public let slots: [Slot]
    public let configs: [String: CourseConfig]
    public let sessions: [Session]
    public let polls: [Poll]
    public let statuses: [String: Status]
    public let stats: [CourseStats]
    public let notify: NotifySettings
    public let unansweredPast: [Poll]

    public init(now: DateTime, semesterStart: Day, weeks: Int, requiredPercent: Int, slots: [Slot],
                configs: [String: CourseConfig], statuses: [String: Status], notify: NotifySettings) {
        self.now = now
        self.semesterStart = semesterStart
        self.weeks = weeks
        self.requiredPercent = requiredPercent
        self.slots = slots
        self.configs = configs
        self.statuses = statuses
        self.notify = notify
        sessions = generateSessions(semesterStart, weeks, slots)
        polls = buildPolls(sessions, configs)
        stats = computeStats(polls, status: { statuses[$0] }, configs: configs, defaultRequired: requiredPercent, now: now)
        unansweredPast = polls.filter { $0.time <= now && statuses[$0.key] == nil }
    }

    public func status(_ key: String) -> Status? { statuses[key] }
    public func config(_ code: String) -> CourseConfig { configs[code] ?? CourseConfig() }
    public func stat(_ code: String) -> CourseStats? { stats.first { $0.code == code } }
    public func title(_ code: String) -> String {
        let n = config(code).name
        return n.isEmpty ? code : n
    }
    public var currentWeek: Int { weekNumber(semesterStart, now.day) }
    public var courseCodes: [String] { Array(Set(slots.map(\.code))).sorted() }
    /// Sıradaki (henüz sorulmamış) soru.
    public var nextAsk: Poll? {
        polls.filter { $0.time > now && statuses[$0.key] == nil }.min { $0.time < $1.time }
    }
}

/// Uygulamanın bütün verisi ve değişiklikleri. Android'deki Repo'nun karşılığı.
public final class AppState {
    public let store: KeyValueStore
    public var clock: () -> DateTime

    /// 4 Kasım 2026 6. hafta → 1. haftanın Pazartesi'si 28 Eylül 2026.
    public static let defaultStart = Day(2026, 9, 28)

    /// İTÜ OBS'deki ders programı.
    public static let defaultSlots: [Slot] = [
        Slot(id: "mak335-pzt", code: "MAK 335", day: 1, start: HM(10, 30), end: HM(13, 29)),
        Slot(id: "mak303-pzt", code: "MAK 303", day: 1, start: HM(15, 30), end: HM(17, 29)),
        Slot(id: "mak309-sal", code: "MAK 309", day: 2, start: HM(10, 30), end: HM(13, 29)),
        Slot(id: "itb220-sal", code: "ITB 220", day: 2, start: HM(14, 30), end: HM(17, 29)),
        Slot(id: "mak305-car", code: "MAK 305", day: 3, start: HM(8, 30), end: HM(10, 29)),
        Slot(id: "mak303-per", code: "MAK 303", day: 4, start: HM(8, 30), end: HM(10, 29)),
        Slot(id: "mak305-per", code: "MAK 305", day: 4, start: HM(10, 30), end: HM(12, 29)),
        Slot(id: "mak317-per", code: "MAK 317", day: 4, start: HM(12, 30), end: HM(14, 29)),
        Slot(id: "mak304e-per", code: "MAK 304E", day: 4, start: HM(15, 30), end: HM(17, 29)),
        Slot(id: "beb104-cum", code: "BEB 104", day: 5, start: HM(10, 30), end: HM(12, 29)),
        Slot(id: "mak118-cum", code: "MAK 118", day: 5, start: HM(14, 0), end: HM(16, 59)),
    ]

    public init(store: KeyValueStore, clock: @escaping () -> DateTime = { DateTime.now() }) {
        self.store = store
        self.clock = clock
    }

    private let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.outputFormatting = [.sortedKeys]
        return e
    }()

    private let decoder = JSONDecoder()

    private func load<T: Decodable>(_ type: T.Type, _ key: String) -> T? {
        guard let s = store.string(forKey: key) else { return nil }
        return try? decoder.decode(T.self, from: Data(s.utf8))
    }

    private func save<T: Encodable>(_ value: T, _ key: String) {
        guard let d = try? encoder.encode(value), let s = String(data: d, encoding: .utf8) else { return }
        store.set(s, forKey: key)
    }

    // MARK: Dönem

    public var semesterStart: Day { store.string(forKey: "start").flatMap { Day(iso: $0) } ?? Self.defaultStart }
    public func setSemesterStart(_ d: Day) { store.set(d.monday.iso, forKey: "start") }

    public var weeks: Int { store.string(forKey: "weeks").flatMap { Int($0) } ?? 13 }
    public func setWeeks(_ w: Int) { store.set(String(min(max(w, 1), 30)), forKey: "weeks") }

    /// Varsayılan devam şartı (%).
    public var requiredPercent: Int { store.string(forKey: "required").flatMap { Int($0) } ?? 70 }
    public func setRequiredPercent(_ p: Int) { store.set(String(min(max(p, 0), 100)), forKey: "required") }

    public func setCurrentWeek(_ week: Int, today: Day? = nil) {
        let t = today ?? clock().day
        setSemesterStart(t.monday.adding(weeks: -(week - 1)))
    }

    // MARK: Ders programı

    public var slots: [Slot] { load([Slot].self, "slots") ?? Self.defaultSlots }
    public func saveSlots(_ list: [Slot]) { save(list, "slots") }

    public func newSlot(code: String, day: Int, start: HM, end: HM) -> Slot {
        Slot(id: UUID().uuidString, code: normalizeCode(code), day: day, start: start, end: end)
    }

    public func addSlot(_ s: Slot) { saveSlots(slots + [s]) }

    /// Ders saatini günceller. Günü değiştiyse eski yoklama kayıtları da yeni güne taşınır.
    public func updateSlot(old: Slot, new: Slot) {
        saveSlots(slots.map { $0.id == old.id ? new : $0 })
        let delta = new.day - old.day
        guard delta != 0 else { return }
        var moved: [String: Status] = [:]
        var rest: [String: Status] = [:]
        for (k, v) in statuses {
            let parts = k.split(separator: "|", omittingEmptySubsequences: false).map(String.init)
            if parts.count >= 2, parts[0] == old.id, let d = Day(iso: parts[1]) {
                let nk = ([parts[0], d.adding(days: delta).iso] + parts.dropFirst(2)).joined(separator: "|")
                moved[nk] = v
            } else {
                rest[k] = v
            }
        }
        saveStatuses(rest.merging(moved) { _, new in new })
    }

    public func removeSlot(id: String) { saveSlots(slots.filter { $0.id != id }) }
    public func resetSlots() { store.set(nil, forKey: "slots") }

    /// Yapay zekâdan gelen programı kaydeder. Mevcut programda aynı ders/gün/başlangıç saatindeki kayıtlar
    /// aynı kimlikle tutulur, böylece yoklama geçmişi kaybolmaz. Dönen değer: kaydedilen ders saati sayısı.
    @discardableResult
    public func importSlots(_ entries: [ImportedSlot], replace: Bool) -> Int {
        let existing = slots
        var used = Set<String>()
        let incoming: [Slot] = entries.map { e in
            if let match = existing.first(where: {
                !used.contains($0.id) && $0.code.lowercased() == e.code.lowercased() && $0.day == e.day && $0.start == e.start
            }) {
                used.insert(match.id)
                var m = match
                m.code = e.code
                m.end = e.end
                return m
            }
            return newSlot(code: e.code, day: e.day, start: e.start, end: e.end)
        }
        saveSlots(replace ? incoming : existing.filter { !used.contains($0.id) } + incoming)

        var cfgs = configs
        var changed = false
        for e in entries where !e.name.isEmpty {
            var c = cfgs[e.code] ?? CourseConfig()
            if c.name.isEmpty {
                c.name = e.name
                cfgs[e.code] = c
                changed = true
            }
        }
        if changed { saveConfigs(cfgs) }
        return incoming.count
    }

    // MARK: Ders ayarları

    public var configs: [String: CourseConfig] { load([String: CourseConfig].self, "courses") ?? [:] }
    private func saveConfigs(_ m: [String: CourseConfig]) { save(m, "courses") }

    /// Ders ayarlarını kaydeder; kod değiştiyse programdaki bütün saatlerini de yeni koda taşır. Yeni kodu döner.
    @discardableResult
    public func saveCourse(oldCode: String, newCode: String, config: CourseConfig) -> String {
        let code = normalizeCode(newCode)
        if code != oldCode {
            saveSlots(slots.map { s in
                var x = s
                if x.code == oldCode { x.code = code }
                return x
            })
        }
        var m = configs
        m[oldCode] = nil
        m[code] = config
        saveConfigs(m)
        return code
    }

    /// Yoklama sayısı arttırıldığında geçmiş derslerdeki yeni yoklamaları 1. yoklamanın cevabıyla doldurur.
    public func fillNewPolls(code: String, oldPolls: Int, now: DateTime? = nil) {
        let snap = snapshot(now: now)
        var m = snap.statuses
        var any = false
        for p in snap.polls where p.code == code && p.index > oldPolls && p.time <= snap.now && m[p.key] == nil {
            if let first = m[pollKey(p.session.key, 1)] {
                m[p.key] = first
                any = true
            }
        }
        if any { saveStatuses(m) }
    }

    // MARK: Yoklama kayıtları

    public var statuses: [String: Status] {
        (load([String: String].self, "attendance") ?? [:]).compactMapValues { Status(rawValue: $0) }
    }

    private func saveStatuses(_ m: [String: Status]) { save(m.mapValues(\.rawValue), "attendance") }

    public func status(_ key: String) -> Status? { statuses[key] }

    public func setStatus(_ keys: [String], _ status: Status?) {
        var m = statuses
        for k in keys { m[k] = status }
        saveStatuses(m)
    }

    /// Bildirim tuşundan gelen cevabı kaydeder. Erken uyarı gerekiyorsa hemen gösterilecek bildirimi döner.
    public func answer(pollKey: String, status: Status) -> PlannedNotification? {
        setStatus([pollKey], status)
        let snap = snapshot()
        guard status == .absent, snap.notify.warnEnabled,
              let poll = snap.polls.first(where: { $0.key == pollKey }),
              let st = snap.stat(poll.code), st.remaining <= snap.notify.warnThreshold else { return nil }
        return Planner.warning(st)
    }

    // MARK: Bildirim ayarları

    public var notify: NotifySettings { load(NotifySettings.self, "notify") ?? NotifySettings() }
    public func setNotify(_ n: NotifySettings) { save(n, "notify") }

    // MARK: Hepsi bir arada

    public func snapshot(now: DateTime? = nil) -> Snapshot {
        Snapshot(now: now ?? clock(), semesterStart: semesterStart, weeks: weeks, requiredPercent: requiredPercent,
                 slots: slots, configs: configs, statuses: statuses, notify: notify)
    }
}
