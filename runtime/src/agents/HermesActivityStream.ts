import type { AgentStatus, ScreenEntry } from "../types.js";

type DataListener = (bytes: Buffer) => void;

const REPLAY_BUFFER_KB = 64;

/**
 * "transcript" — render the terse {@link ScreenEntry} transcript (Hermes
 *   sanctuary villagers). Fed via onStatus/onSay/onLog/onTranscript.
 * "reasoning" — render the agent's FULL, untruncated reasoning (CodeAgent /
 *   build "mason"). Fed via onStatus + writeReasoning. The transcript callbacks
 *   are ignored so the board's truncated lines don't double up with the full
 *   ones the agent pushes through writeReasoning.
 */
export type ActivityStreamMode = "transcript" | "reasoning";

export type ActivityStreamOptions = {
  mode?: ActivityStreamMode;
  /** Banner title (defaults to "hermes-agent"). */
  title?: string;
  /** Banner subtitle line under the room. */
  subtitle?: string;
};

/**
 * A virtual "PTY" for a {@link HermesAgent} or {@link CodeAgent}. It exposes the
 * same surface as {@link ClaudePty} ({@code snapshot}, {@code onData}) so the
 * terminal multiplex server can fan it out to in-game terminal clients without
 * caring whether the agent is a workshop villager (real PTY), a sanctuary
 * villager (Hermes events as ANSI), or a build "mason" (full reasoning as ANSI).
 *
 * The stream is fed from the AgentEvents callbacks the manager already
 * exposes and emits a coloured, terminal-style transcript that reads like
 * watching the agent run.
 *
 * Write-only on the producer side, read-only on the consumer side. Player
 * input arrives through the existing chat path (player_message), not the
 * terminal — typing into the terminal is intentionally a no-op.
 */
export class HermesActivityStream {
  private listeners = new Set<DataListener>();
  private replay: Buffer = Buffer.alloc(0) as Buffer;
  private lastStatus: AgentStatus | null = null;
  private readonly mode: ActivityStreamMode;
  private readonly title: string;
  private readonly subtitle: string;

  constructor(
    private agentId: string,
    private role: string,
    private room: string,
    opts: ActivityStreamOptions = {},
  ) {
    this.mode = opts.mode ?? "transcript";
    this.title = opts.title ?? "hermes-agent";
    this.subtitle = opts.subtitle
      ?? "attached to live hermes session — typing happens via in-game chat";
    this.emitBanner();
  }

  /* ------------------------------------------------------------------
   * Producer surface (called from the runtime side)
   * ---------------------------------------------------------------- */

  onStatus(status: AgentStatus, detail?: string): void {
    if (status === this.lastStatus && !detail) return;
    this.lastStatus = status;
    const color = statusColor(status);
    const tag = `[${ts()}]`;
    const line = `${dim(tag)} ${color}● ${status}${RESET}`
      + (detail ? `  ${dim(truncate(detail, 80))}` : "");
    this.writeLine(line);
  }

  onSay(text: string, playerName?: string): void {
    if (this.mode !== "transcript") return;
    const tag = `[${ts()}]`;
    const who = playerName ? dim(`→ ${playerName}`) : "";
    this.writeLine(`${dim(tag)} ${CYAN}${this.agentId}:${RESET} ${text} ${who}`);
  }

  onLog(line: string, level: "info" | "warn" | "error" | "tool"): void {
    if (this.mode !== "transcript") return;
    const tag = `[${ts()}]`;
    const c = level === "error"
      ? RED
      : level === "warn"
        ? YELLOW
        : level === "tool"
          ? MAGENTA
          : DIM;
    const label = level === "tool" ? "tool" : level;
    this.writeLine(`${dim(tag)} ${c}${label}${RESET}  ${line}`);
  }

  onTranscript(entry: ScreenEntry, isNewTurn: boolean): void {
    if (this.mode !== "transcript") return;
    if (isNewTurn) {
      this.writeLine("");
      this.writeLine(dim("─".repeat(72)));
    }
    const tag = `[${ts()}]`;
    const glyph = kindGlyph(entry.kind);
    const color = kindColor(entry.kind);
    this.writeLine(`${dim(tag)} ${color}${glyph}${RESET} ${entry.text}`);
  }

