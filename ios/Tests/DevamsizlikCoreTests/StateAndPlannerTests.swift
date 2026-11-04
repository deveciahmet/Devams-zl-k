import XCTest
@testable import DevamsizlikCore

/// Android'deki NotificationFlowTest.kt'nin iOS karşılığı: veri işlemleri ve önceden zamanlanan bildirim planı.
final class StateAndPlannerTests: XCTestCase {
    func dt(_ s: String) -> DateTime { DateTime(iso: s)! }

    func makeState(_ now: String) -> AppState {
        let t = dt(now)
        return AppState(store: MemoryStore(), clock: { t })
    }

    // MARK: Bildirim planı

    func testPlanAsksAtClassEnd_withThreeAnswerButtons() {
        let state = makeState("2026-11-02T13:00")
        let plan = Planner.plan(state.snapshot())
        let first = plan[0]
        XCTAssertEqual(first.title, "MAK 335 dersine girdin mi?")
        XCTAssertEqual(first.body, "Pazartesi 10:30–13:29 • Hafta 6")
        XCTAssertEqual(first.time, dt("2026-11-02T13:29"))
        XCTAssertEqual(first.pollKey, "mak335-pzt|2026-11-02")
        XCTAssertTrue(first.hasAnswerButtons)
        // 30 dk sonra hatırlatma
        let remind = plan.first { $0.id == "remind|mak335-pzt|2026-11-02" }!
        XCTAssertEqual(remind.title, "Hatırlatma: MAK 335 dersine girdin mi?")
        XCTAssertEqual(remind.time, dt("2026-11-02T13:59"))
    }

    func testPlanNeverContainsPastEvents_andStaysUnderIOSLimit() {
        let state = makeState("2026-11-04T12:00")
        let plan = Planner.plan(state.snapshot())
        XCTAssertTrue(plan.allSatisfy { $0.time! > dt("2026-11-04T12:00") })
        XCTAssertLessThanOrEqual(plan.count, 64)
        XCTAssertEqual(plan.count, Planner.maxPending + 1)
        XCTAssertEqual(plan.last?.kind, .notice) // kesildiyse "uygulamayı aç" hatırlatması
        XCTAssertEqual(Set(plan.map(\.id)).count, plan.count) // kimlikler benzersiz
        XCTAssertEqual(plan.map(\.time!), plan.map(\.time!).sorted())
    }

    func testAnsweringRemovesQuestionAndReminderFromPlan() {
        let state = makeState("2026-11-02T13:30")
        XCTAssertNotNil(Planner.plan(state.snapshot()).first { $0.id == "remind|mak335-pzt|2026-11-02" })
        XCTAssertNil(state.answer(pollKey: "mak335-pzt|2026-11-02", status: .attended))
        XCTAssertEqual(state.status("mak335-pzt|2026-11-02"), .attended)
        XCTAssertNil(Planner.plan(state.snapshot()).first { $0.id == "remind|mak335-pzt|2026-11-02" })
    }

    func testTwoPollsAreAskedEachHour() {
        let state = makeState("2026-11-02T16:00")
        state.saveCourse(oldCode: "MAK 303", newCode: "MAK 303", config: CourseConfig(polls: 2))
        let plan = Planner.plan(state.snapshot()).filter { $0.kind == .ask }
        XCTAssertEqual(plan[0].title, "MAK 303 dersinin 1. saatine girdin mi?")
        XCTAssertEqual(plan[0].time, dt("2026-11-02T16:30"))
        XCTAssertEqual(plan[1].title, "MAK 303 dersinin 2. saatine girdin mi?")
        XCTAssertEqual(plan[1].time, dt("2026-11-02T17:29"))
        XCTAssertEqual(plan[1].pollKey, "mak303-pzt|2026-11-02|2")
        XCTAssertEqual(plan[1].body, "Pazartesi 15:30–17:29 • Hafta 6 • 2. yoklama")
    }

