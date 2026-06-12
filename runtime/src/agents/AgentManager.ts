import { HermesAgent, type AgentEvents } from "./HermesAgent.js";
import { CodeAgent } from "./CodeAgent.js";
import { WorkshopAgent } from "./WorkshopAgent.js";
import { AdkAgent } from "./AdkAgent.js";
import { HermesActivityStream } from "./HermesActivityStream.js";
import { buildRegistryForRoom } from "../tools/index.js";
import type { RoomKind } from "./prompts.js";
import type { AgentDescriptor, OutboundMessage, Vec3 } from "../types.js";
import { RoomMetrics } from "../rooms/metrics.js";
import { ensureDeck } from "../classroom/deck.js";
import { tmpdir } from "node:os";

export type Sender = (msg: OutboundMessage) => void;
export type Agent = HermesAgent | CodeAgent | WorkshopAgent | AdkAgent;

type PendingApproval = {
  resolve: (approved: boolean) => void;
  timeout: NodeJS.Timeout;
  agentId: string;
  tool: string;
  summary: string;
  requestedAt: number;
};

export type PendingApprovalView = {
  callId: string;
  agentId: string;
  tool: string;
  summary: string;
  requestedAt: number;
};

export type StateSnapshot = {
  generatedAt: number;
  agents: Array<AgentDescriptor & { ownerName: string }>;
  rooms: Array<{
    name: string;
    agentCount: number;
    totalLast24h: number;
    lastActivity: number;
    sparkline: number[];
    topTool: { name: string; count: number } | null;
  }>;
  approvals: {
    pending: PendingApprovalView[];
    approvedTotal: number;
    rejectedTotal: number;
  };
  totals: {
    agentCount: number;
    toolCalls24h: number;
    roomCount: number;
    statusBreakdown: Record<string, number>;
    primaryOwner: string | null;
  };
};

export class AgentManager {
  private agents = new Map<string, Agent>();
  private hermesStreams = new Map<string, HermesActivityStream>();
  private pendingApprovals = new Map<string, PendingApproval>();
  private send: Sender = () => {};
  private metrics = new Map<string, RoomMetrics>();
  private approvedTotal = 0;
  private rejectedTotal = 0;
  private terminalPreference: "claude" | "hermes" | null = null;

  /**
   * Live "terminal stream" for a Hermes agent. Created on first request; the
   * agent's events are mirrored into it by {@link spawn}. Sanctuary cubes
   * use this so the player can see the agent's reasoning + tool calls
   * rendered as a real terminal in-game.
   */
  hermesStream(agentId: string): HermesActivityStream | null {
    return this.hermesStreams.get(agentId) ?? null;
  }

  bindSender(send: Sender) {
    this.send = send;
  }

  // Send an arbitrary outbound message — used by HTTP endpoints (e.g. the
  // /api/teleport call from face/) that need to push a one-off command into
  // the plugin without going through the agent loop.
  broadcast(msg: OutboundMessage): void {
    this.send(msg);
  }

  list(): AgentDescriptor[] {
    return [...this.agents.values()].map((a) => ({
      id: a.id,
      name: a.id,
      role: a.role,
      home: a.home,
      room: a.room,
      status: a.status,
    }));
  }

  pendingApprovalCount(): number {
    return this.pendingApprovals.size;
  }

  approvedTotalCount(): number {
    return this.approvedTotal;
  }

  runningAgentCount(): number {
    let n = 0;
    for (const a of this.agents.values()) {
      const s = a.status;
      if (s === "thinking" || s === "tool_call" || s === "speaking") n++;
    }
    return n;
  }

  notePlayerRoom(room: string): void {
    const lower = room.toLowerCase();
    if (lower === "code") this.terminalPreference = "claude";
    else if (lower === "hermes") this.terminalPreference = "hermes";
  }

  preferredTerminalAgentId(): "claude" | "hermes" | null {
    return this.terminalPreference;
  }

