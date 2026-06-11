#!/usr/bin/env node
// Bake a single static PNG of Omo's idle face into the client-mod's resources.
//
// Why: the in-game HUD renders the live PNG stream from the headless
// overlay, but during the ~1 second window when the face server is just
// booting or the headless Chrome is relaunching, there's no live frame.
// Without a fallback the HUD would either be blank or show a programmatic
// placeholder (the user has called the latter "amateur"). Instead, we ship
// ONE baked PNG of the real avatar.js render so the user can't tell the
// difference for short outages.
//
// Procedure (one-shot, run manually when avatar.js framing changes):
//   1. Start face yourself in another terminal:
//        cd face && OMO_HEADLESS_OVERLAY=false npm start
//      (the override is so this script's puppeteer is the only renderer)
//   2. Run this script:
//        cd face && npm run bake-static-face
//   3. Commit the resulting PNG:
//        git add client-mod/src/main/resources/assets/agentcraft-terminal/textures/omo/omo_static.png
//
// Output: client-mod/src/main/resources/assets/agentcraft-terminal/textures/omo/omo_static.png
// (256×256, transparent, chrome baked in — same compositor as the live frames).

import { existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { platform } from 'node:os';

const FRAME_PX = 256;
const OVERLAY_URL = process.env.OMO_OVERLAY_URL ?? `http://127.0.0.1:${process.env.OMO_FACE_PORT ?? 8080}/overlay`;
const __dirname = dirname(fileURLToPath(import.meta.url));
const OUT_PATH = resolve(__dirname, '..', '..', 'client-mod', 'src', 'main', 'resources',
  'assets', 'agentcraft-terminal', 'textures', 'omo', 'omo_static.png');

function chromePath() {
  if (process.env.OMO_CHROME_BINARY && existsSync(process.env.OMO_CHROME_BINARY)) return process.env.OMO_CHROME_BINARY;
  const candidates = platform() === 'darwin'
    ? ['/Applications/Google Chrome.app/Contents/MacOS/Google Chrome']
    : platform() === 'linux'
      ? ['/usr/bin/google-chrome', '/usr/bin/chromium', '/usr/bin/chromium-browser']
      : ['C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'];
  return candidates.find((c) => existsSync(c)) ?? null;
}

async function main() {
  const cp = chromePath();
  if (!cp) {
    console.error('[bake] no system Chrome found — install it or set OMO_CHROME_BINARY');
    process.exit(1);
  }
  const puppeteer = (await import('puppeteer-core')).default;
  console.log(`[bake] launching Chrome → ${cp}`);
  const browser = await puppeteer.launch({
    executablePath: cp,
    headless: 'new',
    args: ['--no-sandbox', '--mute-audio'],
    defaultViewport: { width: FRAME_PX, height: FRAME_PX, deviceScaleFactor: 2 },
  });
  try {
    const page = await browser.newPage();
    page.on('pageerror', (e) => console.warn('[bake] page error:', e?.message || e));

    console.log(`[bake] navigating to ${OVERLAY_URL}`);
    await page.goto(OVERLAY_URL, { waitUntil: 'domcontentloaded', timeout: 15_000 });

    // Wait for the compositor canvas to have a non-empty alpha — the overlay
    // page warms up for 8 frames before posting, so wait ~2s to be safe.
    await new Promise((r) => setTimeout(r, 2500));

    // Read the off-screen compositor canvas via a small bit of in-page JS.
    // We can't grab #cap directly (it lacks the chrome); instead we recreate
    // the same composite the live capture does by walking through the page
    // and screenshotting the centred #cap region — the compositor in
    // overlay-app.js paints to an off-DOM canvas, so the simplest reliable
    // path is to read the latest PNG the page has POSTed (it's in the
    // runtime's memory buffer at /api/omo-frame).
    //
    // But the bake script can't rely on the runtime running. So instead we
    // grab the latest blob the overlay produced via a tiny in-page hook:
    // overlay-app.js sets window.__omoLastFrame each time it captures.
    const dataUrl = await page.evaluate(() => new Promise((resolve, reject) => {
      const tryRead = (attempt) => {
        if (attempt > 30) { reject(new Error('no frame captured by overlay page after 6s')); return; }
        // Use the canvas the overlay maintains. overlay-app.js exposes the
        // compositor on window for this exact purpose if available; if not,
        // fall back to constructing the same composite from #cap.
        const off = window.__omoCompositor;
        if (off && off.width === 256 && off.height === 256) {
          resolve(off.toDataURL('image/png'));
          return;
        }
        setTimeout(() => tryRead(attempt + 1), 200);
      };
      tryRead(0);
    })).catch(async (e) => {
      // Fallback: just snapshot the visible #cap (no chrome, but still
      // a real Omo head). Better than failing.
      console.warn(`[bake] compositor not exposed (${e.message}) — falling back to raw #cap`);
      const el = await page.$('#cap');
      if (!el) throw new Error('no #cap element on overlay page');
      const buf = await el.screenshot({ omitBackground: true, type: 'png' });
      return 'data:image/png;base64,' + Buffer.from(buf).toString('base64');
    });

    const b64 = String(dataUrl).replace(/^data:image\/png;base64,/, '');
    const png = Buffer.from(b64, 'base64');
    mkdirSync(dirname(OUT_PATH), { recursive: true });
    writeFileSync(OUT_PATH, png);
    console.log(`[bake] wrote ${OUT_PATH} (${png.length} bytes)`);
  } finally {
    await browser.close();
  }
}

main().catch((e) => {
  console.error('[bake] failed:', e?.stack || e?.message || e);
  process.exit(1);
});
