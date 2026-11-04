import Foundation

/// Bir derste en fazla kaç yoklama alınabilir.
public let maxPolls = 4

public enum Status: String, Codable, CaseIterable {
    case attended = "ATTENDED"
    case absent = "ABSENT"
    case cancelled = "CANCELLED"
}

/// Devamsızlık hakkının nasıl hesaplanacağı.
public enum LimitMode: String, Codable, CaseIterable {
    /// Genel ayarlardaki devam şartı yüzdesi.
    case standard = "DEFAULT"
    /// Derse özel devam şartı yüzdesi.
    case percent = "PERCENT"
    /// Doğrudan "kaç kez gelmeme hakkı var".
    case count = "COUNT"
}

/// Haftalık programdaki tek bir ders saati. day: 1 = Pazartesi … 7 = Pazar
public struct Slot: Codable, Hashable, Identifiable {
    public var id: String
    public var code: String
    public var day: Int
    public var start: HM
    public var end: HM

    public init(id: String, code: String, day: Int, start: HM, end: HM) {
        self.id = id
        self.code = code
        self.day = day
        self.start = start
        self.end = end
    }
}

/// Derse özel ayarlar (ders koduna göre saklanır).
public struct CourseConfig: Codable, Hashable {
    public var name: String
    /// Dönemde kaç kez yapılacak; nil = programdan otomatik hesapla.
    public var totalSessions: Int?
    public var mode: LimitMode
    /// percent modunda devam şartı (%).
    public var requiredPercent: Int
    /// count modunda gelmeme hakkı.
    public var allowedCount: Int
    /// Her derste kaç yoklama alınıyor.
    public var polls: Int

    public init(name: String = "", totalSessions: Int? = nil, mode: LimitMode = .standard,
                requiredPercent: Int = 70, allowedCount: Int = 3, polls: Int = 1) {
        self.name = name
        self.totalSessions = totalSessions
        self.mode = mode
        self.requiredPercent = requiredPercent
        self.allowedCount = allowedCount
        self.polls = polls
    }

    enum CodingKeys: String, CodingKey { case name, totalSessions = "total", mode, requiredPercent = "percent", allowedCount = "count", polls }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        totalSessions = try c.decodeIfPresent(Int.self, forKey: .totalSessions).flatMap { $0 > 0 ? $0 : nil }
        mode = (try? c.decodeIfPresent(LimitMode.self, forKey: .mode)) ?? .standard
        requiredPercent = min(max(try c.decodeIfPresent(Int.self, forKey: .requiredPercent) ?? 70, 0), 100)
        allowedCount = max(try c.decodeIfPresent(Int.self, forKey: .allowedCount) ?? 3, 0)
        polls = min(max(try c.decodeIfPresent(Int.self, forKey: .polls) ?? 1, 1), maxPolls)
    }
}

/// Belirli bir tarihteki tek bir ders.
public struct Session: Hashable {
    public let slot: Slot
    public let date: Day
    public let week: Int

    public var key: String { "\(slot.id)|\(date.iso)" }
    public var start: DateTime { DateTime(date, slot.start) }
    public var end: DateTime { DateTime(date, slot.end) }
}

/// Bir dersteki tek bir yoklama (bildirimle sorulan tek bir soru).
public struct Poll: Hashable, Identifiable {
    public let session: Session
    public let index: Int
    public let count: Int
    public let time: DateTime

    public var key: String { pollKey(session.key, index) }
    public var id: String { key }
    public var code: String { session.slot.code }
}

/// 1. yoklamanın anahtarı dersin kendi anahtarıdır (Android sürümüyle aynı biçim).
public func pollKey(_ sessionKey: String, _ index: Int) -> String {
    index <= 1 ? sessionKey : "\(sessionKey)|\(index)"
}

