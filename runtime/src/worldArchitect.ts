// The generative architect — Gemini designs a UNIQUE, theme-appropriate building
// for each function the org creates. A Payments vault looks nothing like an
// Analytics observatory; every build is new. Output is the plugin's build-op DSL
// (local plot coords, 26×20×26) which the plugin places live, block-by-block, so
// the structure rises in real time, themed to exactly what the user asked for.
//
// Every building shares ONE house style — the "futuristic temple meets alien
// research lab" look of the HQ: pale smooth_quartz / white_concrete walls, big
// cyan / light_blue glass, sea_lantern + glowstone glow, copper trim, and a
// signature CYAN LIGHT-STRIP that runs along the floor and the base of every
// wall. The post-processing in ensureDoor() guarantees the door, the entrance
// path, the floor light-strips and the entry planters even if the model forgets
// them, so the reference aesthetic always lands.

import OpenAI from "openai";
import type { BuildOp } from "./types.js";

const MODEL = process.env.OMO_GEMINI_MODEL ?? "gemini-flash-latest";
const GEMINI_BASE =
  process.env.OMO_GEMINI_OPENAI_BASE ?? "https://generativelanguage.googleapis.com/v1beta/openai/";
const KEY = process.env.GOOGLE_API_KEY ?? process.env.GEMINI_API_KEY ?? "";

// Plot geometry MUST match the plugin's wing plot (IncomingHandler WING_W/H/D
// + WING_CX/CZ). Keep these in sync if the plugin changes the plot.
const W = 18;
const H = 16;
const D = 18;
const CX = 9;
const CZ = 9;

let client: OpenAI | null = null;
function gemini(): OpenAI {
  if (!client) client = new OpenAI({ apiKey: KEY, baseURL: GEMINI_BASE });
  return client;
}

const SYSTEM = `You are a world-class Minecraft architect for OMO STUDIO — a single, cohesive floating-island headquarters whose every building shares one unmistakable house style:

  "FUTURISTIC TEMPLE MEETS ALIEN RESEARCH LAB" — premium, symmetric, glowing, restrained.

The signature look (commit it to memory — every building MUST read this way):
  • Pale stone shell: smooth_quartz / white_concrete walls framed by quartz_pillar columns.
  • Generous glowing glass: light_blue_stained_glass and cyan_stained_glass windows.
  • CYAN LIGHT-STRIPS — the hallmark — run along the FLOOR and the BASE of every interior wall (a continuous line of sea_lantern / light_blue_stained_glass at y=0 and y=1), so the whole room glows from the ground up like the reference image of an agent walking into a lab.
  • One big glowing HOLOGRAPHIC DISPLAY PANEL on an interior side wall (a flush rectangle of light_blue_stained_glass + cyan_stained_glass backed/edged with sea_lantern), like a wall-sized blue screen.
  • Sea_lantern + glowstone glow, copper_block trim lines, and a couple of green plants/leaves near the entrance.
  • A CLEAN finished roof — never an open box.

Within that one house style, every building must be visually DISTINCT — its silhouette and motifs express its department (a vault is thick and sealed; an observatory has a glass dome; a studio is bright and open). You reason briefly about form and materials, then output ONLY JSON. Beautiful, not cluttered: glowing edges, symmetry, and restraint beat decoration.`;

