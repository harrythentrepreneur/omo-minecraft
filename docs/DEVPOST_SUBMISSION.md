# Omo Mission Control

**Run an autonomous AI organisation you walk through — state a goal, and the org delegates, pulls real business data, and extends itself, all inside a real Minecraft world.**

*Google for Startups AI Agents Challenge — Track 1: Build (Net-New Agents). Built with Gemini + Google ADK + MCP.*

---

## Inspiration

Teams are deploying fleets of AI agents and flying blind. You can't *see* what an agent is doing, you can't *steer* it mid-task, and standing up a new capability means a developer pre-wiring another agent, another integration, another dashboard. Agents are black boxes and orgs are frozen org-charts.

We asked a different question: what if an organisation were a **place you walk through**, and what if it could **build itself**? If the physical world is a 1:1 render of the agent org, then "scaling the team" is something you watch happen — a new function rises, an agent walks in, a screen lights up with live data. Observability stops being a dashboard you bolt on and becomes the world you stand in. Extensibility stops being a developer task and becomes a sentence you speak.

So we built the agent org first (net-new, on Google ADK + Gemini) and rendered it into the most legible, watchable interface we could think of: a real Minecraft world.

## What it does

You drop a futuristic HQ inside a real Minecraft 1.21.4 game (`/omo hq`) and a **Chief of Staff** villager takes its desk. You walk up and give it a business goal in plain language. From there:

- The **Chief of Staff** (a Gemini-backed ADK coordinator) restates the goal, then **delegates** to specialist sub-agents — **Growth** (ads/marketing/ROI) and **Comms** (emails/announcements) — with the hand-offs visible in real time.
- The **Growth** agent pulls **real Meta Ads data live over MCP** — no fabricated numbers. On our connected account ("PhonicsMaker"), last-30-days insights are ~**$3,558.79 spend, 261,189 impressions, 2.74% CTR, $0.50 CPC, 69,521 reach**. The agent leads with those headline metrics.
- When the mission needs a capability the org doesn't have, the Chief of Staff **extends the org itself** through the **World API** (also MCP tools): it `world_describe`s what exists, `world_add_function`s the gap (e.g. Payments, Analytics), `world_build`s its room (**a new wing rises block-by-block near HQ**), `world_staff`s a Gemini specialist **that walks in**, and `world_assign`s the task. Declarative intent in, a new function out — no code shipped.
- Every step streams into the world as it happens: the agent's reasoning, each tool call with its arguments, each result, and each sub-agent hand-off render on a floating board, a lectern transcript, and a full in-game reasoning terminal.
- A polished **"alien HUD" web dashboard** (KPIs, charts, a live event feed) renders on an in-world cinema wall, designed to show the function's real data.
- Any outward/destructive action (send an email, change an ad budget) routes through a **tap-to-approve** gate before it executes.

Minecraft is the **novel interface** — how you watch and steer the agent — not the agent itself. The agent is a real, standalone ADK service.

## How we built it

Three processes, with the mandatory trifecta living in specific, verifiable places.

**1. The ADK + Gemini organisation — `omo-agent/omo/agent.py`**
A net-new Python `google-adk` multi-agent system, served by `adk api_server` (`/run`, `/run_sse`, FastAPI on :8000):
- `root_agent = LlmAgent(name="ChiefOfStaff", ...)` — the coordinator, with `sub_agents=[growth, comms]` so ADK drives `transfer_to_agent` hand-offs.
- `growth` and `comms` are `LlmAgent` specialists. A reusable `specialist/agent.py` app is the brain for any *hired* function — one generic Gemini specialist that adopts whatever role the runtime seeds it with on its first turn, so the org extends to **any** function with no new code.
- **Gemini** is every agent's model: `MODEL = os.environ.get("OMO_GEMINI_MODEL", "gemini-flash-latest")` (currently resolves to gemini-3.5-flash; pinned to a current alias, not a retired model).
- Tools come from **MCP**: `McpToolset(connection_params=StreamableHTTPConnectionParams(url=MCP_URL, ...), tool_filter=[...])` — each role gets a filtered view of the same MCP server. Growth gets `meta_ads_list_campaigns` + `meta_ads_insights`; the Chief of Staff gets the World API tools.

