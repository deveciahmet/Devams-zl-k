import XCTest
@testable import DevamsizlikCore

/// Android'deki CoreTest.kt'nin karşılığı: aynı girdiler, aynı sonuçlar.
final class CoreTests: XCTestCase {
    let start = Day(2026, 9, 28)
    let slots = [
        Slot(id: "mak335", code: "MAK 335", day: 1, start: HM(10, 30), end: HM(13, 29)),
        Slot(id: "mak303-pzt", code: "MAK 303", day: 1, start: HM(15, 30), end: HM(17, 29)),
        Slot(id: "mak303-per", code: "MAK 303", day: 4, start: HM(8, 30), end: HM(10, 29)),
    ]

    func dt(_ s: String) -> DateTime { DateTime(iso: s)! }

    func testDayArithmetic() {
        XCTAssertEqual(start.weekday, 1) // 28 Eylül 2026 Pazartesi
        XCTAssertEqual(Day(2026, 11, 4).weekday, 3)
        XCTAssertEqual(Day(2026, 11, 8).weekday, 7)
        XCTAssertEqual(Day(2026, 11, 4).monday, Day(2026, 11, 2))
        XCTAssertEqual(Day(2026, 12, 31).adding(days: 1), Day(2027, 1, 1))
        XCTAssertEqual(Day(2028, 2, 28).adding(days: 1), Day(2028, 2, 29)) // artık yıl
        XCTAssertEqual(Day(iso: "2026-10-05"), Day(2026, 10, 5))
        XCTAssertEqual(DateTime(Day(2026, 11, 2), HM(23, 50)).adding(minutes: 20), dt("2026-11-03T00:10"))
        XCTAssertEqual(dt("2026-11-03T00:10").adding(minutes: -20), dt("2026-11-02T23:50"))
        XCTAssertEqual(Day(2026, 11, 4).short, "4 Kas")
        XCTAssertEqual(Day(2026, 9, 28).long, "28 Eylül 2026")
        XCTAssertEqual(pct(23.0769), "%23,1")
    }

    func testWeekNumberIsSixOnNovember4() {
        XCTAssertEqual(weekNumber(start, Day(2026, 11, 4)), 6)
        XCTAssertEqual(weekNumber(start, start), 1)
        XCTAssertEqual(weekNumber(start, start.adding(days: -1)), 0)
        XCTAssertEqual(weekNumber(start, start.adding(weeks: 12).adding(days: 6)), 13)
    }

    func testPollTimesMatchSpec() {
        // 2 saatlik derste 2 yoklama: başlangıçtan 1 saat sonra ve ders bitiminde
        XCTAssertEqual(pollTimes(HM(15, 30), HM(17, 29), 2), [HM(16, 30), HM(17, 29)])
        XCTAssertEqual(pollTimes(HM(10, 30), HM(13, 29), 3), [HM(11, 30), HM(12, 30), HM(13, 29)])
        XCTAssertEqual(pollTimes(HM(10, 30), HM(13, 29), 1), [HM(13, 29)])
        // Ders kısa: eşit aralıklarla
        XCTAssertEqual(pollTimes(HM(8, 30), HM(9, 29), 2), [HM(8, 59), HM(9, 29)])
    }

    func testSessionsCoverWholeSemester() {
        let s = generateSessions(start, 13, slots)
        XCTAssertEqual(s.count, 39)
        XCTAssertEqual(s.first?.start, dt("2026-09-28T10:30"))
        XCTAssertEqual(s.last?.week, 13)
        XCTAssertEqual(s.map(\.start), s.map(\.start).sorted())
    }

    func testPollKeysFirstPollKeepsSessionKey() {
        let s = generateSessions(start, 1, Array(slots.prefix(1)))[0]
        XCTAssertEqual(pollKey(s.key, 1), "mak335|2026-09-28")
        XCTAssertEqual(pollKey(s.key, 2), "mak335|2026-09-28|2")
    }

    func testStatsDefaultPercent() {
        let polls = buildPolls(generateSessions(start, 13, slots), [:])
        let now = dt("2026-11-04T12:00")
        let absent = Set(polls.filter { $0.code == "MAK 335" }.prefix(2).map(\.key))
        let st = computeStats(polls, status: { absent.contains($0) ? .absent : nil }, configs: [:], defaultRequired: 70, now: now)
        let mak335 = st.first { $0.code == "MAK 335" }!
        XCTAssertEqual(mak335.total, 13)
        XCTAssertEqual(mak335.allowed, 3) // ⌊13 × 0,3⌋
        XCTAssertEqual(mak335.absent, 2)
        XCTAssertEqual(mak335.remaining, 1)
        XCTAssertEqual(mak335.unanswered, 4)
        XCTAssertEqual(mak335.upcoming, 7)
        let mak303 = st.first { $0.code == "MAK 303" }!
        XCTAssertEqual(mak303.total, 26)
        XCTAssertEqual(mak303.allowed, 7) // ⌊26 × 0,3⌋
    }

