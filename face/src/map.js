// Sibling copy of runtime/src/map.ts. Keep these two in sync — they are
// the single contract between voice phrases and the rooms the plugin has
// registered. If you change one, mirror the other.
//
// The face only needs the alias → canonical-id resolution (it never sees
// the candidate room names — those are resolved by the runtime / plugin).
// But we duplicate the full table here so the face can log what it sent
// and so this file remains a faithful mirror of the runtime source.

export const MAP_DESTINATIONS = [
  // ─── HQ + 4 portal districts (PRD §8.1) ───────────────────────────
  {
    id: 'hq',
    display: 'HQ',
    aliases: [
      'hq',
      'headquarters',
      'home',
      'spawn',
      'lobby',
      'atrium',
      'hq atrium',
      'base',
      'back to base',
      // Island-spawn (MVP) plaza phrasings.
      'plaza',
      'village',
      'village heart',
      'the island',
    ],
    // "spawn" is the island-spawn (MVP) plaza room. It sits LAST so it only
    // wins on the island build; the old sci-fi HQ rooms still take priority.
    roomCandidates: ['lobby-scifi-hq', 'hq-atrium', 'lobby', 'spawn'],
  },
  {
    id: 'code',
    display: 'Code Lab',
    aliases: [
      'code',
      'code lab',
      'codelab',
      'coding',
      'dev',
      'development',
      'workshop',
      'lab',
      'engineering',
    ],
    roomCandidates: ['workshop-scifi-codelab', 'code-lab', 'workshop'],
  },
  {
    id: 'ads',
    display: 'Money District',
    aliases: [
      'ads',
      'ads room',
      'ads command',
      'ads command center',
      'money',
      'money district',
      'meta',
      'facebook',
      'revenue',
      'ad center',
      'advertising',
    ],
    // "hermes" — island-spawn (MVP) lodge fallback (single Hermes building).
    roomCandidates: ['ads-scifi-command', 'ads-command', 'money-district', 'ads-room', 'hermes'],
  },
  {
    id: 'mail',
    display: 'Comms Hall',
    aliases: [
      'mail',
      'email',
      'mail room',
      'email hall',
      'comms',
      'comms hall',
      'communications',
      'inbox',
      'messages',
    ],
    // "hermes" — island-spawn (MVP) lodge fallback; see ads/task notes.
    roomCandidates: ['mail-scifi-hall', 'comms-hall', 'mail-comms', 'mail-room', 'hermes'],
  },
  {
    id: 'task',
    display: 'Agent Camp',
    aliases: [
      'task',
      'tasks',
      'task island',
      'agents',
      'agent',
      'agent camp',
      'agent dock',
      'camp',
      'squad',
    ],
    // "hermes" — island-spawn (MVP) lodge fallback; see ads note.
    roomCandidates: ['agent-camp-scifi-dorm', 'agent-camp', 'agent-dock', 'agent-park', 'hermes'],
  },

  // ─── Island spawn (MVP / mvp-cut) rooms ──────────────────────────────
  // The flat island world (IslandWorldBuilder) registers four rooms by
  // these literal names: spawn, code, cinema, hermes. "spawn"/"code" are
  // reached through hq/code above (their candidate lists end with the
  // island name). These two add the rooms with no district equivalent.
  {
    id: 'cinema',
    display: 'Cinema',
    aliases: [
      'cinema',
      'the cinema',
      'theater',
      'theatre',
      'movie theater',
      'movies',
      'big screen',
      'the screen',
      'amphitheatre',
      'amphitheater',
      '影院',
      '电影院',
      '剧院',
      '大屏幕',
    ],
    roomCandidates: ['cinema'],
  },
  {
    id: 'hermes',
    display: 'Hermes Lodge',
    aliases: [
      'hermes',
      'hermes lodge',
      'the lodge',
      'lodge',
      'hermes cabin',
      'hermes terminal',
      'hermes room',
      '赫尔墨斯',
      '小屋',
    ],
    roomCandidates: ['hermes'],
  },

  // ─── Sky islands (PRD §8.2) ─────────────────────────────────────────
  {
    id: 'game',
    display: 'Game Island',
    aliases: ['game', 'game island', 'play', 'playground', 'fun'],
    roomCandidates: ['game', 'lobby', 'hq-atrium'],
  },
  {
    id: 'learning',
    display: 'Learning Island',
    aliases: [
      'learning',
      'learning island',
      'learn',
      'study',
      'school',
      'classroom',
    ],
    roomCandidates: ['learning', 'docs-library', 'workshop'],
  },

  // ─── Sub-rooms ──────────────────────────────────────────────────────
  {
    id: 'revenue-tower',
    display: 'Revenue Tower',
    aliases: ['revenue tower', 'revenue', 'stripe', 'tower'],
    roomCandidates: ['agent-bank-scifi-library', 'revenue-tower', 'money-district'],
  },
  {
    id: 'docs-library',
    display: 'Docs Library',
    aliases: ['docs library', 'library', 'docs', 'documents', 'drive', 'google drive'],
    roomCandidates: ['agent-scifi-docs', 'docs-library', 'comms-hall'],
  },
  {
    id: 'calendar',
    display: 'Calendar Portal',
    aliases: ['calendar', 'calendar portal', 'schedule', 'meetings'],
    roomCandidates: ['agent-scifi-calendar', 'calendar', 'comms-hall'],
  },
  {
    id: 'task-archive',
    display: 'Task Archive',
    aliases: ['task archive', 'archive', 'completed tasks'],
    roomCandidates: ['task-archive', 'agent-camp-scifi-dorm', 'agent-camp'],
  },
  {
    id: 'agent-dock',
    display: 'Agent Dock',
    aliases: ['agent dock', 'dock', 'spawn agents', 'agent spawn'],
    roomCandidates: ['agent-camp-scifi-dorm', 'agent-dock', 'agent-camp'],
  },
  {
    id: 'hq-atrium',
    display: 'HQ Atrium',
    aliases: ['hq atrium', 'atrium'],
    roomCandidates: ['lobby-scifi-hq', 'hq-atrium', 'lobby'],
  },

  // ─── Sci-fi-specific destinations ───────────────────────────────────
  {
    id: 'bank',
    display: 'Bank Library',
    aliases: ['bank', 'bank library', 'revenue', 'money'],
    roomCandidates: ['agent-bank-scifi-library', 'revenue-tower'],
  },
  {
    id: 'dorm',
    display: 'Cryo Dorm',
    aliases: ['dorm', 'cryo', 'cryo pods', 'agent dorm'],
    roomCandidates: ['agent-camp-scifi-dorm', 'agent-camp'],
  },
];