  /**
   * Full, untruncated reasoning line for "reasoning"-mode streams (CodeAgent /
   * build mason). Same glyph + colour vocabulary as {@link onTranscript} but it
   * keeps the COMPLETE text and wraps multi-line content (tool output, longer
   * narration) under a hanging marker so it stays grouped under its glyph.
   */
  writeReasoning(text: string, tone: ScreenEntry["kind"], opts?: { newTurn?: boolean }): void {
    if (this.mode !== "reasoning") return;
    if (opts?.newTurn) {
      this.writeLine("");
      this.writeLine(dim("─".repeat(72)));
    }
    const glyph = kindGlyph(tone);
    const color = kindColor(tone);
    const lines = String(text).replace(/\r/g, "").split("\n");
    const head = lines.length ? lines[0] : "";
    this.writeLine(`${dim(`[${ts()}]`)} ${color}${glyph}${RESET} ${head}`);
    for (const cont of lines.slice(1)) {
      this.writeLine(`           ${color}┊${RESET} ${cont}`);
    }
  }

  /* ------------------------------------------------------------------
   * Consumer surface (terminal server side)
   * ---------------------------------------------------------------- */

  /** Last ~64KB of stream so a fresh attacher sees the current frame. */
  snapshot(): Buffer {
    return this.replay;
  }

  onData(listener: DataListener): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  /** Sanctuary terminals are read-only — input is ignored. */
  writeStdinBytes(_buf: Buffer): void { /* no-op */ }

  isAlive(): boolean { return true; }

  errorMessage(): string | null { return null; }

  /** Cols/rows are advisory for the writer — we wrap at 100 by default. */
  resize(_cols: number, _rows: number): void { /* no-op */ }

  /* ------------------------------------------------------------------
   * Internals
   * ---------------------------------------------------------------- */

  private emitBanner(): void {
    const bar = "═".repeat(72);
    this.writeLine(`${CYAN}${bar}${RESET}`);
    this.writeLine(`${CYAN}║${RESET} ${BOLD}${this.title}${RESET}  ${dim("·")}  `
      + `${CYAN}${this.agentId}${RESET}  ${dim("·")}  ${this.role}`);
    this.writeLine(`${CYAN}║${RESET} ${dim(`room: ${this.room}`)}`);
    this.writeLine(`${CYAN}║${RESET} ${dim(this.subtitle)}`);
    this.writeLine(`${CYAN}${bar}${RESET}`);
    this.writeLine("");
  }

  private writeLine(line: string): void {
    const buf = Buffer.from(line + "\r\n", "utf8");
    this.append(buf);
    for (const l of this.listeners) {
      try { l(buf); } catch { /* ignore */ }
    }
  }

  private append(buf: Buffer): void {
    const max = REPLAY_BUFFER_KB * 1024;
    if (buf.length >= max) {
      this.replay = Buffer.from(buf.subarray(buf.length - max));
      return;
    }
    const combined = Buffer.concat([this.replay, buf]);
    this.replay = combined.length > max
      ? Buffer.from(combined.subarray(combined.length - max))
      : combined;
  }
}

/* ------------------------------------------------------------------------
 * ANSI helpers — kept inline so the file has no external deps. The plugin's
 * TerminalBuffer parses the standard SGR sequences, so these render
 * correctly inside the in-game terminal screen.
 * ---------------------------------------------------------------------- */
const RESET = "\x1b[0m";
const BOLD = "\x1b[1m";
const DIM = "\x1b[2m";
const RED = "\x1b[31m";
const GREEN = "\x1b[32m";
const YELLOW = "\x1b[33m";
const BLUE = "\x1b[34m";
const MAGENTA = "\x1b[35m";
const CYAN = "\x1b[36m";

function dim(s: string): string { return `${DIM}${s}${RESET}`; }

function statusColor(s: AgentStatus): string {
  switch (s) {
    case "thinking":  return YELLOW;
    case "tool_call": return MAGENTA;
    case "speaking":  return CYAN;
    case "done":      return GREEN;
    case "error":     return RED;
    case "idle":      return DIM;
    default:          return RESET;
  }
}

function kindGlyph(k: ScreenEntry["kind"]): string {
  switch (k) {
    case "think":  return "·";
    case "tool":   return "→";
    case "result": return "←";
    case "say":    return "»";
    case "done":   return "✓";
    case "error":  return "✗";
    case "system": return "▸";
    default:       return " ";
  }
}

function kindColor(k: ScreenEntry["kind"]): string {
  switch (k) {
    case "think":  return YELLOW;
    case "tool":   return MAGENTA;
    case "result": return BLUE;
    case "say":    return CYAN;
    case "done":   return GREEN;
    case "error":  return RED;
    case "system": return DIM;
    default:       return RESET;
  }
}

function ts(): string {
  const d = new Date();
  const hh = String(d.getHours()).padStart(2, "0");
  const mm = String(d.getMinutes()).padStart(2, "0");
  const ss = String(d.getSeconds()).padStart(2, "0");
  return `${hh}:${mm}:${ss}`;
}

function truncate(s: string, n: number): string {
  return s.length > n ? `${s.slice(0, n)}…` : s;
}
