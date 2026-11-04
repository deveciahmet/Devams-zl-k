import SwiftUI
import UserNotifications

@main
struct DevamsizlikApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var model = AppModel.shared
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .environment(\.locale, Locale(identifier: "tr_TR"))
        }
        .onChange(of: scenePhase) { phase in
            // Uygulama her öne geldiğinde ekranı ve bildirim planını yenile
            if phase == .active { model.refresh() }
        }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate {
    private let notificationDelegate = NotificationDelegate()

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = notificationDelegate
        NotificationService.registerCategories()
        BackgroundRefresh.register()
        return true
    }
}

/// Bildirimdeki Evet / Hayır / Ders yapılmadı tuşları. iOS uygulamayı arka planda başlatıp buraya getirir;
/// cevap uygulama açılmadan kaydedilir.
final class NotificationDelegate: NSObject, UNUserNotificationCenterDelegate {
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        // Uygulama açıkken de bildirimi göster
        completionHandler([.banner, .list, .sound])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let key = response.notification.request.content.userInfo["pollKey"] as? String
        let status = Status(rawValue: response.actionIdentifier)
        let tappedBody = response.actionIdentifier == UNNotificationDefaultActionIdentifier
        DispatchQueue.main.async {
            if let key = key, let status = status {
                AppModel.shared.answer(pollKey: key, status: status)
            } else if tappedBody, key != nil {
                // Bildirimin kendisine dokunuldu: cevaplamak için doldurma ekranını aç
                AppModel.shared.path = [.fill]
            }
            completionHandler()
        }
    }
}