    func testStatsCustomModesAndManualTotal() {
        let cfgs = [
            "MAK 335": CourseConfig(totalSessions: 12, mode: .count, allowedCount: 4),
            "MAK 303": CourseConfig(mode: .percent, requiredPercent: 80, polls: 2),
        ]
        let polls = buildPolls(generateSessions(start, 13, slots), cfgs)
        let st = computeStats(polls, status: { _ in nil }, configs: cfgs, defaultRequired: 70, now: dt("2026-11-04T12:00"))
        let mak335 = st.first { $0.code == "MAK 335" }!
        XCTAssertEqual(mak335.total, 12)
        XCTAssertTrue(mak335.manualTotal)
        XCTAssertEqual(mak335.allowed, 4)
        let mak303 = st.first { $0.code == "MAK 303" }!
        XCTAssertEqual(mak303.pollsPerSession, 2)
        XCTAssertEqual(mak303.sessionCount, 26)
        XCTAssertEqual(mak303.total, 52)
        XCTAssertEqual(mak303.allowed, 10) // ⌊52 × 0,2⌋
        XCTAssertEqual(mak303.unit, "yoklama")
    }

    func testCancelledReducesTotalOnlyWhenAutomatic() {
        let polls = buildPolls(generateSessions(start, 13, Array(slots.prefix(1))), [:])
        let cancelled = polls[0].key
        let status: (String) -> Status? = { $0 == cancelled ? .cancelled : nil }
        let now = dt("2026-11-04T12:00")
        let auto = computeStats(polls, status: status, configs: [:], defaultRequired: 70, now: now)[0]
        XCTAssertEqual(auto.total, 12)
        XCTAssertEqual(auto.allowed, 3)
        let manual = computeStats(polls, status: status, configs: ["MAK 335": CourseConfig(totalSessions: 13)],
                                  defaultRequired: 70, now: now)[0]
        XCTAssertEqual(manual.total, 13)
    }

    func testEventsAskRemindPreclassWeekly() {
        let polls = buildPolls(generateSessions(start, 13, Array(slots.prefix(1))), [:])
        let answered = polls[0].key
        let ns = NotifySettings(reminderMinutes: 30, weeklyEnabled: true, weeklyDay: 1, weeklyTime: HM(8, 0),
                                warnEnabled: true, warnThreshold: 1, preclassMinutes: 60)
        let ev = computeEvents(polls, status: { $0 == answered ? .attended : nil }, riskyCodes: ["MAK 335"],
                               settings: ns, semesterStart: start, weeks: 13)
        XCTAssertEqual(ev.filter { $0.type == .ask }.count, 12)
        XCTAssertEqual(ev.filter { $0.type == .remind }.count, 12)
        XCTAssertEqual(ev.filter { $0.type == .preclass }.count, 13)
        XCTAssertEqual(ev.filter { $0.type == .weekly }.count, 14)
        let ask = ev.first { $0.type == .ask }!
        XCTAssertEqual(ask.time, dt("2026-10-05T13:29"))
        XCTAssertEqual(ev.first { $0.type == .remind && $0.key == ask.key }!.time, dt("2026-10-05T13:59"))
        XCTAssertEqual(ev.first { $0.type == .preclass }!.time, dt("2026-09-28T09:30"))
    }

    func testEventsMultiPollAsksEachHour() {
        let cfgs = ["MAK 303": CourseConfig(polls: 2)]
        let polls = buildPolls(generateSessions(start, 1, slots), cfgs)
        let ev = computeEvents(polls, status: { _ in nil }, riskyCodes: [],
                               settings: NotifySettings(reminderMinutes: 0, weeklyEnabled: false),
                               semesterStart: start, weeks: 1)
        XCTAssertEqual(ev.filter { $0.type == .ask }.map(\.time.time), [HM(13, 29), HM(16, 30), HM(17, 29), HM(9, 30), HM(10, 29)])
    }

    func testSummaryCountsLastSevenDays() {
        let polls = buildPolls(generateSessions(start, 13, slots), [:])
        let to = dt("2026-10-05T08:00")
        let absentKey = polls.first { $0.code == "MAK 303" }!.key
        let sum = summarize(polls, status: { $0 == absentKey ? .absent : .attended }, from: to.adding(days: -7), to: to)
        XCTAssertEqual(sum.total, 3)
        XCTAssertEqual(sum.attended, 2)
        XCTAssertEqual(sum.absent, 1)
        XCTAssertEqual(sum.absentByCourse.map(\.code), ["MAK 303"])
    }

    func testNormalizeCodeOnlyTouchesCodes() {
        XCTAssertEqual(normalizeCode("mak335"), "MAK 335")
        XCTAssertEqual(normalizeCode("  mak   304e "), "MAK 304E")
        XCTAssertEqual(normalizeCode("ITB 220"), "ITB 220")
        XCTAssertEqual(normalizeCode(" Fizik   Laboratuvarı "), "Fizik Laboratuvarı")
    }

    func testGroupByWeek() {
        let polls = buildPolls(generateSessions(start, 2, slots), ["MAK 303": CourseConfig(polls: 2)])
        let g = groupByWeek(polls, semesterStart: start)
        XCTAssertEqual(g.map(\.week), [1, 2])
        XCTAssertEqual(g[0].sessions.count, 3)
        XCTAssertEqual(g[0].sessions[1].polls.count, 2)
        XCTAssertEqual(g[0].title, "Hafta 1  (28 Eyl – 4 Eki)")
    }
}
