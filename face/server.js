// omo-mc face — slim hologram + voice surface on http://localhost:8080.
//
// Responsibilities:
//   1. Serve the holographic cylinder page (public/holo_cylinder.html) and
//      its supporting JS/CSS.
//   2. Mint Gemini Live ephemeral tokens at POST /session so the browser can
//      connect directly to Gemini without seeing GEMINI_API_KEY.
//   3. Execute tool calls from the cylinder at POST /tool. Right now there
//      is exactly one tool that matters — `teleport` — which forwards into
//      the omo-mc runtime over HTTP, which sends a teleport_player message
//      down the Paper bridge.
//
// This is intentionally tiny. The big omo server.js carries dozens of
// integrations (stripe, meta, gmail, drive, github, …); none of those make
// sense inside Minecraft, so they're not ported.

import 'dotenv/config';
import express from 'express';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { readFileSync } from 'node:fs';

import { mintSession, SESSION_META } from './src/session.js';
import { runTool } from './src/tools.js';
import { startHeadlessOverlay } from './src/headless-overlay.js';
import { startHeadlessCinema } from './src/headless-cinema.js';
import { startWindowCapture } from './src/window-capture.js';

const __dirname = dirname(fileURLToPath(import.meta.url));
const PUBLIC_DIR = join(__dirname, 'public');
const PORT = Number(process.env.OMO_FACE_PORT ?? 8080);

// ─── Logging helpers ───────────────────────────────────────────────────
// Tagged + colorised one-liners. Three levels: info / warn / err. Every
// log line gets an HH:MM:SS prefix so you can correlate timestamps across
// face.log / runtime.log / mc.log when something misbehaves.
const C = { dim: '\x1b[2m', red: '\x1b[31m', grn: '\x1b[32m', ylw: '\x1b[33m', cyn: '\x1b[36m', rst: '\x1b[0m' };
const ts = () => new Date().toISOString().slice(11, 19);
const log = (...a) => console.log (`${C.dim}${ts()}${C.rst} ${C.cyn}[face]${C.rst}`, ...a);
const warn = (...a) => console.warn(`${C.dim}${ts()}${C.rst} ${C.ylw}[face]${C.rst}`, ...a);
const err  = (...a) => console.error(`${C.dim}${ts()}${C.rst} ${C.red}[face]${C.rst}`, ...a);

// Format a tool's args as a single short line — long strings get
// truncated, large objects collapse to "{k:N}" so the log stays scannable.
function previewArgs(args) {
  if (args == null) return '{}';
  if (typeof args !== 'object') return String(args);
  const out = [];
  for (const [k, v] of Object.entries(args)) {
    if (v == null) continue;
    let s;
    if (typeof v === 'string') s = v.length > 60 ? `"${v.slice(0, 57)}…"` : `"${v}"`;
    else if (typeof v === 'object') s = `{${Object.keys(v).length}k}`;
    else s = String(v);
    out.push(`${k}=${s}`);
  }
  return out.length ? out.join(' ') : '{}';
}

const app = express();
app.use(express.json({ limit: '256kb' }));

if (!process.env.GEMINI_API_KEY) {
  warn('GEMINI_API_KEY not set — /session will 500 until it is.');
}

// ─── HTTP access log ───────────────────────────────────────────────────
// Surfaces every request that lands on the face with method, path, status,
// duration. Critical for catching the "module fetch failed" class of bug
// — when avatar.js can't load it's usually because one of its sibling
// imports 404'd silently, and without this log you'd never see it.
//
// We dim 200s for static assets so the genuine signal (non-200, /tool,
// /session) jumps off the page. The hot path (/log) is excluded — that's
// already its own log surface.
app.use((req, res, next) => {
  if (req.path === '/log') { next(); return; }
  // /api/omo-frame fires ~10×/s from the headless overlay; access-logging
  // each one drowns out everything else. Errors still surface via the
  // warn() inside the proxy handler.
  if (req.path === '/api/omo-frame') { next(); return; }
  const t0 = Date.now();
  res.on('finish', () => {
    // Stale browser tabs poll the full-omo-only endpoints on a reconnect
    // loop. We 404 them by design (see OPTIONAL_PATHS below); don't log.
    if (res.locals._intentional404) return;
    const ms = Date.now() - t0;
    const code = res.statusCode;
    const isAsset = req.method === 'GET' && /\.(js|css|html|map|svg|png|jpg|webp|ico)$/i.test(req.path);
    let codeStr;
    if (code >= 500)      codeStr = `${C.red}${code}${C.rst}`;
    else if (code >= 400) codeStr = `${C.ylw}${code}${C.rst}`;
    else if (isAsset)     codeStr = `${C.dim}${code}${C.rst}`;
    else                  codeStr = `${C.grn}${code}${C.rst}`;
    const line = `${req.method} ${req.path} → ${codeStr} · ${ms}ms`;
    if (code === 404) warn(line);
    else if (code >= 500) err(line);
    else if (isAsset) console.log(`${C.dim}${ts()} [face]${C.rst} ${line}`);
    else log(line);
  });
  next();
});