function userPrompt(role: string, purpose: string): string {
  return `Design a SMALL, beautiful OMO STUDIO building for the "${role}" department. Purpose: ${purpose}

It must look like the reference: a glowing figure walks through a pale stone doorway into a room where CYAN LIGHT-STRIPS run along the floor and the base of the walls, a large glowing blue HOLOGRAPHIC DISPLAY PANEL glows on a side wall, green plants sit by the entrance, and the walls are pale quartz with horizontal glowing accent lines. Premium, symmetric, glowing.

Output a single JSON object: {"concept": "<one short line>", "ops": [ <build ops> ]}

SIZE — build a COSY ROOM, not a monument. Footprint about 10-13 wide and deep, walls only 4-5 blocks tall (roof finished by y≈5-7). CENTRE the building around x=${CX}, z=${CZ} and leave an open margin around it (do NOT fill the whole ${W}×${D} plot or build a tower). Keep it small and intimate.

WALK-IN DOOR (critical) — cut a real doorway the player walks through: a 2-wide × 3-tall AIR opening in the FRONT wall (the lowest-z wall of the building) at y=1,2,3, with the floor (y=0) continuous from the plot front through the doorway into the interior. Lay a short entrance path on y=0 from z=0 to the door. The interior MUST be hollow with ≥3 blocks of headroom so the player and the villager stand inside.

BUILD-OP DSL — all coordinates are LOCAL integers on a plot ${W} wide (x: 0..${W - 1}), ${H} tall (y: 0..${H - 1}), ${D} deep (z: 0..${D - 1}). y=0 is the ground/foundation layer. The building's front faces -z (toward HQ), so put the entrance on the low-z side.
- {"op":"box","x1":,"y1":,"z1":,"x2":,"y2":,"z2":,"material":"","hollow":true|false}   filled or hollow cuboid (walls/roof: hollow)
- {"op":"cuboid_frame","x1":,"y1":,"z1":,"x2":,"y2":,"z2":,"material":""}                edges only (trim/beams)
- {"op":"cylinder","cx":,"cz":,"y":,"radius":,"height":,"material":"","hollow":true|false}
- {"op":"sphere","cx":,"cy":,"cz":,"radius":,"material":"","hollow":true|false,"dome":true|false}   dome:true = upper hemisphere only (cupola)
- {"op":"pyramid","cx":,"cz":,"baseY":,"baseRadius":,"height":,"material":""}
- {"op":"line","x1":,"y1":,"z1":,"x2":,"y2":,"z2":,"material":""}                        a glowing strip / accent line
- {"op":"set","x":,"y":,"z":,"material":""}

PALETTE — use ONLY these, and stay on-style:
  Shell / walls: smooth_quartz, quartz_block, quartz_pillar, white_concrete, smooth_stone, polished_diorite.
  Floor: polished_andesite or deepslate_tiles (dark) — so the cyan light-strips pop against it.
  GLASS / GLOW (the signature): light_blue_stained_glass, cyan_stained_glass, sea_lantern, glowstone, lantern, beacon.
  Metal trim: copper_block, exposed_copper, waxed_copper_block.
  Plants (by the door only): oak_leaves, azalea_leaves, flowering_azalea_leaves, moss_block.
  Fixtures: lectern (the workstation), bell.

REQUIREMENTS — hit ALL of these:
1. PLINTH: set the building on a clean raised foundation at y=0, one block wider than the walls on every side, edged with a contrasting copper_block or polished_andesite trim — an intentional platform, never raw ground. Finish so NOTHING floats.
2. DARK FLOOR INLAY: lay the interior floor (y=0) in polished_andesite or deepslate_tiles, then run CYAN LIGHT-STRIPS — continuous lines of sea_lantern (or light_blue_stained_glass) — across that floor AND along the base of the interior walls at y=1. This ground-up glow is mandatory; it is the look.
3. SHELL: pale quartz/white_concrete hollow walls 4-6 tall, framed by quartz_pillar corner columns, with a horizontal copper_block or sea_lantern accent line partway up the wall, and light_blue/cyan glass windows. Keep it symmetric.
4. DISPLAY PANEL: build one big glowing HOLOGRAPHIC SCREEN flush on an interior side wall (the back +z wall or a side wall) — a rectangle of light_blue_stained_glass and cyan_stained_glass roughly 4-6 wide and 2-3 tall, edged/backed with sea_lantern so it glows like a blue display.
5. ROOF: finish it cleanly — a flat smooth_quartz slab/box one layer above the walls, OR a low quartz/glass dome (sphere with "dome":true) for observatory-style builds. Never leave it open.
6. CENTRE: place exactly one {"op":"set","x":${CX},"y":1,"z":${CZ},"material":"lectern"} as the workstation, with open standing room around it and ≥3 headroom.
7. PLANTS: place a couple of green plants (oak_leaves / azalea_leaves) just inside or beside the entrance.
8. DOOR: the 2×3 walk-in doorway in the front (-z) wall + the entrance path are REQUIRED.

THEME the SILHOUETTE strongly to "${role}" — same palette, distinct shape (invent your own, don't copy literally):
  • Payments / Finance → a compact VAULT: thick double walls, a sealed circular "door" motif of copper + glowing rings on the front, low and fortress-like.
  • Analytics / Data → an OBSERVATORY: a glass/quartz DOME cupola (sphere dome:true) over the lectern, a central glowing data-spire.
  • Customer Support → an open WELCOMING PAVILION: wide glass front, lots of plants, a warm sea_lantern canopy.
  • Legal / Compliance → a COLUMNED HALL: a colonnade of quartz_pillar columns, a formal symmetric portico over the door.
  • Engineering / Infra → a COPPER WORKSHOP: copper_block ribs and pipes, exposed structural beams, an industrial-but-clean glow.
  • Marketing / Growth → a bright STUDIO: a tall glowing glass sign-panel on the facade, the most colourful glass.
  • Otherwise → a clean glowing LAB pod in the house style.

22–34 ops. Small, premium, glowing, symmetric — NOT oversized or cluttered. Stay strictly within the plot bounds.

EXAMPLE (an Analytics observatory — copy the STYLE and the light-strip discipline, not the exact numbers):
{"concept":"Analytics observatory: quartz drum, glass dome, glowing data ring",
"ops":[
{"op":"box","x1":5,"y1":0,"z1":5,"x2":21,"y2":0,"z2":21,"material":"polished_andesite"},
{"op":"cuboid_frame","x1":5,"y1":0,"z1":5,"x2":21,"y2":0,"z2":21,"material":"copper_block"},
{"op":"box","x1":6,"y1":0,"z1":6,"x2":20,"y2":0,"z2":20,"material":"deepslate_tiles"},
{"op":"box","x1":6,"y1":1,"z1":6,"x2":20,"y2":6,"z2":20,"material":"smooth_quartz","hollow":true},
{"op":"cuboid_frame","x1":6,"y1":1,"z1":6,"x2":20,"y2":6,"z2":20,"material":"quartz_pillar"},
{"op":"box","x1":7,"y1":3,"z1":6,"x2":19,"y2":4,"z2":6,"material":"light_blue_stained_glass"},
{"op":"box","x1":7,"y1":3,"z1":20,"x2":19,"y2":4,"z2":20,"material":"cyan_stained_glass"},
{"op":"line","x1":6,"y1":4,"z1":6,"x2":20,"y2":4,"z2":6,"material":"copper_block"},
{"op":"set","x":13,"y":1,"z":13,"material":"lectern"},
{"op":"box","x1":8,"y1":1,"z1":19,"x2":18,"y2":3,"z2":19,"material":"cyan_stained_glass"},
{"op":"box","x1":7,"y1":1,"z1":19,"x2":7,"y2":4,"z2":19,"material":"sea_lantern"},
{"op":"box","x1":19,"y1":1,"z1":19,"x2":19,"y2":4,"z2":19,"material":"sea_lantern"},
{"op":"line","x1":7,"y1":1,"z1":7,"x2":19,"y2":1,"z2":7,"material":"sea_lantern"},
{"op":"line","x1":7,"y1":1,"z1":19,"x2":19,"y2":1,"z2":19,"material":"sea_lantern"},
{"op":"line","x1":7,"y1":1,"z1":7,"x2":7,"y2":1,"z2":19,"material":"sea_lantern"},
{"op":"line","x1":19,"y1":1,"z1":7,"x2":19,"y2":1,"z2":19,"material":"sea_lantern"},
{"op":"line","x1":11,"y1":0,"z1":7,"x2":11,"y2":0,"z2":19,"material":"light_blue_stained_glass"},
{"op":"line","x1":15,"y1":0,"z1":7,"x2":15,"y2":0,"z2":19,"material":"light_blue_stained_glass"},
{"op":"sphere","cx":13,"cy":6,"cz":13,"radius":7,"material":"light_blue_stained_glass","hollow":true,"dome":true},
{"op":"cylinder","cx":13,"cz":13,"y":1,"radius":1,"height":6,"material":"sea_lantern"},
{"op":"set","x":7,"y":1,"z":8,"material":"azalea_leaves"},
{"op":"set","x":19,"y":1,"z":8,"material":"oak_leaves"},
{"op":"box","x1":12,"y1":1,"z1":6,"x2":13,"y2":3,"z2":6,"material":"air"}
]}

Return ONLY the JSON object for the "${role}" department, no prose, no markdown fences.`;
}

