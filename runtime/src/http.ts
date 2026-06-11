import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import type { AgentManager } from "./agents/AgentManager.js";
import { faceState, type FaceMode } from "./faceState.js";
import { omoFrame } from "./omoFrame.js";
import { cinemaStore, defaultCinemaUrl, type CinemaInputEvent } from "./cinema.js";
import { windowCaptureStore, listWindows, type CaptureFilter } from "./window-capture.js";
import { whiteboardHtml, whiteboardStore } from "./whiteboard.js";
import { ensureDeck } from "./classroom/deck.js";
import { listeningHtml } from "./listening/page.js";
import { listening, TRANSCRIPTS_DIR } from "./listening/session.js";
import { distill, toMarkdown } from "./listening/distill.js";
import { copyTextToClipboard } from "./clipboard.js";
import { transcribeWav } from "./voice/transcribe.js";
import { writeFile, unlink } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join as pathJoin } from "node:path";
import { resolveDestination } from "./map.js";
import { httpAuthorized } from "./auth.js";

// Max size of a single PNG frame the overlay browser tab may push. 256×256
// RGBA PNGs compress to ~60-90 KB typically (chrome + transparency); 512 KB
// is a roomy ceiling that stops a runaway page from buffering megabytes
// per second through the runtime. Anything larger gets rejected with 413.
const OMO_FRAME_MAX_BYTES = 512 * 1024;

// Cinema frames are full-color browser screenshots, not the tiny
// transparent Omo head — they're much bigger. 1024×640 PNG is ~300–500 KB
// at typical web pages; 4 MB is a safe ceiling that still rejects runaways.
const CINEMA_FRAME_MAX_BYTES = 4 * 1024 * 1024;

// Cinemas only refresh ~once a second by design (map-wall network rate),
// so the stale threshold is roomier than omoFrame's — a momentary skipped
// frame shouldn't blank the wall.
const CINEMA_FRAME_STALE_MS = 8000;

// After this many ms with no fresh POST, the GET endpoint answers 204 so
// the in-game HUD falls back to its bundled sprite frames instead of
// drawing a stale "stuck" head.
const OMO_FRAME_STALE_MS = 2000;

// Push-to-talk clips: 16 kHz mono 16-bit WAV ≈ 32 KB/s. 8 MB caps a single
// V-hold at ~4 minutes of speech — far more than any spoken command, and a
// hard ceiling that stops a wedged client from streaming megabytes at us.
const VOICE_WAV_MAX_BYTES = 8 * 1024 * 1024;
// Monotonic counter so concurrent V-holds (two players, one runtime) never
// collide on the temp WAV path.
let voiceClipSeq = 0;

// Where the face/ HTTP server lives — used by /api/voice-trigger to fan
// the in-game keybind out to whichever browser tabs are watching the SSE
// /events stream. Overridable for the same reason face/ overrides the
// reverse direction.
const FACE_HTTP_BASE =
  process.env.AGENTCRAFT_FACE_HTTP ?? "http://127.0.0.1:8080";

/**
 * Tiny localhost HTTP bridge. Two jobs:
 *   1. /api/teleport — the face/ hologram POSTs here; we turn it into a
 *      `teleport_player` WS frame so the plugin can move the player on the
 *      main thread. Also pulses the in-game overlay to `celebrating` for
 *      ~1.5s so the user sees Omo react to her own action.
 *   2. /api/face-state — GET returns the current face mode (polled at ~4Hz
 *      by the Fabric client-mod HUD layer). POST lets the face/ voice loop
 *      push state transitions (listening / thinking / speaking / idle).
 */
export function startHttpServer(manager: AgentManager, port: number): void {
  const server = createServer(async (req, res) => {
    try {
      await handle(manager, req, res);
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      json(res, 500, { error: msg });
    }
  });
  // Bind 0.0.0.0 so friends' clients can poll the Omo frame/state/voice
  // endpoints over the network (the mod's RuntimeHost points here). Lock
  // back to localhost with AGENTCRAFT_BIND_HOST=127.0.0.1 if desired.
  const host = process.env.AGENTCRAFT_BIND_HOST ?? "0.0.0.0";
  server.listen(port, host, () => {
    console.log(`[http] teleport bridge on http://${host}:${port}`);
  });
}