function normalizeKey(s) {
  return String(s).trim().toLowerCase().replace(/\s+/g, ' ');
}

const ALIAS_INDEX = (() => {
  const m = new Map();
  for (const dest of MAP_DESTINATIONS) {
    const keys = new Set([dest.id, ...dest.aliases]);
    for (const k of keys) {
      const norm = normalizeKey(k);
      if (!norm) continue;
      if (!m.has(norm)) m.set(norm, dest);
    }
  }
  return m;
})();

/**
 * Resolve a user phrase to a destination object, or `null` if it doesn't
 * match any known alias. Never returns a guess — callers must surface the
 * null case to the user instead of forwarding raw strings.
 */
export function resolveDestination(input) {
  if (!input) return null;
  const key = normalizeKey(input);
  if (!key) return null;
  const hit = ALIAS_INDEX.get(key);
  if (hit) return hit;
  const stripped = key.replace(/^(the|to|a|an)\s+/, '').trim();
  if (stripped && stripped !== key) {
    const hit2 = ALIAS_INDEX.get(stripped);
    if (hit2) return hit2;
  }
  for (const [alias, dest] of ALIAS_INDEX) {
    if (alias.length >= 4 && key.includes(alias)) return dest;
  }
  return null;
}

/** Top-level destination ids (districts + islands), used in help strings. */
export const PRIMARY_DESTINATION_IDS = [
  'hq',
  'code',
  'cinema',
  'hermes',
  'ads',
  'mail',
  'task',
  'game',
  'learning',
];
