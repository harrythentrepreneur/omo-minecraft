// Minimal tool registry for the omo-mc face. The Hermes/Claude tool ecosystem
// from omo proper isn't shipped here — the face's only job is to give the user
// a voice surface that drives the Minecraft world. Everything else (mail, ads,
// docs, github, …) belongs inside the game on a villager, not on the hologram.
//
// All tool declarations follow the Gemini Live function-declaration shape:
//   { name, description, parameters: { type, properties, required } }
//
// Each tool's run() is async and is invoked by face/server.js's POST /tool.
// Tools may return { ok: true, ... } or { ok: false, error }. The cylinder
// forwards `result` back to Gemini via sendToolResponse.

import { resolveDestination, PRIMARY_DESTINATION_IDS } from './map.js';

const RUNTIME_HTTP =
  process.env.AGENTCRAFT_RUNTIME_HTTP ?? 'http://127.0.0.1:8766';

async function postRuntime(path, body) {
  const url = `${RUNTIME_HTTP}${path}`;
  try {
    const r = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    const text = await r.text();
    let data;
    try { data = text ? JSON.parse(text) : {}; }
    catch { data = { raw: text }; }
    if (!r.ok) {
      console.warn(`[face]   bridge → ${path} ✗ ${r.status} ${data.error || text || ''}`.trim());
      return { ok: false, error: `runtime ${r.status}: ${data.error || text || 'no body'}` };
    }
    return data;
  } catch (e) {
    // Network failure — runtime probably not running. Surface a tight
    // error to the model so it can tell the user instead of stalling.
    const msg = e?.code === 'ECONNREFUSED'
      ? 'runtime not reachable (is ./agentcraft up running?)'
      : (e?.message || String(e));
    console.error(`[face]   bridge → ${path} ✗ ${msg}`);
    return { ok: false, error: msg };
  }
}

// ─── Tools ──────────────────────────────────────────────────────────────

const teleport = {
  definition: {
    name: 'teleport',
    description:
      "Teleport the player to a room of the omo-mc world. Use this whenever the user asks to 'go to', 'show me', 'take me to', or 'teleport to' a place. Accepts canonical ids and many friendly aliases. On the island spawn the rooms are: spawn/hq (the central plaza — 'home', 'back to spawn'), code (the Code Workshop — 'code lab'), cinema (the theater with the big screen — 'cinema', 'movies', 'big screen'), and hermes (the Hermes Lodge — 'hermes', 'the lodge'). Older builds also expose ads, mail, task, game, learning.",
    parameters: {
      type: 'object',
      properties: {
        destination: {
          type: 'string',
          description:
            "Friendly destination name. Top-level options: hq, code, ads, mail, task, game, learning. Sub-rooms also accepted: revenue-tower, docs-library, calendar, task-archive, agent-dock, hq-atrium.",
        },
        player: {
          type: 'string',
          description:
            "Optional Minecraft username to teleport. If omitted, teleports whichever player is currently online (the host).",
        },
      },
      required: ['destination'],
    },
  },
  async run(args) {
    const input = args?.destination;
    if (!input) return { ok: false, error: 'destination required' };

    const dest = resolveDestination(input);
    if (!dest) {
      // Don't fall through to a guess — current bug was that an unmapped
      // phrase became a literal room name lookup that always failed
      // silently inside the plugin. Surface a clear error to Gemini so it
      // tells the user instead of lying about a successful teleport.
      const choices = PRIMARY_DESTINATION_IDS.join(', ');
      console.warn(`[face]   teleport: unknown destination "${input}"`);
      return {
        ok: false,
        error: `unknown destination: "${input}". Try one of: ${choices}.`,
      };
    }

    // Visibility for the alias map — "code lab" → id "code", etc.
    const norm = String(input).trim().toLowerCase();
    if (norm !== dest.id) {
      console.log(`[face]   teleport: "${input}" → id=${dest.id} (${dest.display})`);
    } else {
      console.log(`[face]   teleport: id=${dest.id} (${dest.display})`);
    }

    const out = await postRuntime('/api/teleport', {
      destination: dest.id,
      // Keep `room` set to the canonical id for log readability — the
      // runtime resolves the actual room name from `destination`/`room`
      // through the shared map. The plugin still receives `roomCandidates`
      // and walks them.
      room: dest.id,
      player: args?.player ?? null,
    });
    if (out.ok === false) return out;
    return {
      ok: true,
      destination: dest.id,
      display: dest.display,
      resolvedRoom: out.resolvedRoom ?? null,
      message: `teleported to ${dest.display}`,
    };
  },
};

const finish_task = {
  definition: {
    name: 'finish_task',
    description:
      "Mark the current conversational turn as finished. Call this when there is nothing more to do on the user's last request — they teleported, the answer is given, the dispatch is sent. Returns immediately.",
    parameters: { type: 'object', properties: {} },
  },
  async run() { return { ok: true }; },
};

