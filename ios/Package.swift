// swift-tools-version:5.9
// Uygulamanın Apple'a bağımlı olmayan çekirdeği (hesaplamalar, içe aktarma, bildirim planı).
// Linux ve macOS'ta `swift test` ile test edilebilir; iOS uygulaması bu kaynakları doğrudan derler.
import PackageDescription

let package = Package(
    name: "DevamsizlikCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        .library(name: "DevamsizlikCore", targets: ["DevamsizlikCore"]),
    ],
    targets: [
        .target(name: "DevamsizlikCore", path: "Sources/DevamsizlikCore"),
        .testTarget(
            name: "DevamsizlikCoreTests",
            dependencies: ["DevamsizlikCore"],
            path: "Tests/DevamsizlikCoreTests"
        ),
    ]
)
