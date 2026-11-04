import SwiftUI

struct RootView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        NavigationStack(path: $model.path) {
            HomeView()
                .navigationDestination(for: Route.self) { route in
                    switch route {
                    case .fill:
                        SessionsView(courseCode: nil)
                    case .course(let code):
                        SessionsView(courseCode: code)
                    case .courseEdit(let code):
                        CourseEditView(code: code, snap: model.snap)
                    case .settings:
                        SettingsView()
                    case .importer:
                        ImportView(initialText: model.demoImportText)
                    }
                }
        }
        .onAppear { model.start() }
    }
}
