// omo-tools — the MCP server the net-new ADK + Gemini crew connects to.
//
// This is where the mandatory Model Context Protocol lives: a stateless
// Streamable-HTTP MCP server (official @modelcontextprotocol/sdk) that exposes
// (a) REAL business tools backed by the runtime's existing implementations
// (Meta Ads), and (b) the WORLD API — the declarative self-extension surface the
// Chief of Staff uses to grow the organisation (describe / add_function / build
// / staff / assign). The same server runs inside the runtime process, so the
// World API can drive the live world directly via AgentManager.broadcast and the
// player's villagers via AgentManager.get(...).handleMessage(...).
//
// Transport: stateless (sessionIdGenerator: undefined) — a fresh server +
// transport per POST. Simplest correct mode and fully compatible with ADK's
// McpToolset(StreamableHTTPConnectionParams).

import express, { type Request, type Response } from "express";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { z } from "zod";
import type { AgentManager } from "./agents/AgentManager.js";
import type { WorldStore } from "./worldStore.js";
import type { ToolContext } from "./tools/registry.js";
import { listCampaignsTool, insightsTool } from "./tools/metaAds.js";
import { designStructure } from "./worldArchitect.js";
import { setDashboardData, getDashboardData } from "./dashboardServer.js";

const MCP_PORT = Number(process.env.OMO_MCP_PORT ?? 8090);

type TextResult = { content: { type: "text"; text: string }[] };
function ok(obj: unknown): TextResult {
  return { content: [{ type: "text", text: typeof obj === "string" ? obj : JSON.stringify(obj) }] };
}

