# Village Ecosystem Plan — turning the floating village into a *living* society

**Status:** design only. No code changed by this document.
**Goal:** make the growing skyblock village *feel alive* — an ecosystem of villagers
and realtime agents the player can monitor, where existing buildings get socially
integrated into the village and agents interact across rooms, not just with HQ.

This plan respects the two-session division of labour from `docs/HQ_ISLAND_SPEC.md`:

- **Plugin lane** (Java, Paper) owns the *world*: islands, bridges, paths, NPC
  bodies, animations, glow. It only knows room *names*.
- **Runtime lane** (Node/TS) owns the *brains*: the Gemini architect, the World
  API over MCP, the `AdkAgent`/`HermesAgent` brains, the org graph (`WorldStore`),
  and the dashboard server.
- The two talk over the one WebSocket; the plugin reacts to `*_request` frames
  the runtime broadcasts, and the runtime reacts to nothing the plugin sends back
  except the existing `spawn_agent`/chat paths. **Every new world behaviour below
  is driven by a new outbound frame type from runtime → plugin** (mirroring
  `world_build_request` / `world_staff_request`), so the lanes stay clean.

The four capabilities, ordered by build priority (see **§5 Recommendation**):

1. Agent-to-agent consultation (`world_consult`) — *the soul of the ecosystem*.
2. Social integration of new buildings (paths/light-bridges between related
   buildings + a "villager visits" walk).
3. Live monitoring / Society View (`/dash/society`).
4. Ambient life (idle emotes, short walk loops, thinking-glow).

---

## Grounding — the load-bearing facts these designs build on

- `runtime/src/worldStore.ts` — `WorldStore` holds the org graph: a `Map<id,
  OmoFunction>` where `OmoFunction = { id, role, purpose, tools, room: "fn-<id>",
  index, staffed, agentId, dashboardId, createdAt }`. **No relationships, no
  consultation history today.** In-memory by design.
- `runtime/src/mcpServer.ts` — the World API MCP tools: `world_describe`,
  `world_add_function`, `world_build`, `world_staff`, `world_assign`,
  `dashboard_update`. `world_assign` is **fire-and-forget**:
  `void agent.handleMessage(world.owner, task)` — it does *not* await a reply.
- `runtime/src/agents/AgentManager.ts` — `spawn()` picks the brain from
  `roomKind`; `fn-*` rooms → `mission_control` → `AdkAgent` (the "specialist").
  `list()` returns `{id,name,role,home,room,status}`; `snapshot()` already
  produces a rich `StateSnapshot` (per-room metrics, status breakdown, pending
  approvals). `broadcast(msg)` is the one-liner that pushes any outbound frame to
  the plugin.
- `runtime/src/agents/AdkAgent.ts` — `handleMessage(player, text)` POSTs to the
  ADK `/run_sse` stream and renders events into in-world surfaces. **It returns
  `void` and never resolves with the answer** — the reply only escapes via
  `events.onSay(...)`. This is the crux of `world_consult` (see §1).
- `runtime/src/dashboardServer.ts` — offline Node-`http` server, routes
  `GET /dash/:id` (HTML) and `GET /dash/:id/data` (JSON `DashboardData`). Easy to
  add `/dash/society`. Has `setDashboardData`/`getDashboardData`.
- `plugin/.../bridge/IncomingHandler.java` — `onMessage` switch routes runtime →
  world frames. `handleWorldBuild` lays a floating island via `buildWingIsland`,
  draws a glowing `buildBridge(HQ → wing)`, enqueues the building ops.
  `handleWorldStaff` spawns the villager + a wing dashboard. `wingCenter(m)` fans
  wings 60° apart, `WING_SPACING=32` out from `hq`.
- `plugin/.../village/IslandWorldBuilder.java` — `path(w, fy, x1,z1, x2,z2)`
  carves a 3-wide `DIRT_PATH` between two points; the road idiom to reuse.
