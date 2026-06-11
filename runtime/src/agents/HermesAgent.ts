import { callHermes, type HermesMessage, type HermesToolCall } from "../inference/hermes.js";
import type { ToolRegistry, ToolContext } from "../tools/registry.js";
import type { AgentStatus, BuildOp, ScreenEntry, Vec3 } from "../types.js";
import { systemPromptFor, type RoomKind } from "./prompts.js";
import { dbg } from "../debug.js";

export type AgentEvents = {
  onStatus: (status: AgentStatus, detail?: string) => void;
  onSay: (text: string, playerName?: string) => void;
  onLog: (line: string, level: "info" | "warn" | "error" | "tool") => void;
  onScreen: (entries: ScreenEntry[]) => void;
  onTranscript: (entry: ScreenEntry, isNewTurn: boolean) => void;
  onRequestApproval: (callId: string, tool: string, summary: string) => Promise<boolean>;
  onBuildOps: (ops: BuildOp[], clearFirst: boolean) => void;
  // Dean re-themes the classroom + re-seats the tutor for a new subject.
  onOpenClassroom?: (p: { subject: string; playerName?: string | null }) => void;
  // Optional full, untruncated reasoning feed for the in-game terminal mirror.
  // onScreen/onTranscript are kept terse to fit the floating board + lectern
  // book; onReasoning carries the COMPLETE narration / tool I/O so a player who
  // right-clicks the villager and opens its terminal sees everything it's doing.
  // Only the terminal activity stream consumes it — it never goes to the plugin.
  onReasoning?: (text: string, tone: ScreenEntry["kind"], opts?: { newTurn?: boolean }) => void;
};

const MAX_STEPS = 12;
const SCREEN_BUFFER_LIMIT = 16;
const HEARTBEAT_MS = 10_000;       // tick screen + chat every 10s during long ops
const LOUD_HEARTBEAT_AFTER_MS = 30_000; // first in-chat "still working" after 30s
const HERMES_TIMEOUT_MS = 15 * 60_000;  // 15-minute hard cap per hermes call

export class HermesAgent {
  readonly id: string;
  readonly role: string;
  readonly home: Vec3;
  readonly room: string;
  readonly ownerName: string;
  status: AgentStatus = "idle";

  private history: HermesMessage[] = [];
  private busy = false;
  private screenBuffer: ScreenEntry[] = [];
  private turnStart = 0;
  // Tracked while in a hermes call so /omo say feedback + busy responses
  // can show what we're actually waiting on and for how long.
  private currentStep = 0;
  private currentStepStart = 0;
  private currentTool: string | null = null;

  constructor(
    opts: {
      id: string;
      role: string;
      home: Vec3;
      room: string;
      roomKind: RoomKind;
      ownerName: string;
    },
    private tools: ToolRegistry,
    private events: AgentEvents,
  ) {
    this.id = opts.id;
    this.role = opts.role;
    this.home = opts.home;
    this.room = opts.room;
    this.ownerName = opts.ownerName;
    this.history.push({
      role: "system",
      content: systemPromptFor({
        agentId: opts.id,
        role: opts.role,
        room: opts.room,
        roomKind: opts.roomKind,
        ownerName: opts.ownerName,
      }),
    });
  }

  async handleMessage(playerName: string, text: string): Promise<void> {
    if (this.busy) {
      this.events.onSay(this.busyReply(), playerName);
      return;
    }
    this.busy = true;
    dbg("agent", `${this.id} ← ${playerName}: ${text}`);
    this.history.push({
      role: "user",
      content: `[${playerName}] ${text}`,
    });
    // Fresh board per turn — echo the user's prompt as the first entry.
    this.turnStart = Date.now();
    this.resetScreen({ kind: "system", text: `${playerName}: ${truncate(text, 48)}` });
    // Terminal mirror gets the full prompt (untruncated), starting a fresh turn.
    this.events.onReasoning?.(`[${playerName}] ${text}`, "system", { newTurn: true });
    try {
      await this.runLoop(playerName);
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      this.setStatus("error", msg);
      this.pushScreen({ kind: "error", text: truncate(msg, 60) });
      this.events.onLog(`agent error: ${msg}`, "error");
      this.events.onReasoning?.(`agent error: ${msg}`, "error");
    } finally {
      this.busy = false;
    }
  }

