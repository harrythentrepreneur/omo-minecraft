# Omo Face — handoff doc

This is a top-to-bottom guide to the **face/** subtree: what it is, where it
lives in the wider omo-mc stack, how every piece talks to every other piece,
and what you need to know to extend or debug it.

If you've never touched this codebase before, read this file end-to-end
once. After that you can use the section headers as a map.

---

## 1. What omo-mc is, in one paragraph

omo-mc is the Minecraft integration of **omo** — a voice-first AI business
cofounder. omo proper (the standalone product at `/Users/harryedwards/omo`)
gives founders a holographic Omo character on screen who can read business
data, dispatch agents, render charts, and so on. omo-mc adds a second
screen: a real Java 1.21.4 Minecraft world where business data, AI agents,
tasks, and rooms exist as 3D spaces the user can enter, explore, and bring
collaborators into. The PRD (`Omo_x_Minecraft_PRD_EN.docx`) calls this
"a playable AI operating system for your company."

The **face/** subtree is the half of omo-mc that puts the Omo hologram on
the user's screen on `http://localhost:8080`. The other half is a real
Minecraft server with a custom plugin and a Node runtime that bridges
between Gemini Live voice and the in-world player. When the user talks to
the face and says "take me to the code lab", the chain teleports the
in-game player to the workshop island.

---

## 2. The four processes

`./agentcraft` (the launcher at the repo root) is the single entry point.
It starts and stops four cooperating processes:

```
┌─────────────────────────────────────────────────────────────────────┐
│                                                                     │
│   Browser (localhost:8080)        ←─ face HTTP serves cylinder      │
│      │                                                              │
│      │  voice (16 kHz mic PCM)  ─→ Gemini Live (external WS)        │
│      │  ←─ 24 kHz audio + tool_call from Gemini                     │
│      │                                                              │
│      ↓  POST /tool { name, args }                                   │
│   ┌──────────────┐   POST /api/teleport   ┌───────────┐  WS frame   │
│   │  face        │ ───────────────────→   │ runtime   │ ──────────→ │
│   │  :8080       │                        │ :8766/8765│             │
│   │  Express     │                        │  Node TS  │             │
│   └──────────────┘                        └───────────┘             │
│                                                  │                  │
│                                                  ↓  ws://:8765      │
│                                          ┌─────────────────┐        │
│                                          │  Paper plugin   │        │
│                                          │  Minecraft 1.21 │        │
│                                          │  :25565         │        │
│                                          └─────────────────┘        │
│                                                                     │
│                  ┌──────────────────────┐                           │
│                  │  hermes-agent :8642  │  ← villager AI brain      │
│                  │  (Nous Hermes)       │     used by HermesAgent   │
│                  └──────────────────────┘                           │
└─────────────────────────────────────────────────────────────────────┘
```

| Process | Port | What it does | Where its code lives |
|---|---|---|---|
| **hermes-agent** | 8642 | Nous Hermes brain. Drives villager AI inside MC. Not used by the face directly. | External binary; install via `hermes` |
| **runtime** | 8765 (WS) + 8766 (HTTP) | The bridge. Plugin opens a WS to :8765; face POSTs to :8766. | `runtime/` (Node + TypeScript) |
| **face** | 8080 | The Omo hologram + voice + tool router. | `face/` (Node + Express) |
| **mc** | 25565 | Paper Minecraft server with the AgentCraft plugin loaded. | `plugin/` (Java 21) + `server/` (runtime state) |

When the user runs `./agentcraft`, all four come up and the launcher tails
all their logs into one terminal. Ctrl-C stops everything.

---

## 3. The face/ subtree, in detail

### 3.1 Layout

```
face/
├── package.json                  Three deps: express, @google/genai, dotenv
├── .env                          GEMINI_API_KEY (gitignored)
├── .env.example                  Template with all knobs documented
├── server.js                     ~190 LOC — Express on :8080
├── src/
│   ├── session.js                Gemini Live ephemeral token minter
│   └── tools.js                  Tool registry: teleport, finish_task
└── public/
    ├── index.html                The hologram page (50 LOC)
    ├── face-app.js               Three.js + voice loop + tool dispatch (~280 LOC)
    ├── avatar.js                 Omo's 3D body (copied verbatim from omo)
    ├── spatial.js                Vec helpers used by avatar.js (from omo)
    ├── pcm-worklet.js            16 kHz mic encoder worklet (from omo)
    ├── client-log.js             Browser-side console → /log forwarder (from omo)
    ├── i18n.js                   String table (from omo, currently unused)
    ├── text-overlay.js           Text chat overlay (from omo, currently unused)
    ├── voice-mode.js             Voice-mode HUD (from omo, currently unused)
    ├── world.js                  Stars/background (from omo, currently unused)
    ├── agent-sprites.js          NO-OP STUB
    ├── agent-squads.js           NO-OP STUB
    ├── agent-ambient.js          NO-OP STUB
    ├── momo-awareness.js         NO-OP STUB
    ├── chart-layer.js            NO-OP STUB
    ├── pane-layer.js             NO-OP STUB
    └── holo_cylinder.html        Full omo cylinder, served at /cylinder
```

### 3.2 Why so many "from omo" files?

The face is a *slim* port of the omo holographic frontend. omo proper
contains dozens of features the face doesn't need (Stripe / Meta / Gmail
panels, agent squads, chart rendering, multi-screen panes, etc.). Most of
those features live in their own modules. **avatar.js** is the single load-
bearing import we kept verbatim: it builds the entire 3D Omo character.

Some of avatar.js's transitive imports (`agent-sprites.js`, `chart-layer.js`,
etc.) are referenced statically at the top of avatar.js and would 404 if
we deleted them. Worse — the real versions open WebSockets to `/omo/ws` and
spin in reconnect loops. So we replaced them with **no-op stubs** that
export the same function signatures avatar.js expects.

The files marked `(from omo, currently unused)` are scripts that the
deprecated `holo_cylinder.html` page references. The current `index.html`
does NOT load them. They're kept because:
1. `/cylinder` still works as a fallback view if anyone wants to see the
   full polar-warp hologram (intended for a physical glass-cylinder display).
2. Stripping them is a high-risk diff with no real upside — they sit on
   disk doing nothing.

If you want to delete them, do it after confirming `grep -rn "i18n\|text-overlay\|voice-mode\|world.js"` finds no references in the face's active code path (i.e. `index.html` + `face-app.js` + `avatar.js`).

### 3.3 face/server.js — the HTTP surface

Three things only:

1. **`GET /`** → serves `public/index.html` (the minimal page).
   **`GET /cylinder`** → serves `public/holo_cylinder.html` (the full omo
   cylinder, kept as a fallback). **`GET /holo`** → alias for `/`.
2. **`POST /session`** → mints a one-shot Gemini Live ephemeral token via
   `src/session.js::mintSession`. Returns `{ token, model, voice, setupConfig }`.
   The browser sees the token; `GEMINI_API_KEY` never leaves the server.
3. **`POST /tool`** → routes a tool call (`{ name, arguments }`) to
   `src/tools.js::runTool`. Returns `{ result: <whatever the tool returned> }`.

Plus support endpoints:
- `POST /log` — receives batched browser console logs from `client-log.js`
- `POST /voice/session` — returns 503; the face doesn't ship a local STT
  fallback (omo proper does, via Whisper)
- A static middleware serves `public/*` for assets
- A 404 middleware silently handles paths like `/omo/ws`, `/hq`, `/squad`,
  `/preview` that the cylinder probes but the face doesn't host

**Logging** — every request is logged in the format
`HH:MM:SS [face] METHOD /path → CODE · Xms`. Static asset 200s are dimmed,
real 404s are warn-coloured yellow, intentional 404s on omo-only endpoints
are dimmed. Tool calls and session mints get their own detailed lines.

### 3.4 face/src/session.js — Gemini Live token mint

Calls `GoogleGenAI.authTokens.create()` with:
- `uses: 1` (single-use; the browser consumes it on the live.connect call)
- `expireTime`: now + 30 minutes
- `newSessionExpireTime`: now + 2 minutes (browser must open a session
  within this window or the token is dead)
- `liveConnectConstraints`: the model + the full liveConfig, which
  includes:
  - `responseModalities: ['AUDIO']`
  - `systemInstruction`: the **OMO_INSTRUCTIONS** constant (the persona)
  - `tools`: the function declarations from `tools.js`
  - `speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName`: `Leda` by default
  - `inputAudioTranscription: {}` and `outputAudioTranscription: {}` (so the
    browser receives transcript text alongside audio)

**The persona** (`OMO_INSTRUCTIONS`) tells Gemini:
- You are Omo, a voice-first AI cofounder living in Minecraft
- Keep replies one or two short sentences
- When the user asks to go somewhere, call the `teleport` tool with the
  friendly destination name
- The available destinations + aliases (hq / lobby / spawn / ads / mail /
  workshop / game / learning / task)
- Don't invent business data — there is no Stripe/Gmail/Meta wired up here

To change Omo's character or speech style, edit `OMO_INSTRUCTIONS` in
`face/src/session.js`. Restart the face with `./agentcraft restart-face` —
the next browser session will use the new persona.

### 3.5 face/src/tools.js — the tool registry

Two tools today, both with Gemini-style function declarations:

| Tool | Purpose |
|---|---|
| `teleport({ destination, player? })` | Resolves a friendly destination string to a canonical room id via `ISLAND_ALIASES`, then POSTs to the runtime's `/api/teleport`. |
| `finish_task()` | Marks the conversational turn as done. No side effects. |

**The alias map** is the contract between voice and room names. Adding a
new room name? Update both:
1. `face/src/tools.js::ISLAND_ALIASES` — add the user-facing phrases
2. The plugin must have a room registered with the canonical name (via
   `/omo skyworld` or `/omo room define <name>`)

If a user phrase doesn't match any alias, the input is passed through as-
is, and the plugin will log `teleport_player: no room defined for '<X>'`.

### 3.6 face/public/index.html + face-app.js — the page

`index.html` is intentionally tiny (~50 LOC of HTML + CSS). It contains:
- A `<canvas id="scene">` where Three.js paints
- A status pill (top-left dot, colour-coded by state)
- A 5-line diagnostic readout (top-right)
- A mic button (centre-bottom, hidden once voice is live)
- A phrase hints panel (bottom-left)
- An importmap that resolves the bare specifier `three` to a CDN URL
- A single `<script type="module" src="/face-app.js?v=N">` (the `?v=N`
  cache-buster gets bumped when you change the scene wiring)

`face-app.js` does seven things in order:
1. Build a Three.js scene: renderer, scene, camera, three lights (hemi +
   key + rim).
2. Call `createAvatar()` from avatar.js. Lift the result by `y=0.5` so the
   chest sits at world origin.
3. Aim a 28° FOV camera at `(0, 0, 0)` from `(0, 0.05, 5.4)`.
4. Animation loop: `updateAvatar(avatar, t, dt)` then `renderer.render`.
5. Wait for the mic button click. On click:
   - `POST /session` → token
   - `import('https://esm.sh/@google/genai@1.50.1')` → SDK
   - `setupAudio()` → 16 kHz mic + AudioWorklet + 24 kHz playback ctx
   - `ai.live.connect()` → WS to Gemini, with the four callbacks
6. On every `setupRealtimeInput` frame, b64-encode the mic PCM and send.
7. On every `handleLiveMessage`:
   - `setupComplete` → state becomes `ready`
   - `serverContent.modelTurn.parts[].inlineData` → decode 24 kHz PCM into
     a `BufferSource` and schedule it on a contiguous `playHead`
   - `serverContent.interrupted` → cancel everything queued
   - `toolCall.functionCalls[]` → POST `/tool { name, arguments }`, then
     `sendToolResponse({ functionResponses: [{ id, name, response: { result }}] })`

If you ever need to debug the voice loop, the top-right diag readout shows
the last five state transitions. The browser console + the face server log
(`logs/face.log`) cover everything else.

### 3.7 What's intentionally absent from the face

Compared to the omo cylinder, the face deliberately does not have:
- The polar anamorphic warp shader (only useful for a physical glass
  cylinder; see omo/public/holo_cylinder.html for the implementation)
- The chart layer (data visualisations rendered on the hologram)
- The pane layer (full-stage iframes like /hq, /squad, /preview)
- Agent sprites + squads + ambient life + momo awareness + reactions +
  ingest (omo's NPC-style helpers)
- i18n string switching (UI language toggle)
- Text-overlay chat fallback
- Voice-mode HUD (off/PTT/on toggle)
- Reconnect backoff (the cylinder reconnects automatically; the face waits
  for the user to click "tap to retry")

If a future feature needs any of these, port them carefully — most assume
the full omo server is reachable for /omo/ws events.

---

## 4. The teleport flow, end-to-end

The whole point of the face is that **talking to Omo moves your in-game
player**. Here's the exact path from the user's voice to a Minecraft
`Player.teleport(...)` call.

```
User speaks:  "take me to the code lab"
                       │
                       ▼
   ┌───────────────────────────────────────────┐
   │ Browser mic                                │
   │  ↓ AudioWorklet (pcm-worklet.js)           │
   │  ↓ 16 kHz Int16 PCM frames                 │
   │  ↓ b64-encoded                             │
   │  → liveSession.sendRealtimeInput(...)      │
   └───────────────────────────────────────────┘
                       │
                       ▼   (over WSS to Gemini servers)
   ┌───────────────────────────────────────────┐
   │ Gemini Live (gemini-3.1-flash-live-preview)│
   │  • transcribes speech                      │
   │  • understands intent against the persona  │
   │    + function-declarations from tools.js   │
   │  • emits a toolCall:                       │
   │      { id, name: "teleport",               │
   │        args: { destination: "code lab" }}  │
   └───────────────────────────────────────────┘
                       │
                       ▼
   ┌───────────────────────────────────────────┐
   │ face-app.js::handleFunctionCall            │
   │  POST /tool                                │
   │    { name: "teleport",                     │
   │      arguments: { destination: "code lab"}}│
   └───────────────────────────────────────────┘
                       │
                       ▼
   ┌───────────────────────────────────────────┐
   │ face/server.js POST /tool handler          │
   │  → runTool("teleport", args)               │
   │    in face/src/tools.js                    │
   │  → resolves "code lab" → room "workshop"   │
   │  → POST /api/teleport                      │
   │    to http://127.0.0.1:8766                │
   └───────────────────────────────────────────┘
                       │
                       ▼
   ┌───────────────────────────────────────────┐
   │ runtime/src/http.ts handler                │
   │  → manager.broadcast({                     │
   │      type: "teleport_player",              │
   │      room: "workshop",                     │
   │      player: null })                       │
   │  → ws.send(JSON.stringify(msg))            │
   │    over WS to plugin (:8765)               │
   └───────────────────────────────────────────┘
                       │
                       ▼
   ┌───────────────────────────────────────────┐
   │ plugin IncomingHandler.handleTeleportPlayer│
   │  • resolves room "workshop" via            │
   │    RoomManager.get(name)                   │
   │  • Bukkit.getScheduler().runTask(...)      │
   │    to hop onto the main thread             │
   │  • target = first online player            │
   │  • target.teleport(new Location(...))      │
   │  • target.sendMessage("[omo] teleported …")│
   └───────────────────────────────────────────┘
                       │
                       ▼
            Player visibly warps in-game
                       │
                       ▼
   ┌───────────────────────────────────────────┐
   │ face-app.js                                │
   │  ↓ takes the tool result back              │
   │  → liveSession.sendToolResponse(           │
   │      { functionResponses: [{ id, name,     │
   │          response: { result } }] })        │
   └───────────────────────────────────────────┘
                       │
                       ▼   (over WSS to Gemini)
   ┌───────────────────────────────────────────┐
   │ Gemini Live                                │
   │  • sees the tool succeeded                 │
   │  • emits a short spoken confirmation       │
   │    ("okay, code lab!") as 24 kHz PCM audio │
   └───────────────────────────────────────────┘
                       │
                       ▼
       Audio plays back through face-app.js's
       enqueuePcmChunk → user hears confirmation
```

If any link breaks, the failure is visible in:
- `logs/face.log` → `tool → teleport ... ✗ ...` (with the actual error)
- `logs/runtime.log` → `out teleport_player {...}` should appear if the
  HTTP bridge worked
- `logs/mc.log` → `[omo] teleported to <room>` or
  `teleport_player: no room defined for '<X>'`

---

## 5. The runtime layer (`runtime/`)

A small Node + TypeScript process (~600 LOC across `src/`) doing five jobs:

1. **WebSocket server on :8765** — Paper plugin connects with a token
   handshake, then sends inbound messages (`player_message`, `spawn_agent`,
   `tool_approval`, etc.) and receives outbound (`agent_say`, `agent_status`,
   `teleport_player`, etc.).
2. **HTTP server on :8766** — currently scoped to just `POST /api/teleport`.
   This is what the face hits. If you want to expose more runtime data to
   the face (e.g. "what room is the player in right now?"), add routes here.
3. **AgentManager** — spawns and tracks HermesAgent / CodeAgent instances.
   Most relevant to the face is its public `broadcast(msg)` method, which
   the HTTP handler uses to push `teleport_player` frames onto the WS.
4. **PlayerTracker** — keeps a snapshot of every player's last position,
   room, biome, ping. Updated every 2s from the plugin. Useful telemetry
   if you want to surface live player state to the face.
5. **Tool registry** — but only for *villager* tool calls (Hermes/Claude
   talking through an NPC). The face has its own tool registry inside
   `face/src/tools.js`. The runtime never sees the face's `teleport` tool
   call directly — only the resulting `/api/teleport` POST.

Key file: `runtime/src/types.ts` defines the wire protocol. The
`OutboundMessage` type has a `teleport_player` variant that the face uses.
If you add a new outbound message type, declare it there first.

### Setting `AGENTCRAFT_RUNTIME_HTTP` (optional)

The face hardcodes `http://127.0.0.1:8766` for the runtime bridge but
respects the `AGENTCRAFT_RUNTIME_HTTP` env var. Override it in `face/.env`
if you ever run the runtime on a different host or port.

---

## 6. The Paper plugin (`plugin/`)

Java 21, Maven, Paper 1.21.4. The plugin is the **only** code in the stack
that touches Bukkit/the world.

Relevant classes for the face flow:

| Class | What it does |
|---|---|
| `com.agentcraft.bridge.BridgeClient` | Opens the WS to the runtime, handles reconnect |
| `com.agentcraft.bridge.IncomingHandler` | Switches on `type` and dispatches each kind of outbound message |
| `com.agentcraft.rooms.RoomManager` | Loads/saves `server/plugins/AgentCraft/rooms.yml`, resolves room name → Location |
| `com.agentcraft.rooms.Room` (record) | `{ name, worldName, x, y, z, radius }` |
| `com.agentcraft.commands.HermesCommand` | `/omo …` slash command. `/omo skyworld` is what builds the islands and registers their room names |
| `com.agentcraft.village.SkyIslands` | Builders for each themed island (lobby, ads, mail, workshop, game, learning, task) |
| `com.agentcraft.village.SkyHubBuilder` | Builds the central fairytale hub with portals to each island |

The `teleport_player` handler is at `IncomingHandler.handleTeleportPlayer(JsonObject)`. It:
1. Parses `room` and `player` from JSON
2. Looks up the room via `RoomManager.get(roomName)`
3. Builds a Location lifted by `y+1` so the player stands ON the pad, not in it
4. Hops onto the main thread via `Bukkit.getScheduler().runTask(plugin, () -> { ... })`
   (this is critical — WS callbacks land on JDK reactor threads, and
   touching entities from those threads will crash the server)
5. Finds the target player (named, or the first online player if `null`)
6. Calls `target.teleport(dest)` and sends an `[omo] teleported to <room>`
   chat message

### Adding a new outbound message type for the face

If you want to send a new kind of command from the face into the world
(e.g. "spawn a fireworks burst at the player's location"), the pattern is:

1. **`runtime/src/types.ts`** — add a new variant to `OutboundMessage`
2. **`runtime/src/http.ts`** — add an HTTP route that the face calls,
   which then calls `manager.broadcast(msg)`
3. **`face/src/tools.js`** — add the tool definition and its `run()` that
   POSTs to your new runtime route
4. **`face/src/session.js`** — the persona will automatically advertise
   the new tool to Gemini because `GEMINI_FUNCTION_DECLARATIONS` is
   built from the registry. You may want to add language to
   `OMO_INSTRUCTIONS` describing when to call it.
5. **`plugin/.../bridge/IncomingHandler.java`** — add a new `case` in the
   `switch (type)` and implement the handler. Remember to use
   `Bukkit.getScheduler().runTask(plugin, () -> ...)` for anything that
   touches the world.
6. **Rebuild the plugin** (`./agentcraft rebuild`) and restart MC (the
   launcher does this automatically if it's running).

---

## 7. Rooms & islands

### 7.1 What a "room" is

A `Room` is just a named sphere in a Minecraft world:

```
{ name: "workshop", worldName: "world", x: -1003, y: 192, z: 215, radius: 8 }
```

Rooms get persisted to `server/plugins/AgentCraft/rooms.yml`. The
`RoomManager` loads them on plugin enable, saves on every `define()` call,
and exposes lookup by name + by location.

### 7.2 How the islands get built

`/omo skyworld` (handler: `HermesCommand.handleSkyworld`) builds:
- A central fairytale hub (gateway/spawn) via `SkyHubBuilder`
- Three themed islands (game / learning / task) via `SkyIslands.buildGame`
  etc., ringed around the hub at 200-block radius
- A portal pair between hub and each island
- Registers each island's agent-pad centre as a Room

Older commands (`/omo village build`) add traditional rooms like `ads`,
`mail`, `workshop`, `lobby`. The face's `ISLAND_ALIASES` map assumes both
sets of names exist.

### 7.3 Adding a new island

If you want to add (e.g.) a "library" island that Omo can teleport to:

1. **`plugin/.../village/SkyIslands.java`** — add a `buildLibrary(Location)`
   method following the existing builders' pattern. Return an `IslandResult`
   with `agentPadCenter`, `playerArrival`, `returnPortalAnchor`,
   `returnPortalFacing`, `blocksPlaced`.
2. **`plugin/.../commands/HermesCommand.java::handleSkyworld`** — extend
   the `switch (anchor.slot())` to call your new builder for the new slot.
   You may also need to extend the hub anchors in `SkyHubBuilder` to add
   a fourth portal door.
3. **`plugin/.../commands/HermesCommand.java::roomNameForSlot`** — map
   the slot name to a canonical room id (e.g. `"library" -> "library"`).
4. **`face/src/tools.js::ISLAND_ALIASES`** — add user-facing phrases:
   ```js
   library: 'library',
   'library island': 'library',
   books: 'library',
   ```
5. **`face/src/session.js::OMO_INSTRUCTIONS`** — add `library / books` to
   the Available destinations list so Gemini knows to call it.
6. Rebuild plugin + restart MC; the next `/omo skyworld` will build it.

---

## 8. Setup from scratch

For a fresh laptop:

```bash
# Prerequisites (macOS)
brew install --cask temurin               # JDK 21
brew install maven node jq                # build + runtime

# Hermes-agent (only needed for villager AI inside MC; not needed for
# the face's voice + teleport loop):
curl -fsSL https://raw.githubusercontent.com/NousResearch/hermes-agent/main/scripts/install.sh | bash

# Clone + first boot
git clone <repo> omo-mc
cd omo-mc
./agentcraft setup                        # one-time: download Paper, build plugin JAR, npm install runtime + face

# Add your Gemini key (the face needs this to do voice)
echo "GEMINI_API_KEY=AIza..." > face/.env

# Run everything
./agentcraft
```

What `./agentcraft setup` does in detail:
1. Downloads the Paper 1.21.4 jar into `server/paper.jar`
2. Maven-builds the plugin and drops the JAR at `server/plugins/agentcraft.jar`
3. Runs `npm install` in `runtime/`
4. Generates a token + `runtime/.env` (the shared secret between plugin and runtime)
5. Generates EULA + server.properties
6. Marks `.agentcraft-setup-done` so it doesn't repeat

What `./agentcraft` (the default) does:
1. Verifies prereqs
2. Runs setup if it hasn't been done
3. **`ensure_face_setup`** — npm-installs `face/` if needed, copies `.env.example` to `.env` if missing
4. **`ensure_plugin_fresh`** — rebuilds the plugin JAR if any Java source is newer than the deployed jar
5. Starts hermes (best-effort; logs warning if not installed)
6. Starts the runtime; waits up to 15s for `:8765`
7. Starts the face; waits up to 10s for `:8080`
8. Starts the MC server; waits up to 60s for "Done"
9. Tails all four logs

Ctrl-C in the launcher terminal stops all four.

### Common subcommands

```
./agentcraft               # default: start everything + tail logs
./agentcraft stop          # stop everything
./agentcraft status        # show pids + reachability of each service
./agentcraft logs          # tail logs (no boot)
./agentcraft rebuild       # rebuild plugin JAR + restart MC
./agentcraft restart-face  # restart only the face (port 8080)
./agentcraft restart-runtime
./agentcraft restart-hermes
./agentcraft watch <id>    # open a new Terminal tailing one agent's log
./agentcraft dashboard     # (not currently routed; placeholder)
```

---

## 9. Running, debugging, troubleshooting

### 9.1 Tail everything

```
./agentcraft logs
```

Tails `logs/hermes.log`, `logs/runtime.log`, `logs/face.log`, `logs/mc.log`
together. Each prefixed by `==> filename <==` so you can tell which
process said what.

### 9.2 Face-specific log format

| Pattern | Meaning |
|---|---|
| `HH:MM:SS [face] listening on http://...` | Boot |
| `GET /path → 200 · Xms` (dim) | Static asset served |
| `GET /path → 404 · Xms` (yellow) | Real 404 — something broken |
| `GET /omo/ws → 404 · Xms` (dim) | Intentional 404 — cylinder probing |
| `session ✓ model=… voice=… · Xms` | Gemini token mint succeeded |
| `session ✗ <error>` | Token mint failed (usually GEMINI_API_KEY) |
| `tool → teleport destination="..."` | Tool call started |
| `teleport: "code lab" → room=workshop` | Alias resolved |
| `tool ← teleport ✓ → workshop · Xms` | Tool succeeded |
| `tool ← teleport ✗ <error>` | Tool failed |
| `[client /] [sprites] ws error ...` | Sprite layer failing — should be stubbed |
| `bridge → /api/teleport ✗ <error>` | Runtime call failed |

Set `OMO_FACE_CLIENT_VERBOSE=1` in the env (then `./agentcraft restart-face`)
to also surface routine browser `console.log` chatter from the cylinder.

### 9.3 Symptom → likely fix table

| Symptom | Most likely cause | Fix |
|---|---|---|
| Browser shows a dot but never reveals | Gemini API key invalid or `setupComplete` never arrives | Check `logs/face.log` for `session ✗`; verify `face/.env` |
| Mic permission never prompts | Browser blocked auto-mic; click the "click to wake omo" button | Always present a user gesture before the voice loop tries `getUserMedia` |
| Talking to Omo does nothing | She might not be hearing tool calls — check `logs/face.log` for `tool ⇠ Gemini:` | If absent, the persona may not be advertising the tool; check `OMO_INSTRUCTIONS` mentions the tool, and `GEMINI_FUNCTION_DECLARATIONS` includes it |
| Tool fires but player doesn't move in-game | Room not registered | Run `/omo skyworld` in-game; check `cat server/plugins/AgentCraft/rooms.yml` |
| `logs/mc.log` shows `unknown runtime message: teleport_player` | MC is running the old plugin JAR | `./agentcraft stop && ./agentcraft` (the launcher rebuilds + redeploys) |
| `logs/mc.log` shows `teleport_player: no room defined for 'X'` | Face alias maps to a name the plugin doesn't know | Either define the room (`/omo room define X`) or align `ISLAND_ALIASES` |
| Face log spammed with `/omo/ws` 404s | A non-stubbed module is opening a real WS | `grep -rn "new WebSocket" face/public/` — any hit is the culprit |
| Page is just a black canvas | Three.js scene has no lights, or the avatar is positioned offscreen | Check the diag readout top-right of the page — if it ends at "setup complete" the voice loop is fine; the issue is scene-side |
| `GET /face-app.js` still returns the old code after edit | Browser cached the module | Bump `?v=N` in `index.html`'s `<script>` src, or hard-reload |

### 9.4 Smoke tests you can run by hand

```bash
# face is up + serving
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8080/

# tool dispatch works without the runtime
curl -sS -X POST -H 'Content-Type: application/json' \
  --data '{"name":"finish_task","arguments":{}}' \
  http://127.0.0.1:8080/tool

# end-to-end teleport (face → runtime → plugin)
curl -sS -X POST -H 'Content-Type: application/json' \
  --data '{"name":"teleport","arguments":{"destination":"hq"}}' \
  http://127.0.0.1:8080/tool
# Expect: {"result":{"ok":true,"destination":"hq","room":"lobby","message":"teleported to lobby"}}
# And in-game: player visibly warps + chat says "[omo] teleported to lobby"

# direct runtime endpoint
curl -sS -X POST -H 'Content-Type: application/json' \
  --data '{"room":"workshop"}' \
  http://127.0.0.1:8766/api/teleport
```

---

## 10. Where to make changes — quick map

| You want to change… | Edit this file |
|---|---|
| Omo's voice or persona | `face/src/session.js` (`OMO_INSTRUCTIONS`) |
| Friendly phrases that teleport her | `face/src/tools.js` (`ISLAND_ALIASES`) |
| Add a new tool the face can offer to Gemini | `face/src/tools.js` (register in `REGISTRY`) |
| 3D appearance of Omo | `face/public/avatar.js::createAvatar` |
| Camera framing, lights, background of the face page | `face/public/face-app.js` (the scene setup block, lines ~54–82) |
| Status pill / hints / DOM chrome of the page | `face/public/index.html` |
| Add a new MC outbound message type | `runtime/src/types.ts` + the corresponding plugin case in `IncomingHandler.java` |
| Add a new MC inbound event from plugin to runtime | Same files, `InboundMessage` side |
| Build a new in-world island/room | `plugin/.../village/SkyIslands.java` + `commands/HermesCommand.java` |
| Logging behaviour | `face/server.js` (the access-log middleware) and `runtime/src/server.ts` |
| The launcher | `./agentcraft` (top-level shell script) |
| Boot script setup steps | `scripts/setup.sh`, `scripts/build-plugin.sh` |

---

## 11. Open work / known good things to tackle next

These are obvious follow-ups the team has flagged or that the code's
state suggests:

1. **Strip the omo-only static files** that the new `index.html` no longer
   loads (`text-overlay.js`, `voice-mode.js`, `world.js`, `i18n.js`,
   `chart-layer.js` stub, `pane-layer.js` stub). Confirm no references
   from `index.html` / `face-app.js` / `avatar.js` first.
2. **Add a `look_at_room` tool** so Omo can rotate her avatar to face the
   direction of the room she's about to teleport you to (you'd add a new
   tool that just emits a state-update to face-app.js to rotate the
   avatar; no plugin work needed).
3. **Show the player's current room on the face** — pipe the runtime's
   `PlayerTracker` state out over `/api/state` (the existing http.ts
   endpoint that was removed when we slimmed it down — see the parallel
   HQ dashboard branch for the pattern). Then face-app.js could display
   "currently in: workshop" so the user knows where they are.
4. **Make voice optional**. Right now nothing happens until you click the
   mic. A text input box (`POST /tool` directly) would be useful for demo
   contexts where the mic is blocked.
5. **A `/face/.env` toggle to disable Gemini and run voice-less** — useful
   for local plugin development when you just want to exercise teleports
   via curl.
6. **Multiplayer aware teleport** — currently `player: null` means "the
   first online player". If you have two players in the world, only the
   host moves. The face has no concept of "who am I talking to" yet —
   one fix would be a settings UI where the host names themselves.
7. **Persist a transcript** of every tool call to `runtime/data/face-log.jsonl`
   so you can replay/audit a session.

---

## 12. Pointers to other docs in this repo

- `CLAUDE.md` — high-level project rules (the "leave alone unless asked"
  list, the room → tool-set mapping for villager AI)
- `VISION.md` — the longer "why we built this" narrative
- `Omo_x_Minecraft_PRD_EN.docx` — the original PRD (English; ZH version in `Omo_x_Minecraft_PRD_EN.md`)
- `docs/ARCHITECTURE.md` — full wire-protocol between plugin and runtime
- `docs/HERMES.md` — how the villager AI brain is configured
- `docs/GMAIL.md`, `docs/META_ADS.md` — connector docs for villager tools
- `~/.claude/plans/set-up-a-new-vast-badger.md` — step-by-step guide a
  new dev can follow to see the face working

If anything in this document is wrong or stale, fix it inline rather than
adding a follow-up — the doc is meant to be the next person's first stop
and stale instructions are worse than missing ones.
