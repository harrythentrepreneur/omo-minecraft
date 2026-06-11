# AgentCraft

Spawn and operate real **Hermes agents** inside Minecraft. Walk into their homes,
watch them think, talk to them, approve sensitive actions — and have them do
real things like triage your inbox and manage your Meta ads.

```
[ Minecraft client ]
        │   (Java 1.21.4)
        ▼
[ Paper server + AgentCraft plugin ]
        │   WebSocket (JSON)
        ▼
[ Node.js runtime ]
        │
        ├── Hermes via OpenAI-compatible API (OpenRouter / Together / Nous)
        ├── Gmail tools (gmail_list/read/draft/send)
        ├── Meta Ads tools (campaigns/insights/pause/budget)
        └── Notes (persistent agent memory)
```

## What it gives you

- **Agent NPCs** — every agent is a villager with a floating "[status] role"
  banner and a live 6-line *screen* showing its latest steps.
- **Rooms** — name a region of your world (e.g. `mail-room`, `ads-room`,
  `lobby`). Rooms unlock tool sets:
  - `mail-*` → Gmail tools
  - `ads-*` / `facebook-*` → Meta Ads tools
  - anything else → generalist agent (all tools, but must ask before sensitive
    actions)
- **Chat-routed prompting** — type anything in the in-game chat near an agent
  and it's sent to that agent. Or use `/omo say <id> <text>`.
- **Approval gating** — `gmail_send`, `meta_ads_pause`, `meta_ads_update_budget`
  always prompt the player in-game with `/omo approve <callId>`.
- **Persistent rooms + notes** — rooms saved to `plugins/AgentCraft/rooms.yml`,
  agent notes to `runtime/data/notes/<agent>.md`.

## Setup

One-time install of prerequisites (macOS):

```bash
brew install --cask temurin            # JDK 21
brew install maven node jq             # plus your Minecraft Java client 1.21.4
```

Also have `nousresearch/hermes-agent` installed with its `api_server` platform
enabled on `127.0.0.1:8642` (see [docs/HERMES.md](docs/HERMES.md)).

Then — **one command**:

```bash
./agentcraft
```

That's it. The launcher:

1. checks prereqs
2. runs first-time setup (downloads Paper, builds the plugin, installs runtime deps) — only the first time
3. checks `hermes-agent` is reachable on :8642
4. starts the Node runtime in the background, waits for it to bind :8765
5. starts the Paper server in the background, waits for `Done`
6. tails both logs into your terminal
7. Ctrl-C just *detaches* from the logs — the stack keeps running, so you get
   your terminal back. Run `./agentcraft` again to re-attach or apply a change,
   or `./agentcraft stop` to actually stop everything.

Then launch Minecraft Java 1.21.4 and connect to `localhost`.

**One rule: changed something? Run `./agentcraft`.** You never have to figure out
whether a change was "small" or "big" — it reloads whatever changed and restarts
only what's necessary. Re-running it is always safe (it won't start a second copy),
and it's a no-op if nothing changed. As a bonus, runtime tweaks — agent prompts,
tools, room logic — are already live the moment you save (the in-game villagers
reconnect on their own), so you often don't even need to run anything.

### Other launcher commands

```
./agentcraft           start everything / reload what changed (default)
./agentcraft restart   force a full clean restart of everything
./agentcraft stop      stop everything
./agentcraft status    show what's running
./agentcraft logs      tail all logs
./agentcraft rebuild   rebuild plugin only
./agentcraft setup     re-run one-shot setup
```

## In-game commands

```
/omo village build [homes]      build a full agent village around you
/omo spawn <id> <role...>       spawn a Hermes agent at your position
/omo despawn <id>               remove an agent
/omo list                       list active agents
/omo room define <name>         mark your current position as a named room
/omo room here                  print the room you are standing in
/omo say <id> <text...>         talk to an agent directly
/omo approve <callId>           approve a pending sensitive action
/omo deny <callId>              deny a pending sensitive action
/omo reconnect                  reconnect to the runtime
```

### Building the village

Stand in an open area (flat creative world works best) and run:

```
/omo village build 4
```

That generates a circular plaza with:

- a **quartz Lobby** in the center
- a **Mail Room** (oak + yellow accent) to the north
- an **Ads Room** (dark oak + blackstone) to the south
- 4 **agent homes** in a ring around it

Every building is automatically registered as a room with the right kind, so
when you walk in and `/omo spawn alice ...`, the agent gets the right
tool set (Gmail in the mail room, Meta Ads in the ads room, etc.).

## Example session

```text
/omo room define mail-room
/omo spawn alice triage my unread mail and draft replies to anything urgent
[chat] alice, what's the highest priority right now?
[alice] reading inbox… 3 unread. Top one is from your investor about Thursday.
[APPROVAL] alice wants to gmail_send: draft_id=r-1234  →  /omo approve r-1234
/omo approve r-1234
[alice] sent. moving to the next one.
```

Walk to another room:

```text
/omo room define ads-room
/omo spawn bob watch my Meta ads and pause anything with CPC > $5
```

## Configuring tools

| Tool         | Where credentials live                   | Docs                             |
| ------------ | ---------------------------------------- | -------------------------------- |
| Hermes infer | `runtime/.env` (`HERMES_API_KEY`)        | [docs/HERMES.md](docs/HERMES.md) |
| Gmail        | `runtime/secrets/gmail.json` (OAuth)     | [docs/GMAIL.md](docs/GMAIL.md)   |
| Meta Ads     | `runtime/.env` (long-lived access token) | [docs/META_ADS.md](docs/META_ADS.md) |

## Layout

```
agentcraft/
├── plugin/      Java Paper plugin (Maven)
├── runtime/     Node.js Hermes runtime (TypeScript)
├── server/      Paper server install (created by setup.sh)
├── scripts/     setup / start-mc / start-runtime / build-plugin
└── docs/        per-tool setup guides
```
