// omo-mc overlay capture page — renders a tight close-up of Omo's head
// into a transparent canvas, composites a holoscope chrome (soft radial
// glow under the head + circular alpha mask + thin cyan ring), and POSTs
// each frame to the runtime as PNG. Leave this tab open in any browser;
// the Fabric client-mod's HUD layer polls /api/omo-frame and blits the
// latest PNG into the top-left of the Minecraft window.
//
// Two canvases are involved:
//   - `#cap`  : the WebGL canvas Three.js renders the avatar onto. Plain
//               transparent background. Never POSTed directly — it has
//               square edges and no holo chrome.
//   - off2d   : an offscreen 2D canvas of the same dimensions where we
//               composite glow → avatar → circular mask → ring. THIS is
//               what we toBlob() and ship.
//
// Why composite here and not on the HUD side?
//   - The mod's DrawContext doesn't have a primitive for "soft circular
//     alpha mask under arbitrary texture". Doing it in Canvas2D is
//     trivially expressive.
//   - The PNG already arriving with chrome baked in means the HUD code
//     stays a single drawTexture call — easier to reason about, no
//     per-tick math.
//   - The chrome animates with the face state, so it has to live near the
//     mode polling. That's here.
//
// Bandwidth budget: 12 FPS × ~25-40 KB/frame ≈ 300-480 KB/s on localhost.
// The POST is fire-and-forget but we serialize them (no queue buildup)
// so a slow tab can't pile up requests.

import * as THREE from 'three';
import { createAvatar, updateAvatar } from './avatar.js';

// Frame size: chosen to match the in-game HUD display size exactly so
// there's NO bilinear downscale at blit time. 160 was picked because:
//   - large enough that the head's holo materials read crisply on a
//     1080p HUD at 2× GUI scale
//   - small enough that a 160² PNG of an alpha-circle compresses to
//     <50 KB, well under the runtime's 256 KB body cap
//   - leaves room for the holo glow + ring + breathing animation
// If you change this, also change SPRITE_PX in OmoHudLayer.java and
// re-check the runtime's OMO_FRAME_MAX_BYTES (currently 256 KB).
// 256 chosen over the prior 160 to give crisper holo materials in-game.
// A 256² transparent PNG of the chrome'd head runs ~60-90 KB; at 12 FPS
// that's <1 MB/s on localhost — well under the runtime's 256 KB/frame cap.
const FRAME_PX = 256;
// 40ms → ≈25 FPS. The face-proxy → runtime hop is ~3-5ms on localhost, so
// 25 fps still leaves plenty of headroom. Each PNG is ~90 KB → ~2.3 MB/s
// over loopback, fine. The mod's frame poller matches this period.
const POST_INTERVAL_MS = 40;
// POST to the face server (same-origin) — it proxies to the runtime. This
// is the only path that works in headless Chrome, where Private Network
// Access silently blocks cross-port fetches to 127.0.0.1:8766 with a bare
// "Failed to fetch". The face proxy is in face/server.js::/api/omo-frame.
const RUNTIME_URL = '/api/omo-frame';

// Don't POST until the avatar has had a few frames to settle. The first
// rendered frame can show an uninitialised pose (eyes mid-blink, body at
// rest position before the bob starts) — shipping that as the in-game
// HUD's first frame looks broken.
const WARMUP_FRAMES = 8;

// Optional ?debug=1 shows a checker bg + a status pill so you can visually
// confirm Omo is being drawn correctly when iterating on framing.
const DEBUG = new URLSearchParams(location.search).has('debug');
if (DEBUG) {
  document.body.classList.add('debug');
}

const canvas = document.getElementById('cap');
const pill = document.getElementById('pill');

// In production, hide the status pill entirely — it shouldn't be visible
// if a user accidentally opens the overlay tab. Debug mode keeps it.
if (!DEBUG && pill) pill.style.display = 'none';

// Force the WebGL canvas to FRAME_PX × FRAME_PX (defensive against the
// HTML attribute drifting if someone edits the markup).
canvas.width = FRAME_PX;
canvas.height = FRAME_PX;
canvas.style.width = FRAME_PX + 'px';
canvas.style.height = FRAME_PX + 'px';