// ─── Forward browser console output to the server log ──────────────────
// client-log.js batches console output + window errors and POSTs them here.
// We surface warn/error inline; routine console.log noise from the cylinder
// (mic ticks, heartbeat) is dropped unless ?verbose=1 is set, otherwise
// the face log fills up faster than you can read it.
const CLIENT_VERBOSE = process.env.OMO_FACE_CLIENT_VERBOSE === '1';
app.post('/log', express.json({ type: '*/*', limit: '256kb' }), (req, res) => {
  const entries = Array.isArray(req.body?.logs) ? req.body.logs : [];
  for (const e of entries) {
    const level = ['log', 'info', 'warn', 'error', 'debug'].includes(e?.level) ? e.level : 'log';
    if (!CLIENT_VERBOSE && (level === 'log' || level === 'debug' || level === 'info')) continue;
    const args = Array.isArray(e.args) ? e.args : [];
    // Drop the noisy [sprites]/[i18n] ws-error spam from stale cached
    // clients — the endpoint isn't shipped in slim face/, retry storms
    // are expected, the Event object payload is never useful.
    const first = typeof args[0] === 'string' ? args[0] : '';
    if (/^\[(sprites|i18n)\].*ws/i.test(first)) continue;
    const tag = `[client ${e.page || '-'}]`;
    if (level === 'error') err(tag, ...args);
    else if (level === 'warn') warn(tag, ...args);
    else log(tag, ...args);
  }
  res.status(204).end();
});

// ─── Hologram page routes ──────────────────────────────────────────────
// `/` serves the minimal face — just the 3D Omo body + voice + tool calls.
// The full omo cylinder (with polar warp, agent sprites, chart layer, etc.)
// is preserved at `/cylinder` for the rare case you actually want it (e.g.
// physical glass projector). For omo-mc the minimal page is what we want.
function sendFile(name) {
  return (_req, res) => {
    try {
      const html = readFileSync(join(PUBLIC_DIR, name), 'utf8');
      res.setHeader('Content-Type', 'text/html; charset=utf-8');
      res.send(html);
    } catch (e) {
      res.status(500).send(`face: failed to read ${name} — ${e.message}`);
    }
  };
}
app.get('/',         sendFile('index.html'));
app.get('/holo',     sendFile('index.html'));
app.get('/cylinder', sendFile('holo_cylinder.html'));
// Hidden capture page. Renders a 256×256 transparent close-up of just
// Omo's head and POSTs each PNG frame to the runtime so the in-game
// Fabric HUD can blit it. No voice loop, no UI — leave the tab open in
// any browser. See overlay-app.js for the full story.
app.get('/overlay',  sendFile('overlay.html'));

// Static assets (avatar.js, face-app.js, pcm-worklet.js, …). We disable
// caching because cached module graphs from a previous version keep alive
// reconnect loops to /omo/ws etc. that no longer exist in slim face/.
app.use(express.static(PUBLIC_DIR, {
  fallthrough: true,
  index: false,
  setHeaders: (res) => res.setHeader('Cache-Control', 'no-cache'),
}));

// ─── Gemini Live ephemeral-token mint ──────────────────────────────────
// Returns { token, model, voice, setupConfig }. The cylinder uses these to
// call ai.live.connect() directly. Token is single-use, expires in 30 min.
app.post('/session', async (req, res) => {
  const t0 = Date.now();
  try {
    const voice = (req.query?.voice || '').toString().trim() || undefined;
    const session = await mintSession({ voice });
    log(`session ${C.grn}✓${C.rst} model=${session.model} voice=${session.voice} · ${Date.now() - t0}ms`);
    res.json(session);
  } catch (e) {
    const status = e?.status || 500;
    err(`session ✗ ${status} ${e?.message || e}`);
    res.status(status).json({ error: e?.message || String(e) });
  }
});

