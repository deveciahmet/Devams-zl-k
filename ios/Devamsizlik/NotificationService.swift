import BackgroundTasks
import UIKit
import UserNotifications

/// iOS bildirimleri. iOS'ta uygulama istediği an kod çalıştıramadığı için bütün sorular, hatırlatmalar,
/// ders öncesi uyarılar ve haftalık özet önceden (en fazla 64 tane) zamanlanır; uygulama her açıldığında
/// ve her cevapta plan baştan kurulur.
final class NotificationService {
    /// Ekran görüntüsü (demo) modunda kapalı.
    let active: Bool
    private var center: UNUserNotificationCenter { UNUserNotificationCenter.current() }

    init(active: Bool) { self.active = active }

    /// Evet / Hayır / Ders yapılmadı tuşları. options: [] → tuşa basınca uygulama açılmaz.
    static func registerCategories() {
        let actions = [
            UNNotificationAction(identifier: Status.attended.rawValue, title: "Evet", options: []),
            UNNotificationAction(identifier: Status.absent.rawValue, title: "Hayır", options: []),
            UNNotificationAction(identifier: Status.cancelled.rawValue, title: "Ders yapılmadı", options: []),
        ]
        let category = UNNotificationCategory(identifier: Planner.answerCategory, actions: actions,
                                              intentIdentifiers: [], options: [])
        UNUserNotificationCenter.current().setNotificationCategories([category])
    }

    func requestAuthorization(_ done: @escaping (Bool) -> Void) {
        guard active else { return done(false) }
        center.requestAuthorization(options: [.alert, .sound, .badge]) { granted, _ in
            DispatchQueue.main.async { done(granted) }
        }
    }

    /// Bildirim izni kapalı mı? (Henüz sorulmadıysa açık sayılır.)
    func checkEnabled(_ done: @escaping (Bool) -> Void) {
        guard active else { return done(true) }
        center.getNotificationSettings { s in
            let on = s.authorizationStatus != .denied
            DispatchQueue.main.async { done(on) }
        }
    }

    func reschedule(_ snap: Snapshot) {
        guard active else { return }
        let plan = Planner.plan(snap)
        center.removeAllPendingNotificationRequests()
        for p in plan { center.add(request(p)) }
    }

    func deliverNow(_ p: PlannedNotification) {
        guard active else { return }
        center.add(request(p, immediate: true))
    }

    func removeDelivered(pollKeys: [String]) {
        guard active else { return }
        center.removeDeliveredNotifications(withIdentifiers: pollKeys.flatMap { ["ask|\($0)", "remind|\($0)", "test|\($0)"] })
    }

    private func request(_ p: PlannedNotification, immediate: Bool = false) -> UNNotificationRequest {
        let c = UNMutableNotificationContent()
        c.title = p.title
        c.body = p.body
        c.sound = .default
        if let key = p.pollKey {
            c.categoryIdentifier = Planner.answerCategory
            c.userInfo = ["pollKey": key]
        }
        var trigger: UNNotificationTrigger?
        if let t = p.time, !immediate {
            var dc = DateComponents()
            dc.calendar = Calendar(identifier: .gregorian)
            dc.timeZone = .current
            dc.year = t.day.year
            dc.month = t.day.month
            dc.day = t.day.day
            dc.hour = t.time.hour
            dc.minute = t.time.minute
            trigger = UNCalendarNotificationTrigger(dateMatching: dc, repeats: false)
        }
        return UNNotificationRequest(identifier: p.id, content: c, trigger: trigger)
    }
}

/// Uygulama uzun süre açılmasa da bildirim planı tazelensin diye arka plan yenilemesi (iOS uygun gördüğünde çalışır).
enum BackgroundRefresh {
    static let identifier = "com.deveciahmet.devamsizlik.refresh"

    static func register() {
        _ = BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: nil) { task in
            DispatchQueue.main.async {
                AppModel.shared.refresh()
                schedule()
                task.setTaskCompleted(success: true)
            }
        }
    }

    static func schedule() {
        let r = BGAppRefreshTaskRequest(identifier: identifier)
        r.earliestBeginDate = Date(timeIntervalSinceNow: 6 * 3600)
        try? BGTaskScheduler.shared.submit(r)
    }
}
