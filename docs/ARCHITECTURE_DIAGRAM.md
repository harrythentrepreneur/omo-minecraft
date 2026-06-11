# Omo Mission Control — Architecture & Data Flow

The mandatory trifecta is labelled on the edges/nodes it lives on:
**Gemini** (every agent's model), **ADK** (the multi-agent org over `adk api_server`),
and **MCP** (every tool + the World API, over Streamable HTTP).

## Mermaid

```mermaid
flowchart TD
    Player["🎮 Player<br/>in Minecraft 1.21.4<br/>(/omo hq · talk · approve)"]

    subgraph PLUGIN["Paper plugin · Java 21 (only code that touches the world)"]
        Cmd["HermesCommand.java<br/>/omo hq → seats Chief of Staff"]
        World["world: HQ room · villagers ·<br/>reasoning board · cinema wall · tap-to-approve"]
    end

    subgraph RUNTIME["Node runtime · TypeScript (single-process orchestrator) — server.ts"]
        AM["AgentManager.ts<br/>brain selection (Hermes | Claude | ADK)"]
        ADKBrain["AdkAgent.ts<br/>in-world face of the org<br/>streams reasoning · tool calls · hand-offs"]
        MCP["mcpServer.ts — omo-tools MCP server<br/>(official @modelcontextprotocol/sdk, :8090/mcp)<br/>Meta Ads tools + World API + worldStore.ts"]
        Dash["dashboardServer.ts<br/>alien-HUD web dashboard (:8088 /dash/:id)"]
    end

    subgraph ADKSVC["ADK service · Python — omo-agent/omo/agent.py (adk api_server :8000)"]
        CoS["ChiefOfStaff (LlmAgent · coordinator)"]
        Growth["Growth (LlmAgent)"]
        Comms["Comms (LlmAgent)"]
    end

    Meta["Meta Ads Graph API<br/>(real account · PhonicsMaker)"]

    Player <-->|chat / clicks| PLUGIN
    Cmd --> AM
    PLUGIN <-->|"WebSocket :8765"| AM
    AM --> ADKBrain
    AM --> MCP
    AM --> Dash
    Dash -.->|cinema wall render| World

    ADKBrain <-->|"HTTP SSE · POST /run_sse — ADK"| CoS
    CoS -->|"sub_agents · transfer_to_agent — ADK"| Growth
    CoS -->|"sub_agents · transfer_to_agent — ADK"| Comms

    CoS -.->|Gemini| G((Gemini<br/>gemini-flash-latest))
    Growth -.->|Gemini| G
    Comms -.->|Gemini| G

    CoS <-->|"McpToolset · StreamableHTTP — MCP (World API)"| MCP
    Growth <-->|"McpToolset · StreamableHTTP — MCP (Meta Ads)"| MCP
    MCP -->|live Graph API| Meta
    MCP -.->|"world_build / world_staff / world_assign (broadcast)"| World

    classDef tech fill:#0b3d2e,stroke:#39ff88,color:#eafff4;
    class G tech;
```

## ASCII fallback

```
   🎮 Player (Minecraft 1.21.4) ── /omo hq · talk · approve
        │  chat / clicks
        ▼
 ┌─────────────────────────────────────────────────────────────┐
 │ Paper plugin (Java 21) — only code that touches the world    │
 │   HermesCommand.java (/omo hq → seats Chief of Staff)        │
 │   world: HQ · villagers · reasoning board · cinema · approve │
 └───────────────┬───────────────────────────────▲─────────────┘
                 │ WebSocket :8765                │ cinema-wall render
                 ▼                                │ (dashboard)
 ┌─────────────────────────────────────────────────────────────┐
 │ Node runtime (TypeScript) — server.ts                        │
 │   AgentManager.ts  → brain select (Hermes | Claude | ADK)    │
 │   AdkAgent.ts      → in-world face; streams reasoning/tools  │
 │   mcpServer.ts     → omo-tools MCP (Meta Ads + World API)    │
 │   dashboardServer.ts → alien-HUD board (:8088 /dash/:id) ────┘
 └──────┬───────────────────────────────────┬──────────────────┘
        │ HTTP SSE  POST /run_sse  [ADK]     │ McpToolset StreamableHTTP [MCP]
        ▼                                    ▲
 ┌──────────────────────────────────┐       │  (World API: build/staff/assign
 │ ADK service (Python)             │       │   broadcast back into the world)
 │ omo-agent/omo/agent.py @ :8000   │       │
 │   ChiefOfStaff (LlmAgent) ──[ADK transfer_to_agent]──► Growth / Comms
 │   every agent's model = Gemini (gemini-flash-latest)  │
 │   tools = McpToolset ─────────────────────────────────┘
 └──────────────────────────────────┘
                                              │ live Graph API
                                              ▼
                                   Meta Ads API (real: PhonicsMaker)
```

## Caption

The player talks to a Chief of Staff villager inside Minecraft; the Paper plugin relays that over a WebSocket (:8765) to the Node runtime, where `AgentManager` routes the `hq` room to the **ADK** brain (`AdkAgent`), which POSTs to the Python ADK service's `/run_sse` and streams every reasoning token, tool call, and `transfer_to_agent` hand-off back onto in-world screens. Each ADK agent runs on **Gemini** and reaches its tools over **MCP** — `McpToolset` connecting via Streamable HTTP to the in-runtime `omo-tools` server, which exposes both real Meta Ads tools (live Graph API on the PhonicsMaker account) and the World API the Chief of Staff uses to extend the org itself. Because the MCP server lives inside the runtime, a `world_build`/`world_staff`/`world_assign` call broadcasts straight back into the live world and drives the in-game villagers, while the dashboard server renders the function's real data onto an in-world cinema wall.