// ─── Agent-ops tools (voice → runtime → plugin) ─────────────────────────
// Everything below routes through /api/agents/* in the runtime, which turns
// each request into a *_request OutboundMessage on the WS bridge. The
// plugin's IncomingHandler re-enters the matching /omo command path so
// validation + side effects stay in one place.

const spawn_team = {
  definition: {
    name: 'spawn_team',
    description:
      "Spawn the 4-engineer claude code team (alice/bob/carol/dave) at the Code Lab. Use when the user says: 'spawn the team', 'spawn the engineers', 'launch the team', '生成团队', '召唤工程师团队', '叫工程师来'. Optional `cwd` lets the user pick a working directory; default is the demo Fern project (or $HOME if missing).",
    parameters: {
      type: 'object',
      properties: {
        cwd: {
          type: 'string',
          description:
            "Optional absolute working directory the 4 engineers should operate on. Leave empty for the default.",
        },
      },
    },
  },
  async run(args) {
    const cwd = args?.cwd?.trim() || null;
    const out = await postRuntime('/api/agents/spawn-team', { cwd });
    if (out.ok === false) return out;
    return { ok: true, message: cwd
      ? `team-up dispatched (cwd ${cwd})`
      : 'team-up dispatched (default cwd)' };
  },
};

const spawn_village = {
  definition: {
    name: 'spawn_village',
    description:
      "Spawn the Hermes villagers at the Agent Camp (mail / ads / research / oncall). Use when the user says: 'spawn the agents', 'spawn the village', 'open the camp', '生成代理村', '召唤所有代理', '叫所有代理出来'.",
    parameters: { type: 'object', properties: {} },
  },
  async run() {
    const out = await postRuntime('/api/agents/spawn-village', {});
    if (out.ok === false) return out;
    return { ok: true, message: 'village-up dispatched' };
  },
};

const spawn_code_agent = {
  definition: {
    name: 'spawn_code_agent',
    description:
      "Spawn ONE claude code villager bound to a specific working directory and starting task. Use for one-off requests like 'spawn a coder named max in /Users/.../foo to fix the auth bug' or '生成一个叫max的编程代理'. Prefer spawn_team for the default 4-engineer setup.",
    parameters: {
      type: 'object',
      properties: {
        agentId: {
          type: 'string',
          description: "Short id for the new villager (a-z0-9, ~5 chars).",
        },
        cwd: {
          type: 'string',
          description: "Absolute path to the working directory the agent will operate on.",
        },
        task: {
          type: 'string',
          description: "Plain-English starting task for the agent.",
        },
      },
      required: ['agentId', 'cwd', 'task'],
    },
  },
  async run(args) {
    const agentId = args?.agentId?.trim();
    const cwd     = args?.cwd?.trim();
    const task    = args?.task?.trim();
    if (!agentId || !cwd || !task) {
      return { ok: false, error: 'agentId, cwd and task are all required' };
    }
    const out = await postRuntime('/api/agents/spawn-code', { agentId, cwd, task });
    if (out.ok === false) return out;
    return { ok: true, agentId, cwd, message: `spawned ${agentId}` };
  },
};

const despawn_agent = {
  definition: {
    name: 'despawn_agent',
    description:
      "Remove a single agent by id. Use when the user says: 'despawn alice', 'remove bob', 'kill the carol agent', '关掉alice', '删除代理bob', '让dave退场'.",
    parameters: {
      type: 'object',
      properties: {
        agentId: { type: 'string', description: "Id of the agent to despawn." },
      },
      required: ['agentId'],
    },
  },
  async run(args) {
    const agentId = args?.agentId?.trim();
    if (!agentId) return { ok: false, error: 'agentId required' };
    const out = await postRuntime('/api/agents/despawn', { agentId });
    if (out.ok === false) return out;
    return { ok: true, agentId, message: `despawned ${agentId}` };
  },
};

const list_agents = {
  definition: {
    name: 'list_agents',
    description:
      "List every agent currently registered with the runtime (id, role, room, live status). Call this when the user asks 'who's spawned?', 'list the agents', '有哪些代理在线', '现在有哪些工程师'. Useful before opening a terminal or despawning so you know the right id.",
    parameters: { type: 'object', properties: {} },
  },
  async run() {
    try {
      const r = await fetch(`${RUNTIME_HTTP}/api/agents/list`);
      if (!r.ok) return { ok: false, error: `runtime ${r.status}` };
      const data = await r.json();
      return data;
    } catch (e) {
      return { ok: false, error: e?.message || String(e) };
    }
  },
};

