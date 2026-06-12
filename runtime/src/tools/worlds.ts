import type { ToolImpl } from "./registry.js";

// Lets a Hermes host villager (lobby / generic agent_home) START a new world
// the same way the Gemini Chief of Staff already can — but through our own
// Hermes tool-call structure, not Gemini. Each tool broadcasts the same kind
// of spawn-request frame the face's voice loop uses; the plugin re-enters the
// live /omo spawn-code / /omo spawn command path.
//
// HARD RULE (enforced by the system prompt): the host only calls these when
// the owner EXPLICITLY asks to start a code/hermes world. Never proactively.

// Monotonic per-session counter so two "start a code world" calls never collide
// on agent id. In-memory only — fine, agents reset on runtime restart anyway.
let codeSeq = 0;
let hermesSeq = 0;

// A "code world" needs a real working directory for the Claude Code agent. Pick
// the configured default, else the repo root (resolve up out of runtime/ when
// launched from there), else wherever the runtime is running.
function defaultCodeCwd(): string {
  const env = process.env.AGENTCRAFT_DEFAULT_CWD?.trim();
  if (env) return env;
  const cwd = process.cwd();
  return cwd.endsWith("/runtime") ? cwd.slice(0, -"/runtime".length) : cwd;
}

// Sanitise an owner-supplied name into a safe, room-name-friendly agent id.
function sanitizeId(raw: string): string {
  return raw
    .toLowerCase()
    .replace(/[^a-z0-9-]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 24);
}

export const startCodeWorldTool: ToolImpl = {
  def: {
    type: "function",
    function: {
      name: "start_code_world",
      description:
        "Start a NEW Claude Code world: spawn a Claude Code villager (a real `claude` agent in its own workshop). " +
        "ONLY call this when the owner explicitly asks you to start a code world / a Claude Code agent. " +
        "Never start one on your own initiative or from a vague hint.",
      parameters: {
        type: "object",
        properties: {
          task: {
            type: "string",
            description:
              "What the new code agent should work on. If the owner didn't say, leave it as a short 'await instructions' note.",
          },
          cwd: {
            type: "string",
            description:
              "Absolute path the code agent should work in. Omit to use the default project directory.",
          },
          name: {
            type: "string",
            description: "Optional short name/id for the new agent (e.g. 'builder').",
          },
        },
        required: [],
      },
    },
  },
  needsApproval: () => false,
  async run(args, ctx) {
    const name = String(args.name ?? "").trim();
    const agentId = sanitizeId(name) || `code-${++codeSeq}`;
    const cwd = String(args.cwd ?? "").trim() || defaultCodeCwd();
    const task = String(args.task ?? "").trim() || "Get set up and wait for instructions.";
    ctx.startCodeWorld?.({ agentId, cwd, task });
    ctx.log(`start_code_world: ${agentId} cwd=${cwd}`, "tool");
    return { ok: true, kind: "code", agentId, cwd };
  },
};

export const startHermesWorldTool: ToolImpl = {
  def: {
    type: "function",
    function: {
      name: "start_hermes_world",
      description:
        "Start a NEW Hermes world: spawn a Hermes villager with the given role. " +
        "ONLY call this when the owner explicitly asks you to start a hermes world / a Hermes agent. " +
        "Never start one on your own initiative or from a vague hint.",
      parameters: {
        type: "object",
        properties: {
          role: {
            type: "string",
            description:
              "The new Hermes agent's role/purpose (e.g. 'email assistant', 'ads analyst'). Defaults to a general assistant.",
          },
          name: {
            type: "string",
            description: "Optional short name/id for the new agent.",
          },
        },
        required: [],
      },
    },
  },
  needsApproval: () => false,
  async run(args, ctx) {
    const name = String(args.name ?? "").trim();
    const agentId = sanitizeId(name) || `hermes-${++hermesSeq}`;
    const role = String(args.role ?? "").trim() || "A helpful Hermes assistant.";
    ctx.startHermesWorld?.({ agentId, role });
    ctx.log(`start_hermes_world: ${agentId} role=${role}`, "tool");
    return { ok: true, kind: "hermes", agentId, role };
  },
};
