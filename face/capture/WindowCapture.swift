// WindowCapture — macOS screen-capture bridge for AgentCraft
//
// Streams any window or display into the in-game cinema at up to 60 fps using
// ScreenCaptureKit. Frames are JPEG-encoded and POSTed directly to the runtime
// cinema endpoint. Input events arrive on stdin as newline-delimited JSON and
// are replayed into the target app via CGEventPost.
//
// Usage:
//   WindowCapture list
//     JSON array of capturable windows + screens to stdout, then exit.
//
//   WindowCapture stream [options]
//     Options:
//       --cinema-id  <id>      cinema channel id        (default: main)
//       --window-id  <n>       SCWindow windowID (from list)
//       --app        <name>    partial app name match    (fallback if no --window-id)
//       --screen     <index>   display index 0=main      (fallback if no window)
//       --fps        <n>       target fps                (default: 60)
//       --quality    <0-1>     JPEG quality              (default: 0.70)
//       --endpoint   <url>     frame push URL            (default: http://127.0.0.1:8766/api/cinema/<id>/frame)
//       --token      <tok>     Bearer auth token
//       --no-input             disable stdin event injection
//
// Stdin (while streaming, newline-delimited JSON):
//   {"type":"click",  "nx":0.5,"ny":0.3,"button":"left"}
//   {"type":"move",   "nx":0.5,"ny":0.3}
//   {"type":"scroll", "nx":0.5,"ny":0.3,"dy":-120}
//   {"type":"key",    "key":"Enter"}
//   {"type":"text",   "text":"hello"}
//
// Stdout:
//   STATUS:ready          — capture loop started
//   STATUS:frame:<n>      — liveness ping every 100 frames
//   STATUS:error:<msg>    — non-fatal
//   STATUS:fatal:<msg>    — process about to exit

import Foundation
import ScreenCaptureKit
import CoreGraphics
import CoreImage
import AppKit

// Keep-alive: prevents ARC from collecting the session before the process exits.
// dispatchMain() keeps the process alive; the session drives all work via callbacks.
var liveObjects: [AnyObject] = []

// ── Helpers ──────────────────────────────────────────────────────────────────

func status(_ msg: String) { print("STATUS:\(msg)"); fflush(stdout) }
func stderr(_ msg: String) { fputs("[wincap] \(msg)\n", Foundation.stderr) }

func parseArgs(_ argv: [String]) -> [String: String] {
    var out: [String: String] = [:]
    var i = 1
    while i < argv.count {
        let k = argv[i]
        if k.hasPrefix("--") {
            let key = String(k.dropFirst(2))
            if i + 1 < argv.count && !argv[i+1].hasPrefix("--") {
                out[key] = argv[i+1]; i += 2
            } else {
                out[key] = "true"; i += 1
            }
        } else { i += 1 }
    }
    return out
}

// ── Window list ───────────────────────────────────────────────────────────────

@available(macOS 12.3, *)
func runList() async {
    do {
        let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: false)
        var items: [[String: Any]] = []

        for (idx, disp) in content.displays.enumerated() {
            items.append([
                "type":      "screen",
                "id":        idx,
                "displayId": disp.displayID,
                "width":     disp.width,
                "height":    disp.height,
                "label":     "Screen \(idx) (\(disp.width)×\(disp.height))"
            ])
        }
        for win in content.windows {
            guard let app = win.owningApplication else { continue }
            guard win.frame.width >= 100, win.frame.height >= 60 else { continue }
            items.append([
                "type":       "window",
                "id":         win.windowID,
                "title":      win.title ?? "",
                "appName":    app.applicationName,
                "pid":        app.processID,
                "width":      Int(win.frame.width),
                "height":     Int(win.frame.height),
                "isOnScreen": win.isOnScreen,
                "label":      "\(app.applicationName) — \(win.title ?? "(untitled)")"
            ])
        }
        if let data = try? JSONSerialization.data(withJSONObject: items, options: .prettyPrinted),
           let str = String(data: data, encoding: .utf8) {
            print(str)
        }
    } catch {
        stderr("list failed: \(error)")
        exit(1)
    }
}

// ── Input injection ───────────────────────────────────────────────────────────

