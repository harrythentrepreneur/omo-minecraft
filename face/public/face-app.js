// omo-mc face app — the minimal "see Omo + talk to her + tools fire" loop.
//
// What this does, in order:
//   1. Build a Three.js scene, render Omo's 3D body via avatar.js.
//   2. Wait for a click on #mic-btn (browser requires a gesture before
//      AudioContext + getUserMedia work).
//   3. POST /session → get a one-shot Gemini Live ephemeral token.
//   4. Open ai.live.connect() directly to Gemini (the token is the only
//      thing the browser sees — GEMINI_API_KEY stays on the server).
//   5. Pipe the mic through an AudioWorklet that emits 16 kHz Int16 PCM,
//      base64-encode each chunk, sendRealtimeInput → Gemini.
//   6. On modelTurn audio, decode 24 kHz PCM → AudioBuffer → schedule on
//      a contiguous play head so playback never stutters.
//   7. On toolCall.functionCalls (the model wants to call a tool), POST
//      /tool {name, arguments} to face/server.js, then sendToolResponse
//      back to Gemini with the result.
//
// What's intentionally absent (full omo has these, omo-mc face doesn't):
//   - The polar anamorphic warp shader for a physical glass cylinder.
//   - The chart, pane, sprite, squad, ambient, awareness, reactions, and
//     ingest layers — those need omo's WS infra at /omo/ws.
//   - Reconnect backoff. If the WS dies, the user clicks the mic again.

import * as THREE             from 'three';
import { createAvatar,
         updateAvatar }       from './avatar.js';

// ─── DOM + state ───────────────────────────────────────────────────────
const canvas    = document.getElementById('scene');
const micBtn    = document.getElementById('mic-btn');
const statusTxt = document.getElementById('status-text');
const diagEl    = document.getElementById('diag');

const diagLines = [];
function diag(msg, isErr = false) {
  diagLines.push(msg);
  if (diagLines.length > 5) diagLines.shift();
  diagEl.textContent = diagLines.join(' · ');
  diagEl.classList.toggle('err', isErr);
  console[isErr ? 'warn' : 'log']('[face]', msg);
}
function setState(state, label = null) {
  document.body.dataset.state = state;
  if (label) statusTxt.textContent = label;
}
window.addEventListener('error', (e) => diag('JS ERR: ' + (e.message || e.error), true));
window.addEventListener('unhandledrejection', (e) =>
  diag('PROMISE ERR: ' + (e.reason?.message || e.reason), true));

setState('boot', 'booting');
diag('boot');

// ─── Three.js scene + Omo avatar ───────────────────────────────────────
// Bare scene — JUST the character. No projector beam, no pedestal rings,
// no sparkle starfield, no fog, no halo (those all come from
// createHologramScene, which we deliberately do NOT call here). The body
// uses MeshPhysicalMaterial throughout, so the only thing we add besides
// the avatar is enough light to read those materials.
const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true });
renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
renderer.outputColorSpace = THREE.SRGBColorSpace;
renderer.toneMapping = THREE.ACESFilmicToneMapping;
renderer.toneMappingExposure = 1.15;

const scene = new THREE.Scene();
// Leaving scene.background unset + the renderer's `alpha:true` means the
// HTML radial-gradient bg shows through. If you ever want a flat dark
// fill instead, set scene.background = new THREE.Color(0x04060a).

// Lights — soft sky + a warm key + a cool rim. Enough to make the
// hologram glass materials glow without flattening her.
const hemi = new THREE.HemisphereLight(0xc8e6ff, 0x0a1224, 1.0);
scene.add(hemi);
const key = new THREE.DirectionalLight(0xffffff, 1.1);
key.position.set(2.5, 3.5, 4);
scene.add(key);
const rim = new THREE.DirectionalLight(0x88c8ff, 0.6);
rim.position.set(-3, 2, -2);
scene.add(rim);

const avatar = createAvatar();
window.__momoAvatar = avatar;
// Lift her so her visual centre-of-mass sits at world origin. avatar.js
// builds the body with the torso below the origin (~y=-1.3) and the
// antenna-orb above (~y=1.42) — shifting up by ~0.5 puts the midpoint
// (~chest height) right at y=0, which then lines up with the camera's
// lookAt for a clean centred portrait.
avatar.position.set(0, 0.5, 0);

scene.add(avatar);