- `plugin/.../agents/AgentNpc.java` — bodies already support a walk animation:
  `beginBuild(center)` / `buildStep(focus, holding)` / `endBuild()` ease the
  villager body to a world point with gravity off and re-home via `tickPosition`
  (leash radius → teleport home). `setStatus(status,detail)` is the single hook
  that updates name tag / held item / boss bar — **the natural place to add a
  thinking-glow.** `spawnParticle(Particle.HAPPY_VILLAGER, …)` already used.

---

## 1. Agent-to-agent consultation — `world_consult`

**The feature:** a staffed specialist (or the Chief of Staff) can ask *another*
function's specialist a question and get its answer back inline, so collaboration
is real — Growth asks Finance "what's our blended CAC ceiling?", Finance's agent
answers, Growth uses the number. This is what turns a set of isolated rooms into a
*society*.

### Mechanism — how the reply comes back synchronously

`world_consult` is an **async MCP tool that awaits a one-shot promise**, resolved
when the consulted agent produces its reply. The MCP tool handler can `await` —
the calling Gemini agent's tool call simply blocks until the handler returns, so
"synchronous to the asker" = "the MCP handler returns the answer string".

The hard part: `AdkAgent.handleMessage` returns `void` and only emits its answer
through `events.onSay`. We need to *capture* that answer. Two clean options:

- **Option A (recommended — minimal, no brain change):** add an
  `ask(player, text): Promise<string>` method to `AdkAgent` that runs the *same*
  stream as `handleMessage` but resolves with the final consolidated text (the
  string it would have `onSay`'d — the `stream()` method already computes `spoken`
  / the longest `partials` buffer as a fallback; return that). `handleMessage`
  can be refactored to `await this.ask(...)` then `onSay` the result, so there's
  one code path. This keeps the in-room reasoning stream (the consulted agent's
  room screen still shows it thinking) *and* hands the answer back to the caller.
- **Option B (event-bus, more plumbing):** keep `handleMessage` as-is, register a
  `pendingConsults: Map<consultId, resolver>` in `AgentManager` (mirrors
  `pendingApprovals`), tag the inbound message with a consult id, and have the
  agent resolve it on its first `onSay`. More moving parts; only worth it if we
  later want fan-out (one question to many agents). **Use A for the hackathon.**

### Concrete changes

**Runtime lane:**

- `runtime/src/worldStore.ts` — add a lightweight consultation log + (optional)
  relationships:
  - `type Consultation = { id; from; to; question; answer?; at; status: "pending"|"answered"|"failed" }`
  - `private consults: Consultation[] = []` with `recordConsult(...)` /
    `resolveConsult(id, answer)` / `recentConsults(n)` (last N, newest first).
    Feeds the Society View (§3) and the in-world "visit" animation (§2).
  - Optional `relatesTo: string[]` on `OmoFunction` (function ids it commonly
    works with) — populated lazily: any pair that has consulted becomes
    "related". Cheap, and drives §2's inter-building bridges.
- `runtime/src/agents/AdkAgent.ts` — add `ask(player, text): Promise<string>`
  (Option A above); refactor `handleMessage` to use it. ~25 lines.
- `runtime/src/mcpServer.ts` — register the new tool:

  ```
  world_consult(from_function, to_function, question)
    1. const to = world.get(to_function); guard !staffed → error string.
    2. const c = world.recordConsult({from, to, question});
    3. broadcast a `world_consult_request` frame (for the §2 visit animation):
         { type:"world_consult_request", from, to, fromRoom, toRoom }
    4. const agent = manager.get(to.agentId);
       const answer = await agent.ask(`${from.role} asks: ${question}`);
    5. world.resolveConsult(c.id, answer);
    6. broadcast `world_consult_done` (visitor walks home);
    7. return ok({ from, to, question, answer });
  ```

  Prompt the specialists (their persona seed in `AgentManager.spawn`) that they
  may call `world_consult` to ask a peer for a number/fact they don't own — that
  one sentence is what makes Gemini actually use it.

**Plugin lane (purely cosmetic, optional for v1 — see §2):**

