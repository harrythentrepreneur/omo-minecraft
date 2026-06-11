# Build Spec — Futuristic HQ Island + Floating Complementary Village

**For: the plugin session.** This is the spec for the in-world build. The runtime
(Gemini architect + World API + specialist brains) is done and must keep working —
see the **Integration Contract** at the bottom; don't break it.

## Vision
A sleek **futuristic floating HQ island** in the sky over the untouched real
terrain, where the Chief of Staff (`cos`) sits. As the player talks to HQ, each new
function appears as its **own floating island + building** around the HQ, joined by a
**glowing light-bridge** — a growing futuristic village that *complements* the world
and never overrides the terrain below.

## What already exists (your work — keep it)
- `world_build_request` → registers a `BuildPlot` keyed by the function room (`fn-*`)
  at `wingCenter(index)` and lays a floating grass island (`WING_ISLAND_R=16`).
- `world_staff_request` → spawns the villager + sends `spawn_agent` back.
- Plot: `WING_W/H/D = 26/20/26`, `WING_CX/CZ = 13`, `WING_SPACING = 32` (bridge room).
- Wings fan out 60° apart around the `hq` anchor room.

## The gaps to build
### 1. The futuristic HQ island (biggest win — `handleHq`)
Today `handleHq` only `rooms.define("hq", loc)` + spawns `cos`. Give HQ a real home:
- Build a **futuristic island** at the player's x/z but **at sky level** (reuse the
  `STUDIO_Y = 200` pattern, or float it well above terrain) so the real world below is
  untouched — this is the non-destructive guarantee.
- Use `IslandWorldBuilder` (`village/IslandWorldBuilder.java` — `build(Location center)
  → Result`, defines rooms, lays terrain + structures) as a **template**, but reskin
  **futuristic**: white quartz/concrete + glass, glowing edges (sea_lantern/glowstone),
  light_blue/cyan accents, copper trim. A central **HQ command building / spire** where
  `cos` sits, a landing pad, and a glowing perimeter ring.
- After building, `rooms.define("hq", <island centre>)` and spawn `cos` at the centre
  (keep id `cos`, role "Chief of Staff", room **must** be `"hq"`).
- Teleport the player onto the island.

### 2. Confirm wings float + bridge (mostly done)
- Ensure each wing island is at the **same sky Y as HQ** (so the village is one floating
  layer over the terrain), placed by `wingCenter` (already 32 out, 60° apart).
- Add a **glowing light-bridge** (e.g. sea_lantern + light_blue glass + a quartz path)
  from the HQ island edge to each wing island, so the village reads as connected.

### 3. Aesthetic
Sleek, clean, futuristic. Palette: `smooth_quartz` / `white_concrete` / `quartz_pillar`,
`glass` / `light_blue_stained_glass` / `cyan_stained_glass`, `sea_lantern` / `glowstone`
glow, `copper_block` accents. Glowing edges, symmetry, minimal forms. Match `/omo island`
quality but futuristic.

## Integration Contract — DO NOT BREAK
The runtime feeds the **building** onto each wing plot; you own the **island + bridge +
HQ**. Specifically:
1. **Keep the `hq` room name + anchor.** `wingCenter` resolves `rooms.get("hq")`; the
   World API sends `anchorRoom:"hq"`. If HQ isn't a defined room named `hq`, no wings build.
2. **Keep the wing `BuildPlot` keyed by the function room (`fn-*`).** ~13 s after
   `world_build_request`, the runtime sends `build_ops` with `agentId = fn.room`,
   `clearFirst:true`, and a **Gemini-designed building** sized to the plot
   (`26×20×26`, centred at `13,13`, walk-in door carved toward −z / low-z, lectern at
   `13,1,13`). That overlay is the real building — the fixed `wingOps` pod is only a
   fallback shown if Gemini fails. **Don't remove the plot or change its key.**
3. **If you change `WING_W/H/D` or `WING_CX/CZ`, tell the runtime session** — I keep
   `runtime/src/worldArchitect.ts` constants (`W/H/D/CX/CZ`) in sync; otherwise the
   buildings land off-centre with a misplaced door. (Currently synced to 26/20/26, 13/13.)
4. **Keep `world_staff_request` → `spawn_agent(room: fn-*)`** so the runtime builds the
   specialist `AdkAgent`. Don't change the `fn-` room prefix (it routes to
   `mission_control` → the specialist brain).
5. The building faces **−z (toward HQ)**; put the wing's bridge/entrance on that side so
   the carved walk-in door lines up with the bridge.

## Suggested order
1. `handleHq` → futuristic HQ island at sky level + central HQ building + define `hq` +
   spawn `cos` + teleport player.  2. Bridges HQ→wings.  3. Polish wing island aesthetics
   to match. Rebuild plugin JAR (`./scripts/build-plugin.sh`) + `/reload confirm` (no
   server restart).