// Camera: head-on portrait. FOV 28 + distance 5.4 = cute, not too zoomed.
const camera = new THREE.PerspectiveCamera(28, 1, 0.1, 100);
camera.position.set(0, 0.05, 5.4);
camera.lookAt(0, 0, 0);

// ─── Resize ────────────────────────────────────────────────────────────
// Sizes the renderer to the canvas's CSS box rather than the full window,
// so the page can wrap Omo in a fixed-aspect portrait card instead of
// stretching her edge-to-edge. We fall back to the window dims only if
// the canvas has no laid-out CSS size yet (e.g. very first paint).
function resize() {
  const rect = canvas.getBoundingClientRect();
  const w = Math.max(1, Math.round(rect.width  || window.innerWidth));
  const h = Math.max(1, Math.round(rect.height || window.innerHeight));
  renderer.setSize(w, h, false);
  camera.aspect = w / h;
  camera.updateProjectionMatrix();
}
window.addEventListener('resize', resize);
// Observe the canvas's own CSS box so an Anthropic-style portrait card
// resizing on viewport change keeps Omo crisply rendered.
if (typeof ResizeObserver !== 'undefined') {
  new ResizeObserver(resize).observe(canvas);
}
resize();

// ─── Animation loop ────────────────────────────────────────────────────
let lastT = performance.now();
function frame(now) {
  const t = now / 1000;
  const dt = Math.min(0.1, (now - lastT) / 1000);
  lastT = now;
  try { updateAvatar(avatar, t, dt); } catch (err) { /* one-shot logs above */ }
  renderer.render(scene, camera);
  requestAnimationFrame(frame);
}
requestAnimationFrame(frame);

setState('idle', 'click mic to wake');
diag('scene ready');

// ─── Pre-warm audio ────────────────────────────────────────────────────
// Cold-start of an AudioContext + AudioWorklet load takes ~150-300ms.
// Doing it eagerly on page load (rather than on the first V press) means
// the very first voice trigger skips that cost — the user gets to
// "speak now" ~300ms faster. In the headless Chrome tab the autoplay
// policy flag bypasses the "needs user gesture" rule, so this just works.
// In a real user-facing tab AudioContext.resume() will silently no-op
// until the first click — we retry resume() on the mic button click.
let warmAudio = null;
let micWarmupOk = false;
(async function warmupAudio() {
  try {
    const ctx = new AudioContext({ sampleRate: 16000 });
    // Resume immediately — in headless Chrome this works without a
    // gesture; in a regular tab it'll stay suspended until the user
    // clicks, which is fine, startVoice() resumes it later.
    try { await ctx.resume(); } catch {}
    // Load the worklet module while we're here. It's cached for the
    // life of the page so startVoice's addModule call returns instantly.
    await ctx.audioWorklet.addModule('/pcm-worklet.js');
    warmAudio = ctx;
    console.warn('[voice] audio pre-warmed (state=' + ctx.state + ')');
  } catch (e) {
    // Best-effort — failure here just means startVoice pays the cold cost.
    diag('audio prewarm skipped: ' + (e?.message || e));
  }
})();

// ─── Programmatic mic permission warmup ────────────────────────────────
// Exposed on `window` so the headless Chrome host (see
// face/src/headless-overlay.js) can call it via page.evaluate() right
// after the voice page loads. With:
//   --use-fake-ui-for-media-stream
//   --autoplay-policy=no-user-gesture-required
//   browser.defaultBrowserContext().overridePermissions([microphone])
// already set, this call resolves silently and PROVES the OS-level
// mic grant is in place — so the first V press the user makes won't
// hit a NotAllowedError race.
//
// We grab a stream, immediately close every track (so we're NOT
// listening), and surface the result in MC chat as a one-time system
// line. Subsequent calls are idempotent.
async function omoWarmup() {
  if (micWarmupOk) return { ok: true, cached: true };
  // Make sure the AudioContext is unlocked even if the lazy initializer
  // above hasn't finished yet.
  try {
    if (warmAudio?.state === 'suspended') await warmAudio.resume();
  } catch {}
  let stream;
  try {
    stream = await navigator.mediaDevices.getUserMedia({ audio: true });
  } catch (e) {
    const name = e?.name || 'Error';
    const msg = e?.message || String(e);
    console.warn('[voice] mic warmup FAILED: ' + name + ': ' + msg);
    if (name === 'NotAllowedError' || name === 'SecurityError') {
      postSystemChat(
        'mic not granted — open System Settings → Privacy & Security → ' +
        'Microphone, enable Google Chrome, then run ./agentcraft restart-face. ' +
        'After that, just press V in Minecraft.',
      );
    } else if (name === 'NotFoundError') {
      postSystemChat('no microphone device found — plug one in, then ./agentcraft restart-face.');
    } else {
      postSystemChat('mic warmup failed (' + name + ') — press V in MC and watch chat for details.');
    }
    return { ok: false, error: name + ': ' + msg };
  }
  // Close immediately — we're not actually listening yet. The first V
  // press will reopen a fresh stream.
  try { stream.getTracks().forEach((t) => t.stop()); } catch {}
  micWarmupOk = true;
  console.warn('[voice] mic warmup ok — permission verified, audio ready');
  // One-time "Omo is ready" line so the user knows V will work without
  // opening the browser.
  postSystemChat('Omo is ready — press V in Minecraft to talk. No browser click needed.');
  return { ok: true };
}
// Expose for the puppeteer host to invoke via page.evaluate().
window.__omoWarmup = omoWarmup;

