// Headless cinema renderer — one Chromium page per cinema, loads the
// target URL, screenshots at ~1 fps, POSTs PNG to the runtime so the
// in-game map-wall can blit it.
//
// Cadence rationale: Bukkit caps how often a MapView refresh propagates
// to clients at roughly 1 packet/sec/map. Capturing at 30 fps would burn
// CPU for frames the wall will never show. We aim for 1 fps with a small
// jitter and let the wall pull the freshest one each tick.
//
// Per-cinema lifecycle:
//   - On launch, poll runtime GET /api/cinema/list at ~2s intervals.
//   - For every cinema id we don't have a page for: open one, navigate
//     to the current URL, start the capture loop.
//   - For cinemas whose `urlVersion` advanced: navigate the existing
//     page to the new URL and reset the capture loop.
//   - Page crashes: retry with exponential backoff (same shape as
//     headless-overlay.js).
//
// We share one browser instance with headless-overlay.js where possible
// to avoid two Chrome processes. Today we run our own browser because
// the overlay module doesn't expose its browser handle. A future
// consolidation is welcome but not required for MVP.

import { existsSync, mkdirSync, rmSync } from 'node:fs';
import { platform, homedir } from 'node:os';
import { join } from 'node:path';

const CAPTURE_PERIOD_MS = 400;    // fallback screenshot cadence (round-robins multi-wall foreground)
const POLL_REGISTRY_MS  = 2000;
const VIEWPORT_W = 1280;
const VIEWPORT_H = 800;
const RELAUNCH_BASE_MS = 1000;
const RELAUNCH_MAX_MS  = 30_000;

// CDP screencast: Chrome pushes a JPEG every time the page paints, up to
// the compositor frame rate. We cap our *uploads* to ~15 fps (the wall
// can't show more) but always ACK so Chrome keeps the stream alive.
const SCREENCAST_QUALITY    = 60;
const MIN_POST_INTERVAL_MS  = 33;     // ~30 fps upload — keeps the plugin's 20 Hz pull always fed
const FALLBACK_AFTER_MS     = 600;    // no screencast frame this long → bring-to-front + screenshot
const INPUT_POLL_WAIT_MS    = 500;    // long-poll window for /input drain

// CDP key descriptors for the non-printable keys the in-game keyboard can
// emit (printable text goes through Input.insertText). Anything not here is
// ignored rather than guessed.
const CDP_KEYS = {
  Enter:      { key: 'Enter',      code: 'Enter',      windowsVirtualKeyCode: 13, text: '\r' },
  Backspace:  { key: 'Backspace',  code: 'Backspace',  windowsVirtualKeyCode: 8 },
  Delete:     { key: 'Delete',     code: 'Delete',     windowsVirtualKeyCode: 46 },
  Tab:        { key: 'Tab',        code: 'Tab',        windowsVirtualKeyCode: 9 },
  Escape:     { key: 'Escape',     code: 'Escape',     windowsVirtualKeyCode: 27 },
  ArrowUp:    { key: 'ArrowUp',    code: 'ArrowUp',    windowsVirtualKeyCode: 38 },
  ArrowDown:  { key: 'ArrowDown',  code: 'ArrowDown',  windowsVirtualKeyCode: 40 },
  ArrowLeft:  { key: 'ArrowLeft',  code: 'ArrowLeft',  windowsVirtualKeyCode: 37 },
  ArrowRight: { key: 'ArrowRight', code: 'ArrowRight', windowsVirtualKeyCode: 39 },
  PageUp:     { key: 'PageUp',     code: 'PageUp',     windowsVirtualKeyCode: 33 },
  PageDown:   { key: 'PageDown',   code: 'PageDown',   windowsVirtualKeyCode: 34 },
  Home:       { key: 'Home',       code: 'Home',       windowsVirtualKeyCode: 36 },
  End:        { key: 'End',        code: 'End',        windowsVirtualKeyCode: 35 },
};

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// A Chrome that was force-killed (./agentcraft stop, crash, OS reboot) leaves
// a SingletonLock symlink in its profile dir. The next puppeteer.launch then
// dies with "Failed to create .../SingletonLock: File exists" and the cinema
// stays blank forever. Removing the stale lock files makes a fresh launch
// always win. They're recreated on every launch, so deleting them is safe.
function clearStaleSingletonLocks(profileDir) {
  for (const f of ['SingletonLock', 'SingletonSocket', 'SingletonCookie']) {
    try { rmSync(join(profileDir, f), { force: true }); } catch {}
  }
}