struct WindowGeometry {
    let frame:         CGRect   // window in NSScreen global coords (Y-up from primary bottom-left)
    let mainH:         CGFloat  // primary screen height — for Y-flip in cgPoint
    let pid:           pid_t
    let displayOrigin: CGPoint  // NSScreen origin of the captured display

    // Crop rect in CIImage space.
    // NSScreen and CIImage both use Y-up from the display's bottom-left, so we
    // simply subtract the display origin to get display-relative coords.
    var cropRect: CGRect {
        CGRect(x: frame.minX - displayOrigin.x,
               y: frame.minY - displayOrigin.y,
               width:  frame.width,
               height: frame.height)
    }

    // Normalised [0,1] → CGEvent global coordinates (Y-down from primary top)
    func cgPoint(nx: Double, ny: Double) -> CGPoint {
        let x  = frame.minX + CGFloat(nx) * frame.width
        let nsY = frame.maxY - CGFloat(ny) * frame.height  // ny=0 = top of window
        let y  = mainH - nsY
        return CGPoint(x: x, y: y)
    }
}

func activateApp(pid: pid_t) {
    guard let app = NSRunningApplication(processIdentifier: pid) else { return }
    if #available(macOS 14.0, *) {
        app.activate()
    } else {
        app.activate(options: .activateIgnoringOtherApps)
    }
}

func injectClick(_ ev: [String: Any], geo: WindowGeometry) {
    guard let nx = ev["nx"] as? Double, let ny = ev["ny"] as? Double else { return }
    let pt = geo.cgPoint(nx: nx, ny: ny)
    let isRight = (ev["button"] as? String) == "right"
    let src = CGEventSource(stateID: .hidSystemState)

    let downType: CGEventType = isRight ? .rightMouseDown : .leftMouseDown
    let upType:   CGEventType = isRight ? .rightMouseUp   : .leftMouseUp
    let btn:   CGMouseButton  = isRight ? .right          : .left

    let move = CGEvent(mouseEventSource: src, mouseType: .mouseMoved, mouseCursorPosition: pt, mouseButton: .left)
    move?.post(tap: .cghidEventTap)
    activateApp(pid: geo.pid)
    let down = CGEvent(mouseEventSource: src, mouseType: downType, mouseCursorPosition: pt, mouseButton: btn)
    let up   = CGEvent(mouseEventSource: src, mouseType: upType,   mouseCursorPosition: pt, mouseButton: btn)
    down?.post(tap: .cghidEventTap)
    up?.post(tap:   .cghidEventTap)
}

func injectScroll(_ ev: [String: Any], geo: WindowGeometry) {
    guard let nx = ev["nx"] as? Double, let ny = ev["ny"] as? Double else { return }
    let dy = (ev["dy"] as? Double) ?? 0.0
    let pt = geo.cgPoint(nx: nx, ny: ny)
    let src = CGEventSource(stateID: .hidSystemState)

    let move = CGEvent(mouseEventSource: src, mouseType: .mouseMoved, mouseCursorPosition: pt, mouseButton: .left)
    move?.post(tap: .cghidEventTap)

    let lines = Int32(dy / 10.0)
    if let scroll = CGEvent(scrollWheelEvent2Source: src, units: .line, wheelCount: 1, wheel1: -lines, wheel2: 0, wheel3: 0) {
        scroll.post(tap: .cghidEventTap)
    }
}

let NAMED_KEYS: [String: CGKeyCode] = [
    "Enter":      0x24,
    "Return":     0x24,
    "Backspace":  0x33,
    "Delete":     0x75,
    "Tab":        0x30,
    "Escape":     0x35,
    "ArrowUp":    0x7E,
    "ArrowDown":  0x7D,
    "ArrowLeft":  0x7B,
    "ArrowRight": 0x7C,
    "PageUp":     0x74,
    "PageDown":   0x79,
    "Home":       0x73,
    "End":        0x77,
    "Space":      0x31,
]

func injectKey(_ ev: [String: Any], pid: pid_t) {
    guard let keyName = ev["key"] as? String else { return }
    activateApp(pid: pid)
    let src = CGEventSource(stateID: .hidSystemState)
    if let vk = NAMED_KEYS[keyName] {
        let down = CGEvent(keyboardEventSource: src, virtualKey: vk, keyDown: true)
        let up   = CGEvent(keyboardEventSource: src, virtualKey: vk, keyDown: false)
        down?.post(tap: .cghidEventTap)
        up?.post(tap:   .cghidEventTap)
    }
}