  private async runLoop(playerName: string): Promise<void> {
    for (let step = 0; step < MAX_STEPS; step++) {
      this.currentStep = step + 1;
      this.currentStepStart = Date.now();
      this.currentTool = null;
      this.setStatus("thinking");
      this.pushScreen({ kind: "think", text: `thinking… (step ${step + 1}/${MAX_STEPS})` });
      this.events.onReasoning?.(`step ${step + 1}/${MAX_STEPS} · thinking…`, "think");
      dbg("agent", `${this.id} step ${step + 1}/${MAX_STEPS} → callHermes`, {
        history: this.history.length,
        tools: this.tools.list().length,
      });
      const resp = await this.callHermesWithHeartbeat(playerName);
      dbg("agent", `${this.id} step ${step + 1} result`, {
        content_len: resp.content.length,
        tool_calls: resp.toolCalls.map((t) => t.function.name),
      });

      // Record assistant turn (with tool calls if any) so the model can see its own work.
      this.history.push({
        role: "assistant",
        content: resp.content,
        tool_calls: resp.toolCalls.length ? resp.toolCalls : undefined,
      });

      // If the model talked to the player, speak it.
      if (resp.content?.trim()) {
        const spoken = resp.content.trim();
        this.events.onSay(spoken, playerName);
        this.pushScreen({ kind: "say", text: truncate(spoken, 60) });
        // Terminal mirror gets the full reply, not the 60-char board cut.
        this.events.onReasoning?.(spoken, "say");
      }

      if (resp.toolCalls.length === 0) {
        // No tool calls — turn is over.
        this.setStatus("idle");
        this.pushScreen({ kind: "done", text: this.doneSummary(step + 1) });
        this.events.onReasoning?.(this.doneSummary(step + 1), "done");
        return;
      }

      // Execute tool calls in sequence so the model sees their results in order.
      let finished = false;
      for (const call of resp.toolCalls) {
        const result = await this.runToolCall(call);
        this.history.push({
          role: "tool",
          tool_call_id: call.id,
          content: JSON.stringify(result),
        });
        if (call.function.name === "finish_task") {
          finished = true;
        }
      }
      if (finished) {
        this.setStatus("done");
        this.pushScreen({ kind: "done", text: this.doneSummary(step + 1) });
        this.events.onReasoning?.(this.doneSummary(step + 1), "done");
        return;
      }
    }
    this.setStatus("error", "max steps reached");
    this.pushScreen({ kind: "error", text: "max reasoning steps reached" });
    this.events.onReasoning?.("hit max reasoning steps — pausing.", "error");
    this.events.onSay("hit max reasoning steps — pausing.", playerName);
  }

  private async runToolCall(call: HermesToolCall): Promise<unknown> {
    const tool = this.tools.get(call.function.name);
    if (!tool) {
      this.events.onLog(`unknown tool: ${call.function.name}`, "warn");
      return { error: `unknown tool: ${call.function.name}` };
    }
    let args: Record<string, unknown> = {};
    try {
      args = call.function.arguments ? JSON.parse(call.function.arguments) : {};
    } catch (err) {
      return { error: `bad json args: ${(err as Error).message}` };
    }

    this.currentTool = call.function.name;
    this.currentStepStart = Date.now();
    this.setStatus("tool_call", call.function.name);
    this.events.onLog(`→ ${call.function.name}(${truncate(call.function.arguments, 120)})`, "tool");
    this.pushScreen({
      kind: "tool",
      text: `${call.function.name} ${truncate(summarizeArgs(args), 40)}`,
    });
    // Terminal mirror gets the tool name + FULL (pretty-printed) args.
    this.events.onReasoning?.(detailCall(call.function.name, args), "tool");

    if (tool.needsApproval?.(args)) {
      const summary = `${call.function.name} ${truncate(call.function.arguments, 80)}`;
      this.pushScreen({ kind: "system", text: `awaiting approval: ${call.function.name}` });
      this.events.onReasoning?.(`awaiting owner approval — ${call.function.name}`, "system");
      const approved = await this.events.onRequestApproval(call.id, call.function.name, summary);
      if (!approved) {
        this.pushScreen({ kind: "error", text: "approval declined" });
        this.events.onReasoning?.("owner declined approval", "error");
        return { error: "owner declined approval" };
      }
      this.events.onReasoning?.("owner approved", "system");
    }

    const ctx: ToolContext = {
      agentId: this.id,
      ownerName: this.ownerName,
      room: this.room,
      requestApproval: (s) => this.events.onRequestApproval(call.id, call.function.name, s),
      log: (line, level = "info") => this.events.onLog(line, level),
      openClassroom: (p) =>
        this.events.onOpenClassroom?.({ subject: p.subject, playerName: this.ownerName ?? null }),
    };

    try {
      const result = await tool.run(args, ctx);
      const ms = Date.now() - this.currentStepStart;
      this.events.onLog(`← ${call.function.name} ok (${ms}ms)`, "tool");
      this.pushScreen({
        kind: "result",
        text: `${truncate(summarizeResult(result), 40)} (${(ms / 1000).toFixed(1)}s)`,
      });
      // Terminal mirror: timing header + the fuller, multi-line result body.
      this.events.onReasoning?.(
        `${call.function.name} · ${fmtDuration(ms)}\n${detailResult(result)}`,
        "result",
      );
      return result;
    } catch (err) {
      const ms = Date.now() - this.currentStepStart;
      const msg = err instanceof Error ? err.message : String(err);
      this.events.onLog(`← ${call.function.name} error: ${msg}`, "error");
      this.pushScreen({ kind: "error", text: `${truncate(msg, 40)} (${(ms / 1000).toFixed(1)}s)` });
      this.events.onReasoning?.(`${call.function.name} error · ${fmtDuration(ms)}\n${msg}`, "error");
      return { error: msg };
    } finally {
      this.currentTool = null;
    }
  }

