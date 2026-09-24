import UIKit
import SwiftUI
import ComposeApp

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
    }
}

struct ContentView: View {
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ComposeView()
            // Obscure the app in the app switcher / while backgrounded: the system
            // captures the snapshot at resign-active, so this covers it.
            .overlay {
                if scenePhase != .active {
                    Color.black.ignoresSafeArea()
                }
            }
    }
}