func injectText(_ ev: [String: Any], pid: pid_t) {
    guard let text = ev["text"] as? String, !text.isEmpty else { return }
    activateApp(pid: pid)
    let src = CGEventSource(stateID: .hidSystemState)
    for scalar in text.unicodeScalars {
        var chars = [UniChar(scalar.value & 0xFFFF)]
        guard let down = CGEvent(keyboardEventSource: src, virtualKey: 0, keyDown: true),
              let up   = CGEvent(keyboardEventSource: src, virtualKey: 0, keyDown: false) else { continue }
        down.keyboardSetUnicodeString(stringLength: 1, unicodeString: &chars)
        up.keyboardSetUnicodeString(stringLength: 1,   unicodeString: &chars)
        down.post(tap: .cghidEventTap)
        up.post(tap:   .cghidEventTap)
    }
}

// ── Stream ────────────────────────────────────────────────────────────────────

@available(macOS 12.3, *)
final class CaptureSession: NSObject, SCStreamDelegate, SCStreamOutput {

    private let stream:      SCStream
    private let endpoint:    URL
    private let token:       String?
    private let minInterval: TimeInterval
    private let quality:     Double
    private let enableInput: Bool
    private let ciContext =  CIContext(options: [.useSoftwareRenderer: false, .cacheIntermediates: false])

    private let urlSession: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 5
        c.httpMaximumConnectionsPerHost = 8
        return URLSession(configuration: c)
    }()

    private var frameCount   = 0
    private var lastPostTime = Date.distantPast
    private var geometry:    WindowGeometry?

    init(stream: SCStream, endpoint: URL, token: String?, fps: Double, quality: Double,
         enableInput: Bool, geometry: WindowGeometry?) {
        self.stream      = stream
        self.endpoint    = endpoint
        self.token       = token
        self.minInterval = 1.0 / min(max(fps, 1), 60)
        self.quality     = quality
        self.enableInput = enableInput
        self.geometry    = geometry
    }

    func start() async throws {
        try stream.addStreamOutput(self, type: .screen, sampleHandlerQueue: .global(qos: .userInteractive))
        try await stream.startCapture()
        status("ready")
        if enableInput { startInputLoop() }
    }

    // ── SCStreamOutput ────────────────────────────────────────────────────────

    func stream(_ stream: SCStream, didOutputSampleBuffer sb: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .screen else { return }
        guard CMSampleBufferIsValid(sb) else { return }

        let now = Date()
        guard now.timeIntervalSince(lastPostTime) >= minInterval else { return }
        lastPostTime = now

        guard let pixelBuf = CMSampleBufferGetImageBuffer(sb) else { return }
        var w = CVPixelBufferGetWidth(pixelBuf)
        var h = CVPixelBufferGetHeight(pixelBuf)
        guard w > 0, h > 0 else { return }

        var ci = CIImage(cvImageBuffer: pixelBuf)

        // For window-specific captures: crop the full-display frame to just the
        // window's bounds. NSScreen and CIImage both use Y-up from the display's
        // bottom-left, so geo.cropRect (NSScreen frame minus display origin) maps
        // directly to CIImage coordinates — no flip or scale needed.
        if let geo = geometry {
            let crop = geo.cropRect.intersection(ci.extent)
            if !crop.isEmpty {
                ci = ci.cropped(to: crop)
                w = Int(crop.width.rounded())
                h = Int(crop.height.rounded())
            }
        }

        guard let cs = CGColorSpace(name: CGColorSpace.sRGB) else { return }
        guard let jpeg = ciContext.jpegRepresentation(
            of: ci, colorSpace: cs,
            options: [kCGImageDestinationLossyCompressionQuality as CIImageRepresentationOption: quality]
        ) else { return }

        postFrame(jpeg, w: w, h: h)
        frameCount += 1
        if frameCount % 100 == 0 { status("frame:\(frameCount)") }
    }

    func stream(_ stream: SCStream, didStopWithError error: Error) {
        status("fatal:stream stopped — \(error.localizedDescription)")
        exit(1)
    }

    // ── HTTP frame push ───────────────────────────────────────────────────────

    private func postFrame(_ data: Data, w: Int, h: Int) {
        var comps = URLComponents(url: endpoint, resolvingAgainstBaseURL: false)!
        comps.queryItems = [URLQueryItem(name: "w", value: String(w)),
                            URLQueryItem(name: "h", value: String(h))]
        guard let url = comps.url else { return }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("image/jpeg", forHTTPHeaderField: "Content-Type")
        req.setValue(String(w), forHTTPHeaderField: "X-Cinema-Width")
        req.setValue(String(h), forHTTPHeaderField: "X-Cinema-Height")
        if let tok = token { req.setValue("Bearer \(tok)", forHTTPHeaderField: "Authorization") }
        req.httpBody = data
        urlSession.dataTask(with: req) { _, _, err in
            if let err = err { stderr("post error: \(err.localizedDescription)") }
        }.resume()
    }

    // ── Stdin input loop ──────────────────────────────────────────────────────

    private func startInputLoop() {
        Thread.detachNewThread { [weak self] in
            while let line = readLine(strippingNewline: true) {
                guard let self else { break }
                guard let data = line.data(using: .utf8),
                      let ev = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                      let type = ev["type"] as? String else { continue }
                guard let geo = self.geometry else { continue }
                switch type {
                case "click":  injectClick(ev,  geo: geo)
                case "move":   break
                case "scroll": injectScroll(ev, geo: geo)
                case "key":    injectKey(ev,    pid: geo.pid)
                case "text":   injectText(ev,   pid: geo.pid)
                default: break
                }
            }
        }
    }

    func updateGeometry(_ geo: WindowGeometry?) { geometry = geo }
}