// ─── In-game overlay state push ────────────────────────────────────────
// Mirrors face state to the omo-mc runtime so the Fabric client-mod's
// top-left avatar animates in sync with this hologram. Best-effort:
// failures are swallowed; the in-game overlay falls back to idle if the
// runtime is down. We never block the voice loop on this.
let lastPushedFaceMode = null;
function pushFaceMode(mode) {
  if (mode === lastPushedFaceMode) return;
  lastPushedFaceMode = mode;
  try {
    fetch('/face-state', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ mode }),
      keepalive: true,
    }).catch(() => { /* swallow — overlay just stays at last state */ });
  } catch { /* same */ }
}
pushFaceMode('idle');

// ─── Gemini Live voice loop ────────────────────────────────────────────
// Everything below only runs after the user clicks the mic button.

let liveSession = null;
let connected   = false;
// Tracks the underlying WS readiness. Flipped true in onopen, false in
// onerror/onclose. We check this in the mic worklet handler because the
// `connected` flag isn't cleared until onclose runs teardown — and Chrome
// emits its own "WebSocket is already in CLOSING or CLOSED" console.error
// (separate from the thrown InvalidStateError our try/catch swallows) on
// every send between the remote-close and the onclose callback firing.
let wsAlive = false;
// Re-entry guard. `connected` only flips true AFTER ai.live.connect()
// resolves — so two fast triggers (e.g. user clicks the mic pip the very
// moment SSE delivers the V keybind) used to both pass the `if (connected)`
// check, race the AudioWorklet load, and the second one would fail with
// "AudioWorkletNode cannot be created: node name not defined" because the
// worklet module hadn't finished registering yet. `starting` covers the
// whole start path so a second trigger is a no-op until the first
// settles.
let starting   = false;
let micCtx, micStream, micNode, playCtx;
let playHead = 0;
let playingSources = [];

// The mic button is the user's first gesture (needed by the browser to
// allow getUserMedia + AudioContext). Once voice is live, the button
// fades but stays clickable so the user can toggle voice off / on again
// from the tab without alt-tabbing to MC. In-game V keybinds drive the
// same code path via the SSE listener below.
//
// NOTE: this MUST stay a plain (re-fireable) toggle listener — not
// `{ once: true }`. A single-use binding was the source of the
// "voice broken after one cycle" regression: after the first stop the
// pip would self-reload, which destroyed the unlocked-audio state and
// forced the user back through a fresh permission prompt.
micBtn.addEventListener('click', () => { toggleVoice(); });

// ─── In-game voice trigger (SSE) ───────────────────────────────────────
// The face server pushes a `voice-trigger` event when the player presses
// "V" inside Minecraft. We funnel it into the same startVoice / teardown
// path as the mic button so there's exactly one wiring.
//
// Limitation: without a prior user gesture in THIS tab the browser will
// still refuse to start AudioContext / getUserMedia. In practice the user
// clicks the mic once when the page opens; after that, every subsequent
// "V" press toggles cleanly. If the very first interaction is the V key,
// startVoice will fail with a NotAllowedError → we surface that in diag
// and the mic button stays visible so they can click it once.
try {
  const es = new EventSource('/events');
  es.addEventListener('voice-trigger', (ev) => {
    let action = 'toggle';
    try { action = JSON.parse(ev.data || '{}').action || 'toggle'; } catch {}
    diag('in-game trigger: ' + action);
    if (action === 'start') startVoice();
    else if (action === 'stop') stopVoice();
    else toggleVoice();
  });
  es.onerror = () => { /* EventSource auto-reconnects every 2s */ };
} catch (err) {
  diag('SSE unavailable: ' + (err?.message || err), true);
}