- `IncomingHandler.onMessage` — add `case "world_consult_request"` /
  `"world_consult_done"` → the "villager visits" animation (§2). If we skip the
  animation, consultation is *fully functional with zero plugin work*.

### Difficulty / risk

**Medium, low risk.** The only real surgery is `AdkAgent.ask` returning the
final text; everything else is additive. Risk: a consult that hangs (consulted
agent stuck) blocks the asker — mitigate with a `Promise.race` timeout (~30s) in
the tool handler that resolves to `"(no answer — <to> was busy)"`, mirroring the
120s approval auto-deny pattern already in `AgentManager`. **No plugin rebuild
required** for the functional core.

---

## 2. Social integration of new buildings — roads between peers + "a villager visits"

**The feature:** today every new island only gets a bridge *to HQ* (`buildBridge`
in `handleWorldBuild`). A society has *lateral* connections: related buildings are
joined to each other, and when two functions collaborate, a villager physically
walks from one building to the other. Both are **plugin-lane**, driven by frames
the runtime already knows how to send.

### 2a. Light-bridges / paths between *related* buildings

The plugin already has both primitives: `buildBridge(world, from, to)` (glowing
quartz + sea-lantern span, animated) and `IslandWorldBuilder.path(...)` (carved
dirt road). We reuse `buildBridge` between two *wings*, not just HQ→wing.

**Mechanism:** when the runtime learns two functions are related (first time they
consult, or an explicit architect call), it tells the plugin to span them.

- **Runtime lane:** in `mcpServer.ts`, the first time `world_consult` links
  `from`↔`to` (or via a new tiny tool `world_link(a_function, b_function)` the
  architect can call deliberately), broadcast:

  ```
  { type:"world_link_request", roomA:"fn-growth", roomB:"fn-finance",
    anchorRoom:"hq", indexA, indexB }
  ```

  (`indexA/indexB` let the plugin recompute each wing centre via the existing
  `wingCenter` math without storing positions runtime-side.)
- **Plugin lane:** `IncomingHandler` —
  - Add `case "world_link_request"` → resolve both wing centres with a small
    refactor of `wingCenter` to take an explicit `index` (it already reads
    `index` from the JSON; extract a `wingCenterForIndex(anchorRoom, index)`
    helper), then call the **existing** `buildBridge(w, centreA, centreB)`. The
    bridge animator is index-agnostic — it just spans two points — so this is
    almost free. One guard: skip if a link between this pair already exists
    (track a `Set<String>` of "a|b" keys in `IncomingHandler`).
  - This makes the village read as a *web* of glowing spans between collaborators,
    not a star around HQ.

### 2b. "A villager visits" walk animation