// ─── Three.js scene ────────────────────────────────────────────────────
// Mirrors face-app.js's lighting (so Omo's hologram materials read the
// same), but uses a transparent clear and a head-only framing.
const renderer = new THREE.WebGLRenderer({
  canvas,
  antialias: true,
  alpha: true,
  // PNG composition wants straight (un-premultiplied) alpha so the in-game
  // blit doesn't double-darken the head edges. Three.js defaults to
  // premultiplied, so flip it explicitly.
  premultipliedAlpha: false,
  // preserveDrawingBuffer so we can drawImage(canvas) onto the 2D
  // compositor right after rendering without race conditions on some
  // browsers (Chrome especially). Tiny perf cost at this resolution.
  preserveDrawingBuffer: true,
});
// 2× supersampling — the internal GPU framebuffer is 2× FRAME_PX in each
// dimension; when we drawImage(canvas) into the compositor at FRAME_PX it
// auto-downsamples → free anti-aliasing on the crystal head edges. The PNG
// output stays exactly FRAME_PX so the in-game blit is 1:1.
renderer.setPixelRatio(2);
renderer.setSize(FRAME_PX, FRAME_PX, false);
renderer.setClearColor(0x000000, 0);
renderer.setClearAlpha(0);
renderer.outputColorSpace = THREE.SRGBColorSpace;
renderer.toneMapping = THREE.ACESFilmicToneMapping;
renderer.toneMappingExposure = 1.2;

const scene = new THREE.Scene();

// Lights — same trio as face-app.js, very slightly warmer key to make
// the head pop at this zoom level.
scene.add(new THREE.HemisphereLight(0xc8e6ff, 0x0a1224, 1.0));
const key = new THREE.DirectionalLight(0xffffff, 1.2);
key.position.set(2.5, 3.5, 4);
scene.add(key);
const rim = new THREE.DirectionalLight(0x88c8ff, 0.65);
rim.position.set(-3, 2, -2);
scene.add(rim);

const avatar = createAvatar();
// Match face-app.js's lift so updateAvatar's bobbing animations land where
// the head close-up expects them.
avatar.position.set(0, 0.5, 0);
scene.add(avatar);

// Camera framing — head, shoulders, and orb halo with breathing room.
//   avatar.js geometry: head sphere radius 1, scaled (1,0.96,1) → 2 wide,
//   1.92 tall. Avatar root lifted +0.5, so head sits centred at world
//   y=0.5. Antenna orb at world y=1.92. Torso top around y=-0.8.
//   Vertical extent we want: y=-1.2 (touch of shoulders) → y=2.2 (above
//   orb halo) ≈ 3.4 units. Visual centre y ≈ 0.4.
//   FOV 28° at distance 7.0 gives visible vertical = 2×7×tan(14°) ≈ 3.49
//   units → head (1.92) fills ~55% vertically, with comfortable air above
//   the orb and a sliver of shoulders below. Reads as "Omo is floating in
//   the chamber" rather than "Omo's face fills the entire chamber".
const camera = new THREE.PerspectiveCamera(28, 1, 0.05, 50);
camera.position.set(0, 0.4, 7.0);
camera.lookAt(0, 0.4, 0);

// ─── 2D compositor canvas ──────────────────────────────────────────────
// Off-DOM 2D canvas that receives the WebGL render and applies holoscope
// chrome (glow under, circular alpha mask, holo ring). Same dimensions
// as the WebGL canvas — no resampling.
const off2d = document.createElement('canvas');
off2d.width = FRAME_PX;
off2d.height = FRAME_PX;
const ctx2d = off2d.getContext('2d', { alpha: true });
// Expose the composited canvas on `window` so the static-face bake script
// (face/scripts/bake-static-face.js) can `toDataURL` it directly without
// going through the runtime. Harmless in production — just a back-pointer.
window.__omoCompositor = off2d;

// Pre-build the radial glow that sits BEHIND the head as a static
// ImageBitmap-like cache. Cheap to redraw each frame but caching means
// no per-frame gradient allocation.
function buildGlowGradient(intensity) {
  const cx = FRAME_PX / 2;
  const cy = FRAME_PX / 2;
  // Holo chrome radii are intentionally well inside the canvas (was 0.48 →
  // 0.44) so the in-game 1:1 blit has ~28 px of empty margin on every
  // side. Without that margin the ring + shadow-blur get clipped by the
  // GUI rect's hard edge and Omo looks "boxed in".
  const r = FRAME_PX * 0.44;
  const g = ctx2d.createRadialGradient(cx, cy, 0, cx, cy, r);
  // Cyan-leaning core, fading to fully transparent at the disc edge.
  // Intensity scales the inner alpha — speaking/celebrating brightens.
  g.addColorStop(0.00, `rgba(120, 220, 255, ${0.55 * intensity})`);
  g.addColorStop(0.45, `rgba(80, 180, 240, ${0.22 * intensity})`);
  g.addColorStop(1.00, 'rgba(40, 100, 180, 0.00)');
  return g;
}