// ── Stream setup ──────────────────────────────────────────────────────────────

@available(macOS 12.3, *)
func buildStream(opts: [String: String], snapshot: ScreenSnapshot) async throws -> (SCStream, WindowGeometry?) {
    // NOTE: NSScreen is intentionally NOT called here. All screen data was captured
    // synchronously on the main thread (via ScreenSnapshot) before this Task started.
    // Calling NSScreen inside an async Task can deadlock when dispatchMain() drives
    // the main thread — the @MainActor hop waits for the main thread, which is busy.
    let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: false)

    // Phase 1: find the target window's frame (if any).
    var targetFrame: CGRect? = nil
    var targetPid:   pid_t?  = nil

    if let widStr = opts["window-id"], let wid = UInt32(widStr) {
        guard let win = content.windows.first(where: { $0.windowID == wid }) else {
            throw NSError(domain: "wincap", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "window-id \(wid) not found — it may have closed"])
        }
        targetFrame = win.frame
        targetPid   = win.owningApplication?.processID
    } else if let appName = opts["app"] {
        let matches = content.windows.filter {
            guard let n = $0.owningApplication?.applicationName else { return false }
            return n.localizedCaseInsensitiveContains(appName) && $0.frame.width >= 100
        }.sorted { $0.frame.width * $0.frame.height > $1.frame.width * $1.frame.height }
        guard let win = matches.first else {
            throw NSError(domain: "wincap", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "no window for app '\(appName)'"])
        }
        targetFrame = win.frame
        targetPid   = win.owningApplication?.processID
    }

    // Phase 2: pick the SCDisplay that best contains the target window (or the
    // requested screen index). Use the pre-captured snapshot for the display
    // origin — no NSScreen calls inside this async function.
    let screenIdx: Int
    if let frame = targetFrame {
        let centre = CGPoint(x: frame.midX, y: frame.midY)
        // Find the NSScreen that contains (or is closest to) the window centre,
        // then match it to an SCDisplay by displayID.
        let best = snapshot.screens.min(by: { a, b in
            hypot(a.origin.x - centre.x, a.origin.y - centre.y)
          < hypot(b.origin.x - centre.x, b.origin.y - centre.y)
        })
        if let bestID = best?.displayID,
           let idx = content.displays.firstIndex(where: { $0.displayID == bestID }) {
            screenIdx = idx
        } else {
            screenIdx = 0
        }
    } else {
        screenIdx = Int(opts["screen"] ?? "0") ?? 0
    }
    let display = content.displays.indices.contains(screenIdx) ? content.displays[screenIdx] : content.displays[0]
    let nsDisplayOrigin = snapshot.originForDisplay(display.displayID)

    // Phase 3: build WindowGeometry now that we know which display we're capturing.
    var geo: WindowGeometry? = nil
    if let frame = targetFrame, let pid = targetPid {
        geo = WindowGeometry(frame: frame, mainH: snapshot.mainH, pid: pid, displayOrigin: nsDisplayOrigin)
    }

    // Always use full-display capture with SCContentFilter(display:excludingWindows:[]).
    // SCContentFilter(desktopIndependentWindow:) hangs on startCapture() on macOS 14/15
    // due to a TCC permission layer that is never resolved for CLI binaries.
    // Window isolation is achieved by cropping the CIImage in the frame callback.
    let filter = SCContentFilter(display: display, excludingWindows: [])
    let cfg = SCStreamConfiguration()
    cfg.minimumFrameInterval = CMTime(value: 1, timescale: 60)
    cfg.queueDepth = 3
    cfg.pixelFormat = kCVPixelFormatType_32BGRA
    // Capture at the display's LOGICAL resolution so NSScreen window frame coords
    // (which are in logical points) map 1:1 to pixel positions in the capture.
    cfg.width  = display.width
    cfg.height = display.height
    cfg.showsCursor = false

    let stream = SCStream(filter: filter, configuration: cfg, delegate: nil)
    return (stream, geo)
}

