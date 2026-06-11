// Single source of truth for the omo-mc "real map" — the PRD §8.1–§8.2
// layout (HQ + 4 portal districts + 3 sky islands) plus the sub-rooms
// reachable inside each district.
//
// Why this file exists:
//   The face used to ship its own alias table that pointed at room names
//   the plugin had never registered (e.g. "ads", "mail", "task"), so
//   /api/teleport silently failed for most phrases. This map is the
//   contract: a stable canonical id per district + an ordered list of
//   real room candidates the plugin should try.
//
// Consumers:
//   - runtime/src/http.ts      — resolves /api/teleport requests
//   - face/src/map.js          — sibling copy (plain JS) for the face
//
// If you change this file, mirror it in face/src/map.js. Keep them tiny.

export interface MapDestination {
  /** Canonical id used in alias resolution (lowercase, no spaces). */
  id: string;
  /** User-facing label for logs / error messages. */
  display: string;
  /** Lowercased phrases the user might say. Must include {@link id}. */
  aliases: string[];
  /**
   * Ordered list of real room names the plugin may have registered for
   * this destination. The plugin walks them in order and uses the first
   * match in its room registry. Order = preference (most specific first).
   */
  roomCandidates: string[];
}

export const MAP_DESTINATIONS: MapDestination[] = [
  // ─── HQ + 4 portal districts (PRD §8.1) ───────────────────────────
  {
    id: "hq",
    display: "HQ",
    aliases: [
      "hq",
      "headquarters",
      "home",
      "spawn",
      "lobby",
      "atrium",
      "hq atrium",
      "base",
      "back to base",
      // Island-spawn (MVP) plaza phrasings.
      "plaza",
      "village",
      "village heart",
      "the island",
    ],
    // "spawn" is the island-spawn (MVP) plaza room. It sits LAST so it only
    // wins on the island build; the old sci-fi HQ rooms still take priority
    // when they're registered.
    roomCandidates: ["lobby-scifi-hq", "hq-atrium", "lobby", "spawn"],
  },
  {
    id: "code",
    display: "Code Lab",
    aliases: [
      "code",
      "code lab",
      "codelab",
      "coding",
      "dev",
      "development",
      "workshop",
      "lab",
      "engineering",
    ],
    roomCandidates: ["workshop-scifi-codelab", "code-lab", "workshop"],
  },
  {
    id: "ads",
    display: "Money District",
    aliases: [
      "ads",
      "ads room",
      "ads command",
      "ads command center",
      "money",
      "money district",
      "meta",
      "facebook",
      "revenue",
      "ad center",
      "advertising",
    ],
    // "hermes" is the island-spawn (MVP) lodge — the single Hermes terminal
    // building. It sits LAST so it only catches on the island build, where
    // the dedicated ads district doesn't exist.
    roomCandidates: ["ads-scifi-command", "ads-command", "money-district", "ads-room", "hermes"],
  },
  {
    id: "mail",
    display: "Comms Hall",
    aliases: [
      "mail",
      "email",
      "mail room",
      "email hall",
      "comms",
      "comms hall",
      "communications",
      "inbox",
      "messages",
    ],
    // "hermes" — island-spawn (MVP) lodge fallback; see ads/task notes.
    roomCandidates: ["mail-scifi-hall", "comms-hall", "mail-comms", "mail-room", "hermes"],
  },
  {
    id: "task",
    display: "Agent Camp",
    aliases: [
      "task",
      "tasks",
      "task island",
      "agents",
      "agent",
      "agent camp",
      "agent dock",
      "camp",
      "squad",
    ],
    // "hermes" — island-spawn (MVP) lodge fallback; see ads note.
    roomCandidates: ["agent-camp-scifi-dorm", "agent-camp", "agent-dock", "agent-park", "hermes"],
  },

  // ─── Island spawn (MVP / mvp-cut) rooms ──────────────────────────────
  // The flat island world (IslandWorldBuilder) registers exactly four
  // rooms by these literal names: spawn, code, cinema, hermes. "spawn" and
  // "code" are reached through the hq / code districts above (their
  // candidate lists end with the island name). These two destinations add
  // the rooms that have no district equivalent so the user can say
  // "take me to the cinema" / "go to the hermes lodge" by voice.
  {
    id: "cinema",
    display: "Cinema",
    aliases: [
      "cinema",
      "the cinema",
      "theater",
      "theatre",
      "movie theater",
      "movies",
      "big screen",
      "the screen",
      "amphitheatre",
      "amphitheater",
      "影院",
      "电影院",
      "剧院",
      "大屏幕",
    ],
    roomCandidates: ["cinema"],
  },
  {
    id: "hermes",
    display: "Hermes Lodge",
    aliases: [
      "hermes",
      "hermes lodge",
      "the lodge",
      "lodge",
      "hermes cabin",
      "hermes terminal",
      "hermes room",
      "赫尔墨斯",
      "小屋",
    ],
    roomCandidates: ["hermes"],
  },

  // ─── Themed island destinations (PRD §8.2) ───────────────────────────
  // Aliases only — if the matching room isn't in the current world these
  // fall back to HQ so the user doesn't end up nowhere (the plugin picks
  // the first registered candidate).
  {
    id: "game",
    display: "Game Island",
    aliases: ["game", "game island", "play", "playground", "fun"],
    roomCandidates: ["game", "lobby", "hq-atrium"],
  },
  {
    id: "learning",
    display: "Learning Island",
    aliases: [
      "learning",
      "learning island",
      "learn",
      "study",
      "school",
      "classroom",
    ],
    roomCandidates: ["learning", "docs-library", "workshop"],
  },

  // ─── Sub-rooms that the user can name directly ───────────────────────
  // These are addressable through their own canonical id so a voice line
  // like "take me to the revenue tower" lands precisely.
  {
    id: "revenue-tower",
    display: "Revenue Tower",
    aliases: ["revenue tower", "revenue", "stripe", "tower"],
    roomCandidates: ["agent-bank-scifi-library", "revenue-tower", "money-district"],
  },
  {
    id: "docs-library",
    display: "Docs Library",
    aliases: ["docs library", "library", "docs", "documents", "drive", "google drive"],
    roomCandidates: ["agent-scifi-docs", "docs-library", "comms-hall"],
  },
  {
    id: "calendar",
    display: "Calendar Portal",
    aliases: ["calendar", "calendar portal", "schedule", "meetings"],
    roomCandidates: ["agent-scifi-calendar", "calendar", "comms-hall"],
  },
  {
    id: "task-archive",
    display: "Task Archive",
    aliases: ["task archive", "archive", "completed tasks"],
    roomCandidates: ["task-archive", "agent-camp-scifi-dorm", "agent-camp"],
  },
  {
    id: "agent-dock",
    display: "Agent Dock",
    aliases: ["agent dock", "dock", "spawn agents", "agent spawn"],
    roomCandidates: ["agent-camp-scifi-dorm", "agent-dock", "agent-camp"],
  },
  {
    id: "hq-atrium",
    display: "HQ Atrium",
    aliases: ["hq atrium", "atrium"],
    roomCandidates: ["lobby-scifi-hq", "hq-atrium", "lobby"],
  },

  // ─── Sci-fi-specific destinations ───────────────────────────────────
  {
    id: "bank",
    display: "Bank Library",
    aliases: ["bank", "bank library", "revenue", "money"],
    roomCandidates: ["agent-bank-scifi-library", "revenue-tower"],
  },
  {
    id: "dorm",
    display: "Cryo Dorm",
    aliases: ["dorm", "cryo", "cryo pods", "agent dorm"],
    roomCandidates: ["agent-camp-scifi-dorm", "agent-camp"],
  },
];

