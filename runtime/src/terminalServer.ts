import { WebSocketServer, type WebSocket } from "ws";
import { existsSync } from "node:fs";
import { AgentManager, type Agent } from "./agents/AgentManager.js";
import { WorkshopAgent } from "./agents/WorkshopAgent.js";
import { HermesAgent } from "./agents/HermesAgent.js";
import { CodeAgent } from "./agents/CodeAgent.js";
import { AdkAgent } from "./agents/AdkAgent.js";
import { clipboardImageToFile } from "./clipboard.js";
import { authConfigured, checkClientToken, isLoopback } from "./auth.js";
import { roomKindFromName } from "./rooms/registry.js";
import type { HermesActivityStream } from "./agents/HermesActivityStream.js";

/**
 * Terminal multiplex server — listens on its own port (default 8767).
 *
 * Inbound from the in-game terminal client:
 *   { type: "subscribe", agentId }     — start streaming this agent's source
 *   { type: "input", bytes }           — base64 keystrokes for the current agent
 *   { type: "resize", cols, rows }     — resize current agent's PTY (no-op for hermes)
 *   { type: "paste_image" }            — read an image off the host clipboard,
 *                                        write a temp PNG, feed its path to claude
 *   { type: "list" }                   — request the agent roster
 *
 * Outbound from runtime:
 *   { type: "agents", list }           — agents available + alive state + kind
 *   { type: "pty_data", agentId, b64 } — stream bytes (base64 utf8 chunks)
 *   { type: "subscribed", agentId, team, kind, replay }
 *                                       — confirm + initial replay buffer +
 *                                         peer list for ←/→ cycling.
 *   { type: "error", message }
 *
 * "Source" is either a WorkshopAgent's real {@code claude} PTY (Code Lab) or
 * a HermesAgent's {@link HermesActivityStream} (Agent Camp sanctuary cubes).
 * The wire protocol is identical for both — the screen on the client doesn't
 * need to care which kind it's looking at.
 */

type ClientState = {
  agentId: string | null;
  unsubscribe: (() => void) | null;
  // Set while an explicit agent subscribe is resolving, so the default-attach
  // timer can't replace the screen with the fallback "claude" terminal during
  // the brief window before the requested agent's source appears.
  claiming: string | null;
  // Loopback clients are trusted immediately; a remote client stays false until
  // it sends a valid `hello` token. While false, every other frame is dropped
  // (this is what keeps the agent PTYs off the open internet). See ./auth.ts.
  authed: boolean;
  // "player" = read+write (the seated operator); "spectator" = read-only (input
  // frames dropped, the byte stream run through a redaction tap). Defaults to
  // "player" so existing trusted terminals (Code Lab / Agent Camp) are
  // unchanged; only worlds that mark a room kind untrusted downgrade to it.
  role: TerminalRole;
};

type TerminalRole = "player" | "spectator";

type InMsg =
  | { type: "hello"; token?: string }
  | { type: "subscribe"; agentId?: string | null; role?: TerminalRole; operatorKey?: string }
  | { type: "input"; bytes: string }
  | { type: "resize"; cols: number; rows: number }
  | { type: "paste_image" }
  | { type: "list" };

type AgentKind = "workshop" | "hermes";

type SourceLike = {
  snapshot(): Buffer;
  onData(listener: (b: Buffer) => void): () => void;
  writeStdinBytes(buf: Buffer): void;
  resize(cols: number, rows: number): void;
  isAlive(): boolean;
};

// --- Read-only-terminal security foundation -------------------------------
//
// A subscribe is either a "player" (the seated operator — read+write) or a
// "spectator" (read-only mirror: input frames are dropped and the outgoing byte
// stream is run through a redaction tap). This is the shared gate that lets a
// terminal stream to a crowd without handing the crowd the host shell. Two
// boundaries it enforces:
//
//   1. A spectator NEVER reaches a writable shell (writeInput/paste/resize are
//      dropped for role === "spectator").
//   2. A room kind that puts a real host shell behind (ClaudePty) MUST be
//      listed in UNTRUSTED_KINDS. An untrusted-kind terminal is forced to
//      spectator UNLESS the subscriber presents the operator key bound to that
//      seat via bindTerminalOperator() — so a stranger stepping on the seat
//      watches, and only the matched operator can type.
//
// The patterns are defensive, not a sandbox; the real isolation tier
// (SessionBox) swaps in behind sourceFor() later. Until then UNTRUSTED_KINDS +
// the spectator role keep untrusted audiences off the shell.
// The foundation has no untrusted-kind seats (those shipped with the removed
// worlds). The mechanism stays so any future untrusted room kind can opt in by
// adding its kind here; until then every terminal is a trusted operator shell.
export const UNTRUSTED_KINDS = new Set<string>([]);