**2. The MCP server (omo-tools) — `runtime/src/mcpServer.ts`**
A stateless Streamable-HTTP MCP server built on the official `@modelcontextprotocol/sdk` (`McpServer` + `StreamableHTTPServerTransport`, `sessionIdGenerator: undefined`, on :8090/mcp). It exposes two tool families:
- **Real business tools:** `meta_ads_list_campaigns` and `meta_ads_insights`, which wrap the runtime's existing Meta Ads implementations in `runtime/src/tools/metaAds.ts` (live Graph API calls — the source of the PhonicsMaker numbers).
- **The World API** — the declarative self-extension surface: `world_describe`, `world_add_function`, `world_build`, `world_staff`, `world_assign`. This is the load-bearing novelty: *the org grows itself by calling MCP tools.* These tools mutate the in-memory org graph (`runtime/src/worldStore.ts`: `WorldStore`/`OmoFunction`), broadcast `world_build_request`/`world_staff_request` to the world via `AgentManager.broadcast` — which the Paper plugin turns into a **live block-by-block build** of the wing at a ring position around HQ (`BuildPlot`) plus a new Gemini specialist villager that **walks into it** (`IncomingHandler.handleWorldBuild` / `handleWorldStaff`) — and hand assigned tasks to live in-world agents via `manager.get(id).handleMessage(...)`.

Running the MCP server *inside the runtime process* is what lets a self-extension tool call drive the actual world and the actual villagers in one hop.

**3. The bridge + interface — `runtime/src/agents/AdkAgent.ts`**
`AdkAgent` is a new "brain" that gives the ADK org an in-world face. It implements the same minimal agent surface as the existing brains (`id/role/home/room/ownerName/status` + `handleMessage`), POSTs the player's message to the ADK service's `/run_sse`, and translates the SSE event stream — reasoning text, `functionCall`/`functionResponse` parts, `transfer_to_agent` hand-offs — into the in-world reasoning board, lectern transcript, terminal mirror, and chat bubbles, token-by-token as they arrive. It slots into brain selection in `runtime/src/agents/AgentManager.ts` (the `mission_control` branch), with the room kind mapped from the room name in `runtime/src/rooms/registry.ts` (`hq*`/`mission*`/`fn-*` → `mission_control`). The runtime wires it all up in `runtime/src/server.ts`, which starts the WS bridge, the MCP server (`startMcpServer`), and the dashboard server (`startDashboardServer`).

**In-game entry — `plugin/.../commands/HermesCommand.java`**
`/omo hq` (`handleHq`) defines the `hq` room at the player's location and seats the `cos` ("Chief of Staff") villager. Because the room is named `hq`, the runtime routes it to the ADK brain.

**Live dashboard — `runtime/src/dashboardServer.ts`**
A zero-dependency Node HTTP server (port 8088, `GET /dash/:id` + `/dash/:id/data`) that serves the "alien HUD" page (`dashboard.html.ts`) for headless-Chrome capture onto an in-world cinema wall. It ships a believable Growth/ad-performance demo board and accepts live data via `setDashboardData(id, …)`.

**The two-process model is reused, not rebuilt.** The Paper plugin (Java 21, Bukkit) is the only code that touches the world; the Node runtime is the single-process orchestrator they share over one WebSocket. Adding the ADK org meant adding *one new brain* + *one new MCP server* + *one new command* — no third service, no new wire protocol.

## The business case

**Category: the autonomous-workforce operating layer (agent-ops).** As teams stand up fleets of agents, the unmet need isn't another agent — it's a place to *run* them: see what they're doing, steer them mid-task, stand up new functions without a developer, and govern every outward action. Omo is that operating layer, made spatial.

**Real ROI, proven on real data.** The Growth agent reports on a live, connected Meta Ads account — not a mock. The PhonicsMaker figures above (≈$3.5k spend, 261k impressions, 2.74% CTR, $0.50 CPC, 69.5k reach over 30 days) come straight from the Graph API over MCP. The value is concrete: ask "is our ad spend still paying off," and a Gemini specialist pulls the real numbers and tells you — with budget changes gated behind a human tap. The same MCP pattern extends to Gmail/Drive, Stripe, and any other MCP-connected tool.