// The circular alpha mask used to clip the composite to a soft disc.
// Slight feathering at the edge so it doesn't look pixel-cut.
function buildCircleMaskGradient() {
  const cx = FRAME_PX / 2;
  const cy = FRAME_PX / 2;
  // Inner: fully opaque, outer: fully transparent. Outer gradient stop
  // pulled in from 0.50 → 0.46 of FRAME_PX so the soft alpha cut-off
  // sits ~10 px inside the canvas edge instead of right at it.
  const g = ctx2d.createRadialGradient(cx, cy, 0, cx, cy, FRAME_PX * 0.46);
  g.addColorStop(0.00, 'rgba(255, 255, 255, 1)');
  g.addColorStop(0.90, 'rgba(255, 255, 255, 1)');
  g.addColorStop(1.00, 'rgba(255, 255, 255, 0)');
  return g;
}

// The thin holographic ring just inside the mask edge. Drawn last,
// stroked with a faint cyan + tiny outer glow via shadow.
function drawHoloRing(intensity) {
  const cx = FRAME_PX / 2;
  const cy = FRAME_PX / 2;
  // Ring sits inside the alpha mask edge so its 3 px shadow blur isn't
  // clipped by either the mask OR the canvas bounds. Was 0.475 → 0.435.
  const r = FRAME_PX * 0.435;
  ctx2d.save();
  ctx2d.globalCompositeOperation = 'source-over';
  ctx2d.strokeStyle = `rgba(140, 230, 255, ${0.55 * intensity})`;
  ctx2d.lineWidth = 1;
  ctx2d.shadowColor = `rgba(120, 220, 255, ${0.35 * intensity})`;
  ctx2d.shadowBlur = 3;
  ctx2d.beginPath();
  ctx2d.arc(cx, cy, r, 0, Math.PI * 2);
  ctx2d.stroke();
  ctx2d.restore();
}

// ─── Animation loop ────────────────────────────────────────────────────
// setInterval (not requestAnimationFrame) so the loop ticks reliably in
// headless Chrome — rAF only fires when Chrome thinks the page is being
// PRESENTED, which never happens in `headless: 'new'` regardless of the
// --disable-renderer-backgrounding family of flags. setInterval is driven
// by the timer queue and always fires, headless or not.
let lastT = performance.now();
let framesRendered = 0;
function frame() {
  const now = performance.now();
  const t = now / 1000;
  const dt = Math.min(0.1, (now - lastT) / 1000);
  lastT = now;
  try { updateAvatar(avatar, t, dt); } catch (_) { /* surface via window.onerror */ }
  renderer.render(scene, camera);
  framesRendered++;
}
// ~60 FPS render; capture loop runs separately at ~12 FPS via POST_INTERVAL_MS.
setInterval(frame, 16);

// ─── Face-state subtle visual reaction ─────────────────────────────────
// Light polish: rim light + chrome intensity react to the runtime's
// reported mode. The avatar itself already animates differently per mode
// in updateAvatar, so this is just a halo flourish.
let currentMode = 'idle';
let chromeIntensity = 1.0;  // 0..1.4 — pulses with mode
let chromePulsePhase = 0;
async function pollFaceState() {
  try {
    const r = await fetch('http://127.0.0.1:8766/api/face-state', { cache: 'no-store' });
    if (r.ok) {
      const j = await r.json();
      if (typeof j?.mode === 'string' && j.mode !== currentMode) {
        currentMode = j.mode;
        applyMode(currentMode);
      }
    }
  } catch (_) { /* runtime might not be up; harmless */ }
}
function applyMode(mode) {
  switch (mode) {
    case 'listening':
      rim.color.set(0xff66b3); rim.intensity = 0.9;
      key.intensity = 1.3;
      break;
    case 'thinking':
      rim.color.set(0x88c8ff); rim.intensity = 0.5;
      key.intensity = 1.05;
      break;
    case 'speaking':
      rim.color.set(0xffaad4); rim.intensity = 0.85;
      key.intensity = 1.25;
      break;
    case 'celebrating':
      rim.color.set(0xffd166); rim.intensity = 1.0;
      key.intensity = 1.35;
      break;
    case 'idle':
    default:
      rim.color.set(0x88c8ff); rim.intensity = 0.65;
      key.intensity = 1.2;
      break;
  }
}
setInterval(pollFaceState, 400);
pollFaceState();

