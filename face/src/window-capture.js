// Window-capture bridge — manages the WindowCapture Swift binary lifecycle.
//
// For each active capture (registered via the runtime's window-capture store),
// this module:
//   1. Spawns the binary: WindowCapture stream --cinema-id <id> [--window-id|--app|--screen] ...
//   2. Bridges input: long-polls GET /api/cinema/:id/input and writes events to
//      the binary's stdin so CGEventPost replays them into the native app.
//   3. Restarts on crash with exponential backoff (same pattern as headless-cinema.js).
//
// The binary posts JPEG frames directly to the runtime's /api/cinema/:id/frame
// endpoint — the Node side is never on the hot path.

import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const BINARY = resolve(__dirname, '../capture/WindowCapture');
const POLL_INTERVAL_MS = 2000;   // how often to poll runtime for capture requests
const INPUT_WAIT_MS    = 2000;   // long-poll timeout for cinema input
const RELAUNCH_BASE_MS = 1000;
const RELAUNCH_MAX_MS  = 30_000;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * Start the window-capture watchdog.
 * Polls the runtime for active capture requests and manages binary processes.
 *
 * @param {object} opts
 * @param {string} opts.runtimeBase   e.g. "http://127.0.0.1:8766"
 * @param {Function} opts.log
 * @param {Function} opts.warn
 */