// Snapshot NSScreen data synchronously on the calling thread BEFORE entering
// async context, avoiding any @MainActor hop inside the Task that could deadlock
// when dispatchMain() is the only thing keeping the main thread alive.
struct ScreenSnapshot {
    let screens: [(displayID: CGDirectDisplayID, origin: CGPoint, height: CGFloat)]
    let mainH: CGFloat

    static func capture() -> ScreenSnapshot {
        let screens = NSScreen.screens
        let mainH = screens.first?.frame.height ?? 800
        let data = screens.compactMap { ns -> (CGDirectDisplayID, CGPoint, CGFloat)? in
            guard let id = ns.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? CGDirectDisplayID
            else { return nil }
            return (id, ns.frame.origin, ns.frame.height)
        }
        return ScreenSnapshot(screens: data, mainH: mainH)
    }

    func originForDisplay(_ displayID: CGDirectDisplayID) -> CGPoint {
        screens.first(where: { $0.displayID == displayID })?.origin ?? .zero
    }
}

@available(macOS 12.3, *)
func runStream(opts: [String: String], snapshot: ScreenSnapshot) async {
    let cinemaId  = opts["cinema-id"] ?? "main"
    let fps       = Double(opts["fps"]     ?? "60")   ?? 60
    let quality   = Double(opts["quality"] ?? "0.70") ?? 0.70
    let token     = opts["token"]
    let noInput   = opts["no-input"] == "true"
    let port      = ProcessInfo.processInfo.environment["AGENTCRAFT_HTTP_PORT"] ?? "8766"
    let defaultEp = "http://127.0.0.1:\(port)/api/cinema/\(cinemaId)/frame"
    guard let endpoint = URL(string: opts["endpoint"] ?? defaultEp) else {
        status("fatal:bad endpoint URL"); exit(1)
    }

    do {
        let (stream, geo) = try await buildStream(opts: opts, snapshot: snapshot)
        let session = CaptureSession(stream: stream, endpoint: endpoint, token: token,
                                     fps: fps, quality: quality, enableInput: !noInput, geometry: geo)
        liveObjects = [session, stream]
        try await session.start()
    } catch {
        status("fatal:\(error.localizedDescription)"); exit(1)
    }
}

// ── Entrypoint ────────────────────────────────────────────────────────────────

if #available(macOS 12.3, *) {
    let argv = CommandLine.arguments
    let sub  = argv.count > 1 ? argv[1] : ""
    let opts = parseArgs(argv.dropFirst().map { String($0) })

    switch sub {
    case "list":
        Task { await runList(); exit(0) }
        dispatchMain()

    case "stream":
        // Capture NSScreen data synchronously on the main thread BEFORE launching
        // the async Task — avoids a @MainActor deadlock inside dispatchMain().
        let snapshot = ScreenSnapshot.capture()
        Task { await runStream(opts: opts, snapshot: snapshot) }
        dispatchMain()

    default:
        fputs("Usage: WindowCapture <list|stream> [options]\n", Foundation.stderr)
        exit(1)
    }
} else {
    fputs("WindowCapture requires macOS 12.3 or later.\n", Foundation.stderr)
    exit(1)
}
