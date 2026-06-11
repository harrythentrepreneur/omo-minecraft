# Omo Mission Control — PRD & Vision

**A world that builds itself for autonomous AI teams.**

| | |
|---|---|
| **Status** | Draft v1 · 2026-06-12 |
| **For** | Google for Startups AI Agents Challenge — **Track 1: Build (Net-New Agents)** |
| **Author** | Harry |
| **Mandatory stack** | **Gemini** (every agent's brain) · **Agent Development Kit / ADK** (the multi-agent org) · **Model Context Protocol / MCP** (every tool + the world-extension API itself) |
| **Judging weights** | Technical 30% · Business 30% · Innovation 20% · Demo 20% |

---

## 0. TL;DR

Most agent products are a chat box; the agents are invisible and the org is statically pre-wired. **Omo turns an autonomous AI organisation into a place you walk through.** You drop a small futuristic HQ anywhere on a vanilla Minecraft map. From inside it you give a goal in plain language. The org **designs itself to fit the mission**: it builds a purpose-built world for the task — live, block by block — staffs it with a Gemini agent, connects the tools that agent needs over MCP, and stands up a **live, beautifully-designed web dashboard of the real data inside the room**. You walk in, watch the data update in real time, talk to the agent, and steer it with `/revise <prompt>`. Walk back to HQ, ask for another capability, and a new world rises. **The world is as big as the mission, and it grows itself.**

The deliverable is a **net-new ADK + Gemini multi-agent system** (Track 1); Minecraft is the **novel spatial interface** we render it into — how you watch the agent think, act, and grow. Every external action a human can veto with one tap.

---

## 0.5 Track 1 alignment — a net-new ADK agent on a blank canvas

Track 1 asks for: *a blank canvas + a complex business problem → a **net-new autonomous agent** built on ADK (or LangChain/CrewAI) → from static code to **declarative intent** → **MCP** to securely connect to external tools, gather context, and execute autonomously.* Omo maps to every clause:

| Track 1 requirement | How Omo satisfies it |
|---|---|
| **Blank canvas** | Literal *and* figurative. We write a brand-new ADK agent system from an empty Python project — **no prior agent is reused** (Omo's older villagers ran on Hermes/Claude; none of that is ADK). And in the demo the agent starts on a **bare, empty patch of vanilla Minecraft** and builds the whole organisation from nothing. |
| **Complex business problem** | Standing up and running an autonomous business org — the agent-ops problem: spin up the right functions, connect the right tools, execute, stay observable and governable. |
| **Net-new autonomous agent (on ADK)** | The deliverable is a new **ADK multi-agent system**: a Gemini "Chief of Staff" coordinator that delegates to specialist sub-agents and can extend its own org. ADK chosen over LangChain/CrewAI deliberately — Google-native, multi-agent first-class, MCP built in. |
| **Static code → declarative intent** | You never pre-wire a function. You state intent ("check X"); the agent *declares* the function it needs and the world materialises it. **The World API is the declarative-intent surface.** |
| **MCP to securely connect to external tools** | Every tool — Gmail/Drive (Google Workspace MCP), Meta Ads, Stripe — reaches the agent over MCP; *and the agent extends itself by calling MCP tools* (the World API). "Securely" = every outward action is human-approval-gated. |
| **Gather context + execute autonomously** | The agent calls `world.describe` to ground itself, pulls live data over MCP, then decides, builds, staffs, and acts end-to-end — with the human able to step in at any point. |

**What's net-new vs. what's the canvas.** The *agent* is 100% net-new for this challenge: the ADK/Gemini multi-agent system, the World-API MCP server, the agent→world bridge, and the live-dashboard generator are all written from scratch. Minecraft is **not** the agent — it is the novel interface we render the agent into (our innovation on *how you observe and steer* an autonomous org). This is exactly analogous to building a net-new agent and giving it a frontend; ours just happens to be a world you can walk through.

---

## 1. Vision

### The problem
Teams are deploying fleets of AI agents and flying blind. You can't *see* what an agent is doing, you can't *steer* it mid-task, and standing up a new capability means a developer pre-wiring another agent, another integration, another dashboard. Agents are black boxes and orgs are frozen org-charts. The promised "autonomous workforce" feels neither autonomous nor like a workforce.

### The insight
An organisation is a *living thing*. So make it a **place** — and let it **build itself**. If the physical world is a 1:1 render of the agent org, then "scaling the team" is something you can watch happen: a new room rises, an agent walks in, a screen lights up with live data. Observability stops being a dashboard you bolt on and becomes the world you stand in. Extensibility stops being a developer task and becomes a sentence you speak.

### The experience (the dream, end to end)
> I drop my HQ on a hillside in my survival world — a small, glowing, circular alien office. Three teammates are already at their desks, thinking out loud on the screens behind them. I walk up and say *"check whether our ad spend is still paying off and keep an eye on it."* The Chief of Staff thinks for a beat — I watch the words stream — then says it needs an Analytics function we don't have. **In front of me, a new wing rises out of the ground, block by block.** A new agent walks through the door. I follow it in. The far wall is a live, gorgeous dashboard — ROAS by campaign, spend pacing, a live event feed — updating as the agent pulls fresh numbers over MCP. I say "actually show me last 7 days, and flag anything below 2x." I type `/revise show 7-day window, flag ROAS < 2.0`. The board redraws. The agent talks me through what it found. I walk back to HQ and say *"now build me something that drafts replies to support emails."* Another world rises. The place is exactly as big as what I've asked it to do.

### Why now
- **ADK** makes idiomatic multi-agent orgs (coordinator + delegating sub-agents) a first-class primitive.
- **Gemini** is fast and strong enough to reason, delegate, and author a live web view in the loop.
- **MCP** turns "connect a tool" into a declarative act — so the org can extend *its own* capabilities by calling an MCP tool, not by shipping code.
- The **agent-ops pain** (no visibility, no steering, no governance) is acute in 2026 and unmet.

### Why this wins this challenge
| Criterion | Why Omo scores |
|---|---|
| **Technical (30%)** | Gemini + ADK multi-agent + MCP used *idiomatically and visibly* — including the rare move of exposing **world self-extension as an MCP tool surface**, plus Google Workspace over MCP. |
| **Business (30%)** | A named category (agent-ops / the autonomous-workforce operating layer) with overnight ROI and a credible path to a worlds-as-businesses marketplace. |
| **Innovation (20%)** | No one else turns an agent org into a self-building world with live, agent-authored dashboards. It is unforgettable on sight. |
| **Demo (20%)** | The product *is* a demo. One continuous shot — ask → world rises → walk in → live data → revise → repeat — carries the whole submission. |

---

## 2. The Experience (primary user journey = the demo script)

1. **Place HQ.** Player runs the build at their location on a vanilla map. A small circular futuristic alien office materialises with the founding crew (Chief of Staff + 2 specialists) at desks. Their live reasoning streams on the screens behind them.
2. **Ask.** Player speaks a goal to the Chief of Staff: *"Check X and keep watch."*
3. **The org decides.** Gemini Chief of Staff (ADK coordinator) reasons live, delegates to specialists (hand-offs visible in real time), and detects a capability gap.
4. **The world builds itself.** It calls the World API; a new purpose-built world/wing **rises block-by-block, live**, themed to the task.
5. **It staffs the world.** A new Gemini agent spawns and **walks into the new room**, wired to the MCP tools that function needs.
6. **Walk in → live data.** Inside, the far wall is a **live, designed web dashboard** of the real data, updating in real time as the agent works. The villager works visibly in its own world.
7. **Talk & revise.** Player talks to the agent directly, and iterates the world/dashboard/scope with `/revise <prompt>`. Changes apply live.
8. **Extend again.** Player returns to HQ and asks for another capability → another world rises. Routine, on demand, no configuration.
9. **Human-in-the-loop.** Any outward action (send an email, change a budget, create a payment link) surfaces as a **tap-to-approve** gate before it happens.

---

## 3. Product principles

1. **The world is the org graph, rendered.** Physical structure ≡ org structure. Building a room = adding a function.
2. **Everything is real-time.** Three streams at once: *cognitive* (reasoning/tool-calls/hand-offs), *physical* (villagers moving, blocks rising), *data* (the live dashboard).
3. **Declarative, not configured.** You state intent; the org builds the team, rooms, and tool connections. ("From static code to declarative intent.")
4. **Human-in-the-loop by construction.** You can always walk in, talk, revise, and veto. Oversight is spatial, not a settings page.
5. **Extendable to anything.** A small, composable World API is the only extension primitive — and both the human and the agents call it.

---

## 4. Glossary

- **HQ** — the always-on home base; a small circular futuristic alien office holding the founding crew. Droppable anywhere on a vanilla map.
- **Crew / Chief of Staff** — the founding multi-agent team; the Chief of Staff is the ADK coordinator that delegates and decides when to extend the world.
- **Function / World** — a purpose-built unit = `{ role, purpose, toolset, room, staffing agent, live dashboard }`. Each tool you ask for becomes a Function with its own world.
- **The World API** — the MCP tool surface the org (and the human) uses to extend the world. See §5 / §6.
- **Live Dashboard** — an agent-authored, design-system web view of the Function's real data, served by the runtime and rendered on an in-world screen, updating live.
- **`/revise <prompt>`** — in-game command to iterate the world you're in: its dashboard, scope, or structure.

---

## 5. Functional requirements

> Tags: **[MVP]** = in the hackathon demo build · **[v2]** = fast-follow · **[NS]** = north-star roadmap.

**FR-1 — HQ & founding crew [MVP].** A command builds the circular alien HQ at the player's location with a Chief of Staff + 2 specialists (Comms, Growth), each a Gemini/ADK agent. *Accept:* villagers present, idle reasoning visible on screens, player can talk to any of them.

**FR-2 — Declarative world-building [MVP].** A natural-language goal at HQ causes the org to (a) decide what Function is needed and (b) construct its world live. *Accept:* one spoken goal results in a new room built block-by-block within ~5s and a new agent staffing it.

**FR-3 — The World API over MCP [MVP].** The org extends itself only through these MCP tools: `world.describe`, `world.add_function`, `world.build`, `world.staff`, `world.assign`; `world.connect_tool` **[v2]**. *Accept:* the Gemini coordinator completes a full `describe → add_function → build → staff → assign` chain end-to-end via MCP calls.

**FR-4 — Real-time cognition [MVP].** Reasoning tokens, tool calls, and sub-agent hand-offs stream to in-world screens as they happen (ADK `/run_sse`). *Accept:* on-screen text advances token-by-token; `[transfer]` and `[tool]` lines appear at the instant they fire.

**FR-5 — Real-time physical world [MVP].** Worlds build live (animated block placement); the staffing agent walks into its room; crew at HQ visibly react/coordinate. *Accept:* no instant "pop-in" of finished structures — the rise is visible.

**FR-6 — Live Dashboard [MVP].** Each Function gets an agent-authored web dashboard, served by the runtime, bound to real data from that Function's MCP tools, rendered on an in-world screen, refreshing live. Built on a polished "alien HUD" design system for guaranteed visual quality. *Accept:* the dashboard shows at least one real data source (e.g. Meta Ads ROAS / Gmail volume / Stripe revenue) and updates without a manual reload.

**FR-7 — `/revise <prompt>` [MVP for dashboard scope; v2 for structure].** Standing in a world, the player issues `/revise <prompt>`; the owning agent applies it to the dashboard (metrics/scope/layout) and re-renders live. Revising room structure / tools is **[v2]**. *Accept:* `/revise show 7-day window` visibly changes the live board.

**FR-8 — Conversational control [MVP].** The player can talk to any agent in any room and get a streamed reply grounded in that Function's data/tools.

**FR-9 — Human-in-the-loop approvals [MVP].** Outward/destructive actions (`gmail_send`, `meta_ads_*`, Stripe writes, etc.) route through the existing tap-to-approve gate before execution. *Accept:* at least one real external action is gated and then executed live on approval.

**FR-10 — Anywhere placement [MVP].** HQ and built worlds drop at the player's chosen location on an ordinary survival/creative map — no bespoke pre-built map required.

**FR-11 — Worlds as businesses / marketplace [NS].** A built world can be promoted to a standalone, visitable, transactable Omo World (humans visit, spectate, pay via Stripe MCP). The closing-roadmap vision.

---

## 6. Architecture

```
        ┌──────────────────────────────────────────────────────────────┐
 You ──►│  Minecraft (vanilla world + Omo client-mod for fullscreen      │
 (talk, │  screens). HQ + built worlds placed anywhere.                  │
 /revise,└───────────────┬───────────────────────────────┬──────────────┘
 approve)                │ WS                             │ HTTP (screen = live web)
                 ┌───────▼───────────────────────────────▼──────────────┐
                 │  Node runtime (orchestrator)                          │
                 │   • AgentManager: brain select (Hermes | Claude | ADK)│
                 │   • AdkAgent  ──HTTP/SSE──►  ADK api_server            │
                 │   • omo-tools MCP server  (existing tools + WORLD API) │
                 │   • Dashboard server: serves + live-pushes web views   │
                 │   • Build orchestrator → WS → plugin (live builds)     │
                 └───────┬──────────────────────────┬────────────────────┘
                         │ Streamable HTTP MCP       │ WS
        ┌────────────────▼──────────────┐   ┌────────▼───────────────────┐
        │  ADK service (Python)         │   │  Paper plugin (Java 21)     │
        │   Chief of Staff (Gemini)     │   │   • parametric live builds  │
        │     ├─ Comms  (Gemini)        │   │   • spawn/walk villagers    │
        │     ├─ Growth (Gemini)        │   │   • cinema / CinemaScreen   │
        │     └─ <hired> (Gemini)       │   │   • tap-to-approve UI       │
        │   tools = McpToolset(──────────┐  └─────────────────────────────┘
        └────────────────────────────────┼──── omo-tools World API (self-extension!)
                                          ├──── Google Workspace MCP (Gmail/Drive)
                                          └──── Stripe MCP, Meta Ads
```

**Components**
- **ADK service (new, Python).** Coordinator + sub-agents on Gemini. Delegation via `sub_agents` (`transfer_to_agent`) and `AgentTool`. Exposed over `adk api_server` (`/run`, `/run_sse`). Tools come from `McpToolset`.
- **omo-tools MCP server (new, in runtime).** Exposes the existing tool registry (say/notes/gmail/meta-ads/stripe) **plus the World API**. This is the load-bearing novelty: *the org extends itself by calling MCP tools.* Transport: Streamable HTTP (same-host).
- **AdkAgent brain (new, in runtime).** Slots into the existing `AgentManager` brain-selection branch. Bridges ADK SSE events ↔ in-world `say/status/screen/transcript/approval` messages — so all existing visuals work unchanged.
- **Dashboard server + generator (new, in runtime/face).** Serves a per-Function web view from a polished design system; the agent supplies data bindings + widget choices; live data pushed via SSE/WS to the page; the page is shown on the in-world cinema wall (existing CDP-screencast + fullscreen CinemaScreen).
- **Paper plugin (existing, reused).** Parametric live builds, villager spawn/pathing, cinema screens, approval UI. Minimal additions: HQ blueprint + Function-room blueprints.

**Key data flows**
1. **Ask → build:** Gemini `world.add_function`+`world.build` → omo-tools → build orchestrator → WS → plugin builds live → `world.staff` → villager walks in.
2. **Cognitive stream:** ADK `/run_sse` → AdkAgent → screens, token-by-token.
3. **Dashboard:** agent emits view spec + data → dashboard server → cinema renders → live refresh loop.
4. **/revise:** in-game command → owning agent → regenerate dashboard/scope → live re-render.
5. **Approval:** sensitive MCP tool call → tap-to-approve → execute on approve (120s auto-deny).

---

## 7. The mandatory-tech story (say this to judges, plainly)

- **Gemini is the brain of every agent** — coordinator, specialists, and the agent that authors each live dashboard. Pinned to a current GA model (`gemini-flash-latest` / `gemini-3.5-flash`; not the retired `gemini-2.0-flash`).
- **ADK is the organisation** — a real multi-agent system: a coordinator that *delegates* (`transfer_to_agent`) and calls sub-agents *as tools* (`AgentTool`), with sessions and streamed events.
- **MCP is every connection — and the extension mechanism itself.** Tools reach the world over MCP, *and the org grows itself by calling MCP tools* (the World API). We also connect to **Google's own Workspace MCP** (Gmail/Drive) — Gemini reasoning + ADK orchestration + Google ecosystem over MCP, the full trifecta plus a home-field bonus.

---

## 8. Scope & milestones — the honest line

**MVP (the demo build, ~6h).** Prove the *whole loop* end-to-end with reliability hedges:
- HQ (circular alien office) + crew of 3 Gemini/ADK agents, real-time cognitive streaming. (FR-1, FR-4, FR-8)
- One spoken goal → org builds **one** world via the World API, live (parametric blueprint), staffs a Gemini agent. (FR-2, FR-3, FR-5)
- That world has **one live dashboard** bound to **one real MCP data source** (recommend Meta Ads ROAS or Gmail — both already authenticated), on the cinema wall. (FR-6)
- `/revise` changes the dashboard scope, live. (FR-7)
- A **second** spoken goal builds a **second** world (proves "build another tool"). (FR-2 repeat)
- **One** real external action, approval-gated and then executed. (FR-9)

**Reliability hedges (non-negotiable for a demo that lands):**
- `world.build` uses **parametric blueprints** from a small library (fast, deterministic, gorgeous) — not free-form creative building.
- Dashboards are an **agent-populated polished design system**, not free-generated HTML, so they always look great on camera.
- Free-form building and free-generated dashboards are demoed as B-roll / described as roadmap, never as a live load-bearing beat.

**v2 (fast-follow):** `world.connect_tool` (attach new MCP servers live), `/revise` of room structure, free-generated dashboards, multi-agent-per-Function, persistence of built worlds across restart.

**North star:** worlds-as-businesses marketplace (FR-11) — visitable, transactable autonomous businesses; the closing slide, not the live demo.

---

## 9. Demo & submission plan

**The continuous money-shot** (one take if possible): place HQ → ask → watch reasoning stream → world rises → walk in → live dashboard updating → `/revise` → back to HQ → second world rises → approve one real action → closing line: *"I configured none of this. The org saw what it needed, built the room, hired the agent, connected the tools, and shipped — live. The world is as big as the mission."*

**Required artifacts:** (1) Code — repo + the new ADK service / MCP server / bridge. (2) Video — the continuous shot + short b-roll of reasoning terminals. (3) Architecture diagram — §6, rendered. (4) Testing access — the recorded demo as primary; **plus** the deploy-for-friends host knob so a judge could optionally join the live world (a flex few competitors can offer).

**Judging-criteria mapping:** see §1 table; restate in the writeup with concrete pointers (which file = which mandatory tech).

---

## 10. Risks & mitigations

| Risk | Mitigation |
|---|---|
| Live free-form building stalls on camera | Parametric blueprints only for the live beat; free-form is roadmap. |
| ADK↔Node bridge friction under time pressure | De-risk in the first 30 min (hello-world Gemini over `adk api_server`); fall back to direct Gemini OpenAI-compat via existing `callHermes` if `api_server` misbehaves. |
| Gemini model deprecation (2.0 dead; 2.5 EOL Jun 17) | Pin `gemini-flash-latest` / `gemini-3.5-flash`. |
| Dashboard generation looks rough | Constrain to a design-system shell the agent fills; never free-render layout live. |
| Real external actions (Gmail/Stripe) misfire | Approval gate + use already-authenticated sandboxes; rehearse the one gated action. |
| Concurrent Claude sessions editing runtime | Check git status/mtimes before builds; don't clobber WIP. |

---

## 11. Success metrics

**Hackathon:** the continuous demo runs clean; all three mandatory techs are demonstrably and idiomatically used; the writeup maps each to code; the loop (ask → build → live data → revise → repeat) is shown twice.

**Product:** time-from-intent-to-staffed-world (target < 15s); number of Functions a user can stand up in a session without touching config (target: unbounded); % outward actions correctly gated (target 100%).

---

## 12. Appendix — verified ADK / Gemini / MCP build notes

- **Install/auth:** `pip install google-adk` (Py 3.10+). AI Studio key path: `GOOGLE_API_KEY=...` + `GOOGLE_GENAI_USE_VERTEXAI=FALSE` in `.env`.
- **Models (current):** use `gemini-flash-latest` (alias) or `gemini-3.5-flash` (GA flagship). **Avoid** `gemini-2.0-flash` (shut down); `gemini-2.5-flash/pro` deprecate 2026-06-17.
- **Agents:** `LlmAgent`/`Agent`; plain Python fns auto-wrap as `FunctionTool`. Coordinator via `sub_agents=[...]` (LLM-driven `transfer_to_agent`) and/or `agent_tool.AgentTool(agent=...)`.
- **MCP:** `from google.adk.tools.mcp_tool import McpToolset` with `StdioConnectionParams(StdioServerParameters(...))` (local) or `StreamableHTTPConnectionParams(url=..., headers=...)` (remote). `await toolset.close()` on shutdown; bump `timeout=` for slow servers.
- **Serving:** `adk api_server` (FastAPI, :8000). `POST /apps/{app}/users/{user}/sessions/{session}` to create a session; `POST /run` (full event list) or `POST /run_sse` with `"streaming": true` (token-level). Run from the *parent* dir of the agent folder; folder name = `appName`.
- **Events:** iterate `runner.run_async`; `event.author`, `event.content.parts[].text`, `event.get_function_calls()`, `event.partial`, `event.actions.transfer_to_agent`, `event.is_final_response()`.
- **Integration seams (omo-mc):** brain select in `AgentManager.spawn`; `Agent` union needs `{id,role,home,room,ownerName,status}` + `handleMessage()`; tool registry `ToolImpl = {def, needsApproval?, run}`; outbound wire types `agent_say/agent_status/agent_screen_update/agent_transcript_append/tool_request_approval`; env via `dotenv/config` in `server.ts`.