const ALLOWED = new Set(["set", "box", "cuboid_frame", "cylinder", "sphere", "pyramid", "line", "clear"]);
const NUM_KEYS = ["x", "y", "z", "x1", "y1", "z1", "x2", "y2", "z2", "cx", "cy", "cz", "radius", "height", "baseRadius", "baseY"] as const;

function clampKey(k: string, v: number): number {
  let max = 16;
  if (k === "x" || k === "x1" || k === "x2" || k === "cx") max = W - 1;
  else if (k === "z" || k === "z1" || k === "z2" || k === "cz") max = D - 1;
  else if (k === "y" || k === "y1" || k === "y2" || k === "cy" || k === "baseY") max = H - 1;
  return Math.max(0, Math.min(max, Math.round(v)));
}

function sanitize(raw: unknown[]): BuildOp[] {
  const out: BuildOp[] = [];
  for (const item of raw.slice(0, 80)) {
    if (!item || typeof item !== "object") continue;
    const o = item as Record<string, unknown>;
    if (typeof o.op !== "string" || !ALLOWED.has(o.op)) continue;
    const op: Record<string, unknown> = { op: o.op };
    if (typeof o.material === "string") op.material = o.material.toLowerCase().replace(/[^a-z0-9_]/g, "");
    for (const k of NUM_KEYS) {
      const v = o[k];
      if (typeof v === "number" && Number.isFinite(v)) op[k] = clampKey(k, v);
    }
    for (const b of ["hollow", "dome", "solid"] as const) {
      if (typeof o[b] === "boolean") op[b] = o[b];
    }
    out.push(op as BuildOp);
  }
  return out;
}