// agentId -> operator key the world bound when it seated the trusted player.
const operatorBindings = new Map<string, string>();
/** Bind the operator key allowed to WRITE to an untrusted-kind agent's terminal. */
export function bindTerminalOperator(agentId: string, operatorKey: string): void {
  operatorBindings.set(agentId, operatorKey);
}
/** Release a seat binding (e.g. when the match ends / the player leaves). */
export function unbindTerminalOperator(agentId: string): void {
  operatorBindings.delete(agentId);
}

function isUntrustedAgent(manager: AgentManager, agentId: string): boolean {
  const a = manager.get(agentId);
  return a ? UNTRUSTED_KINDS.has(roomKindFromName(a.room)) : false;
}

// Scrub obvious secrets from a chunk before it streams to a spectator. Best
// effort per-chunk (a secret split across two chunks can slip) — the real
// boundary is "spectators can't get a shell", this just keeps an accidental
// `printenv` off the public wall.
const SECRET_PATTERNS: RegExp[] = [
  /sk-[A-Za-z0-9-_]{8,}/g,           // OpenAI / Anthropic style keys
  /ghp_[A-Za-z0-9]{20,}/g,           // GitHub PATs
  /xox[baprs]-[A-Za-z0-9-]{8,}/g,    // Slack tokens
  /AKIA[0-9A-Z]{12,}/g,              // AWS access key ids
  /\beyJ[A-Za-z0-9._-]{20,}/g,       // JWT-ish
  /(?<=[Bb]earer\s)[A-Za-z0-9._-]{12,}/g,
  /\b(?:ANTHROPIC_API_KEY|OPENAI_API_KEY|HERMES_API_KEY|HERMES_API_SERVER_KEY|META_ADS_ACCESS_TOKEN|OMO_CLIENT_TOKEN|AGENTCRAFT_WS_TOKEN|AWS_SECRET_ACCESS_KEY)\s*=\s*\S+/g,
];
function redactSecrets(buf: Buffer): Buffer {
  const s = buf.toString("utf8");
  let out = s;
  for (const re of SECRET_PATTERNS) {
    re.lastIndex = 0;
    out = out.replace(re, "«redacted»");
  }
  return out === s ? buf : Buffer.from(out, "utf8");
}

export function startTerminalServer(manager: AgentManager, port: number): WebSocketServer {
  // Bind 0.0.0.0 so friends' game clients can reach this over the network
  // (the in-game F4 terminal points here via the mod's RuntimeHost). This
  // exposes the agent PTY stream, so it's only appropriate for a trusted
  // group behind a port-forward — set AGENTCRAFT_BIND_HOST=127.0.0.1 to lock
  // it back to localhost-only.
  const host = process.env.AGENTCRAFT_BIND_HOST ?? "0.0.0.0";
  const wss = new WebSocketServer({ port, host });
  console.log(`[terminal] multiplex listening on ws://${host}:${port}`);

  wss.on("connection", (ws, req) => {
    const addr = req.socket.remoteAddress ?? "";
    const remote = !isLoopback(addr);
    const state: ClientState = {
      agentId: null,
      unsubscribe: null,
      claiming: null,
      authed: !remote,
      role: "player",
    };

    // Remote client but no OMO_CLIENT_TOKEN configured: refuse rather than
    // expose the agent PTYs (an input frame here writes raw keystrokes into a
    // live Claude shell — i.e. RCE) to the network. Loopback is unaffected.
    if (remote && !authConfigured()) {
      console.warn(`[terminal] rejecting remote client ${addr}: OMO_CLIENT_TOKEN not set`);
      ws.close(1008, "auth not configured");
      return;
    }

    console.log(`[terminal] client connected${remote ? ` (remote ${addr}, awaiting hello)` : ""}`);
    if (state.authed) beginSession(ws, state, manager);

    ws.on("message", (raw) => {
      let msg: InMsg;
      try { msg = JSON.parse(raw.toString()) as InMsg; } catch { return; }
      if (!state.authed) {
        // A remote client's first frame must authenticate; anything else is
        // dropped and the socket closed.
        if (msg.type === "hello" && checkClientToken(msg.token)) {
          state.authed = true;
          console.log(`[terminal] remote client ${addr} authenticated`);
          beginSession(ws, state, manager);
        } else {
          console.warn(`[terminal] remote client ${addr} failed auth`);
          ws.close(1008, "unauthorized");
        }
        return;
      }
      if (msg.type === "hello") return; // already authed; ignore duplicate
      handleMessage(ws, state, manager, msg);
    });

    ws.on("close", () => {
      state.unsubscribe?.();
      state.unsubscribe = null;
      state.agentId = null;
      state.claiming = null;
    });
  });

  return wss;
}

