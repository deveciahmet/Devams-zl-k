import Foundation

/// Yapay zekâdan gelen tek bir ders saati.
public struct ImportedSlot: Hashable {
    public var code: String
    public var name: String
    public var day: Int
    public var start: HM
    public var end: HM

    public init(code: String, name: String, day: Int, start: HM, end: HM) {
        self.code = code
        self.name = name
        self.day = day
        self.start = start
        self.end = end
    }
}

public struct ParseResult {
    public let entries: [ImportedSlot]
    public let warnings: [String]
    public let error: String?
}

/// Kullanıcının fotoğrafla birlikte yapay zekâya göndereceği metin (Android sürümüyle aynı).
public let importPrompt = """
Ekteki fotoğraf/ekran görüntüsü bir haftalık ders programı. Programdaki her ders saatini aşağıdaki JSON biçimine çevir.

Kurallar:
1. Cevap olarak SADECE JSON dizisini yaz. Açıklama, başlık veya başka metin ekleme.
2. Her eleman bir ders saatidir: {"code": "...", "name": "...", "day": 1, "start": "HH:mm", "end": "HH:mm"}
3. "code": dersin kodu (örn. "MAK 335"). Kod yoksa dersin adını yaz.
4. "name": dersin tam adı fotoğrafta yazıyorsa onu yaz, yoksa "" bırak.
5. "day": 1=Pazartesi, 2=Salı, 3=Çarşamba, 4=Perşembe, 5=Cuma, 6=Cumartesi, 7=Pazar. Fotoğrafta gün yerine tarih varsa o tarihin haftanın hangi günü olduğunu yaz.
6. "start" ve "end": 24 saat biçiminde "HH:mm" (örn. "08:30", "13:29"). Saatleri fotoğrafta yazdığı gibi yaz, yuvarlama.
7. Aynı ders haftada birden fazla gün veya saatte varsa her biri ayrı eleman olsun.
8. Okuyamadığın bir değer varsa en olası değeri yaz.

Örnek cevap:
[{"code":"MAK 335","name":"","day":1,"start":"10:30","end":"13:29"},{"code":"MAK 303","name":"","day":4,"start":"08:30","end":"10:29"}]
"""

public enum ScheduleImport {

    public static func parse(_ raw: String) -> ParseResult {
        let text = clean(raw)
        if text.isEmpty {
            return ParseResult(entries: [], warnings: [], error: "Önce yapay zekânın cevabını yapıştır.")
        }
        guard let arr = findArray(text) else {
            return ParseResult(entries: [], warnings: [], error:
                "Cevapta ders listesi (JSON) bulunamadı. Cevabı eksiksiz kopyaladığından emin ol; " +
                "gerekirse yapay zekâdan \"sadece JSON yaz\" diye tekrar iste.")
        }
        var warnings: [String] = []
        var out: [ImportedSlot] = []
        var seen = Set<String>()
        for (i, item) in arr.enumerated() {
            guard let o = item as? [String: Any] else {
                warnings.append("\(i + 1). kayıt ders bilgisi değil")
                continue
            }
            switch parseEntry(o) {
            case .failure(let e):
                warnings.append("\(i + 1). kayıt atlandı: \(e.message)")
            case .success(let slot):
                let k = "\(slot.code.lowercased())|\(slot.day)|\(slot.start.text)"
                if seen.contains(k) {
                    warnings.append("\(i + 1). kayıt tekrar ettiği için atlandı (\(slot.code))")
                    continue
                }
                seen.insert(k)
                out.append(slot)
            }
        }
        let entries = out.enumerated().sorted { a, b in
            (a.element.day, a.element.start.minutes, a.offset) < (b.element.day, b.element.start.minutes, b.offset)
        }.map(\.element)
        if entries.isEmpty { return ParseResult(entries: [], warnings: warnings, error: "Geçerli ders saati bulunamadı.") }
        return ParseResult(entries: entries, warnings: warnings, error: nil)
    }