function toggleVoice() {
  // "connected" is set while liveSession is open; idle states (boot, idle,
  // error) all mean no live session, so a toggle = start. If a start is
  // already in flight (starting=true), the second startVoice will no-op
  // — same outcome as a toggle, just without the visible state churn.
  if (connected) stopVoice();
  else if (starting) diag('toggleVoice ignored: start in flight');
  else startVoice();
}

function stopVoice() {
  if (!connected) return;
  diag('stop voice');
  teardown();
  setState('idle', 'click mic to wake');
  // After teardown the mic pip's permanent toggle listener (set once at
  // module load) is still wired up — armRetry just nudges its opacity
  // back up so the user sees it's clickable again. Pressing V also still
  // works because the SSE listener is independent of the pip.
  armRetry();
}

async function startVoice() {
  if (connected) { diag('startVoice ignored: already live'); return; }
  if (starting)  { diag('startVoice ignored: already starting'); return; }
  starting = true;
  voiceStartedAt = Date.now();
  setState('connecting', 'connecting');
  // Listening starts the moment the user opens the mic — Gemini won't
  // emit a separate "listening" signal until it gets audio, and we want
  // the in-game overlay to react immediately to the click.
  pushFaceMode('listening');
  postProgress('wake', 'waking Omo');
  diag('mint /session');
  let sessionInfo;
  try {
    const r = await fetch('/session', { method: 'POST' });
    if (!r.ok) throw new Error(`/session ${r.status}: ${await r.text()}`);
    sessionInfo = await r.json();
  } catch (err) {
    setState('error', 'session error');
    diag('session failed: ' + err.message, true);
    postProgress('error', 'session failed — ' + (err.message || 'unknown'));
    armRetry();
    starting = false;
    return;
  }
  diag('token ok · model=' + sessionInfo.model);
  postProgress('token', 'token minted · loading SDK');

  let GoogleGenAI;
  try {
    ({ GoogleGenAI } = await import('https://esm.sh/@google/genai@1.50.1'));
  } catch (err) {
    setState('error', 'sdk error');
    diag('sdk import failed: ' + err.message, true);
    postProgress('error', 'sdk load failed — ' + (err.message || 'unknown'));
    armRetry();
    starting = false;
    return;
  }
  postProgress('sdk', 'sdk ready · opening mic');

  try {
    await setupAudio();
  } catch (err) {
    setState('error', 'mic error');
    // NotAllowedError = macOS / Chrome refused mic access. The headless
    // tab's auto-grant (--use-fake-ui-for-media-stream + overridePermissions)
    // covers the *page* prompt, but the OS-level grant in System Settings
    // → Privacy & Security → Microphone has to be done once by hand. If
    // that's missing every startVoice attempt fails with NotAllowedError
    // and the user never knows why. Surface the fix-it text in MC chat so
    // they don't have to read face.log.
    const name = err?.name || '';
    if (name === 'NotAllowedError' || name === 'SecurityError') {
      diag('mic permission denied — grant it in System Settings → Privacy & Security → Microphone for Google Chrome', true);
      postSystemChat(
        'mic permission denied — open System Settings → Privacy & Security → Microphone, ' +
        'enable Google Chrome, then run ./agentcraft restart-face and press M again.',
      );
    } else if (name === 'NotFoundError' || name === 'OverconstrainedError') {
      diag('no microphone device available: ' + err.message, true);
      postSystemChat('no microphone device found — plug one in (or grant Chrome access), then press M again.');
    } else {
      diag('mic setup failed: ' + err.message, true);
      postSystemChat('voice unavailable: ' + (err.message || name || 'mic setup failed'));
    }
    armRetry();
    starting = false;
    return;
  }
  diag('mic ready @ 16k');
  // Headless face.log signal: confirm getUserMedia actually returned a
  // stream. Without this, mic-permission failures look identical to
  // "everything fine, just no audio" in face.log. console.log surfaces
  // in face.log only when OMO_FACE_CLIENT_VERBOSE=1 — but the headless
  // console hook in headless-overlay.js gates on type=='error'/'warning'.
  // We use console.warn so it always shows up.
  console.warn('[voice] mic stream open — sampleRate=16000, tracks=' + (micStream?.getAudioTracks?.().length ?? '?'));
  postProgress('mic', 'mic open · connecting to Gemini');

  const ai = new GoogleGenAI({
    apiKey: sessionInfo.token,
    httpOptions: { apiVersion: 'v1alpha' },
  });
  try {
    liveSession = await ai.live.connect({
      model: sessionInfo.model,
      config: sessionInfo.setupConfig,
      callbacks: {
        onopen:    () => { wsAlive = true; diag('ws open'); },
        onmessage: (msg) => handleLiveMessage(msg),
        onerror:   (e) => { wsAlive = false; diag('ws error: ' + (e.message || e), true); setState('error', 'ws error'); },
        onclose:   (e) => {
          wsAlive = false;
          const reason = e?.reason || ('code ' + (e?.code ?? '?'));
          diag('ws closed: ' + reason);
          // If the close happened mid-conversation (we were live, not
          // gracefully torn down by stopVoice), tell the user in MC chat
          // so they know to press M to reconnect. Without this, voice
          // just goes silent and the next M press looks broken.
          //
          // `connected` is still true here because teardown() below sets
          // it false — so we read it BEFORE teardown runs.
          const droppedMidCall = connected;
          teardown();
          setState('idle', 'click mic to wake');
          armRetry();
          if (droppedMidCall) {
            postSystemChat('voice session dropped (' + reason.slice(0, 80) + ') — press M to reconnect.');
          }
        },
      },
    });
  } catch (err) {
    setState('error', 'ws error');
    diag('ws connect failed: ' + err.message, true);
    postProgress('error', 'gemini connect failed — ' + (err.message || 'unknown'));
    armRetry();
    starting = false;
    return;
  }
  connected = true;
  starting = false;
  diag('LIVE — waiting for setup');
  postProgress('ws', 'connected · final handshake');
  // `setupComplete` arrives via handleLiveMessage shortly after — that's
  // where we post the final "ready" line. See line ~440.
}

