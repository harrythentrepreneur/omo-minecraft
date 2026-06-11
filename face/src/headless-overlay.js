// Headless overlay renderer — launches a system-Chrome headless instance
// pointed at /overlay so the in-game HUD always has frames flowing without
// requiring the user to keep a Chrome window open.
//
// We deliberately use `puppeteer-core` (no bundled Chromium download) and
// resolve the system Chrome binary by platform. The overlay page itself
// already captures + POSTs each frame to the runtime; we just need a live
// browser context for the JS to execute in. The page IS the capture loop.
//
// Watchdog:
//   - on launch failure (Chrome missing / args rejected / port in use),
//     wait with exponential backoff (1s → 2s → 4s → ... → 30s cap) and try
//     again. Never crashes the face process.
//   - on page crash / disconnect / navigation away, the same backoff
//     relaunches the page.
//
// Escape hatch: set OMO_HEADLESS_OVERLAY=false in face/.env to disable
// entirely (e.g. running face on a server without Chrome installed). The
// existing /overlay route still works for manual browser tabs in that case.

import { existsSync, mkdirSync, rmSync } from 'node:fs';
import { platform, homedir } from 'node:os';
import { join } from 'node:path';

const FRAME_PX = 256;
const RELAUNCH_BASE_MS = 1000;
const RELAUNCH_MAX_MS = 30_000;

// Persistent profile dir for the headless Chrome. macOS gates microphone
// access at the binary level (System Settings → Privacy & Security →
// Microphone), but Chrome remembers PER-PROFILE prompts (Auto-grant etc).
// Pinning a user-data-dir means: once the user grants mic to Google
// Chrome once, every subsequent ./agentcraft inherits the grant silently.
const HEADLESS_PROFILE = join(homedir(), '.agentcraft', 'headless-chrome');
try { mkdirSync(HEADLESS_PROFILE, { recursive: true }); } catch {}

// A force-killed Chrome leaves a SingletonLock in its profile dir; the next
// puppeteer.launch then fails with "File exists" and the overlay never starts.
// Clearing the stale lock files lets a fresh launch always win (Chrome
// recreates them each run, so removing them is safe).
function clearStaleSingletonLocks(profileDir) {
  for (const f of ['SingletonLock', 'SingletonSocket', 'SingletonCookie']) {
    try { rmSync(join(profileDir, f), { force: true }); } catch {}
  }
}

function resolveChromePath() {
  // Allow explicit override first — useful for unusual installs (Chromium,
  // Chrome Canary, custom path).
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
  for (const c of candidates) {
    if (existsSync(c)) return c;
  }
  return null;
}

/**
 * Start the headless overlay renderer.
 *
 * @param {object} opts
 * @param {string} opts.overlayUrl   absolute http URL to the overlay page
 * @param {(msg: string, ...rest: unknown[]) => void} opts.log
 * @param {(msg: string, ...rest: unknown[]) => void} opts.warn
 * @returns {Promise<{stop: () => Promise<void>}>}
 */