  snapshot(): StateSnapshot {
    const agentList = [...this.agents.values()];
    const statusBreakdown: Record<string, number> = {};
    const ownerCounts = new Map<string, number>();
    const roomsSeen = new Set<string>();
    for (const a of agentList) {
      statusBreakdown[a.status] = (statusBreakdown[a.status] ?? 0) + 1;
      ownerCounts.set(a.ownerName, (ownerCounts.get(a.ownerName) ?? 0) + 1);
      roomsSeen.add(a.room);
    }
    for (const r of this.metrics.keys()) roomsSeen.add(r);

    const rooms = [...roomsSeen].map((name) => {
      const snap = this.getMetrics(name).snapshot();
      const count = agentList.filter((a) => a.room === name).length;
      return {
        name,
        agentCount: count,
        totalLast24h: snap.totalLast24h,
        lastActivity: snap.lastActivity,
        sparkline: snap.sparkline,
        topTool: snap.topTool,
      };
    });

    const toolCalls24h = rooms.reduce((sum, r) => sum + r.totalLast24h, 0);

    let primaryOwner: string | null = null;
    let topCount = 0;
    for (const [owner, count] of ownerCounts) {
      if (count > topCount) { primaryOwner = owner; topCount = count; }
    }

    const pending: PendingApprovalView[] = [];
    for (const [callId, p] of this.pendingApprovals) {
      pending.push({
        callId,
        agentId: p.agentId,
        tool: p.tool,
        summary: p.summary,
        requestedAt: p.requestedAt,
      });
    }
    pending.sort((a, b) => b.requestedAt - a.requestedAt);

    return {
      generatedAt: Date.now(),
      agents: agentList.map((a) => ({
        id: a.id,
        name: a.id,
        role: a.role,
        home: a.home,
        room: a.room,
        status: a.status,
        ownerName: a.ownerName,
      })),
      rooms,
      approvals: {
        pending,
        approvedTotal: this.approvedTotal,
        rejectedTotal: this.rejectedTotal,
      },
      totals: {
        agentCount: agentList.length,
        toolCalls24h,
        roomCount: rooms.length,
        statusBreakdown,
        primaryOwner,
      },
    };
  }

  get(id: string): Agent | undefined {
    return this.agents.get(id);
  }

