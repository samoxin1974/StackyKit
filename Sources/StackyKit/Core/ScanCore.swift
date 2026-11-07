import Foundation
import CoreGraphics
#if canImport(UIKit)
import UIKit
#endif

// StackyKit core. Compiled into Stacky Trainer, the Mac stack benchmark (scripts/stack_bench) and the package: no UI code here.

/// One box the model found in a frame, in upright image pixels.
struct SeenCard: Identifiable {
    let id = UUID()
    let rect: CGRect
    let reading: CardReading
    let accepted: Bool
    let isCorner: Bool   // passed the corner check
    var frames = 1       // frames remembered for this corner (frame memory)
    var track: Int? = nil   // frame-memory track id: the same physical corner across frames
    var pair: CGRect? = nil  // the card's other index, when merged by CornerPairing
}

struct Card: Hashable {
    let rank: String, suit: String   // "10", "S"
    init?(_ code: String) {
        guard let s = code.last, "CDHS".contains(s) else { return nil }
        rank = String(code.dropLast()); suit = String(s)
        guard CardReader.ranks.contains(rank) else { return nil }
    }
    var code: String { rank + suit }
    var red: Bool { suit == "H" || suit == "D" }
    var symbol: String { ["S": "\u{2660}\u{FE0E}", "H": "\u{2665}\u{FE0E}", "D": "\u{2666}\u{FE0E}", "C": "\u{2663}\u{FE0E}"][suit]! }
    var pretty: String { rank + symbol }
    var value: Int { ["A": 14, "K": 13, "Q": 12, "J": 11][rank] ?? Int(rank)! }
    var rankName: String { Card.rankName(value) }
    var suitName: String { ["S": "Spades", "H": "Hearts", "D": "Diamonds", "C": "Clubs"][suit]! }
    var name: String { "\(rankName) of \(suitName)" }
    static func rankName(_ v: Int, plural: Bool = false) -> String {
        let n = [2: "Two", 3: "Three", 4: "Four", 5: "Five", 6: "Six", 7: "Seven", 8: "Eight", 9: "Nine", 10: "Ten", 11: "Jack", 12: "Queen", 13: "King", 14: "Ace"][v]!
        return plural ? (v == 6 ? "Sixes" : n + "s") : n
    }
    static let deck: [Card] = ["S", "H", "C", "D"].flatMap { s in CardReader.ranks.map { Card($0 + s)! } }
}


enum Haptic {
    /// Light taps from the stack scanner (a card fixed, a section done). The SDK turns this off unless asked.
    nonisolated(unsafe) static var enabled = true
    static func tap() {
        guard enabled else { return }
        #if canImport(UIKit)
        DispatchQueue.main.async { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
        #endif
    }
}