function armRetry() {
  // The mic button already has a permanent toggle listener (see the
  // micBtn.addEventListener call above). After an error or a clean stop
  // we just nudge it back to full opacity so the user notices they can
  // tap again to wake voice. No re-binding — re-binding used to stack
  // duplicate listeners and break the next start/stop cycle.
  micBtn.style.opacity = '1';
  micBtn.style.pointerEvents = 'auto';
  micBtn.textContent = 'wake mic';
}

async function setupAudio() {
  micStream = await navigator.mediaDevices.getUserMedia({ audio: true });
  // Reuse the pre-warmed AudioContext + worklet if it's available. Saves
  // ~150-300ms on the first voice trigger because the worklet module is
  // already registered. Falls back to a cold context if prewarm failed
  // (e.g. autoplay policy in a regular non-headless tab).
  if (warmAudio && warmAudio.state !== 'closed') {
    micCtx = warmAudio;
    warmAudio = null;
    if (micCtx.state === 'suspended') {
      try { await micCtx.resume(); } catch {}
    }
  } else {
    micCtx = new AudioContext({ sampleRate: 16000 });
    await micCtx.audioWorklet.addModule('/pcm-worklet.js');
  }
  const src  = micCtx.createMediaStreamSource(micStream);
  micNode = new AudioWorkletNode(micCtx, 'pcm16-worklet');
  micNode.port.onmessage = (e) => {
    if (!connected || !liveSession || !wsAlive) return;
    const b64 = arrayBufferToBase64(e.data);
    try {
      liveSession.sendRealtimeInput({
        audio: { data: b64, mimeType: 'audio/pcm;rate=16000' },
      });
    } catch (err) {
      // Latch off so we don't spam the next ~50 frames with the same
      // "CLOSING/CLOSED" console.error before onclose finally runs.
      wsAlive = false;
    }
  };
  src.connect(micNode);

  playCtx = new AudioContext({ sampleRate: 24000 });
  playHead = playCtx.currentTime;

  // Resume audio contexts on focus / tab visibility return — browsers
  // suspend them aggressively when backgrounded and won't auto-resume.
  const resumeAll = () => {
    if (playCtx?.state === 'suspended') playCtx.resume().catch(() => {});
    if (micCtx?.state  === 'suspended') micCtx.resume().catch(() => {});
  };
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') resumeAll();
  });
  window.addEventListener('focus', resumeAll);
}