  spawn(opts: {
    agentId: string;
    role: string;
    home: Vec3;
    room: string;
    roomKind: RoomKind;
    ownerName: string;
    cwd?: string;
    launch?: string;
  }): Agent {
    if (this.agents.has(opts.agentId)) {
      return this.agents.get(opts.agentId)!;
    }
    // Every villager except the Code-Lab PTY workshop gets a terminal activity
    // stream so the in-game terminal can attach via the multiplex server. Both
    // brain types now render in "reasoning" mode — the FULL, untruncated feed
    // (prompts, narration, tool calls with args, tool results, telemetry) — so
    // right-clicking any villager shows everything it's doing, not just the
    // terse floating-board cut (the board/lectern still get that via onScreen
    // /onTranscript). workshop_team villagers stream their real `claude` PTY
    // instead (null).
    const isPtyWorkshop = opts.roomKind === "workshop_team";
    const isCodeAgent = opts.roomKind === "workshop";
    const isAdk = opts.roomKind === "mission_control";
    const isBuild = opts.room.toLowerCase().startsWith("build");
    const stream = isPtyWorkshop
      ? null
      : new HermesActivityStream(
          opts.agentId,
          opts.role,
          opts.room,
          isAdk
            ? {
                mode: "reasoning",
                title: "omo · chief of staff",
                subtitle: "live org reasoning — Gemini via the ADK · delegation, tool calls & results · talk in chat",
              }
            : isCodeAgent
            ? {
                mode: "reasoning",
                title: isBuild ? "build studio" : "claude code",
                subtitle: isBuild
                  ? "live build reasoning — narration, build ops & results · talk to the mason in chat"
                  : "live agent reasoning — narration, tool calls & results · talk in chat",
              }
            : {
                mode: "reasoning",
                title: "hermes-agent",
                subtitle: "live agent reasoning — prompts, tool calls (full args) & results · talk in chat",
              },
        );
    if (stream) this.hermesStreams.set(opts.agentId, stream);

    const events: AgentEvents = {
      onStatus: (status, detail) => {
        this.send({ type: "agent_status", agentId: opts.agentId, status, detail });
        stream?.onStatus(status, detail);
      },
      onSay: (text, playerName) => {
        this.send({ type: "agent_say", agentId: opts.agentId, text, playerName });
        stream?.onSay(text, playerName);
      },
      onLog: (line, level) => {
        this.send({ type: "agent_log", agentId: opts.agentId, line, level });
        stream?.onLog(line, level);
      },
      onScreen: (entries) =>
        this.send({ type: "agent_screen_update", agentId: opts.agentId, entries }),
      onTranscript: (entry, isNewTurn) => {
        this.send({
          type: "agent_transcript_append",
          agentId: opts.agentId,
          entry,
          isNewTurn,
        });
        stream?.onTranscript(entry, isNewTurn);
        if (entry.kind === "tool") {
          this.getMetrics(opts.room).recordToolCall(toolNameFromEntry(entry.text));
        }
      },
      onReasoning: (text, tone, o) => stream?.writeReasoning(text, tone, o),
      onRequestApproval: (callId, tool, summary) =>
        this.awaitApproval(opts.agentId, callId, tool, summary),
      onBuildOps: (ops, clearFirst) =>
        this.send({ type: "build_ops", agentId: opts.agentId, ops, clearFirst }),
      onOpenClassroom: (p) =>
        this.send({
          type: "open_classroom_request",
          subject: p.subject,
          playerName: p.playerName ?? opts.ownerName ?? null,
        }),
      onStartCodeWorld: (p) =>
        this.send({
          type: "spawn_code_request",
          agentId: p.agentId,
          cwd: p.cwd,
          task: p.task,
          playerName: p.playerName ?? opts.ownerName ?? null,
        }),
      onStartHermesWorld: (p) =>
        this.send({
          type: "spawn_hermes_request",
          agentId: p.agentId,
          role: p.role,
          playerName: p.playerName ?? opts.ownerName ?? null,
        }),
    };

    let agent: Agent;
    if (opts.roomKind === "workshop_team") {
      const cwd = opts.cwd ?? process.cwd();
      const wa = new WorkshopAgent(
        {
          id: opts.agentId,
          role: opts.role,
          home: opts.home,
          room: opts.room,
          ownerName: opts.ownerName,
          cwd,
          launch: opts.launch,
        },
        events,
      );
      // Eager-start the PTY the instant the agent spawns (the plate step), so
      // the shell + tool are already booting before the terminal subscribes.
      // By the time the screen connects, the replay buffer has real output —
      // the terminal opens onto a live shell, never a blank "connecting" wait.
      wa.ensurePty();
      agent = wa;
    } else if (opts.roomKind === "workshop") {
      const cwd = opts.cwd ?? process.cwd();
      agent = new CodeAgent(
        {
          id: opts.agentId,
          role: opts.role,
          home: opts.home,
          room: opts.room,
          ownerName: opts.ownerName,
          cwd,
          buildMode: opts.room.toLowerCase().startsWith("build"),
        },
        events,
      );
    } else if (opts.roomKind === "dean_room") {
      // The Dean runs on the existing CodeAgent (Claude) brain — like the build
      // mason — because the local Hermes model won't reliably emit the
      // open_classroom tool call. Its only tool is the in-process open_classroom
      // MCP tool (deanMode), and it runs on the Haiku tier (fast + cheap). cwd
      // is just a scratch dir (it never touches files); the plugin sends no cwd.
      agent = new CodeAgent(
        {
          id: opts.agentId,
          role: opts.role,
          home: opts.home,
          room: opts.room,
          ownerName: opts.ownerName,
          cwd: opts.cwd ?? tmpdir(),
          deanMode: true,
        },
        events,
      );
    } else if (opts.roomKind === "classroom") {
      // The tutor "ada" runs on the CodeAgent (Claude) brain in teachMode so she
      // RELIABLY drives the slides (show_slide) — the local Hermes model won't
      // call tools dependably. Kick off the Haiku slide deck on spawn (idempotent
      // + fire-and-forget); the path-independent backstop so every spawn route
      // (/omo build, /omo school, the Dean, manual /omo classroom) both
      // themes the board and generates the deck. cwd is a scratch dir (topic
      // mode reads no files).
      const subject =
        (opts.role || "").replace(/\s+tutor$/i, "").replace(/\s+101$/i, "").trim() || "Algebra";
      ensureDeck(subject);
      agent = new CodeAgent(
        {
          id: opts.agentId,
          role: opts.role,
          home: opts.home,
          room: opts.room,
          ownerName: opts.ownerName,
          cwd: opts.cwd ?? tmpdir(),
          teachMode: true,
        },
        events,
      );
    } else if (opts.roomKind === "mission_control") {
      // Omo HQ → the ADK + Gemini Chief of Staff (app "omo"). A staffed function
      // room (fn-*) → a hired specialist (app "specialist"), seeded with its role
      // so it adopts its function instead of acting as another coordinator.
      const isHq = !opts.room.toLowerCase().startsWith("fn-");
      agent = new AdkAgent(
        {
          id: opts.agentId,
          role: opts.role,
          home: opts.home,
          room: opts.room,
          ownerName: opts.ownerName,
          app: isHq ? "omo" : "specialist",
          persona: isHq
            ? undefined
            : `You are the ${opts.role} specialist (your function_id is "${opts.agentId}"), just hired into the Omo organisation. Do your function's work concisely and stay in role as ${opts.role}. Show the owner your key findings on the screen in your room by calling dashboard_update with function_id "${opts.agentId}" (4-6 KPIs + a few feed lines), and revise it whenever they ask.`,
        },
        events,
      );
    } else {
      const tools = buildRegistryForRoom(opts.roomKind);
      agent = new HermesAgent(
        {
          id: opts.agentId,
          role: opts.role,
          home: opts.home,
          room: opts.room,
          roomKind: opts.roomKind,
          ownerName: opts.ownerName,
        },
        tools,
        events,
      );
    }
    this.agents.set(opts.agentId, agent);
    return agent;
  }

