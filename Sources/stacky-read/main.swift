import Foundation
import AVFoundation
import ImageIO
import StackyKit

// stacky-read <model folder> <image or video>... [--fps 6] [--scan]
// Prints what the model reads. Use it to check a model folder before shipping it, and to compare two models.

var args = Array(CommandLine.arguments.dropFirst())
func flag(_ n: String) -> String? { guard let i = args.firstIndex(of: n), i + 1 < args.count else { return nil }; let v = args[i + 1]; args.removeSubrange(i...i + 1); return v }
let fps = Double(flag("--fps") ?? "6")!
let scan = args.contains("--scan"); args.removeAll { $0 == "--scan" }
guard args.count >= 2 else { print("usage: stacky-read <model folder> <image|video>... [--fps 6] [--scan]"); exit(2) }

let model = try await StackyModel.load(from: URL(fileURLWithPath: args[0]))
print("model \(model.name) (exported \(model.manifest.exported), sure at \(model.manifest.accept))")

func line(_ cs: [StackyCard]) -> String {
    cs.sorted { $0.box.minX < $1.box.minX }.map { "\($0.code)\($0.isSure ? "" : "?") \(Int($0.confidence * 100))%" }.joined(separator: "  ")
}

for path in args.dropFirst() {
    let url = URL(fileURLWithPath: path)
    let reader = try StackyReader(model: model)
    let scanner = StackyStackScanner()
    if ["mov", "mp4", "m4v"].contains(url.pathExtension.lowercased()) {
        let asset = AVURLAsset(url: url)
        let gen = AVAssetImageGenerator(asset: asset)
        gen.appliesPreferredTrackTransform = true   // upright frames
        gen.requestedTimeToleranceBefore = .zero; gen.requestedTimeToleranceAfter = .zero
        let dur = try await asset.load(.duration).seconds
        print("\(url.lastPathComponent): \(String(format: "%.1f", dur)) s")
        var t = 0.0
        while t < dur {
            if let (img, _) = try? await gen.image(at: CMTime(seconds: t, preferredTimescale: 600)) {
                let cs = reader.read(img)
                if scan { scanner.feed(cs) } else { print(String(format: "%6.2f s  ", t) + line(cs)) }
            }
            t += 1 / fps
        }
    } else {
        guard let src = CGImageSourceCreateWithURL(url as CFURL, nil),
              let img = CGImageSourceCreateImageAtIndex(src, 0, [kCGImageSourceCreateThumbnailWithTransform: true] as CFDictionary) else { print("\(path): can't open"); continue }
        print("\(url.lastPathComponent): " + line(reader.read(img)))
        if scan { scanner.feed(reader.read(img)) }
    }
    if scan {
        let o = scanner.order
        print("order (\(o.count) cards, \(o.filter { !$0.isSure }.count) to verify): " + o.map { $0.code + ($0.isSure ? "" : "?") }.joined(separator: " "))
        if let m = scanner.match() { print(String(format: "Mnemonica: %.0f%%, top card %@", m.probability * 100, m.topCard)) }
    }
}