function teardown() {
  connected = false;
  wsAlive = false;
  try { liveSession?.close(); } catch {}
  liveSession = null;
  try { micNode?.disconnect(); } catch {}
  try { micStream?.getTracks().forEach((t) => t.stop()); } catch {}
  stopAllPlayback();
  pushFaceMode('idle');
}

// ─── Transcript → in-game chat ─────────────────────────────────────────
// Gemini Live emits inputTranscription (what the user said) and
// outputTranscription (what Omo says back), both as chunked text with a
// `finished` flag. We accumulate chunks per role and flush when the turn
// ends (either chunk.finished or serverContent.turnComplete). Flushed
// lines POST to the runtime, which forwards a chat_message WS frame to
// the plugin so the player sees the conversation in MC chat.
let userBuf = '';
let omoBuf = '';
function flushTranscript(role) {
  const text = (role === 'user' ? userBuf : omoBuf).trim();
  if (role === 'user') userBuf = ''; else omoBuf = '';
  if (!text) return;
  // Fire-and-forget. The plugin handles delivery; we don't wait. Errors
  // are silent so a slow runtime never stalls the audio path.
  fetch('/voice-transcript', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ role, text }),
  }).catch(() => {});
}

// Surface a status line from the voice page in MC chat. The plugin
// renders `system` as italic dark-gray with a "⋯ " prefix — visually
// distinct from real conversation lines. Used for mic-permission /
// dropped-session messages and the V-press progress crawl below.
function postSystemChat(text) {
  if (!text) return;
  fetch('/voice-transcript', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ role: 'system', text: String(text) }),
  }).catch(() => {});
}

// Voice-loop loading progress. Posted at each startVoice() milestone so
// the player sees a "⋯ minting token … ~2s" status crawl in MC chat
// while Gemini Live spins up. ETA is a rolling estimate; we know the
// stages are roughly: token (~300ms), sdk (~200ms), mic (~150ms),
// ws (~1.2s), ready (~50ms after setupComplete) → ~2s total cold.
const EXPECTED_TOTAL_MS = 2500;
let voiceStartedAt = 0;
function postProgress(stage, text) {
  if (!text) return;
  const elapsedMs = voiceStartedAt > 0 ? Date.now() - voiceStartedAt : 0;
  const remaining = Math.max(0, EXPECTED_TOTAL_MS - elapsedMs);
  // Append a "~Ns" tail until we're ready, then drop it (so the final
  // "ready" line doesn't read "ready — ~0s").
  const tail = stage === 'ready' || remaining < 200
    ? ''
    : ` · ~${Math.round(remaining / 1000)}s`;
  fetch('/voice-progress', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      stage,
      text: text + tail,
      etaMs: EXPECTED_TOTAL_MS,
      elapsedMs,
    }),
  }).catch(() => {});
}