  despawn(agentId: string) {
    const a = this.agents.get(agentId);
    if (a && "dispose" in a) a.dispose();
    this.agents.delete(agentId);
    this.hermesStreams.delete(agentId);
  }

  approve(callId: string, approved: boolean) {
    const p = this.pendingApprovals.get(callId);
    if (!p) return;
    clearTimeout(p.timeout);
    this.pendingApprovals.delete(callId);
    if (approved) this.approvedTotal++; else this.rejectedTotal++;
    p.resolve(approved);
  }

  private awaitApproval(
    agentId: string,
    callId: string,
    tool: string,
    summary: string,
  ): Promise<boolean> {
    return new Promise<boolean>((resolve) => {
      this.send({ type: "tool_request_approval", agentId, callId, tool, summary });
      // 2-minute timeout — declined by default if the player doesn't respond.
      const timeout = setTimeout(() => {
        if (this.pendingApprovals.delete(callId)) {
          this.rejectedTotal++;
          resolve(false);
        }
      }, 120_000);
      this.pendingApprovals.set(callId, {
        resolve,
        timeout,
        agentId,
        tool,
        summary,
        requestedAt: Date.now(),
      });
    });
  }

  private getMetrics(room: string): RoomMetrics {
    let m = this.metrics.get(room);
    if (!m) {
      m = new RoomMetrics();
      this.metrics.set(room, m);
    }
    return m;
  }

}

function toolNameFromEntry(text: string): string {
  // Entry text looks like `tool_name "arg"` — strip args to keep frequency keys clean.
  const space = text.indexOf(" ");
  return space < 0 ? text : text.slice(0, space);
}