When a consultation happens (§1's `world_consult_request`), send a villager from
the *asking* building over to the *consulted* building and back, so the player
*sees* the collaboration.

**Mechanism — reuse the build-walk rig in `AgentNpc`:** `beginBuild` already
turns AI + gravity off and lets us drive the body to arbitrary world points via
repeated `buildStep(focus, …)`; `endBuild` restores it and `tickPosition`
re-homes it. We add a purpose-built, simpler `walkTo`/`visit` API rather than
overloading build semantics:

- **Plugin lane:** in `AgentNpc.java` add a small visit state machine:
  `startVisit(Location target)`, a per-tick `visitStep()` that eases `buildPos`
  toward `target` (copy the easing math from `buildStep` minus the block-laying),
  emits the occasional `Particle.HAPPY_VILLAGER`, and on arrival pauses ~2s then
  walks home and calls the existing re-home path. Reuses `villager.setAI(false)` +
  `villager.teleport(buildPos)` exactly like the mason. ~40 lines.
- Drive it from `IncomingHandler`:
  - `case "world_consult_request"` → `AgentNpc visitor = agents.get(<from
    function's agentId>)`; `Location dest = wingCenterForIndex(toRoom's index)`;
    `visitor.startVisit(dest)`. The bridge built in 2a means he walks *across the
    glowing span* — which looks fantastic.
  - `case "world_consult_done"` → `visitor.returnHome()` (or let the visit state
    machine auto-return after its dwell).
  - The runtime must include the *asking* function's `agentId` + the target
    `index` in the `world_consult_request` frame (it has both from `WorldStore`).

**Threading:** all of this runs inside `onMessage`, which is already on the main
thread (per the `IncomingHandler` contract), so entity moves are safe. The
per-tick stepping uses a `BukkitRunnable.runTaskTimer` like `buildBridge` does.

### Difficulty / risk

- **2a (peer bridges): Easy, low risk.** Pure reuse of `buildBridge`; the only
  new logic is computing the second wing centre (math already present) and
  dedup. Worst case a redundant bridge is drawn (idempotent block sets).
- **2b (visit walk): Medium, medium risk.** The walk rig exists but villager
  pathing-by-teleport over a 32-block gap can look janky if the easing constant
  is off, and a villager that wanders past `LEASH_RADIUS` gets yanked home by
  `tickPosition` mid-walk — so the visit state must suppress `tickPosition`'s
  re-home (the build rig already does this via the `building` flag; mirror it
  with a `visiting` flag). Cosmetic-only, so a bug never breaks function.

---

## 3. Live monitoring / Society View — `/dash/society`

**The feature:** one HQ board (and a browser tab) showing the *whole* ecosystem at
a glance: every function, its status (idle / thinking / tool-call), a thumbnail of
its live dashboard, and the consultations flowing between rooms. The player
monitors the society without walking to each building.

### Mechanism — a new dashboard route fed by existing snapshots

Everything the Society View needs already exists in memory; we just join three
sources and render them.

- **Runtime lane:**
  - `runtime/src/dashboardServer.ts` — add a special-case in `handle()` for
    `GET /dash/society` (HTML) and `GET /dash/society/data` (JSON). Because the
    payload shape differs from `DashboardData`, give it its own
    `setSocietyData(...)` store entry (or a dedicated getter) rather than abusing
    the KPI schema. The HTML stays offline (inline CSS/JS, same constraints as
    `dashboard.html.ts`).
  - A small `runtime/src/society.ts` (or a function in `mcpServer.ts`/`server.ts`)
    assembles the payload on each `/data` poll:
    - `world.list()` → every function (role, purpose, room, staffed).
    - `manager.list()` (or the richer `manager.snapshot()`) → live `status` per
      agent, joined to functions by `agentId`. `snapshot()` *already* gives
      status breakdown, per-room tool-call counts, sparklines, and pending
      approvals — most of the Society View is literally `snapshot()` reshaped.
    - `world.recentConsults(20)` (from §1) → the "who's talking to whom" feed +
      live edges.
  - Each function card embeds its own board as a thumbnail via an `<iframe
    src="/dash/<room>">` (same server, already serving those) — so the Society
    View is a *wall of live dashboards* plus a status/edge overlay.
  - Wire `startDashboardServer` is already called at boot; no new lifecycle.
- **Plugin lane (optional):** point a cinema wall on the HQ island at
  `http://127.0.0.1:8088/dash/society` using the **existing** `buildWingDashboard`
  pattern (it already builds a `CinemaScreen` and `setUrl`s it). A `/omo society`
  command or a one-liner in `handleHq` places the board. No new frame type needed
  — it's just another cinema URL. ~15 lines, fully reuses the cinema pipeline.

### What renders

- A node per function (status dot: grey idle / amber thinking / blue tool-call,
  straight from `status`), labelled with role.
- Animated edges for consultations from `recentConsults` (pulse an edge while
  `status==="pending"`).
- A live KPI strip from `snapshot().totals` (agent count, tool calls 24h, rooms,
  pending approvals).
- A grid of live dashboard thumbnails (the iframes).

### Difficulty / risk

**Easy-to-medium, low risk.** The data already exists (`snapshot()` does 80% of
it); the work is one new route + one HTML page + the join. No plugin rebuild
needed for the browser view (only for the in-world cinema board, which is optional
and reuses proven code). Risk is purely presentational.

---

## 4. Ambient life — idle emotes, short walk loops, thinking-glow

**The feature:** cheap touches so the village never feels frozen — idle villagers
occasionally emote or stroll a few blocks; a building **glows** while its agent is
"thinking". All **plugin-lane**, all reuse.

### Mechanisms

- **Thinking-glow (highest impact, lowest cost):** `AgentNpc.setStatus` is already
  called on every status change (`thinking`/`tool_call`/`idle`) from the runtime's
  `onStatus` event. Extend `setStatus` to drive an emissive cue on the building:
  - Simplest: when status ∈ {`thinking`,`tool_call`}, set the villager glowing via
    `villager.setGlowing(true)` (team-coloured outline) and/or place/animate a
    light source (the wing already has a `sea_lantern` crown ring — toggle a few
    `glowstone`↔`sea_lantern` blocks around the rim, or spawn an upward
    `Particle.END_ROD` column). Revert on `idle`. The block/particle target is the
    wing centre, which the NPC knows via `home()`.
  - This makes the skyline *pulse with thought* — you can stand at HQ and watch
    which buildings are working. Single method, ~15 lines, zero new frames.
- **Idle emotes + short walk loops:** `AgentNpc` already runs a per-tick
  `tickPosition`. Add an idle behaviour there: when `status=="idle"` and not
  building/visiting, on a slow random cadence either (a) `swingMainHand()` +
  `spawnParticle(HAPPY_VILLAGER)` (a wave/emote), or (b) ease the body a few
  blocks around `originalHome` and back within the leash radius (a stroll),
  reusing the same easing as `buildStep`. Keep it inside the leash so
  `tickPosition`'s existing re-home guard never fights it. ~30 lines.
- **Optional cross-room flavour:** occasionally (low probability), trigger a §2b
  "visit" to a *random related* building even without a consult, so villagers are
  seen mingling. Reuses §2b entirely; gate it so it never collides with a real
  consult walk.

### Difficulty / risk

**Easy, very low risk.** Self-contained in `AgentNpc`; no runtime changes, no new
frames, no protocol surface. The glow is the single best effort-to-impact line in
this whole plan. Only caution: keep the per-tick work cheap (it runs for every
villager every tick) and keep strolls inside the leash radius so bodies never
drift off their islands.

---

## 5. Recommendation — biggest "alive" for least risk, hackathon-sized

Build, in order:

1. **`world_consult` (§1) + the thinking-glow (§4 first bullet).** This pair is
   the highest leverage. `world_consult` makes the agents a *society* (the
   conceptual core the user asked for) and is mostly additive runtime work with no
   required plugin rebuild. The thinking-glow makes that society *visible* — the
   skyline pulses as agents reason — for ~15 lines in one Java method. Together
   they deliver "an ecosystem of realtime agents we can monitor" with the least
   surface area.
2. **Society View `/dash/society` (§3).** Almost free because `AgentManager.
   snapshot()` already computes the hard parts and the dashboard server +
   cinema-wall pipeline already exist. It's the "monitor the whole ecosystem at a
   glance" deliverable, and it visually *proves* the consultations from step 1.
3. **Peer light-bridges (§2a).** Easy, dramatic, pure reuse of `buildBridge` — the
   village stops looking like a star and starts looking like a web. Do this once
   §1 is emitting `world_link`/consult signals to feed it.

Defer the **visit-walk animation (§2b)** and **idle strolls (§4)** to polish time:
they're the most finicky (teleport-pathing jank, leash fights) and the *least*
load-bearing — beautiful garnish, but the ecosystem already reads as alive from
steps 1-3 (glowing, talking, visibly connected). Ship the garnish only if the
clock allows.

**One-line summary of the minimum viable "living ecosystem":** `world_consult`
(agents talk and answer each other) + thinking-glow (you see them think) +
`/dash/society` (you watch the whole thing) + peer bridges (you see who's
connected) — all but the glow are additive runtime work, the glow is one Java
method, and nothing here violates the plugin/runtime lane split.