// Send the agent roster and arm the default-attach fallback. Runs as soon as a
// loopback client connects, or once a remote client passes the `hello` check.
//
// Older client builds could open the socket but drop their first subscribe
// frame during the Java HttpClient onOpen/buildAsync race. Give current clients
// a moment to name a specific agent, then attach to the default terminal so the
// screen never sits on "connecting..." forever. If a specific agent is already
// being claimed (right-click a villager), don't override it with the default.
function beginSession(ws: WebSocket, state: ClientState, manager: AgentManager): void {
  sendAgentList(ws, manager);
  setTimeout(() => {
    if (state.agentId || state.claiming || ws.readyState !== ws.OPEN) return;
    attachTo(ws, state, manager, "");
  }, 150);
}

function handleMessage(ws: WebSocket, state: ClientState, manager: AgentManager, msg: InMsg): void {
  switch (msg.type) {
    case "list":
      sendAgentList(ws, manager);
      return;
    case "subscribe":
      attachTo(ws, state, manager, msg.agentId ?? "", 0, msg.role ?? "player", msg.operatorKey);
      return;
    case "input":
      writeInput(state, manager, msg.bytes);
      return;
    case "paste_image":
      pasteImage(state, manager);
      return;
    case "resize":
      resizePty(state, manager, msg.cols, msg.rows);
      return;
  }
}

function kindOf(manager: AgentManager, agentId: string): AgentKind | null {
  const a = manager.get(agentId);
  if (!a) return null;
  if (a instanceof WorkshopAgent) return "workshop";
  // CodeAgent (build "mason" / code-lab villager) is a read-only reasoning
  // mirror like Hermes — the player talks to it via in-game chat, not by typing.
  if (a instanceof HermesAgent || a instanceof CodeAgent || a instanceof AdkAgent) return "hermes";
  return null;
}

function sourceFor(manager: AgentManager, agentId: string): { source: SourceLike; kind: AgentKind } | null {
  ensureBuiltinTerminalAgent(manager, agentId);
  const a = manager.get(agentId);
  if (!a) return null;
  if (a instanceof WorkshopAgent) {
    const pty = a.ensurePty();
    if (!pty) return null;
    return { source: pty as unknown as SourceLike, kind: "workshop" };
  }
  if (a instanceof HermesAgent || a instanceof CodeAgent || a instanceof AdkAgent) {
    const stream = manager.hermesStream(agentId);
    if (!stream) return null;
    return { source: stream as unknown as SourceLike, kind: "hermes" };
  }
  return null;
}

function ensureBuiltinTerminalAgent(manager: AgentManager, agentId: string): void {
  if (manager.get(agentId)) return;
  if (agentId !== "claude" && agentId !== "hermes") return;

  const homeDir = process.env.HOME ?? process.cwd();
  const fernDir = `${homeDir}/Fern`;
  const isClaude = agentId === "claude";
  const role = isClaude ? "Claude Code engineer" : "Hermes agent";
  const room = isClaude ? "code" : "hermes";
  const cwd = isClaude && existsSync(fernDir) ? fernDir : homeDir;
  const launch = isClaude ? "claude" : "hermes chat";
  manager.spawn({
    agentId,
    role,
    home: { x: 0, y: 0, z: 0 },
    room,
    roomKind: "workshop_team",
    ownerName: "terminal",
    cwd,
    launch,
  });
  manager.broadcast({
    type: "ensure_terminal_agent_request",
    agentId,
    room,
    role,
    cwd,
    launch,
    playerName: null,
  });
}