const open_terminal = {
  definition: {
    name: 'open_terminal',
    description:
      "Open the in-game terminal screen for an agent (or the default team terminal when no agent id is supplied). Use when the user says: 'open alice's terminal', 'show me bob', 'open the terminal', '打开alice的终端', '看一下bob', '打开终端'.",
    parameters: {
      type: 'object',
      properties: {
        agentId: {
          type: 'string',
          description:
            "Optional id of the agent whose terminal to open. Omit to open the default team terminal pane.",
        },
      },
    },
  },
  async run(args) {
    const agentId = args?.agentId?.trim() || null;
    const out = await postRuntime('/api/agents/open-terminal', { agentId });
    if (out.ok === false) return out;
    return { ok: true, agentId, message: agentId
      ? `opened ${agentId} terminal`
      : 'opened team terminal' };
  },
};

const close_terminal = {
  definition: {
    name: 'close_terminal',
    description:
      "Close whichever in-game terminal screen is currently open. Use when the user says: 'close the terminal', 'exit', 'go back', 'dismiss', '关掉终端', '退出', '关闭', '返回'.",
    parameters: { type: 'object', properties: {} },
  },
  async run() {
    const out = await postRuntime('/api/agents/close-terminal', {});
    if (out.ok === false) return out;
    return { ok: true, message: 'closed terminal' };
  },
};

// ─── Cinema channel (voice → runtime → map-wall screen) ─────────────────
// The in-world cinema is a single screen addressed by the id "main" (the
// plugin's CinemaManager.DEFAULT_ID). Setting its url POSTs to the same
// runtime endpoint the `/omo cinema <url>` command uses, so the headless
// browser re-navigates and the new page streams onto the map-wall.

const CINEMA_ID = 'main';

// Mirror the plugin's normalizeUrl (HermesCommand.java) plus a little
// voice-friendly cleanup: speech-to-text often renders "example dot com"
// or "localhost colon 3000", and names a couple of built-in channels.
function normalizeCinemaUrl(raw) {
  let u = String(raw).trim();
  // Collapse spoken punctuation before deciding on a scheme.
  u = u.replace(/\s+dot\s+/gi, '.').replace(/\s+colon\s+/gi, ':').trim();
  const low = u.toLowerCase();
  if (low === 'whiteboard' || low === 'white board' || low === '白板') {
    return `${RUNTIME_HTTP}/whiteboard`;
  }
  if (low === 'default' || low === 'home' || low === 'localhost') {
    return 'http://localhost:3000';
  }
  if (low === 'blank' || low === 'nothing' || low === 'off') return 'about:blank';
  if (u.startsWith('http://') || u.startsWith('https://') || u.startsWith('about:')) return u;
  if (u.startsWith(':')) return `http://localhost${u}`;     // ":3000"
  return `http://${u}`;                                      // "localhost:3000", "example.com"
}

const set_cinema_url = {
  definition: {
    name: 'set_cinema_url',
    description:
      "Change the website shown on the cinema's big screen (the in-world theater wall). Use whenever the user wants to put a page on the screen: 'show X on the cinema', 'open <site> on the big screen', 'change the channel to …', 'put localhost 3000 on the screen', '把…放到大屏幕', '影院打开…', '换台到…'. Accepts a full URL or a short form like 'localhost:3000', ':3000', 'example.com'. Special channels: 'whiteboard' (the classroom board), 'default' (localhost:3000), 'blank' (clear the screen).",
    parameters: {
      type: 'object',
      properties: {
        url: {
          type: 'string',
          description:
            "The website to display. Full URL or short form (localhost:3000, :3000, example.com). Also accepts the words 'whiteboard', 'default', or 'blank'.",
        },
      },
      required: ['url'],
    },
  },
  async run(args) {
    const raw = args?.url?.trim();
    if (!raw) return { ok: false, error: 'url required' };
    const url = normalizeCinemaUrl(raw);
    console.log(`[face]   cinema: "${raw}" → ${url}`);
    const out = await postRuntime(`/api/cinema/${CINEMA_ID}/url`, { url });
    if (out.ok === false) return out;
    return { ok: true, url, message: `cinema → ${url}` };
  },
};

// ─── Registry ───────────────────────────────────────────────────────────

export const REGISTRY = {
  teleport,
  set_cinema_url,
  spawn_team,
  spawn_village,
  spawn_code_agent,
  despawn_agent,
  list_agents,
  open_terminal,
  close_terminal,
  finish_task,
};

export const GEMINI_FUNCTION_DECLARATIONS = Object.values(REGISTRY).map(
  (t) => t.definition,
);

export const TOOL_NAMES = Object.keys(REGISTRY);

export async function runTool(name, args) {
  const t = REGISTRY[name];
  if (!t) return { ok: false, error: `unknown tool: ${name}` };
  try { return await t.run(args || {}); }
  catch (err) { return { ok: false, error: String(err?.message || err) }; }
}