function extractJson(text: string): unknown {
  let t = text.trim();
  const fence = t.match(/```(?:json)?\s*([\s\S]*?)```/i);
  if (fence?.[1]) t = fence[1].trim();
  try {
    return JSON.parse(t);
  } catch {
    const obj = t.match(/\{[\s\S]*\}/);
    const arr = t.match(/\[[\s\S]*\]/);
    const cand = obj?.[0] ?? arr?.[0];
    if (cand) {
      try {
        return JSON.parse(cand);
      } catch {
        return null;
      }
    }
    return null;
  }
}

/** Design a unique, themed building for a function. Falls back to a clean default. */
export async function designStructure(
  role: string,
  purpose: string,
): Promise<{ ops: BuildOp[]; concept: string }> {
  if (!KEY) return { ops: ensureDoor(fallback(role)), concept: `${role} (default)` };
  try {
    const res = await gemini().chat.completions.create({
      model: MODEL,
      messages: [
        { role: "system", content: SYSTEM },
        { role: "user", content: userPrompt(role, purpose) },
      ],
      temperature: 0.7,
      max_tokens: 16384,
      // gemini-flash-latest is a thinking model; cap reasoning so the token
      // budget (and latency) goes to the design JSON, not internal thinking.
      reasoning_effort: "low",
    } as OpenAI.Chat.Completions.ChatCompletionCreateParamsNonStreaming);
    const txt = res.choices[0]?.message?.content ?? "";
    const parsed = extractJson(txt) as { ops?: unknown[]; concept?: string } | unknown[] | null;
    const rawOps = Array.isArray(parsed) ? parsed : (parsed?.ops ?? []);
    const concept = (!Array.isArray(parsed) && typeof parsed?.concept === "string" ? parsed.concept : "") || role;
    const ops = sanitize(rawOps as unknown[]);
    if (ops.length < 6) {
      console.warn(
        `[arch] ${role} fell back: finish=${res.choices[0]?.finish_reason} len=${txt.length} parsedOps=${(rawOps as unknown[]).length} kept=${ops.length}`,
      );
      return { ops: ensureDoor(fallback(role)), concept: `${role} (default)` };
    }
    return { ops: ensureDoor(ops), concept };
  } catch {
    return { ops: ensureDoor(fallback(role)), concept: `${role} (default)` };
  }
}