    private static func clean(_ raw: String) -> String {
        var s = raw
        for (a, b) in [("\u{201C}", "\""), ("\u{201D}", "\""), ("\u{201E}", "\""), ("\u{00AB}", "\""), ("\u{00BB}", "\""),
                       ("\u{2018}", "'"), ("\u{2019}", "'"), ("\u{00A0}", " ")] {
            s = s.replacingOccurrences(of: a, with: b)
        }
        // ```json … ``` kod blokları
        s = s.replacingOccurrences(of: "```[a-zA-Z]*", with: " ", options: .regularExpression)
        return s.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func json(_ s: String) -> Any? {
        try? JSONSerialization.jsonObject(with: Data(s.utf8))
    }

    /// Metnin içinden ders listesini (JSON dizisi) bulur; etrafında açıklama olsa bile.
    private static func findArray(_ t: String) -> [Any]? {
        let chars = Array(t)
        let starts = chars.indices.filter { chars[$0] == "[" }.prefix(20)
        let ends = chars.indices.filter { chars[$0] == "]" }.reversed().prefix(20)
        for s in starts {
            for e in ends where e > s {
                if let arr = json(String(chars[s...e])) as? [Any], let first = arr.first, first is [String: Any] {
                    return arr
                }
            }
        }
        if let os = chars.firstIndex(of: "{"), let oe = chars.lastIndex(of: "}"), oe > os,
           let o = json(String(chars[os...oe])) as? [String: Any] {
            for key in o.keys.sorted() {
                if let a = o[key] as? [Any], !a.isEmpty { return a }
            }
            return [o]
        }
        return nil
    }

    /// Türkçe karakterleri sadeleştirip küçük harfe çevirir: "Çarşamba" → "carsamba".
    static func fold(_ s: String) -> String {
        var out = ""
        for c in s {
            switch c {
            case "ç", "Ç": out.append("c")
            case "ğ", "Ğ": out.append("g")
            case "ı", "I", "İ", "i": out.append("i")
            case "ö", "Ö": out.append("o")
            case "ş", "Ş": out.append("s")
            case "ü", "Ü": out.append("u")
            case "â", "Â": out.append("a")
            case "î", "Î": out.append("i")
            case "û", "Û": out.append("u")
            default: out.append(contentsOf: String(c).lowercased())
            }
        }
        return out
    }

    private static func stringValue(_ v: Any) -> String {
        if v is NSNull { return "" }
        if let s = v as? String { return s.trimmingCharacters(in: .whitespacesAndNewlines) }
        if let i = v as? Int { return String(i) }
        if let d = v as? Double {
            return d == d.rounded() && Swift.abs(d) < 1e12 ? String(Int(d)) : String(d)
        }
        return "\(v)".trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func fields(_ o: [String: Any]) -> [String: String] {
        var m: [String: String] = [:]
        for (k, v) in o {
            let s = stringValue(v)
            if !s.isEmpty { m[fold(k).filter { $0.isLetter }] = s }
        }
        return m
    }

    struct EntryError: Error { let message: String }

    private static func parseEntry(_ o: [String: Any]) -> Result<ImportedSlot, EntryError> {
        let f = fields(o)
        func pick(_ keys: String...) -> String? { keys.lazy.compactMap { f[$0] }.first }

        let name = pick("name", "ad", "adi", "isim", "dersadi", "title", "coursename") ?? ""
        guard let codeRaw = pick("code", "kod", "derskodu", "coursecode", "course", "ders") ?? (name.isEmpty ? nil : name) else {
            return .failure(EntryError(message: "ders kodu yok"))
        }
        guard let dayRaw = pick("day", "gun", "weekday", "dayofweek") else {
            return .failure(EntryError(message: "gün yok (\(codeRaw))"))
        }
        guard let day = parseDay(dayRaw) else {
            return .failure(EntryError(message: "gün anlaşılamadı: \"\(dayRaw)\" (\(codeRaw))"))
        }
        var startRaw = pick("start", "baslangic", "begin", "from", "starttime", "baslangicsaati")
        var endRaw = pick("end", "bitis", "finish", "to", "endtime", "bitissaati")
        if startRaw == nil || endRaw == nil, let range = pick("time", "saat", "hours", "zaman") {
            let parts = range.split(whereSeparator: { $0 == "-" || $0 == "–" || $0 == "—" })
                .map { $0.trimmingCharacters(in: .whitespaces) }
            if parts.count == 2 {
                if startRaw == nil { startRaw = parts[0] }
                if endRaw == nil { endRaw = parts[1] }
            }
        }
        guard let start = startRaw.flatMap(parseTime) else {
            return .failure(EntryError(message: "başlangıç saati anlaşılamadı: \"\(startRaw ?? "")\" (\(codeRaw))"))
        }
        guard let end = endRaw.flatMap(parseTime) else {
            return .failure(EntryError(message: "bitiş saati anlaşılamadı: \"\(endRaw ?? "")\" (\(codeRaw))"))
        }
        guard end > start else {
            return .failure(EntryError(message: "bitiş saati başlangıçtan önce (\(codeRaw) \(start.text)–\(end.text))"))
        }
        let code = normalizeCode(codeRaw)
        let trimmedName = name.trimmingCharacters(in: .whitespaces)
        let cleanName = trimmedName.lowercased() == codeRaw.lowercased() || normalizeCode(trimmedName) == code ? "" : trimmedName
        return .success(ImportedSlot(code: code, name: cleanName, day: day, start: start, end: end))
    }

    private static let dayAliases: [String: Int] = [
        "pzt": 1, "pts": 1, "pt": 1, "mon": 1,
        "sal": 2, "sl": 2, "tue": 2, "tues": 2,
        "car": 3, "crs": 3, "cars": 3, "wed": 3,
        "per": 4, "prs": 4, "pers": 4, "thu": 4, "thur": 4, "thurs": 4,
        "cum": 5, "cm": 5, "fri": 5,
        "cmt": 6, "cts": 6, "sat": 6,
        "paz": 7, "pz": 7, "sun": 7,
    ]

    // Uzun isimler önce: "pazartesi" "pazar"dan, "cumartesi" "cuma"dan önce denenmeli.
    private static let dayFull: [(String, Int)] = [
        ("pazartesi", 1), ("sali", 2), ("carsamba", 3), ("persembe", 4), ("cumartesi", 6), ("cuma", 5), ("pazar", 7),
        ("monday", 1), ("tuesday", 2), ("wednesday", 3), ("thursday", 4), ("friday", 5), ("saturday", 6), ("sunday", 7),
    ]

    public static func parseDay(_ raw: String) -> Int? {
        let t = raw.trimmingCharacters(in: .whitespaces)
        if let i = Int(t) { return (1...7).contains(i) ? i : nil }
        if let d = Double(t) { return d == d.rounded() && (1...7).contains(Int(d)) ? Int(d) : nil }
        let n = fold(t).filter { $0.isLetter }
        if n.isEmpty { return nil }
        if let d = dayAliases[n] { return d }
        for (name, d) in dayFull where n.hasPrefix(name) { return d }
        return nil
    }

    public static func parseTime(_ raw: String) -> HM? {
        let s = raw.trimmingCharacters(in: .whitespaces)
        func digits(_ x: Substring) -> Bool { !x.isEmpty && x.allSatisfy { $0.isASCII && $0.isNumber } }
        var h: Int?
        var m: Int?
        let colon = s.split(separator: ":", omittingEmptySubsequences: false)
        let dot = s.split(separator: ".", omittingEmptySubsequences: false)
        if colon.count == 2 || colon.count == 3 {
            guard colon[0].count <= 2, colon[1].count == 2, colon.allSatisfy(digits),
                  colon.count == 2 || colon[2].count == 2 else { return nil }
            h = Int(colon[0])
            m = Int(colon[1])
        } else if dot.count == 2 {
            guard dot[0].count <= 2, dot[1].count == 2, dot.allSatisfy(digits) else { return nil }
            h = Int(dot[0])
            m = Int(dot[1])
        } else if s.count == 4, digits(Substring(s)) {
            h = Int(s.prefix(2))
            m = Int(s.suffix(2))
        }
        guard let hh = h, let mm = m, (0...23).contains(hh), (0...59).contains(mm) else { return nil }
        return HM(hh, mm)
    }
}