    func testLastRightGivesImmediateWarning_andPreClassReminder() {
        let state = makeState("2026-11-02T13:29")
        state.setStatus(["mak335-pzt|2026-09-28"], .absent)
        let warning = state.answer(pollKey: "mak335-pzt|2026-11-02", status: .absent)
        XCTAssertEqual(warning?.title, "⚠ MAK 335: son 1 devamsızlık hakkın kaldı")
        XCTAssertNil(warning?.time) // hemen
        let pre = Planner.plan(state.snapshot()).first { $0.kind == .preclass }!
        XCTAssertEqual(pre.title, "MAK 335 dersin yaklaşıyor (10:30–13:29)")
        XCTAssertEqual(pre.body, "Son 1 devamsızlık hakkın kaldı, kaçırma!")
        XCTAssertEqual(pre.time, dt("2026-11-09T09:30"))
    }

    func testExhaustedRightWarning() {
        let state = makeState("2026-11-02T13:29")
        state.setStatus(["mak335-pzt|2026-09-28", "mak335-pzt|2026-10-05"], .absent)
        XCTAssertEqual(state.answer(pollKey: "mak335-pzt|2026-11-02", status: .absent)?.title,
                       "⛔ MAK 335: devamsızlık hakkın bitti!")
    }

    func testNoWarningWhenPlentyOfRightsLeft() {
        let state = makeState("2026-11-02T13:29")
        XCTAssertNil(state.answer(pollKey: "mak335-pzt|2026-11-02", status: .absent))
    }

    func testWeeklySummaryContent() {
        let state = makeState("2026-11-08T20:00")
        state.setStatus(["mak335-pzt|2026-11-02"], .attended)
        state.setStatus(["mak303-pzt|2026-11-02"], .absent)
        let weekly = Planner.plan(state.snapshot()).first { $0.kind == .weekly }!
        XCTAssertEqual(weekly.time, dt("2026-11-09T08:00"))
        XCTAssertEqual(weekly.title, "Haftalık özet • 6. hafta")
        XCTAssertTrue(weekly.body.contains("Girdin: 1 • Girmedin: 1"), weekly.body)
        XCTAssertTrue(weekly.body.contains("Devamsızlık: MAK 303 (1)"), weekly.body)
    }

    func testDisabledReminderAndWeekly() {
        let state = makeState("2026-11-02T13:00")
        state.setNotify(NotifySettings(reminderMinutes: 0, weeklyEnabled: false))
        let plan = Planner.plan(state.snapshot())
        XCTAssertTrue(plan.allSatisfy { $0.kind == .ask || $0.kind == .notice })
    }

    // MARK: Veri işlemleri

    func testDefaultsAndWeek() {
        let snap = makeState("2026-11-04T12:00").snapshot()
        XCTAssertEqual(snap.currentWeek, 6)
        XCTAssertEqual(snap.slots.count, 11)
        // 5 tam hafta × 11 + bu hafta Pzt (2) + Sal (2) + Çar MAK 305 (1) = 60
        XCTAssertEqual(snap.unansweredPast.count, 60)
        XCTAssertEqual(snap.nextAsk?.time, dt("2026-11-05T10:29"))
    }

    func testSetCurrentWeekMovesSemesterStart() {
        let state = makeState("2026-11-04T12:00")
        state.setCurrentWeek(7)
        XCTAssertEqual(state.semesterStart, Day(2026, 9, 21))
        XCTAssertEqual(state.snapshot().currentWeek, 7)
    }

    func testImportReplaceKeepsHistoryOfMatchingClasses() {
        let state = makeState("2026-11-04T12:00")
        state.setStatus(["mak335-pzt|2026-09-28"], .absent)
        let r = ScheduleImport.parse("""
        [{"code":"MAK 335","name":"Makine Dinamiği","day":1,"start":"10:30","end":"13:29"},
         {"code":"FIZ 101","day":3,"start":"13:30","end":"15:29"}]
        """)
        XCTAssertEqual(state.importSlots(r.entries, replace: true), 2)
        let snap = state.snapshot()
        XCTAssertEqual(snap.courseCodes, ["FIZ 101", "MAK 335"])
        XCTAssertEqual(snap.stat("MAK 335")?.absent, 1)
        XCTAssertEqual(snap.stat("MAK 335")?.name, "Makine Dinamiği")
    }