async function handle(
  manager: AgentManager,
  req: IncomingMessage,
  res: ServerResponse,
): Promise<void> {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "content-type, authorization");
  if (req.method === "OPTIONS") { res.writeHead(204); res.end(); return; }

  // Backend access control: on-box callers (face, plugin, headless Chrome — all
  // loopback) are trusted; anything from the network must carry
  // `Authorization: Bearer <OMO_CLIENT_TOKEN>`. Closes the spawn-code / Gemini-
  // mint freeloading paths to remote clients. See ./auth.ts.
  if (!httpAuthorized(req.socket.remoteAddress, req.headers.authorization)) {
    json(res, 401, { ok: false, error: "unauthorized" });
    return;
  }

  const url = new URL(req.url ?? "/", `http://${req.headers.host ?? "localhost"}`);

  // The classroom whiteboard page. The cinema channel "whiteboard" points
  // here; face/ captures it and the plugin paints it on the map-wall behind
  // the tutor. Self-contained HTML, so the headless capture needs no network.
  if (req.method === "GET" && url.pathname === "/whiteboard") {
    res.writeHead(200, {
      "Content-Type": "text/html; charset=utf-8",
      "Cache-Control": "no-store",
    });
    res.end(whiteboardHtml());
    return;
  }

  // The wall polls this ~every 1.2s for the current subject/title/content.
  if (req.method === "GET" && url.pathname === "/api/whiteboard/state") {
    json(res, 200, whiteboardStore.get());
    return;
  }

  // Player flipped a slide with the on-wall ‹ › arrows (the click came through
  // the cinema input pipeline → CDP DOM click → the arrow's fetch). Step the deck.
  if (req.method === "POST" && url.pathname === "/api/whiteboard/nav") {
    const body = await readBody(req);
    let dir = "next";
    try { dir = String(JSON.parse(body || "{}").dir || "next"); } catch { dir = "next"; }
    whiteboardStore.nudge(dir === "prev" ? -1 : 1);
    json(res, 200, whiteboardStore.get());
    return;
  }

  // ── The Listening Room ───────────────────────────────────────────────
  // Live-transcript wall page (cinema channel "listening" points here).
  if (req.method === "GET" && url.pathname === "/listening") {
    res.writeHead(200, {
      "Content-Type": "text/html; charset=utf-8",
      "Cache-Control": "no-store",
    });
    res.end(listeningHtml());
    return;
  }
  // The wall polls this for the rolling transcript + recording state.
  if (req.method === "GET" && url.pathname === "/api/listening/state") {
    json(res, 200, listening.getState());
    return;
  }
  // Arm/disarm the mic (the RECORD lever). No `armed` field → toggle.
  if (req.method === "POST" && url.pathname === "/api/listening/arm") {
    const body = await readBody(req);
    let want: boolean;
    try {
      const parsed = JSON.parse(body || "{}");
      want = typeof parsed.armed === "boolean" ? parsed.armed : !listening.isArmed();
    } catch {
      want = !listening.isArmed();
    }
    if (want) listening.arm();
    else listening.disarm();
    json(res, 200, listening.getState());
    return;
  }
  // DISTILL: transcript → an organised plan of agent-ready work items. Stash it
  // on the session (flips the wall to the board view), copy the full clean plan
  // to the host clipboard, archive a markdown copy, and return the structured
  // result so the plugin can open it as an in-game book.
  if (req.method === "POST" && url.pathname === "/api/listening/distill") {
    const transcript = listening.getFullText();
    listening.beginDistill(); // flip the wall to a "Distilling…" state right away
    const result = await distill(transcript);
    listening.setDistill(result);
    const copied = result.clipboard ? copyTextToClipboard(result.clipboard) : false;
    try {
      const stamp = new Date().toISOString().replace(/[:.]/g, "-");
      await writeFile(pathJoin(TRANSCRIPTS_DIR, `${stamp}.md`), toMarkdown(result, transcript), "utf8");
    } catch {
      /* archival is best-effort */
    }
    json(res, 200, { ...result, copied });
    return;
  }
  // Click-to-copy: a left-click on a board item (forwarded through the cinema
  // input pipeline) lands here — copy just that item's prompt to the clipboard
  // so you can hand a different prompt to each code-N agent.
  if (req.method === "POST" && url.pathname === "/api/listening/copy") {
    const body = await readBody(req);
    let id = 0;
    try { id = Number(JSON.parse(body || "{}").id) || 0; } catch { id = 0; }
    const prompt = listening.itemPrompt(id);
    if (!prompt) { json(res, 404, { ok: false, error: "no such item" }); return; }
    const copied = copyTextToClipboard(prompt);
    if (copied) listening.markCopied(id);
    json(res, 200, { ok: true, id, copied });
    return;
  }

  if (req.method === "POST" && url.pathname === "/api/teleport") {
    const body = await readBody(req);
    let input = ""; let player: string | null = null;
    try {
      const parsed = JSON.parse(body || "{}");
      // Accept either `destination` (the new canonical-id field the face
      // sends) or legacy `room` (raw room name). Both flow through the
      // shared map resolver below.
      const destField = typeof parsed.destination === "string" ? parsed.destination : "";
      const roomField = typeof parsed.room === "string" ? parsed.room : "";
      input = (destField || roomField).trim();
      player = parsed.player == null ? null : String(parsed.player);
    } catch {
      json(res, 400, { ok: false, error: "invalid JSON body" });
      return;
    }
    if (!input) { json(res, 400, { ok: false, error: "destination required" }); return; }

    const dest = resolveDestination(input);
    if (!dest) {
      // Fail loudly so the face can tell the user instead of pretending
      // we teleported them somewhere that doesn't exist — this was the
      // silent-failure path the user reported.
      console.warn(`[http] teleport rejected: unknown destination "${input}"`);
      json(res, 400, { ok: false, error: `unknown destination: "${input}"`, input });
      return;
    }

    // Send canonical id as `room` for log readability and as a literal
    // fallback the plugin can try if any backfilled alias matches.
    // `roomCandidates` is the ordered preference list the plugin walks.
    manager.broadcast({
      type: "teleport_player",
      room: dest.id,
      player,
      roomCandidates: dest.roomCandidates,
    });
    console.log(
      `[http] teleport → ${dest.id} (${dest.display}) candidates=[${dest.roomCandidates.join(", ")}]` +
        (player ? ` player=${player}` : ""),
    );
    // Fire a short celebratory burst on the in-game overlay. Auto-reverts
    // to "idle" after 1.5s unless the face pushes a fresher state first.
    faceState.set({ room: dest.id });
    faceState.pulse("celebrating", 1500);
    json(res, 200, {
      ok: true,
      destination: dest.id,
      display: dest.display,
      room: dest.id,
      roomCandidates: dest.roomCandidates,
      player,
    });
    return;
  }

  // ─── In-game voice keybind → browser voice loop ──────────────────────
  // The Fabric client-mod POSTs here when the user presses "V" inside MC.
  // We forward the action to the face/ server's /voice-control endpoint,
  // which then fans it out to every browser tab subscribed to SSE
  // /events. The browser face-app.js handler runs the same code path as
  // a manual mic-button click. Best-effort: if the face isn't running,
  // we 502 so the mod can log a one-line hint, but we never block.
  if (req.method === "POST" && url.pathname === "/api/voice-trigger") {
    const body = await readBody(req);
    let action = "toggle";
    try {
      const parsed = JSON.parse(body || "{}");
      if (typeof parsed.action === "string") action = parsed.action.toLowerCase();
    } catch {
      json(res, 400, { ok: false, error: "invalid JSON body" });
      return;
    }
    if (!["toggle", "start", "stop"].includes(action)) {
      json(res, 400, { ok: false, error: "action must be toggle|start|stop" });
      return;
    }
    try {
      const r = await fetch(`${FACE_HTTP_BASE}/voice-control`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ action }),
      });
      if (!r.ok) {
        console.warn(`[http] voice-trigger: face responded ${r.status}`);
        json(res, 502, { ok: false, error: `face ${r.status}` });
        return;
      }
      const out = (await r.json().catch(() => ({}))) as Record<string, unknown>;
      const listeners = typeof out.listeners === "number" ? out.listeners : "?";
      console.log(`[http] voice-trigger → ${action} (face listeners=${listeners})`);
      json(res, 200, { ok: true, action, ...out });
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      console.warn(`[http] voice-trigger: face unreachable (${msg})`);
      json(res, 502, { ok: false, error: `face unreachable: ${msg}` });
    }
    return;
  }

  // ─── Push-to-talk: in-game "V" hold → whisper → chat ─────────────────
  // The Fabric client-mod records the player's mic while V is held and POSTs
  // the WAV here on release. We transcribe locally with whisper.cpp and hand
  // the text straight back as plain text; the client then injects it as a
  // normal chat packet, so it flows through the plugin's ChatListener exactly
  // as if the player had typed it. We deliberately do NOT broadcast here —
  // keeping the transcript on the real chat path is what makes voice behave
  // identically to typing (same gaze/room agent routing, same terminal open).
  if (req.method === "POST" && url.pathname === "/api/voice-transcribe") {
    let total = 0;
    const chunks: Buffer[] = [];
    let aborted = false;
    await new Promise<void>((resolve) => {
      req.on("data", (chunk: Buffer) => {
        if (aborted) return;
        total += chunk.length;
        if (total > VOICE_WAV_MAX_BYTES) {
          aborted = true;
          json(res, 413, { ok: false, error: "audio too large" });
          req.destroy();
          resolve();
          return;
        }
        chunks.push(chunk);
      });
      req.on("end", () => resolve());
      req.on("error", () => { aborted = true; resolve(); });
    });
    if (aborted) return;
    if (total === 0) {
      res.writeHead(200, { "Content-Type": "text/plain; charset=utf-8" });
      res.end("");
      return;
    }
    const wav = pathJoin(tmpdir(), `omo-ptt-${Date.now()}-${voiceClipSeq++}.wav`);
    try {
      await writeFile(wav, Buffer.concat(chunks));
      const text = await transcribeWav(wav);
      console.log(
        `[http] voice-transcribe ← ${(total / 1024) | 0}KB → ${text ? JSON.stringify(text) : "(no speech)"}`,
      );
      res.writeHead(200, { "Content-Type": "text/plain; charset=utf-8" });
      res.end(text);
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      console.warn(`[http] voice-transcribe failed: ${msg}`);
      json(res, 500, { ok: false, error: msg });
    } finally {
      unlink(wav).catch(() => {});
    }
    return;
  }

  // ─── Voice-driven agent ops (face/tools.js → runtime → plugin) ───────
  // The face mints a Gemini Live tool call (e.g. spawn_team, open_terminal,
  // despawn_agent), POSTs the JSON body here, and we translate it into
  // one of the *_request OutboundMessages the plugin's IncomingHandler
  // already knows about. Best-effort: we broadcast the WS frame and
  // return ok immediately — there's no synchronous "did the spawn
  // succeed" reply on the bridge, so the model gets back a generic
  // "request dispatched" confirmation. If anything goes wrong on the
  // plugin side, the in-game chat sees it (and so does the player).
  if (req.method === "POST" && url.pathname.startsWith("/api/agents/")) {
    const action = url.pathname.slice("/api/agents/".length);

    // GET-style: enumerate agents the runtime knows about. No plugin
    // round-trip needed — AgentManager already has the full list.
    if (action === "list") {
      const agents = manager.list().map((a) => ({
        id: a.id,
        role: a.role,
        room: a.room,
        status: a.status,
      }));
      json(res, 200, { ok: true, agents, count: agents.length });
      return;
    }

    const body = await readBody(req);
    let parsed: Record<string, unknown> = {};
    try { parsed = body ? JSON.parse(body) : {}; }
    catch { json(res, 400, { ok: false, error: "invalid JSON body" }); return; }

    const playerName = typeof parsed.player === "string" ? parsed.player
                     : typeof parsed.playerName === "string" ? parsed.playerName
                     : null;

    switch (action) {
      case "spawn-team": {
        const cwd = typeof parsed.cwd === "string" && parsed.cwd.trim()
          ? parsed.cwd.trim() : null;
        manager.broadcast({ type: "spawn_team_request", cwd, playerName });
        console.log(`[http] spawn-team cwd=${cwd ?? "(default)"}`);
        json(res, 200, { ok: true, action: "spawn-team", cwd });
        return;
      }
      case "spawn-village": {
        manager.broadcast({ type: "spawn_village_request", playerName });
        console.log(`[http] spawn-village`);
        json(res, 200, { ok: true, action: "spawn-village" });
        return;
      }
      case "open-classroom": {
        const subject = typeof parsed.subject === "string" && parsed.subject.trim()
          ? parsed.subject.trim()
          : "Algebra";
        ensureDeck(subject);
        manager.broadcast({ type: "open_classroom_request", subject, playerName });
        console.log(`[http] open-classroom ${subject}`);
        json(res, 200, { ok: true, action: "open-classroom", subject, room: "classroom" });
        return;
      }
      case "spawn-code": {
        const agentId = typeof parsed.agentId === "string" ? parsed.agentId.trim() : "";
        const cwd = typeof parsed.cwd === "string" ? parsed.cwd.trim() : "";
        const task = typeof parsed.task === "string" ? parsed.task.trim() : "";
        if (!agentId || !cwd || !task) {
          json(res, 400, { ok: false, error: "agentId, cwd and task are all required" });
          return;
        }
        manager.broadcast({ type: "spawn_code_request", agentId, cwd, task, playerName });
        console.log(`[http] spawn-code ${agentId} cwd=${cwd}`);
        json(res, 200, { ok: true, action: "spawn-code", agentId, cwd });
        return;
      }
      case "despawn": {
        const agentId = typeof parsed.agentId === "string" ? parsed.agentId.trim() : "";
        if (!agentId) { json(res, 400, { ok: false, error: "agentId required" }); return; }
        manager.broadcast({ type: "despawn_agent_request", agentId });
        console.log(`[http] despawn ${agentId}`);
        json(res, 200, { ok: true, action: "despawn", agentId });
        return;
      }
      case "open-terminal": {
        const agentId = typeof parsed.agentId === "string" && parsed.agentId.trim()
          ? parsed.agentId.trim() : null;
        manager.broadcast({ type: "open_terminal_request", agentId, playerName });
        console.log(`[http] open-terminal ${agentId ?? "(default)"}`);
        json(res, 200, { ok: true, action: "open-terminal", agentId });
        return;
      }
      case "close-terminal": {
        manager.broadcast({ type: "close_terminal_request", playerName });
        console.log(`[http] close-terminal`);
        json(res, 200, { ok: true, action: "close-terminal" });
        return;
      }
      default:
        json(res, 404, { ok: false, error: `unknown agents action: ${action}` });
        return;
    }
  }

  // Voice transcripts from the face/ Gemini Live loop. Body:
  //   { role: "user" | "omo", text: "..." }
  // We forward to the plugin as a chat_message WS frame so the player
  // sees the conversation in MC chat. Best-effort; bad input → 400.
  if (req.method === "POST" && url.pathname === "/api/voice-transcript") {
    const body = await readBody(req);
    let role = "";
    let text = "";
    try {
      const parsed = JSON.parse(body || "{}");
      role = String(parsed.role || "").toLowerCase();
      text = String(parsed.text || "").trim();
    } catch {
      json(res, 400, { ok: false, error: "invalid JSON body" });
      return;
    }
    if (role !== "user" && role !== "omo") {
      json(res, 400, { ok: false, error: "role must be user|omo" });
      return;
    }
    if (!text) { json(res, 200, { ok: true, skipped: "empty" }); return; }
    // Minecraft chat soft-wraps but a single 600-char line still looks
    // terrible (and Mojang's component pipeline hard-caps individual
    // messages anyway). Split Omo's long replies into ~180-char chunks
    // on sentence/whitespace boundaries so the chat scroll stays
    // readable. Short replies pass through untouched.
    const chunks = splitForChat(text, 180);
    for (const chunk of chunks) {
      manager.broadcast({ type: "chat_message", role: role as "user" | "omo", text: chunk });
    }
    console.log(
      `[http] transcript ${role}: ${text.slice(0, 120)}` +
        (chunks.length > 1 ? ` (split into ${chunks.length} lines)` : ""),
    );
    json(res, 200, { ok: true, lines: chunks.length });
    return;
  }

  // Voice-loop progress pings from face-app.js while Gemini Live is
  // minting / connecting / setting up the mic. Lets the player see a
  // status crawl in MC chat ("⋯ minting token … ~2s") so V doesn't feel
  // like a black box.
  //
  // Body:
  //   { stage: "wake"|"token"|"sdk"|"mic"|"ws"|"ready"|"stop",
  //     text:  human-readable line,
  //     etaMs: estimated total ms to ready (optional),
  //     elapsedMs: ms since the V-press (optional) }
  //
  // We forward as a chat_message with role:"system" so the plugin renders
  // it italic-gray, visually distinct from real conversation lines.
  if (req.method === "POST" && url.pathname === "/api/voice-progress") {
    const body = await readBody(req);
    let stage = "", text = "", etaMs = 0, elapsedMs = 0;
    try {
      const parsed = JSON.parse(body || "{}");
      stage = String(parsed.stage || "").toLowerCase();
      text = String(parsed.text || "").trim();
      etaMs = Number(parsed.etaMs || 0);
      elapsedMs = Number(parsed.elapsedMs || 0);
    } catch {
      json(res, 400, { ok: false, error: "invalid JSON body" });
      return;
    }
    if (!text) { json(res, 200, { ok: true, skipped: "empty" }); return; }
    manager.broadcast({ type: "chat_message", role: "system", text });
    console.log(`[http] progress ${stage} (${elapsedMs}ms/${etaMs}ms): ${text}`);
    json(res, 200, { ok: true });
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/face-state") {
    json(res, 200, faceState.get());
    return;
  }

  if (req.method === "POST" && url.pathname === "/api/face-state") {
    const body = await readBody(req);
    let mode: FaceMode | undefined;
    let room: string | undefined;
    let transcript: string | undefined;
    try {
      const parsed = JSON.parse(body || "{}");
      if (parsed.mode != null) mode = String(parsed.mode) as FaceMode;
      if (parsed.room != null) room = String(parsed.room);
      if (parsed.transcript != null) transcript = String(parsed.transcript);
    } catch {
      json(res, 400, { ok: false, error: "invalid JSON body" });
      return;
    }
    try {
      const next = faceState.set({
        ...(mode !== undefined ? { mode } : {}),
        ...(room !== undefined ? { room } : {}),
        ...(transcript !== undefined ? { transcript } : {}),
      });
      json(res, 200, next);
    } catch (e) {
      json(res, 400, { ok: false, error: e instanceof Error ? e.message : String(e) });
    }
    return;
  }

  // ─── Live Omo head frame (browser overlay → runtime → in-game HUD) ──
  // POST: browser pushes a 256×256 transparent PNG of just Omo's head at
  // ~12 FPS. Body is raw image/png — no JSON wrapper, to keep CPU off the
  // hot path. Width/height arrive as query params (?w=256&h=256) so the
  // GET side can echo them back without having to parse the PNG.
  if (req.method === "POST" && url.pathname === "/api/omo-frame") {
    const ct = (req.headers["content-type"] || "").toString().toLowerCase();
    if (!ct.startsWith("image/png")) {
      json(res, 415, { ok: false, error: "expected image/png body" });
      return;
    }
    const w = Math.max(1, Math.min(1024, parseInt(url.searchParams.get("w") || "256", 10) || 256));
    const h = Math.max(1, Math.min(1024, parseInt(url.searchParams.get("h") || "256", 10) || 256));
    let total = 0;
    const chunks: Buffer[] = [];
    let aborted = false;
    await new Promise<void>((resolve) => {
      req.on("data", (chunk: Buffer) => {
        if (aborted) return;
        total += chunk.length;
        if (total > OMO_FRAME_MAX_BYTES) {
          aborted = true;
          json(res, 413, { ok: false, error: "frame too large" });
          req.destroy();
          resolve();
          return;
        }
        chunks.push(chunk);
      });
      req.on("end", () => resolve());
      req.on("error", () => { aborted = true; resolve(); });
    });
    if (aborted) return;
    omoFrame.set(Buffer.concat(chunks), w, h);
    res.writeHead(204);
    res.end();
    return;
  }

  // GET: HUD layer polls this at ~12 Hz. We hand back raw PNG bytes (no
  // base64, no JSON) plus a small header trio so the mod knows dimensions
  // and freshness without decoding the PNG. When the overlay tab is
  // closed or the runtime just started, we answer 204 — the mod takes
  // that as "fall back to bundled sprites".
  if (req.method === "GET" && url.pathname === "/api/omo-frame") {
    if (omoFrame.isStale(OMO_FRAME_STALE_MS)) {
      res.writeHead(204);
      res.end();
      return;
    }
    const snap = omoFrame.get();
    if (!snap.png) {
      res.writeHead(204);
      res.end();
      return;
    }
    res.writeHead(200, {
      "Content-Type": "image/png",
      "Content-Length": String(snap.png.length),
      "X-Omo-Width": String(snap.width),
      "X-Omo-Height": String(snap.height),
      "X-Omo-Updated-At": String(snap.updatedAt),
      // Disable any intermediate caching — every poll wants the freshest
      // frame, not the one Cloudflare or the browser would replay.
      "Cache-Control": "no-store, no-cache, must-revalidate",
    });
    res.end(snap.png);
    return;
  }

  // ─── Cinema: live webpage screens on in-game map walls ───────────────
  // Three routes per cinema id (default "main"):
  //   POST /api/cinema/:id/frame   image/png body, ?w=&h= — face pushes
  //   GET  /api/cinema/:id/frame   PNG out, 204 if stale — plugin polls
  //   POST /api/cinema/:id/url     {url} — swap channel; face sees the
  //                                bumped urlVersion via the next GET /list
  //   GET  /api/cinema/list        roster + current url + urlVersion +
  //                                whether each cinema has a fresh frame.
  //                                face polls this and re-navigates pages
  //                                whose urlVersion advanced.
  if (url.pathname.startsWith("/api/cinema/")) {
    // /api/cinema/list — roster
    if (req.method === "GET" && url.pathname === "/api/cinema/list") {
      const list = cinemaStore.list().map((c) => ({
        id: c.id,
        url: c.url,
        urlVersion: c.urlVersion,
        width: c.width,
        height: c.height,
        updatedAt: c.updatedAt,
        hasFrame: c.png !== null,
      }));
      json(res, 200, { cinemas: list });
      return;
    }

    // /api/cinema/:id/(frame|url|input)
    const m = url.pathname.match(/^\/api\/cinema\/([a-zA-Z0-9_-]+)\/(frame|url|input)$/);
    if (m && m[1] && m[2]) {
      const id: string = m[1];
      const verb: string = m[2];

      if (verb === "frame" && req.method === "POST") {
        const ct = (req.headers["content-type"] || "").toString().toLowerCase();
        // PNG (screenshot fallback) or JPEG (CDP screencast — much cheaper
        // per frame, which is what lets the wall run at video rates).
        if (!ct.startsWith("image/")) {
          json(res, 415, { ok: false, error: "expected image/* body" });
          return;
        }
        const w = Math.max(1, Math.min(4096, parseInt(url.searchParams.get("w") || "1024", 10) || 1024));
        const h = Math.max(1, Math.min(4096, parseInt(url.searchParams.get("h") || "640", 10) || 640));
        let total = 0;
        const chunks: Buffer[] = [];
        let aborted = false;
        await new Promise<void>((resolve) => {
          req.on("data", (chunk: Buffer) => {
            if (aborted) return;
            total += chunk.length;
            if (total > CINEMA_FRAME_MAX_BYTES) {
              aborted = true;
              json(res, 413, { ok: false, error: "frame too large" });
              req.destroy();
              resolve();
              return;
            }
            chunks.push(chunk);
          });
          req.on("end", () => resolve());
          req.on("error", () => { aborted = true; resolve(); });
        });
        if (aborted) return;
        // Ensure the cinema exists — face may POST before any explicit
        // URL set if the plugin pre-registered the wall.
        cinemaStore.ensure(id, defaultCinemaUrl(id));
        cinemaStore.setFrame(id, Buffer.concat(chunks), w, h);
        res.writeHead(204);
        res.end();
        return;
      }

      if (verb === "frame" && req.method === "GET") {
        if (cinemaStore.isStale(id, CINEMA_FRAME_STALE_MS)) {
          res.writeHead(204);
          res.end();
          return;
        }
        const c = cinemaStore.get(id);
        if (!c || !c.png) { res.writeHead(204); res.end(); return; }
        res.writeHead(200, {
          "Content-Type": "image/png",
          "Content-Length": String(c.png.length),
          "X-Cinema-Width": String(c.width),
          "X-Cinema-Height": String(c.height),
          "X-Cinema-Updated-At": String(c.updatedAt),
          "X-Cinema-Url-Version": String(c.urlVersion),
          "Cache-Control": "no-store, no-cache, must-revalidate",
        });
        res.end(c.png);
        return;
      }

      if (verb === "url" && req.method === "POST") {
        const body = await readBody(req);
        let newUrl = "";
        try {
          const parsed = JSON.parse(body || "{}");
          newUrl = String(parsed.url || "").trim();
        } catch {
          json(res, 400, { ok: false, error: "invalid JSON body" });
          return;
        }
        if (!newUrl) {
          json(res, 400, { ok: false, error: "url required" });
          return;
        }
        const version = cinemaStore.setUrl(id, newUrl, defaultCinemaUrl(id));
        console.log(`[http] cinema ${id} → ${newUrl} (v${version})`);
        json(res, 200, { ok: true, id, url: newUrl, urlVersion: version });
        return;
      }

      // ─── Interactive input: plugin enqueues, face drains ────────────────
      // The in-game wall is clickable. The plugin ray-casts a player's aim
      // onto the screen and POSTs the resulting gesture here as normalised
      // [0,1] coordinates; the face long-polls GET and replays each gesture
      // into the headless Chrome via the CDP Input domain.
      if (verb === "input" && req.method === "POST") {
        const body = await readBody(req);
        let parsed: unknown;
        try {
          parsed = JSON.parse(body || "{}");
        } catch {
          json(res, 400, { ok: false, error: "invalid JSON body" });
          return;
        }
        // Accept either a bare event or { events: [...] }.
        const events: unknown[] = Array.isArray((parsed as any)?.events)
          ? (parsed as any).events
          : [parsed];
        cinemaStore.ensure(id, defaultCinemaUrl(id));
        let accepted = 0;
        for (const ev of events) {
          const norm = normalizeCinemaInput(ev);
          if (norm) {
            cinemaStore.enqueueInput(id, norm);
            accepted++;
          }
        }
        json(res, 200, { ok: true, accepted });
        return;
      }

      if (verb === "input" && req.method === "GET") {
        // ?wait=<ms> opts into long-poll; default 0 = immediate drain.
        const waitRaw = parseInt(url.searchParams.get("wait") || "0", 10) || 0;
        const waitMs = Math.max(0, Math.min(5000, waitRaw));
        const events = await cinemaStore.waitForInput(id, waitMs);
        json(res, 200, { events });
        return;
      }
    }
  }

  // ─── Window capture control API ────────────────────────────────────────
  // The face polls GET /list to know what to capture; everything else is
  // write-only from the plugin / in-game command.
  //
  //   GET  /api/window-capture/list            active capture configs (face polls)
  //   POST /api/window-capture/start           {cinemaId, filter, fps?, quality?}
  //   POST /api/window-capture/stop            {cinemaId}
  if (url.pathname.startsWith("/api/window-capture/")) {
    const verb = url.pathname.replace("/api/window-capture/", "");

    if (verb === "list" && req.method === "GET") {
      json(res, 200, { captures: windowCaptureStore.list() });
      return;
    }

    // /api/window-capture/windows — one-shot window enumeration via the binary.
    // The plugin calls this to build the clickable selection list.
    if (verb === "windows" && req.method === "GET") {
      const windows = listWindows();
      json(res, 200, { windows });
      return;
    }

    if (verb === "start" && req.method === "POST") {
      const raw = await readJson(req);
      const cinemaId = typeof raw?.cinemaId === "string" ? raw.cinemaId.trim() : "";
      if (!cinemaId || !/^[a-zA-Z0-9_-]+$/.test(cinemaId) || !raw) {
        json(res, 400, { ok: false, error: "invalid cinemaId" });
        return;
      }
      const body = raw;
      // Build the filter object from the request.
      let filter: CaptureFilter;
      if (typeof body.windowId === "number") {
        filter = { kind: "window", windowId: body.windowId };
      } else if (typeof body.appName === "string" && body.appName.trim()) {
        filter = { kind: "app", appName: (body.appName as string).trim() };
      } else {
        filter = { kind: "screen", screenIndex: typeof body.screenIndex === "number" ? body.screenIndex : 0 };
      }
      const fps     = typeof body.fps     === "number" ? body.fps     : 60;
      const quality = typeof body.quality === "number" ? body.quality : 0.70;
      const entry = windowCaptureStore.start(cinemaId, filter, fps, quality);
      // Ensure the cinema channel exists so the client mod can poll it.
      cinemaStore.ensure(cinemaId, defaultCinemaUrl(cinemaId));
      console.log(`[window-capture] started cinema "${cinemaId}" filter=${JSON.stringify(filter)} fps=${entry.fps}`);
      json(res, 200, { ok: true, entry });
      return;
    }

    if (verb === "stop" && req.method === "POST") {
      const body = await readJson(req);
      const cinemaId = typeof body?.cinemaId === "string" ? body.cinemaId.trim() : "";
      const stopped = windowCaptureStore.stop(cinemaId);
      console.log(`[window-capture] stopped cinema "${cinemaId}" (was active: ${stopped})`);
      json(res, 200, { ok: true, stopped });
      return;
    }

    json(res, 404, { error: "unknown window-capture verb" });
    return;
  }

  json(res, 404, { error: "not found" });
}