// Cylinder also probes /voice/session for a non-Gemini fallback path. We
// don't ship local STT/TTS in face/, so just report unavailable — the
// cylinder will silently keep going down the Gemini Live path.
app.post('/voice/session', (_req, res) => {
  res.status(503).json({ available: false, reason: 'local voice not configured in face/' });
});

// ─── Tool dispatch ─────────────────────────────────────────────────────
// Both the Gemini Live tool-call handler and the text-overlay path POST
// here as { name, arguments }. We return whatever the tool runner returns
// — the cylinder forwards `result` back to Gemini via sendToolResponse.
app.post('/tool', async (req, res) => {
  const { name, arguments: args } = req.body || {};
  if (!name) {
    warn('tool ✗ called without name');
    res.status(400).json({ error: 'name required' });
    return;
  }
  const t0 = Date.now();
  log(`tool → ${C.cyn}${name}${C.rst} ${C.dim}${previewArgs(args)}${C.rst}`);
  const result = await runTool(name, args || {});
  const ms = Date.now() - t0;
  if (result?.ok === false) {
    warn(`tool ← ${name} ${C.red}✗${C.rst} ${result.error || 'failed'} · ${ms}ms`);
  } else {
    // Pick a short summary for the happy path. Teleport gets its resolved
    // room called out; everything else just says "ok".
    const tail = result?.room ? `→ ${result.room}` : 'ok';
    log(`tool ← ${name} ${C.grn}✓${C.rst} ${tail} · ${ms}ms`);
  }
  res.json({ result });
});

// ─── Face state push (browser → face/ → runtime) ──────────────────────
// face-app.js POSTs here when the voice loop transitions state (mic open,
// model audio start/stop, tool dispatch). We forward to the runtime's
// /api/face-state so the in-game Fabric overlay (top-left of the MC
// window) animates in sync. Best-effort: any failure is swallowed so a
// missing runtime never breaks the voice loop.
const RUNTIME_HTTP_BASE =
  process.env.AGENTCRAFT_RUNTIME_HTTP ?? 'http://127.0.0.1:8766';
let lastFaceStateWarnMs = 0;
app.post('/face-state', async (req, res) => {
  const { mode, room, transcript } = req.body || {};
  // Reply to the browser immediately — we don't want the runtime round-trip
  // on the voice-loop hot path.
  res.status(204).end();
  const body = {};
  if (typeof mode === 'string') body.mode = mode;
  if (typeof room === 'string') body.room = room;
  if (typeof transcript === 'string') body.transcript = transcript;
  if (Object.keys(body).length === 0) return;
  try {
    await fetch(`${RUNTIME_HTTP_BASE}/api/face-state`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
  } catch (e) {
    // Throttle the warning: don't spam the log when the runtime is just
    // not running — but do surface it once a minute so a misconfig is
    // visible.
    const now = Date.now();
    if (now - lastFaceStateWarnMs > 60_000) {
      lastFaceStateWarnMs = now;
      const msg = e?.code === 'ECONNREFUSED'
        ? 'runtime not reachable'
        : (e?.message || String(e));
      warn(`face-state push ✗ ${msg} (overlay won't react)`);
    }
  }
});

// ─── Overlay PNG frame proxy (browser → face/ → runtime) ──────────────
// The overlay capture page POSTs each 256×256 PNG here so it stays on the
// same origin and dodges browser CORS + Private Network Access (which
// silently blocks cross-port fetches inside headless Chrome). We forward
// the raw bytes to the runtime's /api/omo-frame in-process.
let lastFramePushWarnMs = 0;
app.post(
  '/api/omo-frame',
  express.raw({ type: 'image/png', limit: '512kb' }),
  async (req, res) => {
    res.status(204).end();
    const w = req.query.w || '256';
    const h = req.query.h || '256';
    try {
      await fetch(
        `${RUNTIME_HTTP_BASE}/api/omo-frame?w=${encodeURIComponent(String(w))}&h=${encodeURIComponent(String(h))}`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'image/png' },
          body: req.body,
        },
      );
    } catch (e) {
      const now = Date.now();
      if (now - lastFramePushWarnMs > 60_000) {
        lastFramePushWarnMs = now;
        const msg = e?.code === 'ECONNREFUSED'
          ? 'runtime not reachable'
          : (e?.message || String(e));
        warn(`omo-frame push ✗ ${msg} (in-game HUD will show static fallback)`);
      }
    }
  },
);

