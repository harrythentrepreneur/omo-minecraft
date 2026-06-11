import type { AgentEvents } from "./HermesAgent.js";
import type { AgentStatus, ScreenEntry, Vec3 } from "../types.js";
import { dbg } from "../debug.js";

// The AdkAgent is the in-world face of the net-new ADK + Gemini organisation.
// It implements the same minimal Agent surface as HermesAgent (id/role/home/
// room/ownerName/status + handleMessage) and the same AgentEvents callbacks, so
// it slots into AgentManager with zero changes to the wire protocol or the
// plugin. The brain itself lives in the Python ADK service (omo-agent/), served
// by `adk api_server`; this class POSTs the player's message to /run_sse and
// translates the streamed events (reasoning, tool calls, sub-agent hand-offs)
// into the floating board, the lectern transcript, the terminal mirror, and
// chat bubbles — in real time, as they happen.

const ADK_BASE = process.env.OMO_ADK_URL ?? "http://127.0.0.1:8000";
const APP = process.env.OMO_ADK_APP ?? "omo";
const SCREEN_LIMIT = 16;

export class AdkAgent {
  readonly id: string;
  readonly role: string;
  readonly home: Vec3;
  readonly room: string;
  readonly ownerName: string;
  status: AgentStatus = "idle";

  private busy = false;
  private sessionReady = false;
  private screen: ScreenEntry[] = [];
  private turnStart = 0;
  private personaSent = false;
  private lastAnswer: string | null = null; // consolidated final text of the last turn (for ask()/world_consult)
  private readonly app: string;
  private readonly persona: string | null;

  constructor(
    opts: { id: string; role: string; home: Vec3; room: string; ownerName: string; app?: string; persona?: string },
    private events: AgentEvents,
  ) {
    this.id = opts.id;
    this.role = opts.role;
    this.home = opts.home;
    this.room = opts.room;
    this.ownerName = opts.ownerName;
    this.app = opts.app ?? APP;
    this.persona = opts.persona ?? null;
  }

  async handleMessage(playerName: string, text: string): Promise<void> {
    if (this.busy) {
      this.events.onSay("One moment — I'm still working through the last request.", playerName);
      return;
    }
    // The player-facing path: run the turn AND speak the reply in chat.
    await this.runTurn(playerName, text, true);
  }

  /**
   * Run one full turn through the ADK and RESOLVE with the consolidated final
   * text — the same string `handleMessage` would speak. This is the synchronous
   * surface `world_consult` awaits: one specialist asks another a question and
   * gets its answer back inline. `speak:false` keeps the answer in-room only
   * (the consulted agent's reasoning still streams onto its screens) instead of
   * broadcasting it to the world. Returns the answer, or null on error / no
   * answer / busy, so the consult tool can render a graceful fallback.
   */
  async ask(playerName: string, text: string): Promise<string | null> {
    if (this.busy) return null; // caller (world_consult) renders a "busy" answer
    return this.runTurn(playerName, text, false);
  }

  private async runTurn(playerName: string, text: string, speak: boolean): Promise<string | null> {
    this.busy = true;
    this.turnStart = Date.now();
    this.lastAnswer = null;
    dbg("adk", `${this.id} ← ${playerName}: ${text}`);
    this.resetScreen({ kind: "system", text: `${playerName}: ${trunc(text, 48)}` });
    this.events.onReasoning?.(`[${playerName}] ${text}`, "system", { newTurn: true });
    this.setStatus("thinking");
    try {
      await this.ensureSession();
      // Seed the specialist's role on its first turn so it adopts its function.
      let toSend = text;
      if (this.persona && !this.personaSent) {
        toSend = `${this.persona}\n\n${text}`;
        this.personaSent = true;
      }
      await this.stream(playerName, toSend, 0, speak);
      if (this.status !== "error") {
        this.setStatus("idle");
        this.push({ kind: "done", text: this.doneSummary() });
        this.events.onReasoning?.(this.doneSummary(), "done");
      }
      return this.lastAnswer;
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      this.setStatus("error", msg);
      this.push({ kind: "error", text: trunc(msg, 60) });
      this.events.onLog(`adk error: ${msg}`, "error");
      this.events.onReasoning?.(`error: ${msg}`, "error");
      if (speak) this.events.onSay(`I hit a problem reaching the crew: ${trunc(msg, 80)}`, playerName);
      return null;
    } finally {
      this.busy = false;
    }
  }