/** Read the full request body as JSON, returning null on parse error or oversized body. */
async function readJson(req: IncomingMessage): Promise<Record<string, unknown> | null> {
  return new Promise((resolve) => {
    const chunks: Buffer[] = [];
    let total = 0;
    req.on("data", (c: Buffer) => { total += c.length; if (total < 65536) chunks.push(c); });
    req.on("end", () => {
      try { resolve(JSON.parse(Buffer.concat(chunks).toString())); }
      catch { resolve(null); }
    });
    req.on("error", () => resolve(null));
  });
}

function json(res: ServerResponse, status: number, body: unknown): void {
  res.writeHead(status, { "Content-Type": "application/json; charset=utf-8" });
  res.end(JSON.stringify(body));
}

/**
 * Validate + clamp a raw cinema input event from the plugin. Returns null
 * for anything malformed so a bad gesture can't wedge the face's dispatch
 * loop. Coordinates are clamped to [0,1] (the wall surface).
 */
function normalizeCinemaInput(raw: unknown): CinemaInputEvent | null {
  if (!raw || typeof raw !== "object") return null;
  const o = raw as Record<string, unknown>;
  const clamp01 = (v: unknown): number => {
    const n = Number(v);
    if (!Number.isFinite(n)) return NaN;
    return Math.max(0, Math.min(1, n));
  };
  switch (o.type) {
    case "click": {
      const nx = clamp01(o.nx), ny = clamp01(o.ny);
      if (Number.isNaN(nx) || Number.isNaN(ny)) return null;
      const button = o.button === "right" ? "right" : "left";
      return { type: "click", nx, ny, button };
    }
    case "move": {
      const nx = clamp01(o.nx), ny = clamp01(o.ny);
      if (Number.isNaN(nx) || Number.isNaN(ny)) return null;
      return { type: "move", nx, ny };
    }
    case "scroll": {
      const nx = clamp01(o.nx), ny = clamp01(o.ny);
      if (Number.isNaN(nx) || Number.isNaN(ny)) return null;
      const dy = Number(o.dy);
      const dx = Number(o.dx);
      return {
        type: "scroll",
        nx,
        ny,
        dy: Number.isFinite(dy) ? dy : 0,
        dx: Number.isFinite(dx) ? dx : 0,
      };
    }
    case "text": {
      const text = String(o.text ?? "");
      if (!text) return null;
      return { type: "text", text: text.slice(0, 2000) };
    }
    case "key": {
      const key = String(o.key ?? "");
      if (!key) return null;
      return { type: "key", key: key.slice(0, 32) };
    }
    default:
      return null;
  }
}