public struct CourseStats: Hashable, Identifiable {
    public let code: String
    public let name: String
    public let pollsPerSession: Int
    /// Programdan hesaplanan ders sayısı.
    public let sessionCount: Int
    /// Toplam yoklama: elle girilen ders sayısı × yoklama ya da (programdaki yoklamalar − yapılmayanlar).
    public let total: Int
    public let manualTotal: Bool
    public let attended: Int
    public let absent: Int
    public let cancelled: Int
    public let unanswered: Int
    public let upcoming: Int
    public let allowed: Int

    public var id: String { code }
    public var remaining: Int { allowed - absent }
    public var absentPercent: Double { total <= 0 ? 0 : Double(absent) * 100 / Double(total) }
    public var exhausted: Bool { remaining <= 0 }
    public var title: String { name.isEmpty ? code : name }
    public var unit: String { pollsPerSession > 1 ? "yoklama" : "ders" }
}

public struct NotifySettings: Codable, Hashable {
    /// Cevapsız soruyu kaç dakika sonra tekrar sorsun (0 = kapalı).
    public var reminderMinutes: Int
    public var weeklyEnabled: Bool
    /// 1 = Pazartesi … 7 = Pazar
    public var weeklyDay: Int
    public var weeklyTime: HM
    public var warnEnabled: Bool
    /// Kalan hak bu sayıya inince uyar.
    public var warnThreshold: Int
    /// Riskli derslerden kaç dakika önce hatırlatsın (0 = kapalı).
    public var preclassMinutes: Int

    public init(reminderMinutes: Int = 30, weeklyEnabled: Bool = true, weeklyDay: Int = 1, weeklyTime: HM = HM(8, 0),
                warnEnabled: Bool = true, warnThreshold: Int = 1, preclassMinutes: Int = 60) {
        self.reminderMinutes = reminderMinutes
        self.weeklyEnabled = weeklyEnabled
        self.weeklyDay = weeklyDay
        self.weeklyTime = weeklyTime
        self.warnEnabled = warnEnabled
        self.warnThreshold = warnThreshold
        self.preclassMinutes = preclassMinutes
    }

    public init(from decoder: Decoder) throws {
        let d = NotifySettings()
        let c = try decoder.container(keyedBy: CodingKeys.self)
        reminderMinutes = max(try c.decodeIfPresent(Int.self, forKey: .reminderMinutes) ?? d.reminderMinutes, 0)
        weeklyEnabled = try c.decodeIfPresent(Bool.self, forKey: .weeklyEnabled) ?? d.weeklyEnabled
        weeklyDay = min(max(try c.decodeIfPresent(Int.self, forKey: .weeklyDay) ?? d.weeklyDay, 1), 7)
        weeklyTime = (try? c.decodeIfPresent(HM.self, forKey: .weeklyTime)) ?? d.weeklyTime
        warnEnabled = try c.decodeIfPresent(Bool.self, forKey: .warnEnabled) ?? d.warnEnabled
        warnThreshold = min(max(try c.decodeIfPresent(Int.self, forKey: .warnThreshold) ?? d.warnThreshold, 1), 10)
        preclassMinutes = max(try c.decodeIfPresent(Int.self, forKey: .preclassMinutes) ?? d.preclassMinutes, 0)
    }
}

public enum EventType: String {
    case ask, remind, preclass, weekly
}

public struct Event: Hashable {
    public let time: DateTime
    public let type: EventType
    public let key: String
}

public struct WeekSummary {
    public let total: Int
    public let attended: Int
    public let absent: Int
    public let cancelled: Int
    public let unanswered: Int
    /// Devamsızlık yapılan dersler, ilk görülme sırasıyla.
    public let absentByCourse: [(code: String, count: Int)]
}

/// Listelerde haftalara ve derslere göre gruplama.
public struct SessionGroup: Identifiable {
    public let session: Session
    public let polls: [Poll]
    public var id: String { session.key }
}

public struct WeekGroup: Identifiable {
    public let week: Int
    public let monday: Day
    public let sessions: [SessionGroup]
    public var id: Int { week }
    /// "Hafta 1  (28 Eyl – 4 Eki)"
    public var title: String { "Hafta \(week)  (\(monday.short) – \(monday.adding(days: 6).short))" }
}