export async function startWindowCapture({ runtimeBase, log, warn }) {
  if (!existsSync(BINARY)) {
    warn(`window-capture disabled — binary not found at ${BINARY}`);
    warn(`  run: face/capture/build.sh   to compile it (needs Xcode)`);
    return { stop: async () => {} };
  }

  // cinemaId → { proc, inputLoop, restartTimer, stopped }
  const captures = new Map();
  let stopped = false;
  let pollTimer = null;

  // ── Fetch active capture requests from the runtime ───────────────────────

  async function fetchPending() {
    try {
      const r = await fetch(`${runtimeBase}/api/window-capture/list`);
      if (!r.ok) return [];
      const { captures: list } = await r.json();
      return Array.isArray(list) ? list : [];
    } catch { return []; }
  }

  // ── Reconcile: start missing, stop removed ────────────────────────────────

  async function reconcile() {
    const pending = await fetchPending();
    const wantedIds = new Set(pending.map((c) => c.cinemaId));

    // Start captures that appeared in the pending list.
    for (const cfg of pending) {
      if (!captures.has(cfg.cinemaId)) {
        startCapture(cfg);
      }
    }
    // Stop captures that disappeared from the pending list.
    for (const [id, entry] of captures) {
      if (!wantedIds.has(id)) {
        stopCapture(id, entry);
      }
    }
  }

  // ── Start one capture ─────────────────────────────────────────────────────

  function startCapture(cfg) {
    const { cinemaId, filter, fps = 60, quality = 0.70 } = cfg;
    // Unpack the filter union — runtime stores {kind, windowId|appName|screenIndex}.
    const windowId    = filter?.kind === 'window' ? filter.windowId    : undefined;
    const appName     = filter?.kind === 'app'    ? filter.appName     : undefined;
    const screenIndex = filter?.kind === 'screen' ? filter.screenIndex : undefined;
    const entry = { proc: null, inputLoop: null, restartTimer: null, stopped: false, delay: RELAUNCH_BASE_MS };
    captures.set(cinemaId, entry);
    log(`window-capture: starting cinema "${cinemaId}" filter=${JSON.stringify(filter)}`);
    launchBinary(entry, { cinemaId, windowId, appName, screenIndex, fps, quality, runtimeBase });
  }

  function stopCapture(id, entry) {
    entry.stopped = true;
    if (entry.restartTimer) clearTimeout(entry.restartTimer);
    if (entry.inputLoop) { entry.inputLoop.abort = true; }
    if (entry.proc) {
      try { entry.proc.kill('SIGTERM'); } catch {}
    }
    captures.delete(id);
    log(`window-capture: stopped cinema "${id}"`);
  }

  // ── Binary lifecycle ──────────────────────────────────────────────────────

  function launchBinary(entry, opts) {
    if (entry.stopped) return;

    const { cinemaId, windowId, appName, screenIndex, fps, quality, runtimeBase } = opts;
    const endpoint = `${runtimeBase}/api/cinema/${cinemaId}/frame`;

    const binaryArgs = ['stream', '--cinema-id', cinemaId, '--fps', String(fps),
                        '--quality', String(quality), '--endpoint', endpoint];
    if (windowId != null)         { binaryArgs.push('--window-id', String(windowId)); }
    else if (appName)             { binaryArgs.push('--app', appName); }
    else if (screenIndex != null) { binaryArgs.push('--screen', String(screenIndex)); }

    const proc = spawn(BINARY, binaryArgs, { stdio: ['pipe', 'pipe', 'pipe'] });
    entry.proc = proc;

    proc.stdout.on('data', (data) => {
      for (const line of data.toString().split('\n').filter(Boolean)) {
        if (line.startsWith('STATUS:ready')) {
          entry.delay = RELAUNCH_BASE_MS; // reset backoff on successful start
          log(`window-capture: cinema "${cinemaId}" streaming`);
          // Start the input bridge once the binary is ready.
          entry.inputLoop = startInputBridge(entry, proc, cinemaId, runtimeBase);
        } else if (line.startsWith('STATUS:fatal:')) {
          warn(`window-capture [${cinemaId}]: ${line.slice(13)}`);
        } else if (line.startsWith('STATUS:error:')) {
          warn(`window-capture [${cinemaId}]: ${line.slice(13)}`);
        }
      }
    });

    proc.stderr.on('data', (data) => {
      warn(`window-capture [${cinemaId}] err: ${data.toString().trim()}`);
    });

    proc.on('exit', (code, sig) => {
      if (entry.stopped) return;
      if (entry.inputLoop) { entry.inputLoop.abort = true; }
      entry.proc = null;
      const reason = sig ? `signal ${sig}` : `exit ${code}`;
      warn(`window-capture [${cinemaId}] exited (${reason}) — restarting in ${entry.delay}ms`);
      entry.restartTimer = setTimeout(() => {
        entry.delay = Math.min(entry.delay * 2, RELAUNCH_MAX_MS);
        launchBinary(entry, opts);
      }, entry.delay);
    });
  }

  // ── Input bridge ──────────────────────────────────────────────────────────
  // Long-polls GET /api/cinema/:id/input (same endpoint the face's CDP path uses)
  // and writes each event to the binary's stdin as newline-delimited JSON.

  function startInputBridge(entry, proc, cinemaId, runtimeBase) {
    const ctrl = { abort: false };
    const headers = { 'Content-Type': 'application/json' };

    (async () => {
      while (!ctrl.abort && !entry.stopped) {
        try {
          const url = `${runtimeBase}/api/cinema/${cinemaId}/input?wait=${INPUT_WAIT_MS}`;
          const r = await fetch(url, { headers, signal: AbortSignal.timeout(INPUT_WAIT_MS + 3000) });
          if (!r.ok) { await sleep(500); continue; }
          const { events } = await r.json();
          if (!Array.isArray(events) || events.length === 0) continue;
          if (ctrl.abort || entry.stopped || !proc.stdin.writable) break;
          for (const ev of events) {
            try { proc.stdin.write(JSON.stringify(ev) + '\n'); } catch {}
          }
        } catch { await sleep(500); }
      }
    })();

    return ctrl;
  }

  // ── Poll loop ─────────────────────────────────────────────────────────────

  async function pollLoop() {
    while (!stopped) {
      await reconcile();
      await sleep(POLL_INTERVAL_MS);
    }
  }

  // Give the runtime a moment to fully start before first reconcile.
  pollTimer = setTimeout(() => pollLoop(), 3000);
  log('window-capture: watchdog started');

  return {
    stop: async () => {
      stopped = true;
      if (pollTimer) clearTimeout(pollTimer);
      for (const [id, entry] of captures) stopCapture(id, entry);
    },
  };
}

/**
 * One-shot: list available windows by running `WindowCapture list`.
 * Returns an array of window/screen descriptors, or [] if the binary is unavailable.
 */
export async function listWindows() {
  if (!existsSync(BINARY)) return [];
  return new Promise((resolve) => {
    let out = '';
    const proc = spawn(BINARY, ['list'], { stdio: ['ignore', 'pipe', 'pipe'] });
    proc.stdout.on('data', (d) => { out += d; });
    proc.on('exit', () => {
      try { resolve(JSON.parse(out)); } catch { resolve([]); }
    });
    proc.on('error', () => resolve([]));
    // Kill if it hangs — list should be near-instant.
    setTimeout(() => { try { proc.kill(); } catch {} }, 8000);
  });
}
