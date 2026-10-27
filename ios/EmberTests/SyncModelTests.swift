import XCTest
@testable import Ember

/// The same cases as Android's SyncMergeTest, so both apps follow exactly the same rules.
final class SyncModelTests: XCTestCase {

    let fold = "fold"
    let phone = "iphone"
    let bk = "thelanternthief|adawren"
    lazy var all: Set<String> = ["*", bk]

    func pos(_ p: Int64, _ lp: Int64) -> String { Canon.obj([("p", .int(p)), ("lp", .int(lp)), ("d", 3600)]) }
    func e(_ v: String?, _ t: Int64, _ d: String = "iphone", _ o: String? = nil) -> SyncEntry { SyncEntry(v: v, t: t, d: d, o: o ?? bk) }
    func li(_ v: String, _ o: String? = nil, hint: Int64 = 0, hinted: Bool = false) -> LocalItem { LocalItem(v: v, o: o ?? bk, hint: hint, hinted: hinted) }

    func testCanonicalTextIsTheSameWhateverTheOrder() {
        XCTAssertEqual(#"{"a":1,"b":"x"}"#, Canon.of(#"{ "b" : "x", "a" : 1 }"#))
        XCTAssertEqual(Canon.obj([("p", 5), ("n", .str("hi \"there\""))]), Canon.of(#"{"n":"hi \"there\"","p":5}"#))
        XCTAssertTrue(Canon.same(#"{"v":["a","b"]}"#, Canon.obj([("v", .list(["a", "b"]))])))
        XCTAssertFalse(Canon.same(#"{"v":1}"#, #"{"v":2}"#))
        XCTAssertTrue(Canon.same(#"{"v":1.0,"u":"a\/b"}"#, #"{"u":"a/b","v":1}"#))
        XCTAssertTrue(Canon.same(#"{"b":true}"#, Canon.obj([("b", true)])))
        XCTAssertFalse(Canon.same(#"{"b":true}"#, #"{"b":1}"#))
    }

    /// Exactly the text Android writes for the same values (so neither device sees a change that isn't one).
    func testSameTextAsAndroid() {
        XCTAssertEqual(#"{"c":"","n":"Saved","p":123000}"#, Canon.obj([("p", 123000), ("n", "Saved"), ("c", "")]))
        XCTAssertEqual(#"{"ce":false,"si":5}"#, Canon.obj([("si", 5), ("ce", false)]))
    }

    func testBookKeysIgnoreCaseAccentsAndPunctuation() {
        XCTAssertEqual("thelanternthiefofvell|adawren", SyncKeys.book(title: "The Lantern Thief of Vell", author: "Ada Wren", external: false))
        XCTAssertEqual(SyncKeys.book(title: "Café: Société", author: "Zoë", external: false), SyncKeys.book(title: "cafe societe", author: "ZOE", external: false))
        XCTAssertEqual("x|saltglass|corinhale", SyncKeys.book(title: "Saltglass", author: "Corin Hale", external: true))
        XCTAssertNil(SyncKeys.book(title: "  ", author: "Someone", external: false))
        XCTAssertNotNil(SyncKeys.logFile("a|b").range(of: #"^log-[0-9a-f]{20}\.json$"#, options: .regularExpression))
    }

    /// The same file name Android computes (SHA-1 of the key).
    func testLogFileNameMatchesAndroid() {
        XCTAssertEqual("log-88393465741aa111be93.json", SyncKeys.logFile("thelanternthief|adawren"))
    }

    func testFirstSyncUploadsEverything() {
        let local = ["b/\(bk)/pos": li(pos(1000, 50), hint: 50, hinted: true), "goal": li(Canon.obj([("d", 30), ("y", 24)]), "*")]
        let r = SyncMerge.merge(remote: [:], shadow: [:], local: local, ownersNow: all, ownersBefore: [], me: fold, now: 100)
        XCTAssertTrue(r.upload)
        XCTAssertEqual(Set(["b/\(bk)/pos", "goal"]), r.won)
        XCTAssertEqual(50, r.merged["b/\(bk)/pos"]?.t)
        XCTAssertEqual(0, r.merged["goal"]?.t)
        XCTAssertTrue(r.apply.isEmpty)
    }

    func testNewerPlaceOnTheOtherDeviceComesHere() {
        let shadow = ["b/\(bk)/pos": e(pos(1000, 50), 50, fold)]
        let remote = ["b/\(bk)/pos": e(pos(9000, 80), 80)]
        let local = ["b/\(bk)/pos": li(pos(1000, 50), hint: 50, hinted: true)]
        let r = SyncMerge.merge(remote: remote, shadow: shadow, local: local, ownersNow: all, ownersBefore: all, me: fold, now: 100)
        XCTAssertFalse(r.upload)
        XCTAssertEqual(pos(9000, 80), r.apply["b/\(bk)/pos"]?.v)
    }

    func testListeningHereLaterBeatsTheOtherDevice() {
        let shadow = ["b/\(bk)/pos": e(pos(1000, 50), 50, fold)]
        let remote = ["b/\(bk)/pos": e(pos(9000, 80), 80)]
        let local = ["b/\(bk)/pos": li(pos(2000, 90), hint: 90, hinted: true)]
        let r = SyncMerge.merge(remote: remote, shadow: shadow, local: local, ownersNow: all, ownersBefore: all, me: fold, now: 100)
        XCTAssertTrue(r.upload)
        XCTAssertTrue(r.apply.isEmpty)
        XCTAssertEqual(pos(2000, 90), r.merged["b/\(bk)/pos"]?.v)
    }

    func testListeningHereEarlierLosesEvenIfSyncedLater() {
        let shadow = ["b/\(bk)/pos": e(pos(1000, 50), 50, fold)]
        let remote = ["b/\(bk)/pos": e(pos(9000, 80), 80)]
        let local = ["b/\(bk)/pos": li(pos(2000, 70), hint: 70, hinted: true)]
        let r = SyncMerge.merge(remote: remote, shadow: shadow, local: local, ownersNow: all, ownersBefore: all, me: fold, now: 1000)
        XCTAssertEqual(pos(9000, 80), r.merged["b/\(bk)/pos"]?.v)
        XCTAssertEqual(pos(9000, 80), r.apply["b/\(bk)/pos"]?.v)
    }

    func testMarkingFinishedMovesThePlaceToo() {
        func st(_ s: Int64) -> String { Canon.obj([("s", .int(s)), ("f", 0)]) }
        let shadow = ["b/\(bk)/pos": e(pos(9000, 80), 80), "b/\(bk)/status": e(st(1), 40)]
        let local = ["b/\(bk)/pos": li(pos(3_600_000, 80), hint: 80, hinted: true), "b/\(bk)/status": li(st(2))]
        let r = SyncMerge.merge(remote: shadow, shadow: shadow, local: local, ownersNow: all, ownersBefore: all, me: fold, now: 500)
        XCTAssertEqual(pos(3_600_000, 80), r.merged["b/\(bk)/pos"]?.v)
        XCTAssertEqual(st(2), r.merged["b/\(bk)/status"]?.v)
        XCTAssertTrue(r.apply.isEmpty)
    }

    func testABookThatJustAppearedTakesTheOtherDevicesPlace() {
        let remote = [
            "b/\(bk)/pos": e(pos(9000, 80), 80),
            "b/\(bk)/status": e(Canon.obj([("s", 1), ("f", 0)]), 60),
            "b/\(bk)/rating": e(Canon.obj([("v", 4)]), 70),
        ]
        let local = [
            "b/\(bk)/pos": li(pos(0, 0), hint: 0, hinted: true),
            "b/\(bk)/status": li(Canon.obj([("s", 0), ("f", 0)])),
            "b/\(bk)/rating": li(Canon.obj([("v", 0)])),
        ]
        let r = SyncMerge.merge(remote: remote, shadow: remote, local: local, ownersNow: all, ownersBefore: ["*"], me: fold, now: 500)
        XCTAssertFalse(r.upload)
        XCTAssertEqual(3, r.apply.count)
    }

    func testBookmarksFromBothDevicesAreKept() {
        let m1 = Canon.obj([("b", .str(bk)), ("p", 100), ("n", ""), ("c", "")])
        let m2 = Canon.obj([("b", .str(bk)), ("p", 200), ("n", ""), ("c", "")])
        let r = SyncMerge.merge(remote: ["mark/1": e(m1, 10)], shadow: [:], local: ["mark/2": li(m2)], ownersNow: all, ownersBefore: all, me: fold, now: 50)
        XCTAssertEqual(Set(["mark/1", "mark/2"]), Set(r.merged.keys))
        XCTAssertEqual(m1, r.apply["mark/1"]?.v)
        XCTAssertTrue(r.upload)
    }

    func testDeletingABookmarkHereDeletesItEverywhere() {
        let m1 = Canon.obj([("b", .str(bk)), ("p", 100), ("n", ""), ("c", "")])
        let remote = ["mark/1": e(m1, 10)]
        let r = SyncMerge.merge(remote: remote, shadow: remote, local: [:], ownersNow: all, ownersBefore: all, me: fold, now: 50)
        XCTAssertNil(r.merged["mark/1"]!.v)
        XCTAssertEqual(50, r.merged["mark/1"]!.t)
        XCTAssertTrue(r.upload)
    }

    func testBookmarksOfABookNotHereAreNeverDeleted() {
        let m1 = Canon.obj([("b", .str(bk)), ("p", 100), ("n", ""), ("c", "")])
        let remote = ["mark/1": e(m1, 10)]
        let r = SyncMerge.merge(remote: remote, shadow: remote, local: [:], ownersNow: ["*"], ownersBefore: ["*"], me: fold, now: 50)
        XCTAssertEqual(m1, r.merged["mark/1"]?.v)
        XCTAssertFalse(r.upload)
        let r2 = SyncMerge.merge(remote: remote, shadow: remote, local: [:], ownersNow: ["*"], ownersBefore: all, me: fold, now: 50)
        XCTAssertEqual(m1, r2.merged["mark/1"]?.v)
    }

    func testAnUnansweredQuestionDoesNotOverwriteTheOtherDevice() {
        let shadow = ["b/\(bk)/pos": e(pos(9000, 80), 80)]
        let local = ["b/\(bk)/pos": li(pos(1000, 95), hint: 95, hinted: true)]
        let r = SyncMerge.merge(remote: shadow, shadow: shadow, local: local, ownersNow: all, ownersBefore: all, me: fold, now: 500, frozen: ["b/\(bk)/pos"])
        XCTAssertFalse(r.upload)
        XCTAssertEqual(pos(9000, 80), r.merged["b/\(bk)/pos"]?.v)
    }

    func testOldDeletionsAreForgotten() {
        let old: Int64 = 1_000
        let now = old + (SyncMerge.tombstoneDays + 1) * 86_400_000
        let remote = ["mark/1": e(nil, old)]
        let r = SyncMerge.merge(remote: remote, shadow: remote, local: [:], ownersNow: all, ownersBefore: all, me: fold, now: now)
        XCTAssertNil(r.merged["mark/1"])
    }

    func testStateFileRoundTripsAndReadsAndroidsFormat() {
        var s = SyncState()
        s.items["mark/1"] = SyncEntry(v: Canon.obj([("p", 1)]), t: 5, d: phone, o: bk)
        s.items["mark/2"] = SyncEntry(v: nil, t: 6, d: fold, o: bk)
        s.devices[phone] = ("iPhone", 9)
        s.owners.insert(bk)
        let back = SyncState.parse(s.toJSON())
        XCTAssertEqual(s.items, back.items)
        XCTAssertEqual("iPhone", back.devices[phone]?.name)
        XCTAssertEqual(s.owners, back.owners)
        // As Android writes it.
        let android = #"{"app":"Ember","sync":1,"items":{"b/x|y/pos":{"t":12,"d":"a-1","o":"x|y","v":"{\"d\":5,\"lp\":12,\"p\":7}"}},"devices":{"a-1":{"name":"Fold","seen":3}}}"#
        let a = SyncState.parse(android)
        XCTAssertEqual(#"{"d":5,"lp":12,"p":7}"#, a.items["b/x|y/pos"]?.v)
        XCTAssertEqual("Fold", a.devices["a-1"]?.name)
    }

    func le(_ u: String, _ at: Int64, to: Int64 = -1) -> LogEntry {
        LogEntry(u: u, d: String(u.split(separator: ":")[0]), at: at, k: 1, p: at * 10, to: to, s: 100, r: 0)
    }

    func testLogsFromBothDevicesInterleave() {
        let (list, cleared) = LogMerge.merge(remote: [le("iphone:1", 10), le("iphone:2", 30)], remoteCleared: 0, local: [le("fold:1", 20), le("fold:2", 40)], localCleared: 0)
        XCTAssertEqual(["fold:2", "iphone:2", "fold:1", "iphone:1"], list.map(\.u))
        XCTAssertEqual(0, cleared)
    }

    func testClearAllOnEitherDeviceClearsBoth() {
        let (list, cleared) = LogMerge.merge(remote: [le("iphone:1", 10), le("iphone:2", 30)], remoteCleared: 25, local: [le("fold:1", 20), le("fold:2", 40)], localCleared: 0)
        XCTAssertEqual(["fold:2", "iphone:2"], list.map(\.u))
        XCTAssertEqual(25, cleared)
    }

    func testLogKeepsTheNewest400() {
        let local = (1...300).map { le("fold:\($0)", Int64($0)) }
        let remote = (1...300).map { le("iphone:\($0)", Int64($0) * 2) }
        let (list, _) = LogMerge.merge(remote: remote, remoteCleared: 0, local: local, localCleared: 0)
        XCTAssertEqual(400, list.count)
        XCTAssertEqual("iphone:300", list.first?.u)
    }

    func testLogFileRoundTrips() {
        let f = LogFile(bookKey: bk, cleared: 7, entries: [le("fold:1", 10, to: 99)])
        let back = LogFile.parse(f.toJSON())!
        XCTAssertEqual(bk, back.bookKey)
        XCTAssertEqual(7, back.cleared)
        XCTAssertEqual(f.entries, back.entries)
    }
}
