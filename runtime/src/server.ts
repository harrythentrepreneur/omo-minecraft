import "dotenv/config";
import { WebSocketServer, type WebSocket } from "ws";
import { AgentManager } from "./agents/AgentManager.js";
import { roomKindFromName } from "./rooms/registry.js";
import type { InboundMessage, OutboundMessage, ScreenEntry } from "./types.js";
import { dbg, debugEnabled, info } from "./debug.js";
import { logAgentEvent, closeAll as closeAgentLogs } from "./agentLog.js";
import { startHttpServer } from "./http.js";
import { startTerminalServer } from "./terminalServer.js";
import { startMcpServer } from "./mcpServer.js";
import { startDashboardServer, setSocietyProvider } from "./dashboardServer.js";
import { ensureCustomDashboard } from "./dashboardArchitect.js";
import { startDashboardFeed } from "./dashboardFeed.js";
import { WorldStore } from "./worldStore.js";

const PORT = Number(process.env.AGENTCRAFT_WS_PORT ?? 8765);
const HTTP_PORT = Number(process.env.AGENTCRAFT_HTTP_PORT ?? 8766);
const TERMINAL_PORT = Number(process.env.AGENTCRAFT_TERMINAL_PORT ?? 8767);
const TOKEN = process.env.AGENTCRAFT_WS_TOKEN ?? "";

if (!TOKEN) {
  console.warn(
    "[bridge] AGENTCRAFT_WS_TOKEN not set — plugin connections will be rejected. Set it in runtime/.env",
  );
}

const manager = new AgentManager();
const world = new WorldStore();

// Bind loopback only: the Paper plugin runs on the same box and connects to
// 127.0.0.1:8765. The bridge carries no player traffic, so it must never be
// reachable from the network — keep this on localhost even though the two
// player-facing ports (:8766/:8767) bind 0.0.0.0.
const wss = new WebSocketServer({ port: PORT, host: "127.0.0.1" });
console.log(`[bridge] Omo runtime listening on ws://127.0.0.1:${PORT}`);
if (debugEnabled) info("bridge", "debug logging ENABLED (AGENTCRAFT_DEBUG=true)");

startHttpServer(manager, HTTP_PORT);
startTerminalServer(manager, TERMINAL_PORT);
// Omo Mission Control: the MCP server the ADK + Gemini crew connects to
// (real Meta Ads tools + the World API) and the live dashboard server.
startMcpServer(manager, world);
startDashboardServer();
startDashboardFeed();

// The HQ crew's two boards get bespoke, function-designed pages too (Society is
// already its own custom page). Pre-warmed at boot + disk-cached, so the HQ
// triptych is custom from the first `/omo hq`. Each built function designs its
// own page when its room is built (mcpServer world_build).
ensureCustomDashboard("growth", "Growth", "paid acquisition, ad performance, ROAS/CAC, funnel and revenue growth");
ensureCustomDashboard("comms", "Comms", "outreach, lifecycle email, announcements, replies and support");

// Society View (/dash/society): join the live agent statuses (snapshot()) with
// the org graph (world.list()) and the consultation log into one whole-ecosystem
// board. Assembled lazily on each /dash/society/data poll so it's always current.
setSocietyProvider(() => {
  const snap = manager.snapshot();
  const statusByAgent = new Map(snap.agents.map((a) => [a.id, a.status]));
  const nodes = world.list().map((f) => ({
    id: f.id,
    role: f.role,
    purpose: f.purpose,
    room: f.room,
    staffed: f.staffed,
    status: f.staffed && f.agentId ? statusByAgent.get(f.agentId) ?? "idle" : "unstaffed",
  }));
  const consults = world.recentConsults(20);
  const working = nodes.filter(
    (n) => n.status === "thinking" || n.status === "tool_call" || n.status === "speaking",
  ).length;
  return {
    nodes,
    edges: consults.map((c) => ({
      from: c.from,
      to: c.to,
      fromRole: c.fromRole,
      toRole: c.toRole,
      question: c.question,
      answer: c.answer,
      status: c.status,
      at: c.at,
    })),
    totals: {
      functions: nodes.length,
      staffed: nodes.filter((n) => n.staffed).length,
      working,
      consults: consults.length,
      pendingApprovals: snap.approvals.pending.length,
    },
    updatedAt: Date.now(),
  };
});