// Build an alias → MapDestination index once at module load. Lowercased,
// whitespace-collapsed key. Aliases earlier in the list win when there
// are collisions (which is also why districts come before sub-rooms above
// — "docs" routes to docs-library, not learning).
const ALIAS_INDEX: Map<string, MapDestination> = (() => {
  const m = new Map<string, MapDestination>();
  for (const dest of MAP_DESTINATIONS) {
    const keys = new Set<string>([dest.id, ...dest.aliases]);
    for (const k of keys) {
      const norm = normalizeKey(k);
      if (!norm) continue;
      if (!m.has(norm)) m.set(norm, dest);
    }
  }
  return m;
})();

function normalizeKey(s: string): string {
  return String(s).trim().toLowerCase().replace(/\s+/g, " ");
}

/**
 * Resolve a user phrase to a {@link MapDestination}.
 *
 * Returns `null` if the phrase doesn't match any known alias — callers
 * MUST surface that to the user instead of forwarding the raw string to
 * the plugin (which would silently fail at the rooms registry lookup).
 */
export function resolveDestination(input: string | null | undefined): MapDestination | null {
  if (!input) return null;
  const key = normalizeKey(input);
  if (!key) return null;
  // Exact alias hit.
  const hit = ALIAS_INDEX.get(key);
  if (hit) return hit;
  // Try the input minus common articles ("the ads room", "the workshop").
  const stripped = key.replace(/^(the|to|a|an)\s+/, "").trim();
  if (stripped && stripped !== key) {
    const hit2 = ALIAS_INDEX.get(stripped);
    if (hit2) return hit2;
  }
  // Last-resort substring sweep — handy for phrasings like "ads command
  // center please" that still embed an alias. Only matches alias keys ≥ 4
  // chars to avoid noise from "to"/"the"/"go".
  for (const [alias, dest] of ALIAS_INDEX) {
    if (alias.length >= 4 && key.includes(alias)) return dest;
  }
  return null;
}

/** Canonical ids that voice phrases can resolve to. Exported for diagnostics. */
export function allDestinationIds(): string[] {
  return MAP_DESTINATIONS.map((d) => d.id);
}
