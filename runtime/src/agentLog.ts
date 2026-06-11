// Per-agent log files for the `./agentcraft watch <id>` viewer.
// Each line is human-readable + ANSI-coloured, so `tail -F logs/agents/<id>.log`
// in a real terminal looks like watching the agent's CLI.

import { existsSync, mkdirSync, createWriteStream, type WriteStream } from "node:fs";
import { resolve } from "node:path";
import type { OutboundMessage } from "./types.js";

const LOG_DIR = resolve(process.cwd(), "..", "logs", "agents");
const streams = new Map<string, WriteStream>();

const c = {
  dim: "\x1b[2m",
  cyan: "\x1b[36m",
  magenta: "\x1b[35m",
  yellow: "\x1b[33m",
  red: "\x1b[31m",
  green: "\x1b[32m",
  blue: "\x1b[34m",
  bold: "\x1b[1m",
  rst: "\x1b[0m",
};

function ensureDir(): void {
  if (!existsSync(LOG_DIR)) mkdirSync(LOG_DIR, { recursive: true });
}

function streamFor(agentId: string): WriteStream {
  let s = streams.get(agentId);
  if (s) return s;
  ensureDir();
  s = createWriteStream(resolve(LOG_DIR, `${agentId}.log`), { flags: "a" });
  streams.set(agentId, s);
  const ts = stamp();
  s.write(`${c.dim}${ts}${c.rst}  ${c.bold}── watching ${agentId} ──${c.rst}\n`);
  return s;
}

function stamp(): string {
  return new Date().toISOString().slice(11, 19);
}

function write(agentId: string, line: string): void {
  streamFor(agentId).write(`${c.dim}${stamp()}${c.rst}  ${line}\n`);
}

/**
 * Hook called whenever the runtime ships an OutboundMessage. Splits the
 * message stream into per-agent files. Safe to call for messages with no
 * agentId — those get dropped.
 */
export function logAgentEvent(msg: OutboundMessage): void {
  if (!("agentId" in msg) || !msg.agentId) return;
  const id = msg.agentId;

  switch (msg.type) {
    case "agent_status": {
      const colour =
        msg.status === "error" ? c.red :
        msg.status === "done" ? c.green :
        msg.status === "tool_call" ? c.magenta :
        msg.status === "thinking" ? c.cyan :
        c.yellow;
      const detail = msg.detail ? ` ${c.dim}${msg.detail}${c.rst}` : "";
      write(id, `${colour}● ${msg.status}${c.rst}${detail}`);
      return;
    }
    case "agent_say": {
      write(id, `${c.green}💬 ${id}:${c.rst} ${msg.text.replace(/\n/g, " ⏎ ")}`);
      return;
    }
    case "agent_log": {
      const colour =
        msg.level === "error" ? c.red :
        msg.level === "warn" ? c.yellow :
        msg.level === "tool" ? c.magenta :
        c.dim;
      write(id, `${colour}${msg.line}${c.rst}`);
      return;
    }
    case "agent_transcript_append": {
      const e = msg.entry;
      const icons: Record<string, string> = {
        system: "  ←",
        think:  "  ⠋",
        tool:   "  →",
        result: "  ←",
        say:    " 💬",
        done:   "  ✓",
        error:  "  ✗",
      };
      const colours: Record<string, string> = {
        system: c.dim,
        think:  c.cyan,
        tool:   c.magenta,
        result: c.green,
        say:    c.green,
        done:   c.bold + c.green,
        error:  c.red,
      };
      const icon = icons[e.kind] ?? " ·";
      const colour = colours[e.kind] ?? c.rst;
      const newTurnMark = msg.isNewTurn ? `\n${c.dim}─────────────────────${c.rst}\n` : "";
      streamFor(id).write(`${newTurnMark}${c.dim}${stamp()}${c.rst} ${colour}${icon}${c.rst}  ${e.text}\n`);
      return;
    }
    case "tool_request_approval": {
      write(id, `${c.yellow}⚠ approval needed:${c.rst} ${msg.tool} — ${msg.summary} ${c.dim}(callId=${msg.callId})${c.rst}`);
      return;
    }
    case "agent_screen_update":
      // Redundant given transcript_append — skip to keep the log readable.
      return;
  }
}

export function closeAll(): void {
  for (const s of streams.values()) {
    try { s.end(); } catch { /* ignore */ }
  }
  streams.clear();
}