**Path to monetization.** Near-term: a hosted agent-ops layer where teams connect their tools over MCP and operate their agents from a watchable HQ, priced per seat / per connected tool. North-star: **worlds-as-businesses marketplace** — a built function can be promoted to a standalone, visitable, transactable Omo World where others spectate or pay (via Stripe MCP) to use it. Because every function is already `{role, purpose, tools, room, staffing agent, dashboard}`, "publish this function as a product" is a small step from "build this function."

## Challenges

- **Bridging two ecosystems cleanly.** Streaming ADK's Python `/run_sse` events into a TypeScript runtime — and making them land on in-world surfaces in real time — meant parsing partial vs. consolidated text, tolerating camel/snake-case event shapes, and recovering from a dropped ADK session by recreating it and retrying once (`AdkAgent.stream`).
- **Making self-extension a *tool*, not a script.** The hard design call was exposing org-growth as MCP tools (`world_*`) the agent itself calls, rather than hardcoding the org. Running the MCP server inside the runtime process is what makes a tool call able to mutate the world graph and drive live villagers in one hop.
- **Keeping the demo honest.** Every number is pulled live; nothing is invented (the Growth agent's instruction forbids guessing). The dashboard is a constrained design system the agent populates, so it always looks right on camera.
- **Model currency.** Pinned to `gemini-flash-latest` to avoid the deprecated `gemini-2.0`/`2.5` models.

## Accomplishments we're proud of

- A **genuinely end-to-end trifecta**: Gemini (every agent), ADK (coordinator + delegating sub-agents over `adk api_server`), and MCP (every tool *and* the self-extension API) — each used idiomatically, each pinpointable to a file.
- **Self-extension as an MCP tool surface** — the org grows itself by calling tools, the rarest and most novel move in the build.
- **Real data, live** — a Gemini specialist reporting on a real Meta Ads account over MCP, with mutations human-gated.
- **A net-new agent that drops into a real game** with no changes to the wire protocol or the plugin — the new brain implements the same agent surface as the existing ones.

## What we learned

- **ADK's `sub_agents` + `transfer_to_agent` express a real org chart with no glue code** — modelling a coordinator + specialists and letting Gemini route between them beat a hand-rolled router.
- **MCP is a better extensibility primitive than a plugin system** — once "add a capability" is just "call a tool," the line between *using* a tool and *acquiring* one disappears, which is what made agent-driven self-extension possible.
- **Streaming is the product** — watching reasoning tokens, hand-offs, blocks rising, and live data all move at once is what makes an autonomous org feel governable rather than spooky.

## What's next

- **`world_connect_tool`:** let the org attach brand-new MCP servers to a function live, so it can acquire capabilities we never anticipated (Stripe, Drive — anything MCP-speaking).
- **Live dashboards per function**, each bound to that function's real MCP data, refreshing on the cinema wall.
- **`/revise <prompt>`** to iterate a function's dashboard scope/structure live, in-world.
- **Worlds-as-businesses marketplace** — promote a built function to a visitable, transactable Omo World.

## How we map to the judging criteria

| Criterion (weight) | Concrete evidence |
|---|---|
| **Technical (30%)** | Gemini + ADK multi-agent + MCP, each idiomatic and locatable: `omo-agent/omo/agent.py` (LlmAgent coordinator + `sub_agents` + `McpToolset`), `runtime/src/mcpServer.ts` (official MCP SDK, real tools + World API), `runtime/src/agents/AdkAgent.ts` (SSE bridge into a real game), `runtime/src/agents/AgentManager.ts` (brain selection). Self-extension exposed *as* MCP tools is a rare, hard move. |
| **Business (30%)** | A named category (agent-ops / autonomous-workforce operating layer), real ROI proven on a live Meta Ads account (PhonicsMaker: ≈$3.5k/30d spend, 2.74% CTR), human-gated mutations, and a credible path to a worlds-as-businesses marketplace. |
| **Innovation (20%)** | An autonomous agent org rendered as a self-building world with live reasoning, hand-offs, and dashboards — unforgettable on sight, and net-new for this challenge. |
| **Demo (20%)** | The product *is* the demo: `/omo hq` → ask a goal → watch the Chief of Staff delegate and pull real numbers → extend the org via the World API — one continuous, watchable shot. |

## Built with

`gemini` · `google-adk` · `model-context-protocol` · `typescript` · `python` · `minecraft` · `paper` · `node` · `express`