// Per-mode chrome pulse: gentle breathing on idle/listening, brighter
// + faster on speaking/celebrating. Sampled each capture, not each render
// frame, so the cost is ~12 Hz of trig.
function updateChromeIntensity(now) {
  const tSec = now / 1000;
  let base = 1.0;
  let amp = 0.10;
  let freq = 0.7;
  switch (currentMode) {
    case 'listening':  base = 1.05; amp = 0.14; freq = 0.9; break;
    case 'speaking':   base = 1.18; amp = 0.18; freq = 1.6; break;
    case 'celebrating':base = 1.30; amp = 0.20; freq = 2.2; break;
    case 'thinking':   base = 0.90; amp = 0.08; freq = 0.5; break;
    case 'idle':
    default:           base = 1.00; amp = 0.10; freq = 0.7; break;
  }
  chromePulsePhase = tSec * freq;
  chromeIntensity = base + Math.sin(chromePulsePhase * Math.PI * 2) * amp;
  if (chromeIntensity < 0.55) chromeIntensity = 0.55;
}

// ─── Compositor: WebGL canvas → 2D canvas with holoscope chrome ───────
function composite(now) {
  updateChromeIntensity(now);
  // 1. Clear the 2D canvas (fully transparent).
  ctx2d.clearRect(0, 0, FRAME_PX, FRAME_PX);

  // 2. Soft radial glow UNDER the head (additive feel via source-over
  //    with cyan-tinted gradient — destination is transparent so this
  //    just paints the disc).
  ctx2d.save();
  ctx2d.globalCompositeOperation = 'source-over';
  ctx2d.fillStyle = buildGlowGradient(chromeIntensity);
  ctx2d.fillRect(0, 0, FRAME_PX, FRAME_PX);
  ctx2d.restore();

  // 3. The avatar on top.
  ctx2d.drawImage(canvas, 0, 0, FRAME_PX, FRAME_PX);

  // 4. Circular alpha mask — anything outside the soft disc gets clipped.
  //    destination-in keeps only pixels where the new alpha is non-zero.
  ctx2d.save();
  ctx2d.globalCompositeOperation = 'destination-in';
  ctx2d.fillStyle = buildCircleMaskGradient();
  ctx2d.fillRect(0, 0, FRAME_PX, FRAME_PX);
  ctx2d.restore();

  // 5. Thin holographic ring just inside the mask edge — sells the
  //    "projection chamber" idea.
  drawHoloRing(chromeIntensity);
}

// ─── Frame capture + POST ──────────────────────────────────────────────
// toBlob (not toDataURL) — toDataURL synchronously base64-encodes on the
// main thread and is markedly slower. We use a single in-flight POST at a
// time so a hiccup never queues up multiple frames.
let inFlight = false;
let lastSizeBytes = 0;
let postsThisSecond = 0;
let lastTickMs = performance.now();
let consecutiveErrors = 0;

function captureAndSend() {
  if (inFlight) return;
  // Skip until the avatar has been ticked a few times — otherwise the
  // first frame is an uninitialised pose.
  if (framesRendered < WARMUP_FRAMES) return;
  inFlight = true;
  composite(performance.now());
  off2d.toBlob((blob) => {
    if (!blob) { inFlight = false; return; }
    lastSizeBytes = blob.size;
    fetch(`${RUNTIME_URL}?w=${FRAME_PX}&h=${FRAME_PX}`, {
      method: 'POST',
      headers: { 'Content-Type': 'image/png' },
      body: blob,
      // No keepalive — Chrome silently drops keepalive fetches with
      // bodies > 64 KB and our chrome'd PNGs are typically 90-100 KB.
    })
      .then((res) => {
        if (res.ok || res.status === 204) consecutiveErrors = 0;
        else consecutiveErrors++;
      })
      .catch(() => { consecutiveErrors++; })
      .finally(() => { inFlight = false; postsThisSecond++; });
  }, 'image/png');
}
setInterval(captureAndSend, POST_INTERVAL_MS);

// ─── Status pill (debug surface only) ──────────────────────────────────
setInterval(() => {
  if (!DEBUG || !pill) return;
  const now = performance.now();
  const elapsedSec = (now - lastTickMs) / 1000;
  const fps = elapsedSec > 0 ? (postsThisSecond / elapsedSec) : 0;
  postsThisSecond = 0;
  lastTickMs = now;
  const kb = (lastSizeBytes / 1024).toFixed(1);
  pill.textContent = `${fps.toFixed(1)} fps · ${kb} KB · ${currentMode}`;
  pill.className = consecutiveErrors > 3 ? 'bad' : 'good';
}, 1000);