    func testImportAppendDoesNotDuplicate() {
        let state = makeState("2026-11-04T12:00")
        state.importSlots(ScheduleImport.parse("[{\"code\":\"MAK 335\",\"day\":1,\"start\":\"10:30\",\"end\":\"13:29\"}]").entries,
                          replace: false)
        XCTAssertEqual(state.slots.count, 11)
    }

    func testMovingClassToAnotherDayMovesItsHistory() {
        let state = makeState("2026-11-04T12:00")
        state.setStatus(["mak335-pzt|2026-09-28"], .absent)
        state.setStatus(["mak335-pzt|2026-09-28|2"], .attended)
        let old = state.slots.first { $0.id == "mak335-pzt" }!
        var new = old
        new.day = 2
        state.updateSlot(old: old, new: new)
        XCTAssertNil(state.status("mak335-pzt|2026-09-28"))
        XCTAssertEqual(state.status("mak335-pzt|2026-09-29"), .absent)
        XCTAssertEqual(state.status("mak335-pzt|2026-09-29|2"), .attended)
    }

    func testRenamingCourseKeepsHistoryAndSettings() {
        let state = makeState("2026-11-04T12:00")
        state.setStatus(["mak335-pzt|2026-09-28"], .absent)
        let saved = state.saveCourse(oldCode: "MAK 335", newCode: "mak 336",
                                     config: CourseConfig(name: "Dinamik", mode: .count, allowedCount: 2))
        XCTAssertEqual(saved, "MAK 336")
        let snap = state.snapshot()
        XCTAssertNil(snap.stat("MAK 335"))
        XCTAssertEqual(snap.stat("MAK 336")?.absent, 1)
        XCTAssertEqual(snap.stat("MAK 336")?.allowed, 2)
        XCTAssertEqual(snap.stat("MAK 336")?.title, "Dinamik")
    }

    func testIncreasingPollsCopiesFirstAnswerToPastClasses() {
        let state = makeState("2026-11-04T12:00")
        state.setStatus(["mak303-pzt|2026-09-28"], .absent)
        state.setStatus(["mak303-per|2026-10-01"], .attended)
        state.saveCourse(oldCode: "MAK 303", newCode: "MAK 303", config: CourseConfig(polls: 2))
        state.fillNewPolls(code: "MAK 303", oldPolls: 1)
        XCTAssertEqual(state.status("mak303-pzt|2026-09-28|2"), .absent)
        XCTAssertEqual(state.status("mak303-per|2026-10-01|2"), .attended)
        XCTAssertNil(state.status("mak303-pzt|2026-10-05|2"))
    }

    func testSettingsPersistThroughStore() {
        let store = MemoryStore()
        let t = dt("2026-11-04T12:00")
        let a = AppState(store: store, clock: { t })
        a.setWeeks(14)
        a.setRequiredPercent(80)
        a.setNotify(NotifySettings(reminderMinutes: 15, weeklyDay: 7, weeklyTime: HM(20, 30)))
        a.saveCourse(oldCode: "MAK 317", newCode: "MAK 317", config: CourseConfig(name: "Mukavemet", totalSessions: 12))
        let b = AppState(store: store, clock: { t })
        XCTAssertEqual(b.weeks, 14)
        XCTAssertEqual(b.requiredPercent, 80)
        XCTAssertEqual(b.notify.reminderMinutes, 15)
        XCTAssertEqual(b.notify.weeklyTime, HM(20, 30))
        XCTAssertEqual(b.configs["MAK 317"]?.name, "Mukavemet")
        XCTAssertEqual(b.configs["MAK 317"]?.totalSessions, 12)
    }
}