// ─── Gemini Live message handler ───────────────────────────────────────
function handleLiveMessage(msg) {
  if (msg.setupComplete) {
    setState('ready', 'ready');
    pushFaceMode('idle');
    diag('setup complete');
    // Final progress line — drops the ETA tail since we ARE ready.
    const tookMs = voiceStartedAt > 0 ? Date.now() - voiceStartedAt : 0;
    postProgress('ready', tookMs > 0
      ? `ready · speak now (woke in ${(tookMs / 1000).toFixed(1)}s)`
      : 'ready · speak now');
    voiceStartedAt = 0;
    return;
  }
  const sc = msg.serverContent;
  if (sc?.modelTurn?.parts) {
    for (const part of sc.modelTurn.parts) {
      const inline = part.inlineData;
      if (inline && typeof inline.data === 'string' && /audio\/pcm/i.test(inline.mimeType || '')) {
        const rate = extractRate(inline.mimeType) || 24000;
        const int16 = base64ToInt16(inline.data);
        if (document.body.dataset.state !== 'speaking') {
          setState('speaking', 'speaking');
          pushFaceMode('speaking');
        }
        enqueuePcmChunk(int16, rate);
      }
    }
  }
  if (sc?.interrupted) { stopAllPlayback(); setState('ready', 'ready'); pushFaceMode('idle'); }

  // Accumulate user-side speech transcription. `finished: true` marks the
  // utterance boundary — flush right then so MC chat shows one tidy line
  // per thing the user said.
  const inT = sc?.inputTranscription;
  if (inT?.text) userBuf += inT.text;
  if (inT?.finished) flushTranscript('user');

  // Same for Omo's side. Flush on `finished` OR on turnComplete (some
  // model responses don't carry the explicit per-chunk finished flag).
  const outT = sc?.outputTranscription;
  if (outT?.text) omoBuf += outT.text;
  if (outT?.finished) flushTranscript('omo');

  if (sc?.generationComplete || sc?.turnComplete) {
    // Backstop flush — in case neither side emitted finished:true.
    flushTranscript('user');
    flushTranscript('omo');
    const drain = Math.max(0, (playHead - (playCtx?.currentTime || 0)) * 1000);
    setTimeout(() => {
      if (playingSources.length === 0) {
        setState('ready', 'ready');
        pushFaceMode('idle');
      }
    }, drain + 80);
  }

  const activity = msg.serverContent?.inputActivity || msg.inputActivity;
  if (activity?.start) { setState('listening', 'listening'); pushFaceMode('listening'); }
  if (activity?.end && document.body.dataset.state === 'listening') {
    setState('ready', 'ready');
    pushFaceMode('idle');
  }

  if (msg.toolCall?.functionCalls) {
    for (const call of msg.toolCall.functionCalls) {
      diag('tool ⇠ ' + call.name);
      handleFunctionCall(call);
    }
  }
}

async function handleFunctionCall(call) {
  const { id, name, args } = call;
  // Tool dispatch ≈ "thinking" from the user's POV — show the spinner on
  // the in-game overlay until the result comes back (then turnComplete
  // will flip us back to speaking/idle).
  pushFaceMode('thinking');
  const t0 = performance.now();
  let output;
  try {
    const r = await fetch('/tool', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, arguments: args || {} }),
    });
    output = await r.json();
  } catch (err) {
    output = { error: String(err) };
  }
  const ms = Math.round(performance.now() - t0);
  const result = output.result ?? output;
  const tail = result?.error ? ' ✗ ' + result.error.slice(0, 60) : (result?.room ? ' → ' + result.room : ' ✓');
  diag(`tool ${name}${tail} · ${ms}ms`, !!result?.error);

  if (!liveSession) return;
  try { liveSession.sendToolResponse({ functionResponses: [{ id, name, response: { result } }] }); }
  catch (err) { console.warn('[face] sendToolResponse failed', err); }
}

// ─── PCM helpers ───────────────────────────────────────────────────────
function enqueuePcmChunk(int16, sampleRate) {
  if (!playCtx) return;
  const buf = playCtx.createBuffer(1, int16.length, sampleRate);
  const ch = buf.getChannelData(0);
  for (let i = 0; i < int16.length; i++) ch[i] = int16[i] / 0x8000;
  const src = playCtx.createBufferSource();
  src.buffer = buf;
  src.connect(playCtx.destination);
  const startAt = Math.max(playHead, playCtx.currentTime + 0.02);
  src.start(startAt);
  playHead = startAt + buf.duration;
  playingSources.push(src);
  src.onended = () => { playingSources = playingSources.filter((s) => s !== src); };
}

function stopAllPlayback() {
  playingSources.forEach((s) => { try { s.stop(); } catch {} });
  playingSources = [];
  if (playCtx) playHead = playCtx.currentTime;
}

function base64ToInt16(b64) {
  const bin = atob(b64); const len = bin.length;
  const bytes = new Uint8Array(len);
  for (let i = 0; i < len; i++) bytes[i] = bin.charCodeAt(i);
  return new Int16Array(bytes.buffer, bytes.byteOffset, Math.floor(len / 2));
}
function arrayBufferToBase64(buf) {
  const bytes = new Uint8Array(buf); let s = '';
  for (let i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
  return btoa(s);
}
function extractRate(mime) {
  const m = /rate=(\d+)/i.exec(mime || '');
  return m ? parseInt(m[1], 10) : null;
}
