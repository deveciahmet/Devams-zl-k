import SwiftUI

/// Veriler telefonda UserDefaults'ta saklanır.
final class UserDefaultsStore: KeyValueStore {
    private let defaults: UserDefaults
    init(_ defaults: UserDefaults = .standard) { self.defaults = defaults }
    func string(forKey key: String) -> String? { defaults.string(forKey: key) }
    func set(_ value: String?, forKey key: String) {
        if let v = value { defaults.set(v, forKey: key) } else { defaults.removeObject(forKey: key) }
    }
}

enum Route: Hashable {
    case fill
    case course(String)
    case courseEdit(String)
    case settings
    case importer
}

/// Ekranların kullandığı tek veri kaynağı. Her değişiklikten sonra ekranı ve bildirim planını yeniler.
final class AppModel: ObservableObject {
    static let shared = AppModel()

    let state: AppState
    let notifications: NotificationService
    /// Ekran görüntüsü için örnek veriyle açıldı (yalnızca geliştirme sürümünde).
    let demo: Bool
    @Published private(set) var snap: Snapshot
    @Published var path: [Route] = []
    @Published private(set) var notificationsEnabled = true
    var demoImportText: String?

    private init() {
        var demoMode = false
        var store: KeyValueStore = UserDefaultsStore()
        var clock: () -> DateTime = { DateTime.now() }
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-demo") {
            demoMode = true
            store = MemoryStore()
            let t = DateTime(iso: "2026-11-04T12:00")!
            clock = { t }
        }
        #endif
        state = AppState(store: store, clock: clock)
        demo = demoMode
        notifications = NotificationService(active: !demoMode)
        snap = state.snapshot()
        #if DEBUG
        if demoMode { Demo.seed(self) }
        #endif
    }

    /// Ekranı yeniler (bildirimlere dokunmadan).
    func reload() { snap = state.snapshot() }

    /// Ekranı ve önceden zamanlanmış bildirimleri yeniler.
    func refresh() {
        reload()
        notifications.reschedule(snap)
        notifications.checkEnabled { [weak self] on in self?.notificationsEnabled = on }
    }

    /// Uygulama ilk açıldığında: bildirim izni iste, planı kur.
    func start() {
        notifications.requestAuthorization { [weak self] _ in self?.refresh() }
        refresh()
        if !demo { BackgroundRefresh.schedule() }
    }

    // MARK: Yoklama

    func setStatuses(_ keys: [String], _ status: Status?) {
        state.setStatus(keys, status)
        if status != nil { notifications.removeDelivered(pollKeys: keys) }
        refresh()
    }

    /// Bildirim tuşundan gelen cevap.
    func answer(pollKey: String, status: Status) {
        let warning = state.answer(pollKey: pollKey, status: status)
        notifications.removeDelivered(pollKeys: [pollKey])
        if let w = warning { notifications.deliverNow(w) }
        refresh()
    }

    func sendTest() {
        let p = snap.polls.last(where: { $0.time <= snap.now }) ?? snap.polls.first
        guard let poll = p else { return }
        let n = Planner.ask(poll, snap.config(poll.code))
        notifications.deliverNow(PlannedNotification(id: "test|\(poll.key)", kind: .ask, title: n.title, body: n.body,
                                                     time: nil, pollKey: poll.key))
    }

    // MARK: Dönem ve ayarlar

    func setSemesterStart(_ d: Day) { state.setSemesterStart(d); refresh() }
    func setCurrentWeek(_ w: Int) { state.setCurrentWeek(w); refresh() }
    func setWeeks(_ w: Int) { state.setWeeks(w); refresh() }
    func setRequiredPercent(_ p: Int) { state.setRequiredPercent(p); refresh() }
    func setNotify(_ n: NotifySettings) { state.setNotify(n); refresh() }

    // MARK: Program

    func addSlot(_ d: SlotDraft) {
        state.addSlot(state.newSlot(code: d.code, day: d.day, start: d.start, end: d.end))
        refresh()
    }

    func updateSlot(_ old: Slot, _ d: SlotDraft) {
        var new = old
        new.code = normalizeCode(d.code)
        new.day = d.day
        new.start = d.start
        new.end = d.end
        state.updateSlot(old: old, new: new)
        refresh()
    }

    func removeSlot(id: String) { state.removeSlot(id: id); refresh() }
    func resetSlots() { state.resetSlots(); refresh() }

    @discardableResult
    func importSlots(_ entries: [ImportedSlot], replace: Bool) -> Int {
        let n = state.importSlots(entries, replace: replace)
        refresh()
        return n
    }

    /// Ders ayarlarını kaydeder. [fillFromPolls]: yoklama sayısı arttıysa eski sayı (geçmişi doldurmak için).
    @discardableResult
    func saveCourse(oldCode: String, newCode: String, config: CourseConfig, fillFromPolls: Int?) -> String {
        let saved = state.saveCourse(oldCode: oldCode, newCode: newCode, config: config)
        if let old = fillFromPolls { state.fillNewPolls(code: saved, oldPolls: old) }
        refresh()
        return saved
    }
}

#if DEBUG
/// Ekran görüntüleri için örnek veri: `-demo -screen home|fill|course|courseEdit|settings|import`
enum Demo {
    static func seed(_ model: AppModel) {
        let s = model.state
        s.saveCourse(oldCode: "MAK 303", newCode: "MAK 303", config: CourseConfig(name: "Makine Teorisi", polls: 2))
        let snap = s.snapshot()
        var absent = Set<String>()
        for p in snap.polls {
            if p.code == "MAK 317" && [1, 3, 4].contains(p.session.week) { absent.insert(p.key) }
            if p.code == "MAK 309" && [2, 5].contains(p.session.week) { absent.insert(p.key) }
            if p.code == "MAK 303" && p.session.week == 2 && p.index == 2 { absent.insert(p.key) }
        }
        let attended = snap.polls.filter { $0.time <= snap.now && $0.session.week <= 5 && !absent.contains($0.key) }
        s.setStatus(attended.map(\.key), .attended)
        s.setStatus(Array(absent), .absent)
        model.reload()

        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-screen"), i + 1 < args.count else { return }
        switch args[i + 1] {
        case "fill": model.path = [.fill]
        case "course": model.path = [.course("MAK 303")]
        case "courseEdit": model.path = [.course("MAK 303"), .courseEdit("MAK 303")]
        case "settings": model.path = [.settings]
        case "import":
            model.demoImportText = """
            [{"code":"MAK 335","name":"Makine Dinamiği","day":1,"start":"10:30","end":"13:29"},
             {"code":"MAK 303","name":"","day":1,"start":"15:30","end":"17:29"},
             {"code":"MAK 309","name":"","day":9,"start":"10:30","end":"13:29"},
             {"code":"ITB 220","name":"","day":"Salı","start":"14:30","end":"17:29"}]
            """
            model.path = [.settings, .importer]
        default: break
        }
    }
}
#endif
