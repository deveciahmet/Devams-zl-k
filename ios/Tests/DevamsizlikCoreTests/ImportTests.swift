import XCTest
@testable import DevamsizlikCore

/// Android'deki ImportTest.kt'nin karşılığı.
final class ImportTests: XCTestCase {
    let obsAnswer = """
    [{"code":"MAK 335","name":"","day":1,"start":"10:30","end":"13:29"},
     {"code":"MAK 303","name":"","day":1,"start":"15:30","end":"17:29"},
     {"code":"MAK 309","name":"","day":2,"start":"10:30","end":"13:29"},
     {"code":"ITB 220","name":"","day":2,"start":"14:30","end":"17:29"},
     {"code":"MAK 305","name":"","day":3,"start":"08:30","end":"10:29"},
     {"code":"MAK 303","name":"","day":4,"start":"08:30","end":"10:29"},
     {"code":"MAK 305","name":"","day":4,"start":"10:30","end":"12:29"},
     {"code":"MAK 317","name":"","day":4,"start":"12:30","end":"14:29"},
     {"code":"MAK 304E","name":"","day":4,"start":"15:30","end":"17:29"},
     {"code":"BEB 104","name":"","day":5,"start":"10:30","end":"12:29"},
     {"code":"MAK 118","name":"","day":5,"start":"14:00","end":"16:59"}]
    """

    func testParsesObsSchedule() {
        let r = ScheduleImport.parse(obsAnswer)
        XCTAssertNil(r.error)
        XCTAssertTrue(r.warnings.isEmpty)
        XCTAssertEqual(r.entries.count, 11)
        XCTAssertEqual(r.entries.first, ImportedSlot(code: "MAK 335", name: "", day: 1, start: HM(10, 30), end: HM(13, 29)))
        XCTAssertEqual(r.entries.last, ImportedSlot(code: "MAK 118", name: "", day: 5, start: HM(14, 0), end: HM(16, 59)))
        struct K: Hashable { let code: String; let day: Int; let start: HM; let end: HM }
        XCTAssertEqual(Set(r.entries.map { K(code: $0.code, day: $0.day, start: $0.start, end: $0.end) }),
                       Set(AppState.defaultSlots.map { K(code: $0.code, day: $0.day, start: $0.start, end: $0.end) }))
    }

    func testToleratesChatterAndCodeFences() {
        let text = "Tabii! İşte programın:\n```json\n" +
            "[{\"code\":\"mak335\",\"name\":\"Makine Dinamiği\",\"day\":\"Pazartesi\",\"start\":\"10.30\",\"end\":\"13:29\"}]\n" +
            "```\nBaşka bir şey ister misin? [Not: saatler OBS'den]"
        let r = ScheduleImport.parse(text)
        XCTAssertNil(r.error)
        XCTAssertEqual(r.entries, [ImportedSlot(code: "MAK 335", name: "Makine Dinamiği", day: 1, start: HM(10, 30), end: HM(13, 29))])
    }

    func testAcceptsTurkishKeysDayNamesAndTimeRanges() {
        let text = """
        {"dersler": [
          {"Kod": "BEB 104", "Gün": "Cuma", "Saat": "10:30 - 12:29"},
          {"ders_kodu": "MAK 309", "gun": "Salı", "başlangıç": "10:30", "bitiş": "13:29"},
          {"code": "X 1", "day": "Cumartesi", "start": "9:00", "end": "10:00"},
          {"code": "Y 2", "day": "Pazar", "start": "0900", "end": "1000"}
        ]}
        """
        let r = ScheduleImport.parse(text)
        XCTAssertNil(r.error)
        XCTAssertEqual(r.entries.count, 4)
        XCTAssertEqual(Set(r.entries.map(\.day)), [5, 2, 6, 7])
        XCTAssertEqual(r.entries.first { $0.code == "BEB 104" }?.end, HM(12, 29))
        XCTAssertEqual(r.entries.first { $0.code == "Y 2" }?.start, HM(9, 0))
    }

    func testReportsBadEntriesAndDuplicates() {
        let text = """
        [{"code":"MAK 335","day":1,"start":"10:30","end":"13:29"},
         {"code":"MAK 335","day":1,"start":"10:30","end":"13:29"},
         {"code":"MAK 999","day":9,"start":"10:30","end":"13:29"},
         {"code":"MAK 998","day":1,"start":"25:00","end":"26:00"},
         {"code":"MAK 997","day":1,"start":"14:00","end":"13:00"},
         {"day":1,"start":"10:00","end":"11:00"}]
        """
        let r = ScheduleImport.parse(text)
        XCTAssertNil(r.error)
        XCTAssertEqual(r.entries.count, 1)
        XCTAssertEqual(r.warnings.count, 5)
    }

    func testErrorWhenNoJson() {
        let r = ScheduleImport.parse("Üzgünüm, fotoğrafı okuyamadım.")
        XCTAssertNotNil(r.error)
        XCTAssertTrue(r.entries.isEmpty)
        XCTAssertNotNil(ScheduleImport.parse("   ").error)
    }

    func testSmartQuotesFromMessagingApps() {
        let r = ScheduleImport.parse("[{“code”:“MAK 317”,“day”:4,“start”:“12:30”,“end”:“14:29”}]")
        XCTAssertNil(r.error)
        XCTAssertEqual(r.entries.map(\.code), ["MAK 317"])
    }

    func testDayAndTimeParsers() {
        XCTAssertEqual(ScheduleImport.parseDay("Pazartesi"), 1)
        XCTAssertEqual(ScheduleImport.parseDay("PAZAR"), 7)
        XCTAssertEqual(ScheduleImport.parseDay("cumartesi"), 6)
        XCTAssertEqual(ScheduleImport.parseDay("Cuma"), 5)
        XCTAssertEqual(ScheduleImport.parseDay("Çarşamba"), 3)
        XCTAssertEqual(ScheduleImport.parseDay("Per"), 4)
        XCTAssertEqual(ScheduleImport.parseDay("Tuesday"), 2)
        XCTAssertEqual(ScheduleImport.parseDay("4"), 4)
        XCTAssertNil(ScheduleImport.parseDay("0"))
        XCTAssertNil(ScheduleImport.parseDay("yarın"))
        XCTAssertEqual(ScheduleImport.parseTime("8:30"), HM(8, 30))
        XCTAssertEqual(ScheduleImport.parseTime("13:29:00"), HM(13, 29))
        XCTAssertNil(ScheduleImport.parseTime("24:00"))
        XCTAssertNil(ScheduleImport.parseTime("öğlen"))
    }
}
