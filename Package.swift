// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "StackyKit",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        .library(name: "StackyKit", targets: ["StackyKit"]),
        .executable(name: "stacky-read", targets: ["stacky-read"]),
    ],
    targets: [
        .target(name: "StackyKit", path: "Sources/StackyKit"),
        .executableTarget(name: "stacky-read", dependencies: ["StackyKit"], path: "Sources/stacky-read"),
    ]
)