function defaultTerminalAgentId(manager: AgentManager): string | null {
  const preferred = manager.preferredTerminalAgentId();
  if (!manager.list().length) {
    ensureBuiltinTerminalAgent(manager, preferred ?? "claude");
  }
  const ids = manager.list().map((a) => a.id);
  const priority = preferred
    ? [preferred, ...(["claude", "hermes"] as const).filter((id) => id !== preferred)]
    : (["claude", "hermes"] as const);
  for (const candidate of priority) {
    if (ids.includes(candidate) && sourceFor(manager, candidate)) return candidate;
  }
  for (const a of manager.list()) {
    if (sourceFor(manager, a.id)) return a.id;
  }
  return null;
}

function isLegacyFallbackAgent(agentId: string): boolean {
  return ["alice", "bob", "carol", "dave"].includes(agentId.toLowerCase());
}

function teamFor(manager: AgentManager, agent: Agent | undefined): string[] {
  // Shift+←/→ cycles within the same terminal family so the player walks from
  // one peer's view to the next without leaving the screen: Code-Lab PTY
  // workshop agents, build/workshop CodeAgents ("masons"), or the sanctuary
  // Hermes cubes. A CodeAgent never gets mixed into the Hermes cube cycle.
  const sameFamily = (other: Agent): boolean => {
    if (agent instanceof WorkshopAgent) return other instanceof WorkshopAgent;
    if (agent instanceof CodeAgent) return other instanceof CodeAgent;
    if (agent instanceof HermesAgent || agent instanceof AdkAgent)
      return other instanceof HermesAgent || other instanceof AdkAgent;
    return false;
  };
  return manager.list()
    .map((d) => manager.get(d.id))
    .filter((a): a is Agent => a != null && sameFamily(a))
    .map((a) => a.id);
}

function sendAgentList(ws: WebSocket, manager: AgentManager): void {
  const list = manager.list().map((a) => {
    const real = manager.get(a.id);
    const kind: AgentKind | null = real instanceof WorkshopAgent
      ? "workshop"
      : (real instanceof HermesAgent || real instanceof CodeAgent || real instanceof AdkAgent)
        ? "hermes"
        : null;
    if (!kind) return null;
    let alive = false;
    if (real instanceof WorkshopAgent) alive = real.getPty()?.isAlive() ?? false;
    else if (real instanceof HermesAgent || real instanceof CodeAgent || real instanceof AdkAgent) {
      alive = manager.hermesStream(a.id) != null;
    }
    return {
      id: a.id,
      role: a.role,
      room: a.room,
      status: a.status,
      kind,
      alive,
    };
  }).filter((x): x is NonNullable<typeof x> => x != null);
  send(ws, { type: "agents", list });
}

