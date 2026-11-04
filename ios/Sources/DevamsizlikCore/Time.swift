import Foundation

/// Takvim günü (saat dilimi olmadan), ör. 2026-11-04. Android'deki LocalDate karşılığı.
/// Hesaplar tam sayılarla yapılır; telefonun takvim ayarından (ör. Budist takvimi) etkilenmez.
public struct Day: Hashable, Comparable, Codable, CustomStringConvertible {
    public let year: Int
    public let month: Int
    public let day: Int

    public init(_ year: Int, _ month: Int, _ day: Int) {
        self.year = year
        self.month = month
        self.day = day
    }

    /// Julian gün numarası: gün farkları ve haftanın günü için.
    public var jdn: Int {
        let a = (14 - month) / 12
        let y = year + 4800 - a
        let m = month + 12 * a - 3
        return day + (153 * m + 2) / 5 + 365 * y + y / 4 - y / 100 + y / 400 - 32045
    }

    public init(jdn: Int) {
        let a = jdn + 32044
        let b = (4 * a + 3) / 146097
        let c = a - 146097 * b / 4
        let d = (4 * c + 3) / 1461
        let e = c - 1461 * d / 4
        let m = (5 * e + 2) / 153
        self.init(100 * b + d - 4800 + m / 10, m + 3 - 12 * (m / 10), e - (153 * m + 2) / 5 + 1)
    }

    public func adding(days n: Int) -> Day { Day(jdn: jdn + n) }
    public func adding(weeks n: Int) -> Day { adding(days: 7 * n) }

    /// 1 = Pazartesi … 7 = Pazar
    public var weekday: Int { jdn % 7 + 1 }
    /// Bu günün haftasının Pazartesi'si.
    public var monday: Day { adding(days: 1 - weekday) }
    public func days(until other: Day) -> Int { other.jdn - jdn }

    public var iso: String { String(format: "%04d-%02d-%02d", year, month, day) }
    public var description: String { iso }

    public init?(iso: String) {
        let p = iso.split(separator: "-").compactMap { Int($0) }
        guard p.count == 3, (1...12).contains(p[1]), (1...31).contains(p[2]) else { return nil }
        self.init(p[0], p[1], p[2])
    }

    public static func < (a: Day, b: Day) -> Bool { a.jdn < b.jdn }

    public init(from decoder: Decoder) throws {
        let s = try decoder.singleValueContainer().decode(String.self)
        guard let d = Day(iso: s) else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "Geçersiz tarih: \(s)"))
        }
        self = d
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(iso)
    }
}

/// Saat ve dakika, ör. 13:29. Android'deki LocalTime karşılığı (saniyesiz).
public struct HM: Hashable, Comparable, Codable, CustomStringConvertible {
    /// Gün içindeki dakika (0…1439).
    public let minutes: Int

    public init(_ hour: Int, _ minute: Int) { minutes = hour * 60 + minute }
    public init(minutes: Int) { self.minutes = minutes }

    public var hour: Int { minutes / 60 }
    public var minute: Int { minutes % 60 }
    public var text: String { String(format: "%02d:%02d", hour, minute) }
    public var description: String { text }

    /// "10:30" biçimini okur.
    public init?(_ text: String) {
        let p = text.split(separator: ":").compactMap { Int($0) }
        guard p.count == 2, (0...23).contains(p[0]), (0...59).contains(p[1]) else { return nil }
        self.init(p[0], p[1])
    }

    public static func < (a: HM, b: HM) -> Bool { a.minutes < b.minutes }

    public init(from decoder: Decoder) throws {
        let s = try decoder.singleValueContainer().decode(String.self)
        guard let t = HM(s) else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "Geçersiz saat: \(s)"))
        }
        self = t
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(text)
    }
}

/// Yerel tarih ve saat (dakika hassasiyetinde). Android'deki LocalDateTime karşılığı.
public struct DateTime: Hashable, Comparable, CustomStringConvertible {
    public let day: Day
    /// Gün içindeki dakika (0…1439).
    public let minutes: Int

    public init(_ day: Day, _ time: HM) { self.init(day: day, minutes: time.minutes) }

    /// Dakika 0…1439 dışındaysa günü kaydırarak düzeltir.
    public init(day: Day, minutes: Int) {
        let q = minutes >= 0 ? minutes / 1440 : -((-minutes + 1439) / 1440)
        self.day = day.adding(days: q)
        self.minutes = minutes - q * 1440
    }

    public var time: HM { HM(minutes: minutes) }
    public func adding(minutes n: Int) -> DateTime { DateTime(day: day, minutes: minutes + n) }
    public func adding(days n: Int) -> DateTime { DateTime(day: day.adding(days: n), minutes: minutes) }

    public var ordinal: Int { day.jdn * 1440 + minutes }
    public static func < (a: DateTime, b: DateTime) -> Bool { a.ordinal < b.ordinal }
    public var description: String { "\(day.iso)T\(time.text)" }

    /// "2026-11-04T12:00" biçimini okur.
    public init?(iso: String) {
        let p = iso.split(separator: "T")
        guard p.count == 2, let d = Day(iso: String(p[0])) else { return nil }
        let hm = p[1].split(separator: ":").prefix(2).joined(separator: ":")
        guard let t = HM(hm) else { return nil }
        self.init(d, t)
    }

    /// Telefonun saatine göre şimdi (Miladi takvim, cihazın saat dilimi).
    public static func now(_ date: Date = Date()) -> DateTime {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = .current
        let c = cal.dateComponents([.year, .month, .day, .hour, .minute], from: date)
        return DateTime(Day(c.year ?? 2000, c.month ?? 1, c.day ?? 1), HM(c.hour ?? 0, c.minute ?? 0))
    }
}

// MARK: - Türkçe biçimlendirme

private let dayShort = ["Pzt", "Sal", "Çar", "Per", "Cum", "Cmt", "Paz"]
private let dayLong = ["Pazartesi", "Salı", "Çarşamba", "Perşembe", "Cuma", "Cumartesi", "Pazar"]
private let monthShort = ["Oca", "Şub", "Mar", "Nis", "May", "Haz", "Tem", "Ağu", "Eyl", "Eki", "Kas", "Ara"]
private let monthLong = ["Ocak", "Şubat", "Mart", "Nisan", "Mayıs", "Haziran", "Temmuz", "Ağustos", "Eylül", "Ekim",
                         "Kasım", "Aralık"]

public func dayShortName(_ day: Int) -> String { dayShort[min(max(day - 1, 0), 6)] }
public func dayLongName(_ day: Int) -> String { dayLong[min(max(day - 1, 0), 6)] }

public extension Day {
    /// "4 Kas"
    var short: String { "\(day) \(monthShort[min(max(month - 1, 0), 11)])" }
    /// "4 Kasım 2026"
    var long: String { "\(day) \(monthLong[min(max(month - 1, 0), 11)]) \(year)" }
}

/// "%23,1"
public func pct(_ v: Double) -> String {
    "%" + String(format: "%.1f", v).replacingOccurrences(of: ".", with: ",")
}