  private doneSummary(steps: number): string {
    const elapsed = Math.max(0, Math.round((Date.now() - this.turnStart) / 1000));
    return `done (${steps} step${steps === 1 ? "" : "s"}, ${elapsed}s)`;
  }

  /**
   * Wrap a hermes call with:
   *  - a 10s screen heartbeat showing elapsed seconds
   *  - a 30s in-chat "still working…" so the player isn't left hanging
   *  - a 5min hard timeout — aborts the call and surfaces the error
   * The model itself stays untouched — this is purely UI/UX.
   */
  private async callHermesWithHeartbeat(playerName: string) {
    let chattered = false;
    let elapsed = 0;
    let ticks = 0;
    const tick = () => {
      ticks++;
      elapsed = Math.round((Date.now() - this.currentStepStart) / 1000);
      const what = this.currentTool ?? `step ${this.currentStep}/${MAX_STEPS}`;
      // Replace the live "thinking" line in place so we don't flood the screen
      // with one new entry per heartbeat. Visually: a single line whose elapsed
      // seconds tick up and whose step counter advances when the loop moves on.
      this.replaceLiveThinking({
        kind: "think",
        text: `${what} · ${elapsed}s`,
      });
      // The terminal mirror is append-only (can't rewrite a line), so emit a
      // coarser heartbeat there — one line every ~30s — instead of every tick.
      if (ticks % 3 === 0) {
        this.events.onReasoning?.(`still ${what} · ${elapsed}s`, "think");
      }
      if (!chattered && elapsed >= LOUD_HEARTBEAT_AFTER_MS / 1000) {
        chattered = true;
        this.events.onSay(
          `still working on it — ${elapsed}s in. hermes can take a while when it digs around.`,
          playerName,
        );
      }
    };
    const interval = setInterval(tick, HEARTBEAT_MS);

    const timeoutSignal = new AbortController();
    const timeoutHandle = setTimeout(() => timeoutSignal.abort(), HERMES_TIMEOUT_MS);

    try {
      return await Promise.race([
        callHermes(this.history, this.tools.list(), this.id),
        new Promise<never>((_, reject) => {
          timeoutSignal.signal.addEventListener("abort", () => {
            reject(new Error(`hermes timeout after ${HERMES_TIMEOUT_MS / 1000}s`));
          });
        }),
      ]);
    } finally {
      clearInterval(interval);
      clearTimeout(timeoutHandle);
    }
  }

  /** Message shown when a second prompt arrives while we're mid-turn. */
  private busyReply(): string {
    const elapsed = Math.round((Date.now() - this.currentStepStart) / 1000);
    const what = this.currentTool
      ? `running ${this.currentTool}`
      : `thinking (step ${this.currentStep}/${MAX_STEPS})`;
    return `still ${what} — ${elapsed}s elapsed. I'll reply when this turn finishes; you can queue questions but I'll answer in order.`;
  }

  private setStatus(status: AgentStatus, detail?: string) {
    this.status = status;
    this.events.onStatus(status, detail);
  }

  private pushScreen(entry: ScreenEntry) {
    this.screenBuffer.push(entry);
    if (this.screenBuffer.length > SCREEN_BUFFER_LIMIT) this.screenBuffer.shift();
    this.events.onScreen([...this.screenBuffer]);
    this.events.onTranscript(entry, false);
  }

