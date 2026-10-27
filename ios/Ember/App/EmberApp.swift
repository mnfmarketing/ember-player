import SwiftUI
import SwiftData

@main
struct EmberApp: App {
    @State private var model = AppModel()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .environment(model.settings)
                .environment(model.player)
                .modelContainer(model.container)
                .preferredColorScheme(model.settings.appearance == "light" ? .light : (model.settings.appearance == "dark" ? .dark : nil))
                .task { await model.started() }
        }
        .onChange(of: phase) { _, p in
            if p == .active { model.foreground() }
            if p == .background { model.background() }
        }
        .backgroundTask(.appRefresh(AppModel.refreshID)) {
            await model.backgroundRefresh()
        }
    }
}