// Break a long string into <= maxLen chunks, preferring sentence ends
// (. ! ? 。!?), then whitespace, then a hard cut. Handles non-ASCII fine
// because we're slicing by character index — surrogate pairs are rare in
// transcript text and the worst case is one chopped character at a
// boundary. Returns at least one chunk (the original string) when its
// length is already <= maxLen.
function splitForChat(text: string, maxLen: number): string[] {
  const t = text.trim();
  if (t.length <= maxLen) return [t];
  const out: string[] = [];
  let rest = t;
  // Match sentence terminators followed by optional whitespace, including
  // Chinese/Japanese fullwidth punctuation since the user may switch
  // languages with Omo.
  const sentenceEnd = /[.!?。！？](\s|$)/g;
  while (rest.length > maxLen) {
    const slice = rest.slice(0, maxLen);
    let cut = -1;
    sentenceEnd.lastIndex = 0;
    let m: RegExpExecArray | null;
    while ((m = sentenceEnd.exec(slice)) !== null) cut = m.index + 1;
    if (cut < Math.floor(maxLen * 0.5)) {
      // No sentence end in the comfortable range — fall back to last
      // whitespace before maxLen so we don't chop mid-word.
      const ws = slice.lastIndexOf(" ");
      cut = ws > Math.floor(maxLen * 0.5) ? ws : maxLen;
    }
    out.push(rest.slice(0, cut).trim());
    rest = rest.slice(cut).trim();
  }
  if (rest.length) out.push(rest);
  return out;
}

function readBody(req: IncomingMessage): Promise<string> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    req.on("data", (c) => chunks.push(c as Buffer));
    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
    req.on("error", reject);
  });
}