// ─── In-game voice trigger fan-out (SSE) ───────────────────────────────
// Lets the user press the "V" keybind in Minecraft to start/stop talking to
// Omo without alt-tabbing to this browser tab. The plumbing is:
//
//     MC client-mod keybind  →  POST runtime /api/voice-trigger
//                            →  POST face   /voice-control     (this server)
//                            →  SSE push    /events            (this server)
//                            →  browser face-app.js handler    (same code
//                                                              path as the
//                                                              mic button)
//
// We keep one Set of live SSE clients in memory. Any disconnect drops the
// client. The events themselves are tiny — one named event per trigger —
// so this is cheap and never backs up.
const sseClients = new Set();

app.get('/events', (req, res) => {
  res.setHeader('Content-Type', 'text/event-stream');
  res.setHeader('Cache-Control', 'no-cache, no-transform');
  res.setHeader('Connection', 'keep-alive');
  res.setHeader('X-Accel-Buffering', 'no');
  // Flush headers so the browser EventSource opens immediately rather than
  // waiting for the first bodied write.
  res.flushHeaders?.();
  // Tell the client to never auto-retry faster than 2s.
  res.write('retry: 2000\n\n');
  sseClients.add(res);
  const ping = setInterval(() => {
    try { res.write(': ping\n\n'); } catch { /* will be cleaned up below */ }
  }, 25_000);
  req.on('close', () => {
    clearInterval(ping);
    sseClients.delete(res);
    try { res.end(); } catch {}
  });
});

function broadcastSse(event, data) {
  const payload = `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
  for (const res of sseClients) {
    try { res.write(payload); } catch { sseClients.delete(res); }
  }
}

app.post('/voice-control', (req, res) => {
  const action = String(req.body?.action || 'toggle').toLowerCase();
  if (!['toggle', 'start', 'stop'].includes(action)) {
    res.status(400).json({ ok: false, error: 'action must be toggle|start|stop' });
    return;
  }
  log(`voice-control → ${C.cyn}${action}${C.rst} fan-out=${sseClients.size}`);
  broadcastSse('voice-trigger', { action });
  res.json({ ok: true, action, listeners: sseClients.size });
});

// Transcript + progress proxies. face-app.js POSTs to these from inside
// the browser tab; we forward straight to the omo-mc runtime which fans
// it out as a chat_message WS frame to the Paper plugin. We don't render
// anything ourselves — face/ is just a relay so the browser tab never
// needs to talk to the runtime directly (CORS-free same-origin POSTs).
//
// Fire-and-forget: if the runtime is down the voice loop must not stall,
// so we never await the upstream response beyond a 1 s timeout.
async function relayToRuntime(path, body) {
  try {
    await fetch(`${RUNTIME_HTTP_BASE}${path}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(1000),
    });
    return true;
  } catch {
    return false;
  }
}

app.post('/voice-transcript', async (req, res) => {
  const role = String(req.body?.role || '').toLowerCase();
  const text = String(req.body?.text || '').trim();
  if (role !== 'user' && role !== 'omo' && role !== 'system') {
    res.status(400).json({ ok: false, error: 'role must be user|omo|system' });
    return;
  }
  if (!text) { res.json({ ok: true, skipped: 'empty' }); return; }
  const ok = await relayToRuntime('/api/voice-transcript', { role, text });
  res.json({ ok });
});

app.post('/voice-progress', async (req, res) => {
  const stage = String(req.body?.stage || '');
  const text = String(req.body?.text || '').trim();
  const etaMs = Number(req.body?.etaMs || 0);
  const elapsedMs = Number(req.body?.elapsedMs || 0);
  if (!text) { res.json({ ok: true, skipped: 'empty' }); return; }
  const ok = await relayToRuntime('/api/voice-progress', {
    stage, text, etaMs, elapsedMs,
  });
  res.json({ ok });
});