export async function startHeadlessOverlay({ overlayUrl, log, warn }) {
  // Lazy import so face/ still boots if puppeteer-core isn't installed
  // (e.g. first-run before `npm install`). We surface a one-line warning
  // and bail gracefully.
  let puppeteer;
  try {
    puppeteer = (await import('puppeteer-core')).default;
  } catch (e) {
    warn(`headless overlay disabled — puppeteer-core not installed (${e?.code || e?.message || e})`);
    warn('  open ' + overlayUrl + ' in any browser to keep frames flowing manually.');
    return { stop: async () => {} };
  }

  const chromePath = resolveChromePath();
  if (!chromePath) {
    warn('headless overlay disabled — no system Chrome found.');
    warn('  install Chrome OR set OMO_CHROME_BINARY=/path/to/chrome in face/.env');
    warn('  OR open ' + overlayUrl + ' in any browser to keep frames flowing manually.');
    return { stop: async () => {} };
  }

  let browser = null;
  let page = null;          // /overlay — frame capture (existing)
  let voicePage = null;     // /        — voice loop (face-app.js)
  let stopped = false;
  let attemptDelay = RELAUNCH_BASE_MS;
  let relaunchTimer = null;

  // Derive the voice URL from the overlay URL by swapping the path.
  // Same origin/port so getUserMedia + SSE /events both work on
  // http://127.0.0.1:8080 without HTTPS.
  let voiceUrl = '';
  try {
    const u = new URL(overlayUrl);
    u.pathname = '/';
    u.search = '';
    voiceUrl = u.toString();
  } catch {}

  async function teardown() {
    // Null refs FIRST so the page.close()s don't trigger our own
    // 'close' / 'framenavigated' watchdog handlers into scheduling a
    // bogus relaunch on top of an in-flight relaunch.
    const vp = voicePage; voicePage = null;
    if (vp) { try { await vp.close({ runBeforeUnload: false }); } catch {} }
    const pg = page; page = null;
    if (pg) { try { await pg.close({ runBeforeUnload: false }); } catch {} }
    const br = browser; browser = null;
    if (br) { try { await br.close(); } catch {} }
  }

  function scheduleRelaunch(reason) {
    if (stopped) return;
    const delay = attemptDelay;
    attemptDelay = Math.min(RELAUNCH_MAX_MS, attemptDelay * 2);
    warn(`headless overlay: ${reason} — relaunch in ${delay}ms`);
    if (relaunchTimer) clearTimeout(relaunchTimer);
    relaunchTimer = setTimeout(() => { launch().catch(() => {}); }, delay);
  }

  async function launch() {
    if (stopped) return;
    await teardown();
    clearStaleSingletonLocks(HEADLESS_PROFILE);
    try {
      browser = await puppeteer.launch({
        executablePath: chromePath,
        // 'new' headless is the only path that runs WebGL reliably on
        // Chrome 109+; the legacy --headless mode disables it.
        headless: 'new',
        // Pin the user-data-dir so the macOS mic grant + Chrome-level
        // permission persist between ./agentcraft restarts.
        userDataDir: HEADLESS_PROFILE,
        args: [
          '--no-sandbox',
          // NOTE: --mute-audio is INTENTIONALLY OMITTED. Omo's TTS replies
          // need to play through the system speakers; muting them silenced
          // her voice on the user side.
          '--disable-background-timer-throttling',
          '--disable-renderer-backgrounding',
          '--disable-backgrounding-occluded-windows',
          // Allow autoplay so AudioContext.resume() doesn't need a
          // user gesture.
          '--autoplay-policy=no-user-gesture-required',
          // Auto-accept the page-level "Allow microphone?" prompt. macOS
          // still gates at the OS level — that's a one-time System
          // Settings click — but this removes the per-page friction.
          '--use-fake-ui-for-media-stream',
        ],
        defaultViewport: { width: FRAME_PX, height: FRAME_PX, deviceScaleFactor: 2 },
        // Brief timeout so a wedged Chrome doesn't hang the watchdog.
        timeout: 15_000,
      });
      // Pre-grant 'microphone' for the face origin so getUserMedia()
      // resolves silently in the voice page. Combined with the
      // --use-fake-ui flag above this clears every Chrome-level gate.
      if (voiceUrl) {
        try {
          const origin = new URL(voiceUrl).origin;
          await browser.defaultBrowserContext().overridePermissions(origin, ['microphone']);
        } catch (e) {
          warn(`could not pre-grant mic permission (${e?.message || e})`);
        }
      }
      // Browser-level disconnect: Chrome died (OOM, killed by OS, crashed).
      browser.on('disconnected', () => {
        if (stopped) return;
        scheduleRelaunch('browser disconnected');
      });

      // Puppeteer's launch opens an initial about:blank page; reuse it so
      // the /overlay page is the FIRST (and therefore foregrounded) tab.
      // Opening a second page leaves about:blank in the foreground and
      // Chrome's renderer-backgrounding kicks in on the overlay tab,
      // stalling requestAnimationFrame even with the throttling flags set.
      const pages = await browser.pages();
      page = pages[0] ?? await browser.newPage();
      page.on('error', (e) => scheduleRelaunch(`page error: ${e?.message || e}`));
      page.on('pageerror', (e) => warn(`overlay page JS error: ${e?.message || e}`));
      // Surface console.error from the overlay page (e.g. import-map failure,
      // WebGL context-lost) — these are the failure modes that make the page
      // load but never start posting frames.
      page.on('console', (msg) => {
        if (msg.type() === 'error') warn(`overlay page console.error: ${msg.text().slice(0, 240)}`);
      });
      page.on('requestfailed', (req) => {
        const url = req.url();
        if (url.includes('/favicon.ico')) return;
        // The overlay POSTs /api/omo-frame ~10×/s; in-flight requests get
        // ERR_ABORTED on page teardown / reload, which is expected churn,
        // not a failure mode worth logging.
        const errText = req.failure()?.errorText || '?';
        if (url.includes('/api/omo-frame') && errText === 'net::ERR_ABORTED') return;
        warn(`overlay page request failed: ${req.method()} ${url} (${errText})`);
      });
      page.on('framenavigated', (frame) => {
        // If something causes the page to navigate away from /overlay we
        // want to relaunch so frames keep flowing. Strip query string from
        // both sides so `?v=4` etc. doesn't trip the comparison.
        if (frame !== page.mainFrame()) return;
        const cur = frame.url().split('?')[0];
        const expected = overlayUrl.split('?')[0];
        warn(`[debug] framenavigated cur=${cur} expected=${expected}`);
        if (cur === expected || cur === 'about:blank' || cur === '') return;
        scheduleRelaunch(`page navigated away to ${frame.url()}`);
      });

      await page.goto(overlayUrl, { waitUntil: 'domcontentloaded', timeout: 15_000 });
      // Reset the backoff after a clean launch.
      attemptDelay = RELAUNCH_BASE_MS;
      log(`headless overlay started — Chrome=${chromePath.split('/').pop()} url=${overlayUrl}`);

      // ─── Voice tab + auto-warmup ────────────────────────────────────
      // Open the hologram page in a second tab so face-app.js is live,
      // subscribed to /events, and ready for an SSE-driven V press.
      // Then call window.__omoWarmup() which verifies mic access by
      // grabbing a stream and immediately closing it — proving that the
      // first V press won't hit a permission race. The user sees a
      // one-time "Omo is ready" line in MC chat if everything's good,
      // or an actionable fix-it line if mic permission is missing.
      //
      // Voice failure isn't fatal — overlay/HUD still works either way.
      if (voiceUrl) {
        try {
          voicePage = await browser.newPage();
          voicePage.on('pageerror', (e) => warn(`voice page JS error: ${e?.message || e}`));
          voicePage.on('console', (msg) => {
            const t = msg.type();
            if (t === 'error' || t === 'warning') {
              warn(`voice page console.${t}: ${msg.text().slice(0, 240)}`);
            }
          });
          await voicePage.goto(voiceUrl, { waitUntil: 'domcontentloaded', timeout: 15_000 });
          log(`headless voice host started — url=${voiceUrl}`);
          // Give face-app.js a beat to wire window.__omoWarmup, then
          // invoke it. We retry a few times because the module is
          // loaded as <script type="module"> and may not have executed
          // by domcontentloaded.
          (async () => {
            for (let i = 0; i < 10; i++) {
              await new Promise((r) => setTimeout(r, 250));
              try {
                const result = await voicePage.evaluate(async () => {
                  if (typeof window.__omoWarmup !== 'function') return null;
                  return await window.__omoWarmup();
                });
                if (result == null) continue; // module not ready yet
                if (result.ok) {
                  log(`voice warmup ok${result.cached ? ' (cached)' : ''}`);
                } else {
                  warn(`voice warmup failed: ${result.error}`);
                }
                return;
              } catch (e) {
                // Page might still be initializing — try again.
                if (i === 9) warn(`voice warmup never ran: ${e?.message || e}`);
              }
            }
          })();
        } catch (e) {
          warn(`voice page failed to open (${e?.message || e}) — voice will require opening ${voiceUrl} manually`);
        }
      }
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

  // Clean shutdown on signals — without this the headless Chrome process
  // can outlive the face server if face is killed roughly.
  const onSig = () => { stop().finally(() => process.exit(0)); };
  process.once('SIGINT', onSig);
  process.once('SIGTERM', onSig);

  return { stop };
}