wss.on("connection", (ws) => {
  let authed = false;
  let serverName = "unknown";

  const send = (msg: OutboundMessage) => {
    if (ws.readyState === ws.OPEN) {
      const json = JSON.stringify(msg);
      dbg("bridge", `out ${msg.type}`, json.length > 200 ? `${json.length}b` : msg);
      logAgentEvent(msg);
      ws.send(json);
    }
  };

  ws.on("message", async (raw) => {
    let msg: InboundMessage;
    try {
      msg = JSON.parse(raw.toString()) as InboundMessage;
    } catch {
      return;
    }
    dbg("bridge", `in  ${msg.type}`, msg);

    if (!authed) {
      if (msg.type !== "hello") {
        ws.close(1008, "hello required first");
        return;
      }
      if (msg.token !== TOKEN) {
        ws.close(1008, "bad token");
        return;
      }
      authed = true;
      serverName = msg.serverName;
      console.log(`[bridge] paper server "${serverName}" connected`);
      // Bind this connection as the broadcaster.
      manager.bindSender(send);
      send({ type: "ready" });
      return;
    }

    await handleAuthed(msg, send);
  });

  ws.on("close", () => {
    console.log(`[bridge] paper server "${serverName}" disconnected`);
  });

  ws.on("error", (err) => {
    console.warn(`[bridge] socket error: ${err.message}`);
  });
});

async function handleAuthed(msg: InboundMessage, send: (m: OutboundMessage) => void) {
  switch (msg.type) {
    case "spawn_agent": {
      const kind = roomKindFromName(msg.room);
      // spawn() is idempotent: a right-click "wake" re-sends spawn_agent for an
      // agent that may already be alive. Only announce a fresh spawn for a NEW
      // agent so a wake-ping never flips a busy villager's board back to "idle".
      const existed = manager.get(msg.agentId) != null;
      manager.spawn({
        agentId: msg.agentId,
        role: msg.role,
        home: msg.home,
        room: msg.room,
        roomKind: kind,
        ownerName: msg.playerName,
        cwd: msg.cwd,
        launch: msg.launch,
      });
      if (!existed) {
        send({
          type: "agent_status",
          agentId: msg.agentId,
          status: "idle",
          detail: `spawned in ${msg.room} (${kind})`,
        });
      }
      return;
    }
    case "despawn_agent": {
      manager.despawn(msg.agentId);
      return;
    }
    case "player_message": {
      const agent = manager.get(msg.agentId);
      if (!agent) {
        send({
          type: "agent_say",
          agentId: msg.agentId,
          text: "(no agent here — spawn me with /omo spawn)",
          playerName: msg.playerName,
        });
        return;
      }
      // Fire-and-forget; loop emits its own status/say events.
      void agent.handleMessage(msg.playerName, msg.text);
      return;
    }
    case "player_enter_room": {
      manager.notePlayerRoom(msg.room);
      send({
        type: "room_screen_update",
        room: msg.room,
        entries: [
          { kind: "system", text: `welcome, ${msg.playerName}` },
          ...summarize(msg.room),
        ],
      });
      return;
    }
    case "player_leave_room":
      return;
    case "tool_approval": {
      manager.approve(msg.callId, msg.approved);
      return;
    }
    case "hello":
      return;
  }
}

function summarize(room: string): ScreenEntry[] {
  const agents = manager.list().filter((a) => a.room === room);
  if (!agents.length) {
    return [{ kind: "system", text: "(no agents — /omo spawn <role>)" }];
  }
  return agents.map((a) => ({
    kind: "system" as const,
    text: `${a.id} [${a.status}] ${a.role}`,
  }));
}

process.on("SIGINT", () => {
  console.log("[bridge] shutting down");
  closeAgentLogs();
  wss.close(() => process.exit(0));
});