function resolveChromePath() {
  if (process.env.OMO_CHROME_BINARY && existsSync(process.env.OMO_CHROME_BINARY)) {
    return process.env.OMO_CHROME_BINARY;
  }
  const candidates = platform() === 'darwin'
    ? [
        '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
        '/Applications/Chromium.app/Contents/MacOS/Chromium',
        '/Applications/Google Chrome Canary.app/Contents/MacOS/Google Chrome Canary',
      ]
    : platform() === 'linux'
      ? [
          '/usr/bin/google-chrome',
          '/usr/bin/google-chrome-stable',
          '/usr/bin/chromium',
          '/usr/bin/chromium-browser',
        ]
      : platform() === 'win32'
        ? [
            'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
            'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
          ]
        : [];
  for (const c of candidates) if (existsSync(c)) return c;
  return null;
}

/**
 * Start the cinema capture watchdog.
 *
 * @param {object} opts
 * @param {string} opts.runtimeBase   absolute base URL of the runtime HTTP
 *                                    bridge (default http://127.0.0.1:8766).
 * @param {(msg: string, ...rest: unknown[]) => void} opts.log
 * @param {(msg: string, ...rest: unknown[]) => void} opts.warn
 * @returns {Promise<{stop: () => Promise<void>}>}
 */
export async function startHeadlessCinema({ runtimeBase, log, warn }) {
  let puppeteer;
  try {
    puppeteer = (await import('puppeteer-core')).default;
  } catch (e) {
    warn(`headless cinema disabled — puppeteer-core not installed (${e?.code || e?.message || e})`);
    return { stop: async () => {} };
  }
  const chromePath = resolveChromePath();
  if (!chromePath) {
    warn('headless cinema disabled — no system Chrome found (set OMO_CHROME_BINARY).');
    return { stop: async () => {} };
  }

  let browser = null;
  let stopped = false;
  let attemptDelay = RELAUNCH_BASE_MS;
  let relaunchTimer = null;
  // id → { page, captureTimer, urlVersion, navInFlight }
  const pages = new Map();
  // ids whose openPageFor() is in flight. Because openPageFor awaits
  // browser.newPage() *before* it records the entry in `pages`, two
  // overlapping registry polls would otherwise both see "no page for id" and
  // each open a duplicate Chrome tab — two captures fighting over one wall,
  // which reads in-game as the screen flickering or staying blank. This set
  // reserves the id synchronously so the second caller bails.
  const opening = new Set();
  let registryTimer = null;
  // Re-entrancy guard: pollRegistry is async and slower than its 2s interval
  // when many cinemas appear at once (each openPageFor opens + navigates a
  // page). Without this, the next tick fires a second poll that races the
  // first. One poll at a time; ticks that land mid-poll are dropped (the next
  // tick re-checks the registry anyway).
  let polling = false;

  const profileDir = join(homedir(), '.agentcraft', 'headless-chrome-cinema');
  try { mkdirSync(profileDir, { recursive: true }); } catch {}

  async function tearDownPages() {
    for (const [, entry] of pages) {
      if (entry.captureTimer) clearInterval(entry.captureTimer);
      if (entry.client) {
        try { await entry.client.send('Page.stopScreencast'); } catch {}
        try { await entry.client.detach(); } catch {}
      }
      try { await entry.page.close({ runBeforeUnload: false }); } catch {}
    }
    pages.clear();
    opening.clear();
  }
  async function teardown() {
    if (registryTimer) { clearInterval(registryTimer); registryTimer = null; }
    await tearDownPages();
    if (browser) { try { await browser.close(); } catch {} browser = null; }
  }

  function scheduleRelaunch(reason) {
    if (stopped) return;
    const delay = attemptDelay;
    attemptDelay = Math.min(RELAUNCH_MAX_MS, attemptDelay * 2);
    warn(`headless cinema: ${reason} — relaunch in ${delay}ms`);
    if (relaunchTimer) clearTimeout(relaunchTimer);
    relaunchTimer = setTimeout(() => { launch().catch(() => {}); }, delay);
  }

  async function postFrame(id, body, contentType) {
    try {
      await fetch(
        `${runtimeBase}/api/cinema/${encodeURIComponent(id)}/frame?w=${VIEWPORT_W}&h=${VIEWPORT_H}`,
        { method: 'POST', headers: { 'Content-Type': contentType }, body },
      );
    } catch (e) {
      // Quiet by design — runtime down is the same failure mode every
      // other face→runtime push handles silently. The registry poll
      // surfaces it once a minute via its own warn path.
    }
  }

  // Fallback path: a single PNG screenshot, used only when the CDP
  // screencast has gone quiet (page backgrounded behind another cinema,
  // a stalled compositor, etc.) so the wall never freezes for long.
  async function capture(id, entry) {
    const client = entry.client;
    if (!client) return;
    try {
      // Raw CDP captureScreenshot renders the page's OWN renderer on demand, so
      // it returns LIVE frames even when the tab is backgrounded behind another
      // cinema — so several walls (HQ's 3 + every wing) all stay live at once.
      // Crucially it does NOT touch the foreground, so the walls never "refresh"
      // — unlike page.screenshot()+bringToFront, which round-robins the active
      // tab and makes each page repaint as it's activated. Verified empirically:
      // captureScreenshot(default fromSurface) = background live; fromSurface:false
      // = background frozen; bringToFront = live but flickers. See git history.
      const { data } = await client.send('Page.captureScreenshot', {
        format: 'jpeg',
        quality: SCREENCAST_QUALITY,
      });
      if (!data) return;
      entry.lastFrameAt = Date.now();
      await postFrame(id, Buffer.from(data, 'base64'), 'image/jpeg');
    } catch (e) {
      // Page may be mid-navigation; skip this tick.
    }
  }

  // Start (or restart) the CDP screencast for an entry's page. Frames arrive
  // via 'Page.screencastFrame'; we upload at most ~15 fps and always ACK so
  // Chrome keeps streaming.
  async function startScreencast(id, entry) {
    const client = entry.client;
    if (!client) return;
    try {
      await client.send('Page.startScreencast', {
        format: 'jpeg',
        quality: SCREENCAST_QUALITY,
        maxWidth: VIEWPORT_W,
        maxHeight: VIEWPORT_H,
        everyNthFrame: 1,
      });
      entry.screencast = true;
      log(`cinema "${id}" screencast started`);
    } catch (e) {
      entry.screencast = false;
      warn(`cinema "${id}" screencast unavailable (${e?.message || e}) — using screenshot fallback`);
    }
  }

  async function navigateAndStart(id, url, entry) {
    entry.navInFlight = true;
    try {
      // 'load' would wait for every image and is too slow on heavy pages;
      // 'domcontentloaded' is enough for the first screenshot to be
      // meaningful, and subsequent ticks pick up late-loaded content.
      await entry.page.goto(url, { waitUntil: 'domcontentloaded', timeout: 15_000 });
      log(`cinema "${id}" navigated → ${url}`);
    } catch (e) {
      warn(`cinema "${id}" navigation failed (${e?.message || e}) — will keep showing whatever loaded`);
    } finally {
      entry.navInFlight = false;
    }
    // Fallback timer: only screenshots when the screencast has been silent
    // for a while (so a backgrounded/stalled page still shows *something*).
    // When screencast is healthy this fires and immediately returns.
    if (entry.captureTimer) clearInterval(entry.captureTimer);
    entry.captureTimer = setInterval(() => {
      if (entry.navInFlight) return;
      const quiet = Date.now() - (entry.lastFrameAt || 0) > FALLBACK_AFTER_MS;
      if (quiet) capture(id, entry).catch(() => {});
    }, CAPTURE_PERIOD_MS);
  }

  async function openPageFor(id, url, urlVersion) {
    if (!browser) return;
    // Belt-and-suspenders against a duplicate open for the same id: bail if a
    // page already exists or one is mid-open. The reservation is released once
    // the entry lands in `pages` (success) or on the error paths (so a failed
    // open can be retried by the next poll).
    if (pages.has(id) || opening.has(id)) return;
    opening.add(id);
    let page;
    try {
      page = await browser.newPage();
      await page.setViewport({ width: VIEWPORT_W, height: VIEWPORT_H, deviceScaleFactor: 1 });
    } catch (e) {
      warn(`cinema "${id}" failed to open page (${e?.message || e})`);
      opening.delete(id);
      return;
    }
    page.on('pageerror', (e) => warn(`cinema "${id}" page JS error: ${e?.message || e}`));
    page.on('console', (msg) => {
      if (msg.type() === 'error') warn(`cinema "${id}" console.error: ${msg.text().slice(0, 240)}`);
    });
    const entry = {
      page,
      client: null,
      captureTimer: null,
      urlVersion,
      navInFlight: false,
      screencast: false,
      lastFrameAt: 0,
      lastPostAt: 0,
      vw: VIEWPORT_W,
      vh: VIEWPORT_H,
    };
    pages.set(id, entry);

    // Raw CDP session: the source of both the live screencast and the
    // synthetic mouse/keyboard input we replay from in-game gestures.
    try {
      entry.client = await page.createCDPSession();
      entry.client.on('Page.screencastFrame', async (e) => {
        // Always ACK first so Chrome never stalls the stream waiting on us.
        const sid = e?.sessionId;
        const now = Date.now();
        entry.lastFrameAt = now;
        let posted = false;
        if (now - entry.lastPostAt >= MIN_POST_INTERVAL_MS) {
          entry.lastPostAt = now;
          posted = true;
        }
        try {
          if (posted && e?.data) {
            await postFrame(id, Buffer.from(e.data, 'base64'), 'image/jpeg');
          }
        } finally {
          try { if (sid != null) await entry.client.send('Page.screencastFrameAck', { sessionId: sid }); } catch {}
        }
      });
      await startScreencast(id, entry);
    } catch (e) {
      warn(`cinema "${id}" CDP session failed (${e?.message || e}) — screenshot fallback only`);
    }

    // Entry is now in `pages`; the id is reserved by the map itself.
    opening.delete(id);

    await navigateAndStart(id, url, entry);
    drainInputLoop(id, entry).catch(() => {});
  }

  // ── Input replay ─────────────────────────────────────────────────────
  // Long-poll the runtime for queued in-game gestures and dispatch each one
  // into the page via the CDP Input domain. One loop per page; it ends when
  // the page is torn down or replaced.
  async function dispatchKey(client, name) {
    const k = CDP_KEYS[name];
    if (!k) return;
    await client.send('Input.dispatchKeyEvent', { type: 'keyDown', ...k });
    await client.send('Input.dispatchKeyEvent', { type: 'keyUp', ...k });
  }

  async function dispatchInput(entry, ev) {
    const client = entry.client;
    if (!client || !ev || typeof ev !== 'object') return;
    const x = Math.round((Number(ev.nx) || 0) * entry.vw);
    const y = Math.round((Number(ev.ny) || 0) * entry.vh);
    switch (ev.type) {
      case 'move':
        await client.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x, y });
        break;
      case 'click': {
        const button = ev.button === 'right' ? 'right' : 'left';
        await client.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x, y });
        await client.send('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button, clickCount: 1 });
        await client.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button, clickCount: 1 });
        break;
      }
      case 'scroll':
        await client.send('Input.dispatchMouseEvent', {
          type: 'mouseWheel', x, y,
          deltaX: Number(ev.dx) || 0,
          deltaY: Number(ev.dy) || 0,
        });
        break;
      case 'text':
        if (ev.text) await client.send('Input.insertText', { text: String(ev.text) });
        break;
      case 'key':
        await dispatchKey(client, String(ev.key));
        break;
    }
  }

  async function drainInputLoop(id, entry) {
    while (!stopped && pages.get(id) === entry) {
      let events = [];
      try {
        const r = await fetch(
          `${runtimeBase}/api/cinema/${encodeURIComponent(id)}/input?wait=${INPUT_POLL_WAIT_MS}`,
        );
        if (r.ok) {
          const j = await r.json();
          events = Array.isArray(j?.events) ? j.events : [];
        } else {
          await sleep(INPUT_POLL_WAIT_MS);
        }
      } catch (e) {
        // Runtime down / restart — back off and keep trying.
        await sleep(1000);
        continue;
      }
      for (const ev of events) {
        if (pages.get(id) !== entry) break;
        try { await dispatchInput(entry, ev); }
        catch (e) { warn(`cinema "${id}" input dispatch failed (${e?.message || e})`); }
      }
    }
  }

  let registryWarnAt = 0;
  async function pollRegistry() {
    if (!browser || stopped || polling) return;
    polling = true;
    try {
      let list = [];
      try {
        const r = await fetch(`${runtimeBase}/api/cinema/list`);
        if (!r.ok) return;
        const j = await r.json();
        list = Array.isArray(j?.cinemas) ? j.cinemas : [];
      } catch (e) {
        const now = Date.now();
        if (now - registryWarnAt > 60_000) {
          registryWarnAt = now;
          warn(`cinema registry poll ✗ ${e?.code || e?.message || e}`);
        }
        return;
      }
      for (const c of list) {
        const id = String(c?.id || '');
        if (!id) continue;
        const url = String(c?.url || 'about:blank');
        const version = Number(c?.urlVersion || 0);
        const existing = pages.get(id);
        if (!existing) {
          await openPageFor(id, url, version);
        } else if (existing.urlVersion !== version) {
          existing.urlVersion = version;
          await navigateAndStart(id, url, existing);
        }
      }
    } finally {
      polling = false;
    }
  }

  async function launch() {
    if (stopped) return;
    await teardown();
    clearStaleSingletonLocks(profileDir);
    try {
      browser = await puppeteer.launch({
        executablePath: chromePath,
        headless: 'new',
        userDataDir: profileDir,
        args: [
          '--no-sandbox',
          '--mute-audio',
          '--disable-background-timer-throttling',
          '--disable-renderer-backgrounding',
          '--disable-backgrounding-occluded-windows',
          '--autoplay-policy=no-user-gesture-required',
        ],
        defaultViewport: { width: VIEWPORT_W, height: VIEWPORT_H, deviceScaleFactor: 1 },
        timeout: 15_000,
      });
      browser.on('disconnected', () => {
        if (stopped) return;
        scheduleRelaunch('browser disconnected');
      });
      attemptDelay = RELAUNCH_BASE_MS;
      log(`headless cinema started — Chrome=${chromePath.split('/').pop()}`);

      // Kick the first registry poll immediately; loop after that.
      registryTimer = setInterval(() => { pollRegistry().catch(() => {}); }, POLL_REGISTRY_MS);
      pollRegistry().catch(() => {});
    } catch (e) {
      scheduleRelaunch(`launch failed: ${e?.message || e}`);
    }
  }

  await launch();

  const stop = async () => {
    stopped = true;
    if (relaunchTimer) clearTimeout(relaunchTimer);
    await teardown();
  };
  const onSig = () => { stop().finally(() => process.exit(0)); };
  process.once('SIGINT', onSig);
  process.once('SIGTERM', onSig);

  return { stop };
}