function attachTo(ws: WebSocket, state: ClientState, manager: AgentManager, requestedAgentId: string, attempt = 0, requestedRole: TerminalRole = "player", operatorKey?: string): void {
  const hadAttachment = state.agentId != null;
  // Unsubscribe previous attachment first.
  state.unsubscribe?.();
  state.unsubscribe = null;
  state.agentId = null;

  const requested = requestedAgentId.trim();
  // An explicit request (right-click a villager / F4 a named agent) claims this
  // socket so the default-attach timer won't swap in the fallback "claude"
  // terminal while we wait for the requested agent's source to appear.
  const explicit = requested !== "" && !isLegacyFallbackAgent(requested);
  if (explicit) state.claiming = requested;
  let agentId = requested;
  let sourced = requested ? sourceFor(manager, requested) : null;

  // A no-agent TeamTerminalScreen still opens by subscribing to its first
  // fallback entry, "claude". If the player is standing in the Hermes room and
  // this is the first attachment for this socket, treat that as the default
  // request and show the Hermes agent instead. Once attached, explicit
  // switch-agent requests are honoured normally.
  const preferred = manager.preferredTerminalAgentId();
  if (!hadAttachment && requested === "claude" && preferred === "hermes") {
    agentId = "hermes";
    sourced = sourceFor(manager, agentId);
  }

  // Older client builds opened a no-arg terminal as alice/bob/carol/dave.
  // The MVP boxes now expose claude/hermes PTYs, so treat those stale
  // placeholders as "pick the default terminal" instead of a hard failure.
  if (!sourced && (!requested || isLegacyFallbackAgent(requested))) {
    const fallback = defaultTerminalAgentId(manager);
    if (fallback) {
      agentId = fallback;
      sourced = sourceFor(manager, fallback);
    }
  }

  // The plate spawns the agent and opens the terminal in the same tick, so a
  // subscribe can land before the spawn_agent message has been processed.
  // Wait up to ~3s for the agent + its source to appear before giving up.
  const a = agentId ? manager.get(agentId) : undefined;
  if (!sourced) {
    if (ws.readyState === ws.OPEN && attempt < 15) {
      setTimeout(() => attachTo(ws, state, manager, requestedAgentId, attempt + 1, requestedRole, operatorKey), 200);
      return;
    }
    const label = requested || "(default)";
    console.warn(`[terminal] no source for ${label} after ${attempt} tries (exists=${!!a})`);
    state.claiming = null;
    send(ws, { type: "error", message: `no live source for ${label}` });
    return;
  }
  // Resolve the effective role. An untrusted-kind seat (a real host shell
  // behind a public seat) is forced read-only unless the subscriber presents
  // the operator key the world bound to this agent — so a stranger watches and
  // only the seated operator types. Trusted kinds (Code Lab / Agent Camp) keep
  // the requested role, which defaults to "player".
  let role: TerminalRole = requestedRole;
  if (role === "player" && isUntrustedAgent(manager, agentId)) {
    const bound = operatorBindings.get(agentId);
    if (!bound || bound !== operatorKey) role = "spectator";
  }
  state.role = role;

  console.log(`[terminal] subscribed ${agentId} (${sourced.kind}, ${role})`);
  const { source, kind } = sourced;
  const team = teamFor(manager, a ?? manager.get(agentId));

  // Replay buffer first so the new attacher sees the current frame. Spectators
  // get a redacted replay + redacted live stream.
  const snap = source.snapshot();
  send(ws, {
    type: "subscribed",
    agentId,
    kind,
    role,
    team,
    replay: (role === "spectator" ? redactSecrets(snap) : snap).toString("base64"),
  });

  const unsub = source.onData((bytes) => {
    if (ws.readyState !== ws.OPEN) return;
    const out = role === "spectator" ? redactSecrets(bytes) : bytes;
    send(ws, { type: "pty_data", agentId, b64: out.toString("base64") });
  });

  state.agentId = agentId;
  state.claiming = null;
  state.unsubscribe = unsub;
}

function writeInput(state: ClientState, manager: AgentManager, b64: string): void {
  if (!state.agentId) return;
  if (state.role === "spectator") return; // read-only: never write to the shell
  const sourced = sourceFor(manager, state.agentId);
  if (!sourced) return;
  let buf: Buffer;
  try { buf = Buffer.from(b64, "base64"); } catch { return; }
  sourced.source.writeStdinBytes(buf);
}

function pasteImage(state: ClientState, manager: AgentManager): void {
  if (!state.agentId) return;
  if (state.role === "spectator") return; // read-only: no paste into the shell
  const sourced = sourceFor(manager, state.agentId);
  if (!sourced) return;
  // Only the real claude PTY can receive input — the hermes stream is a
  // read-only mirror, so there's nothing to paste into.
  if (sourced.kind !== "workshop") return;
  const file = clipboardImageToFile();
  if (!file) {
    console.log("[terminal] paste_image: no image on the clipboard");
    return;
  }
  // Inject the path the way dragging an image into the real Claude Code
  // terminal does — claude auto-detects image paths and attaches them on
  // submit. Trailing space so the player can keep typing after it.
  console.log(`[terminal] paste_image -> ${file}`);
  sourced.source.writeStdinBytes(Buffer.from(`${file} `, "utf8"));
}

function resizePty(state: ClientState, manager: AgentManager, cols: number, rows: number): void {
  if (!state.agentId) return;
  if (state.role === "spectator") return; // read-only: don't let a viewer resize the shared PTY
  if (cols < 5 || cols > 500 || rows < 3 || rows > 200) return;
  const sourced = sourceFor(manager, state.agentId);
  if (!sourced) return;
  sourced.source.resize(cols, rows);
}

function send(ws: WebSocket, msg: unknown): void {
  if (ws.readyState !== ws.OPEN) return;
  ws.send(JSON.stringify(msg));
}

// Suppress unused-import warning when tsc strips the type-only reference.
void (null as unknown as HermesActivityStream | null);