// The cylinder polls a couple of optional endpoints (preview, momo-ingest,
// hq, squad, agents-grid, etc.) that exist only in the full omo. Reply 404
// quietly so we don't fill the server log with red errors.
const OPTIONAL_PATHS = [
  '/omo/api', '/omo/ws', '/omo/preview', '/hq', '/squad', '/agents-grid',
  '/preview', '/voices', '/auth', '/raw',
];
app.use((req, res, next) => {
  if (OPTIONAL_PATHS.some((p) => req.path.startsWith(p))) {
    // Mark this 404 as "expected" so the access log dims it instead of
    // shouting in yellow. These endpoints only exist in full-fat omo;
    // the cylinder probes them speculatively.
    res.locals._intentional404 = true;
    res.status(404).end();
    return;
  }
  next();
});

// Bind loopback only. Every caller (the hologram browser, the headless overlay/
// cinema Chrome, the runtime's voice-trigger forward) is on this box. Nothing
// remote should ever reach :8080 — this is what keeps the Gemini-token mint
// (/session) and /tool off the network.
app.listen(PORT, '127.0.0.1', async () => {
  log(`listening on ${C.grn}http://127.0.0.1:${PORT}${C.rst}`);
  log(`  model=${SESSION_META.model} voice=${SESSION_META.voice} lang=${SESSION_META.language}`);
  log(`  runtime bridge → ${process.env.AGENTCRAFT_RUNTIME_HTTP ?? 'http://127.0.0.1:8766'}`);
  if (!process.env.GEMINI_API_KEY) {
    warn('  ⚠ GEMINI_API_KEY missing — set it in face/.env to enable voice');
  }
  log(`  client console: ${CLIENT_VERBOSE ? 'verbose' : 'errors+warnings only'} (toggle with OMO_FACE_CLIENT_VERBOSE=1)`);

  // Auto-launch a headless Chrome that loads /overlay so the in-game HUD
  // always has fresh Omo frames flowing without the user keeping a browser
  // tab open. Opt-out: OMO_HEADLESS_OVERLAY=false in face/.env.
  const headlessEnabled = (process.env.OMO_HEADLESS_OVERLAY ?? 'true').toLowerCase() !== 'false';
  if (headlessEnabled) {
    const overlayUrl = `http://127.0.0.1:${PORT}/overlay`;
    try {
      await startHeadlessOverlay({ overlayUrl, log, warn });
    } catch (e) {
      warn(`headless overlay failed to start (${e?.message || e}) — open ${overlayUrl} manually`);
    }
  } else {
    log(`  headless overlay disabled via OMO_HEADLESS_OVERLAY=false — open /overlay manually to stream frames`);
  }

  // Auto-launch the cinema renderer — second headless Chrome that hosts
  // one page per in-game cinema, capturing screenshots at ~1 fps and
  // POSTing them to the runtime so the map-wall in Code Lab can blit
  // arbitrary live webpages. Opt-out: OMO_HEADLESS_CINEMA=false.
  const cinemaEnabled = (process.env.OMO_HEADLESS_CINEMA ?? 'true').toLowerCase() !== 'false';
  if (cinemaEnabled) {
    try {
      await startHeadlessCinema({ runtimeBase: RUNTIME_HTTP_BASE, log, warn });
    } catch (e) {
      warn(`headless cinema failed to start (${e?.message || e})`);
    }
  } else {
    log(`  headless cinema disabled via OMO_HEADLESS_CINEMA=false`);
  }

  // Window capture watchdog — mirrors any native macOS window into a cinema
  // channel at up to 60 fps using ScreenCaptureKit.  Requires the Swift binary
  // at face/capture/WindowCapture (built with face/capture/build.sh).
  // Opt-out: OMO_WINDOW_CAPTURE=false.
  const wincapEnabled = (process.env.OMO_WINDOW_CAPTURE ?? 'true').toLowerCase() !== 'false';
  if (wincapEnabled) {
    try {
      await startWindowCapture({ runtimeBase: RUNTIME_HTTP_BASE, log, warn });
    } catch (e) {
      warn(`window-capture failed to start (${e?.message || e})`);
    }
  } else {
    log(`  window-capture disabled via OMO_WINDOW_CAPTURE=false`);
  }
});