  /**
   * Update the most recent "think" entry in place rather than appending a new
   * one — used by the heartbeat tick so the screen shows a single live status
   * line whose elapsed seconds tick up instead of a wall of duplicate lines.
   * Skips the transcript book (heartbeats are ephemeral).
   */
  private replaceLiveThinking(entry: ScreenEntry) {
    const tail = this.screenBuffer[this.screenBuffer.length - 1];
    if (tail && tail.kind === "think") {
      this.screenBuffer[this.screenBuffer.length - 1] = entry;
    } else {
      this.screenBuffer.push(entry);
      if (this.screenBuffer.length > SCREEN_BUFFER_LIMIT) this.screenBuffer.shift();
    }
    this.events.onScreen([...this.screenBuffer]);
  }

  private resetScreen(initial?: ScreenEntry) {
    this.screenBuffer = initial ? [initial] : [];
    this.events.onScreen([...this.screenBuffer]);
    if (initial) this.events.onTranscript(initial, true);
  }
}

function truncate(s: string, n: number): string {
  return s.length > n ? `${s.slice(0, n)}…` : s;
}

// Keep at most maxLines lines and maxChars characters, with a "+N more" tail.
function clampLines(s: string, maxLines: number, maxChars: number): string {
  const capped = truncate(s, maxChars);
  const lines = capped.split("\n");
  if (lines.length <= maxLines) return lines.join("\n");
  const extra = lines.length - maxLines;
  return `${lines.slice(0, maxLines).join("\n")}\n…(+${extra} more line${extra === 1 ? "" : "s"})`;
}

// 420 → "420ms", 12100 → "12.1s", 63000 → "1m03s".
function fmtDuration(ms: number): string {
  if (!Number.isFinite(ms) || ms < 0) return "";
  if (ms < 1000) return `${Math.round(ms)}ms`;
  const s = ms / 1000;
  if (s < 60) return `${s.toFixed(1)}s`;
  const m = Math.floor(s / 60);
  const rem = Math.round(s % 60);
  return `${m}m${String(rem).padStart(2, "0")}s`;
}

// Tool name + FULL, pretty-printed args for the terminal mirror (no 40/120-char
// board cut). Compact objects stay inline; anything larger drops to a clamped,
// indented block under the tool name.
function detailCall(name: string, args: Record<string, unknown>): string {
  if (Object.keys(args).length === 0) return name;
  let pretty: string;
  try {
    pretty = JSON.stringify(args, null, 2);
  } catch {
    return `${name}  ${truncate(summarizeArgs(args), 1500)}`;
  }
  if (pretty.length <= 100 && !pretty.includes("\n")) return `${name}  ${pretty}`;
  return `${name}\n${clampLines(pretty, 40, 3000)}`;
}

// Fuller, multi-line tool-result body for the terminal mirror (vs the 40-char
// board summary). Pretty-prints objects and clamps so a huge payload can't
// flood the stream.
function detailResult(result: unknown): string {
  if (result == null) return "ok";
  if (typeof result === "string") return clampLines(result, 40, 3000) || "ok";
  try {
    return clampLines(JSON.stringify(result, null, 2), 40, 3000);
  } catch {
    return summarizeResult(result);
  }
}

function summarizeArgs(args: Record<string, unknown>): string {
  const keys = Object.keys(args);
  if (keys.length === 0) return "";
  // Single-string arg → render its value bare (e.g. notes_write content); else "k=v k=v".
  if (keys.length === 1) {
    const k = keys[0]!;
    const v = args[k];
    if (typeof v === "string") return `"${v}"`;
    return `${k}=${JSON.stringify(v)}`;
  }
  return keys
    .map((k) => {
      const v = args[k];
      const rendered = typeof v === "string" ? `"${v}"` : JSON.stringify(v);
      return `${k}=${rendered}`;
    })
    .join(" ");
}

function summarizeResult(result: unknown): string {
  if (result == null) return "ok";
  if (typeof result === "string") return result;
  if (typeof result !== "object") return String(result);
  const obj = result as Record<string, unknown>;
  if (typeof obj.error === "string") return `err: ${obj.error}`;
  // Prefer a short summary field if present.
  for (const k of ["summary", "message", "status", "ok"]) {
    if (typeof obj[k] === "string") return `${k}: ${obj[k]}`;
  }
  // Counts make great summaries: "12 threads", "3 items".
  for (const k of ["threads", "items", "results", "messages", "rows", "ads"]) {
    const v = obj[k];
    if (Array.isArray(v)) return `${v.length} ${k}`;
  }
  try {
    const s = JSON.stringify(obj);
    return s.length > 64 ? `${s.slice(0, 64)}…` : s;
  } catch {
    return "ok";
  }
}