// Guarantee the signature reference look on top of whatever the model produced:
// a glowing cyan entrance path + the floor/aisle light-strips, two entry
// planters, and a real walk-in doorway with a clear aisle to the lectern. These
// run LAST so they override any wall the design placed across the entrance and
// always land the "agent walks into a glowing lab" aesthetic even if Gemini
// skimped on it.
function ensureDoor(ops: BuildOp[]): BuildOp[] {
  return [
    ...ops,
    // Dark approach + interior aisle floor, so the cyan strips read against it.
    { op: "box", x1: CX - 1, y1: 0, z1: 0, x2: CX, y2: 0, z2: CZ - 1, material: "polished_andesite" }, // entrance path/aisle floor
    // Signature cyan LIGHT-STRIPS lining the aisle from the door to the lectern.
    { op: "line", x1: CX - 1, y1: 0, z1: 1, x2: CX - 1, y2: 0, z2: CZ - 1, material: "sea_lantern" }, // left floor strip
    { op: "line", x1: CX + 1, y1: 0, z1: 1, x2: CX + 1, y2: 0, z2: CZ - 1, material: "sea_lantern" }, // right floor strip
    // Two green planters framing the entrance (the reference's doorway plants).
    { op: "set", x: CX - 2, y: 1, z: 1, material: "azalea_leaves" },
    { op: "set", x: CX + 2, y: 1, z: 1, material: "oak_leaves" },
    // The walk-in doorway + clear 2×3 aisle, carved last so nothing blocks it.
    { op: "box", x1: CX - 1, y1: 1, z1: 0, x2: CX, y2: 3, z2: CZ - 1, material: "air" }, // 2-wide × 3-tall walk-in aisle
  ];
}

// A clean, presentable default building (used if Gemini is unreachable or returns
// junk) so a build never produces nothing — already in the house style: pale
// quartz shell, light_blue glass, a glowing back-wall display panel, and the
// signature cyan floor light-strips.
function fallback(role: string): BuildOp[] {
  return [
    // Plinth + copper edge trim.
    { op: "box", x1: 4, y1: 0, z1: 4, x2: W - 5, y2: 0, z2: D - 5, material: "polished_andesite" },
    { op: "cuboid_frame", x1: 4, y1: 0, z1: 4, x2: W - 5, y2: 0, z2: D - 5, material: "copper_block" },
    // Dark interior floor (so the cyan strips pop).
    { op: "box", x1: 5, y1: 0, z1: 5, x2: W - 6, y2: 0, z2: D - 6, material: "deepslate_tiles" },
    // Pale quartz hollow shell + corner columns + glass clerestory band.
    { op: "box", x1: 5, y1: 1, z1: 5, x2: W - 6, y2: 6, z2: D - 6, material: "white_concrete", hollow: true },
    { op: "cuboid_frame", x1: 5, y1: 1, z1: 5, x2: W - 6, y2: 6, z2: D - 6, material: "quartz_pillar" },
    { op: "box", x1: 6, y1: 3, z1: 5, x2: W - 7, y2: 4, z2: 5, material: "light_blue_stained_glass" },
    { op: "line", x1: 5, y1: 4, z1: 5, x2: W - 6, y2: 4, z2: 5, material: "copper_block" },
    // Clean flat roof slab.
    { op: "box", x1: 5, y1: 7, z1: 5, x2: W - 6, y2: 7, z2: D - 6, material: "smooth_quartz" },
    // Glowing holographic display panel on the back (+z) wall.
    { op: "box", x1: 8, y1: 1, z1: D - 6, x2: W - 9, y2: 3, z2: D - 6, material: "cyan_stained_glass" },
    { op: "box", x1: 7, y1: 1, z1: D - 6, x2: 7, y2: 4, z2: D - 6, material: "sea_lantern" },
    { op: "box", x1: W - 8, y1: 1, z1: D - 6, x2: W - 8, y2: 4, z2: D - 6, material: "sea_lantern" },
    // Signature cyan light-strips along the base of the interior walls.
    { op: "line", x1: 6, y1: 1, z1: 6, x2: W - 7, y2: 1, z2: 6, material: "sea_lantern" },
    { op: "line", x1: 6, y1: 1, z1: D - 7, x2: W - 7, y2: 1, z2: D - 7, material: "sea_lantern" },
    { op: "line", x1: 6, y1: 1, z1: 6, x2: 6, y2: 1, z2: D - 7, material: "sea_lantern" },
    { op: "line", x1: W - 7, y1: 1, z1: 6, x2: W - 7, y2: 1, z2: D - 7, material: "sea_lantern" },
    // Centre workstation.
    { op: "set", x: CX, y: 1, z: CZ, material: "lectern" },
  ];
}