function buildMcpServer(manager: AgentManager, world: WorldStore): McpServer {
  const server = new McpServer({ name: "omo-tools", version: "1.0.0" });

  // Read-only context for wrapping the runtime's existing tool impls.
  const ctx: ToolContext = {
    agentId: "omo-mcp",
    ownerName: world.owner,
    room: world.hqRoom,
    requestApproval: async () => true,
    log: () => {},
  };

  // ── REAL business data (Meta Ads) ─────────────────────────────────────────
  server.registerTool(
    "meta_ads_list_campaigns",
    {
      title: "List Meta ad campaigns",
      description:
        "List the REAL Meta ad campaigns on the connected ad account (name, status, daily budget, objective). Live data — never fabricate.",
      inputSchema: { limit: z.number().optional().describe("max campaigns, default 25") },
    },
    async ({ limit }) => ok(await listCampaignsTool.run({ limit: limit ?? 25 }, ctx)),
  );

  server.registerTool(
    "meta_ads_insights",
    {
      title: "Meta ad performance insights",
      description:
        "Get REAL performance insights (spend, impressions, clicks, ctr, cpc, cpm, reach) for a campaign, or the whole account if campaign_id is omitted, over a date window.",
      inputSchema: {
        campaign_id: z.string().optional().describe("omit for account-level"),
        date_preset: z.string().optional().describe("yesterday | last_7d | last_30d | this_month"),
      },
    },
    async ({ campaign_id, date_preset }) =>
      ok(await insightsTool.run({ campaign_id, date_preset: date_preset ?? "last_7d" }, ctx)),
  );

  // ── WORLD API — declarative org self-extension ────────────────────────────
  server.registerTool(
    "world_describe",
    {
      title: "Describe the world / org",
      description:
        "Return the current Omo organisation: the HQ and every function (id, role, purpose, tools, room, staffed?). ALWAYS call this first to ground yourself before extending the world.",
      inputSchema: {},
    },
    async () =>
      ok({
        hq: world.hqRoom,
        functions: world.list().map((f) => ({
          id: f.id,
          role: f.role,
          purpose: f.purpose,
          tools: f.tools,
          room: f.room,
          staffed: f.staffed,
        })),
        note: world.list().length
          ? undefined
          : "No functions yet — the org is a blank canvas. Add what the mission needs.",
      }),
  );

  server.registerTool(
    "world_add_function",
    {
      title: "Add an org function",
      description:
        "Declare a NEW function the organisation needs but does not have yet (e.g. Payments, Customer Support, Analytics). Registers it and reserves its room. Then call world_build, then world_staff, then world_assign.",
      inputSchema: {
        role: z.string().describe("the function's role, e.g. 'Payments'"),
        purpose: z.string().describe("one sentence on what it does"),
        tools: z.array(z.string()).optional().describe("tool names it will use"),
      },
    },
    async ({ role, purpose, tools }) => {
      const fn = world.addFunction({ role, purpose, tools });
      return ok({
        ok: true,
        function: { id: fn.id, role: fn.role, room: fn.room, index: fn.index },
        next: "call world_build(function_id) to raise its room, then world_staff",
      });
    },
  );

  server.registerTool(
    "world_build",
    {
      title: "Build a function's room (live)",
      description:
        "Physically construct the room/wing for a function inside the world — blocks rise live near HQ. Call after world_add_function.",
      inputSchema: { function_id: z.string() },
    },
    async ({ function_id }) => {
      const fn = world.get(function_id);
      if (!fn) return ok({ ok: false, error: `no function '${function_id}' — call world_add_function first` });
      // 1) Allocate the plot near HQ (the plugin registers + clears it).
      manager.broadcast({
        type: "world_build_request",
        room: fn.room,
        anchorRoom: world.hqRoom,
        index: fn.index,
        role: fn.role,
      });
      // 2) Gemini designs a UNIQUE building themed to THIS function (a Payments
      //    vault looks nothing like an Analytics observatory), then we stream it
      //    onto that plot so it rises live, custom to exactly what was asked.
      void designStructure(fn.role, fn.purpose)
        .then(({ ops }) => {
          if (ops.length) manager.broadcast({ type: "build_ops", agentId: fn.room, clearFirst: true, ops });
        })
        .catch(() => {});
      return ok({ ok: true, built: fn.room, note: `designing a custom ${fn.role} building and raising it live near HQ` });
    },
  );

  server.registerTool(
    "world_staff",
    {
      title: "Staff a function (a specialist walks in)",
      description:
        "Hire and seat a specialist agent in the function's room — a new villager appears, wired to the function's tools. Call after world_build.",
      inputSchema: { function_id: z.string() },
    },
    async ({ function_id }) => {
      const fn = world.get(function_id);
      if (!fn) return ok({ ok: false, error: `no function '${function_id}'` });
      world.markStaffed(fn.id, fn.id);
      // Seed this function's OWN live board from the real ad board so its room
      // screen shows real data immediately (and stays revisable, unlike "growth").
      const seed = getDashboardData("growth");
      if (seed) setDashboardData(fn.room, { ...seed, title: `${fn.role} · Live`, subtitle: `${fn.role} — ${fn.purpose}` });
      manager.broadcast({
        type: "world_staff_request",
        agentId: fn.id,
        role: fn.role,
        room: fn.room,
        anchorRoom: world.hqRoom,
        index: fn.index,
      });
      return ok({ ok: true, staffed: fn.role, agentId: fn.id, note: `${fn.role} is taking their desk in ${fn.room}` });
    },
  );

  server.registerTool(
    "world_assign",
    {
      title: "Assign a task to a function",
      description:
        "Hand a task to a staffed function's agent. They work on it in their own room (their reasoning streams there). Returns once the task is handed off.",
      inputSchema: { function_id: z.string(), task: z.string() },
    },
    async ({ function_id, task }) => {
      const fn = world.get(function_id);
      if (!fn || !fn.agentId) return ok({ ok: false, error: `function '${function_id}' is not staffed yet` });
      const agent = manager.get(fn.agentId);
      if (agent) void agent.handleMessage(world.owner, task);
      return ok({ ok: true, assigned_to: fn.role, task, note: "the specialist is on it in their room" });
    },
  );

  server.registerTool(
    "dashboard_update",
    {
      title: "Show / revise the live dashboard in your room",
      description:
        "Set or REVISE the live data board on the screen in your function's room. Call this to show the owner the data they asked for, and again whenever they ask you to revise it (change the window, add a metric, re-rank). Provide your function_id and the board contents.",
      inputSchema: {
        function_id: z.string().describe("your function id, e.g. 'billing'"),
        title: z.string().describe("board title, e.g. 'Billing · Live'"),
        subtitle: z.string().optional(),
        kpis: z
          .array(
            z.object({
              label: z.string(),
              value: z.union([z.string(), z.number()]),
              unit: z.string().optional(),
              delta: z.string().optional(),
              trend: z.enum(["up", "down", "flat"]).optional(),
            }),
          )
          .describe("4-6 big-number tiles"),
        feed: z
          .array(
            z.object({
              text: z.string(),
              ts: z.string().optional(),
              tone: z.enum(["info", "good", "warn", "bad"]).optional(),
            }),
          )
          .optional()
          .describe("recent activity lines"),
      },
    },
    async ({ function_id, title, subtitle, kpis, feed }) => {
      const fn = world.get(function_id);
      const id = fn ? fn.room : function_id;
      setDashboardData(id, { title, subtitle, status: "LIVE", kpis, feed });
      return ok({ ok: true, updated: id, note: "the room screen refreshes within ~2s" });
    },
  );

  // ── world_consult — the SOUL of the society: agents ask each other ─────────
  server.registerTool(
    "world_consult",
    {
      title: "Consult another function's specialist",
      description:
        "Ask ANOTHER staffed function's specialist a question and get their answer back inline. Use this when you need a number, fact, or judgement that another function owns rather than guessing — e.g. Growth asking Finance for the CAC ceiling. Returns the peer's answer so you can use it in your own reply.",
      inputSchema: {
        from_function: z.string().describe("your function id (the asker), e.g. 'growth'"),
        to_function: z.string().describe("the function id you want to consult, e.g. 'finance'"),
        question: z.string().describe("the question to ask that function's specialist"),
      },
    },
    async ({ from_function, to_function, question }) => {
      const to = world.get(to_function);
      if (!to) return ok({ ok: false, error: `no function '${to_function}' — call world_describe to see who exists` });
      if (!to.staffed || !to.agentId)
        return ok({ ok: false, error: `function '${to_function}' is not staffed yet — staff it before consulting` });
      const from = world.get(from_function);

      const c = world.recordConsult({
        from: from_function,
        to: to_function,
        question,
        fromRole: from?.role,
        toRole: to.role,
      });
      manager.broadcast({
        type: "world_consult_request",
        from: from_function,
        to: to_function,
        fromRoom: from?.room ?? from_function,
        toRoom: to.room,
      });

      const agent = manager.get(to.agentId);
      const askable = agent && "ask" in agent ? agent : null;
      let answer: string | null = null;
      if (askable) {
        const asker = from?.role ?? "A colleague";
        answer = await Promise.race<string | null>([
          askable.ask(world.owner, `${asker} asks: ${question}`),
          new Promise<string | null>((resolve) => setTimeout(() => resolve(null), 30_000)),
        ]);
      }

      world.resolveConsult(c.id, answer);
      manager.broadcast({ type: "world_consult_done", from: from_function, to: to_function });

      if (answer == null) {
        const why = askable ? `${to.role} was busy or slow to answer` : `${to.role} has no live brain`;
        return ok({ ok: false, from: from_function, to: to_function, question, answer: `(no answer — ${why})` });
      }
      return ok({ ok: true, from: from_function, to: to_function, question, answer });
    },
  );

  return server;
}

export function startMcpServer(manager: AgentManager, world: WorldStore, port = MCP_PORT): void {
  const app = express();
  app.use(express.json());

  app.post("/mcp", async (req: Request, res: Response) => {
    const server = buildMcpServer(manager, world);
    const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined });
    res.on("close", () => {
      void transport.close();
      void server.close();
    });
    try {
      await server.connect(transport);
      await transport.handleRequest(req, res, req.body);
    } catch {
      if (!res.headersSent) {
        res.status(500).json({ jsonrpc: "2.0", error: { code: -32603, message: "internal error" }, id: null });
      }
    }
  });

  const notAllowed = (_req: Request, res: Response) =>
    res.status(405).json({ jsonrpc: "2.0", error: { code: -32000, message: "method not allowed" }, id: null });
  app.get("/mcp", notAllowed);
  app.delete("/mcp", notAllowed);

  app.listen(port, "127.0.0.1", () =>
    console.log(`[mcp] omo-tools MCP (stateless) on http://127.0.0.1:${port}/mcp`),
  );
}