  /** Create (idempotently) the ADK session that backs this villager's memory. */
  private async ensureSession(force = false): Promise<void> {
    if (this.sessionReady && !force) return;
    const u = enc(this.ownerName || "owner");
    const s = enc(this.id);
    try {
      await fetch(`${ADK_BASE}/apps/${this.app}/users/${u}/sessions/${s}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: "{}",
      });
    } catch {
      // A session may already exist (non-fatal); /run_sse will surface a real error.
    }
    this.sessionReady = true;
  }

  /** POST to /run_sse and translate the event stream into in-world surfaces.
   *  `speak` gates whether the consolidated reply is said aloud in chat
   *  (player turn) or merely captured into `lastAnswer` (a world_consult). */
  private async stream(playerName: string, text: string, attempt = 0, speak = true): Promise<void> {
    const body = JSON.stringify({
      appName: this.app,
      userId: this.ownerName || "owner",
      sessionId: this.id,
      streaming: true,
      newMessage: { role: "user", parts: [{ text }] },
    });
    const res = await fetch(`${ADK_BASE}/run_sse`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
      body,
    });
    if (!res.ok || !res.body) {
      if (attempt === 0) {
        // The session may have been lost (e.g. the ADK service restarted).
        // Recreate it and retry once before giving up.
        await this.ensureSession(true);
        return this.stream(playerName, text, 1, speak);
      }
      throw new Error(`adk /run_sse ${res.status} ${res.statusText}`);
    }

    const reader = res.body.getReader();
    const dec = new TextDecoder();
    let buf = "";
    const partials = new Map<string, string>(); // author -> running partial text
    const state = { spoke: false };

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += dec.decode(value, { stream: true });
      let nl: number;
      while ((nl = buf.indexOf("\n\n")) >= 0) {
        const frame = buf.slice(0, nl);
        buf = buf.slice(nl + 2);
        const dataLine = frame.split("\n").find((l) => l.startsWith("data:"));
        if (!dataLine) continue;
        const json = dataLine.slice(5).trim();
        if (!json) continue;
        let ev: AdkEvent;
        try {
          ev = JSON.parse(json) as AdkEvent;
        } catch {
          continue;
        }
        this.handleEvent(ev, playerName, partials, state, speak);
      }
    }

    // Fallback: if the stream only emitted partial text (no consolidated final),
    // use the longest accumulated buffer so the player still hears an answer
    // (and the consult still gets one to return).
    if (!state.spoke && partials.size) {
      const best = [...partials.values()].sort((a, b) => b.length - a.length)[0]?.trim();
      if (best) {
        this.lastAnswer = best;
        if (speak) this.events.onSay(best, playerName);
        this.push({ kind: "say", text: trunc(best, 60) });
        this.events.onReasoning?.(best, "say");
      }
    }
  }

  private handleEvent(
    ev: AdkEvent,
    playerName: string,
    partials: Map<string, string>,
    state: { spoke: boolean },
    speak = true,
  ): void {
    const author = ev.author || "agent";
    const transfer = ev.actions?.transferToAgent ?? ev.actions?.transfer_to_agent;
    const parts = ev.content?.parts ?? [];

    for (const p of parts) {
      const fc = p.functionCall ?? p.function_call;
      const fr = p.functionResponse ?? p.function_response;

      if (fc) {
        if (fc.name === "transfer_to_agent") {
          const target = (fc.args?.agent_name as string) ?? transfer ?? "specialist";
          this.setStatus("thinking", `→ ${target}`);
          this.push({ kind: "system", text: `${author} → ${target}` });
          this.events.onReasoning?.(`↪ ${author} hands off to ${target}`, "system");
        } else {
          const label = taskLabel(fc.name, fc.args);
          this.setStatus("tool_call", label);
          this.push({ kind: "tool", text: label });
          this.events.onReasoning?.(detailCall(fc.name, fc.args), "tool");
        }
        continue;
      }

      if (fr) {
        this.push({ kind: "result", text: trunc(previewResult(fr.response), 40) });
        this.events.onReasoning?.(`${fr.name ?? "result"} → ${detailResult(fr.response)}`, "result");
        continue;
      }

      const t = typeof p.text === "string" ? p.text : "";
      if (!t) continue; // skip empty / thought-signature-only parts

      if (ev.partial) {
        // Streaming token chunk: grow a single live board line; keep the
        // terminal clean (it gets the consolidated text once, below).
        const cur = (partials.get(author) ?? "") + t;
        partials.set(author, cur);
        this.setStatus("speaking");
        this.replaceLive({ kind: "say", text: trunc(cur, 60) });
      } else {
        // Consolidated final text for this agent's turn. Always capture it as the
        // answer (so world_consult can return it); speak it in chat only on a
        // player turn (speak), not on a silent consult.
        partials.delete(author);
        const spoken = t.trim();
        if (spoken) {
          this.lastAnswer = spoken;
          if (speak) this.events.onSay(spoken, playerName);
          this.push({ kind: "say", text: trunc(spoken, 60) });
          this.events.onReasoning?.(spoken, "say");
          state.spoke = true;
        }
      }
    }
  }

  private doneSummary(): string {
    const s = Math.max(0, Math.round((Date.now() - this.turnStart) / 1000));
    return `done (${s}s)`;
  }

  private setStatus(status: AgentStatus, detail?: string): void {
    this.status = status;
    this.events.onStatus(status, detail);
  }

  private push(entry: ScreenEntry): void {
    this.screen.push(entry);
    if (this.screen.length > SCREEN_LIMIT) this.screen.shift();
    this.events.onScreen([...this.screen]);
    this.events.onTranscript(entry, false);
  }

  /** Replace the last board line in place (used while streaming a reply). */
  private replaceLive(entry: ScreenEntry): void {
    const tail = this.screen[this.screen.length - 1];
    if (tail && (tail.kind === "say" || tail.kind === "think")) {
      this.screen[this.screen.length - 1] = entry;
    } else {
      this.screen.push(entry);
      if (this.screen.length > SCREEN_LIMIT) this.screen.shift();
    }
    this.events.onScreen([...this.screen]);
  }

  private resetScreen(initial: ScreenEntry): void {
    this.screen = [initial];
    this.events.onScreen([...this.screen]);
    this.events.onTranscript(initial, true);
  }
}

// ── ADK event shape (the subset we read; tolerant to camel/snake case) ────────
type AdkPart = {
  text?: string;
  functionCall?: { name: string; args?: Record<string, unknown> };
  function_call?: { name: string; args?: Record<string, unknown> };
  functionResponse?: { name?: string; response?: unknown };
  function_response?: { name?: string; response?: unknown };
};
type AdkEvent = {
  author?: string;
  partial?: boolean;
  content?: { parts?: AdkPart[] };
  actions?: { transferToAgent?: string; transfer_to_agent?: string };
};

function enc(s: string): string {
  return encodeURIComponent(s);
}
// Human-readable task label for the floating board + BossBar, so a watcher sees
// WHAT the agent is doing (the progress bar's title), not the raw tool name.
function taskLabel(name: string, args?: Record<string, unknown>): string {
  switch (name) {
    case "world_describe":
      return "Reviewing the organisation";
    case "world_add_function":
      return `Planning a new ${(args?.role as string) ?? "function"}`;
    case "world_build":
      return "Designing & raising the building";
    case "world_staff":
      return "Hiring a specialist";
    case "world_assign":
      return "Handing over the task";
    case "meta_ads_list_campaigns":
      return "Pulling ad campaigns";
    case "meta_ads_insights":
      return "Pulling live ad performance";
    case "draft_welcome_emails":
      return "Drafting welcome emails";
    default:
      return name.replace(/_/g, " ");
  }
}
function trunc(s: string, n: number): string {
  return s.length > n ? `${s.slice(0, n)}…` : s;
}
function previewArgs(args?: Record<string, unknown>): string {
  if (!args || !Object.keys(args).length) return "";
  try {
    const s = JSON.stringify(args);
    return s.length > 60 ? `${s.slice(0, 60)}…` : s;
  } catch {
    return "";
  }
}
function previewResult(r: unknown): string {
  if (r == null) return "ok";
  if (typeof r === "string") return r;
  try {
    const s = JSON.stringify(r);
    return s.length > 60 ? `${s.slice(0, 60)}…` : s;
  } catch {
    return "ok";
  }
}
function detailCall(name: string, args?: Record<string, unknown>): string {
  if (!args || !Object.keys(args).length) return name;
  try {
    const pretty = JSON.stringify(args, null, 2);
    return pretty.length <= 100 && !pretty.includes("\n") ? `${name}  ${pretty}` : `${name}\n${pretty}`;
  } catch {
    return name;
  }
}
function detailResult(r: unknown): string {
  if (r == null) return "ok";
  if (typeof r === "string") return r;
  try {
    return JSON.stringify(r, null, 2);
  } catch {
    return "ok";
  }
}
