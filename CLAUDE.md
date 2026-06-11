# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

AgentCraft spawns Nous Hermes AI agents as villagers inside a real Minecraft
1.21.4 game. Rooms gate tool sets (mail room → Gmail, ads room → Meta Ads).
This is **not** a web/voxel clone — the user plays in the actual Java client.
See `VISION.md` for the longer "why" and the non-negotiables.

## Common commands

The `./agentcraft` launcher is the entry point for almost everything. In day-to-day
use you only need the first command:

```
./agentcraft           start hermes-agent + runtime + face + Paper, tail all logs.
                       Also the ONE command to run after ANY change — it reloads
                       whatever changed and restarts only what's necessary, so the
                       user never has to classify a change as "small" or "big".
./agentcraft restart   force a full clean restart of everything (alias: refresh)
./agentcraft stop      stop everything
./agentcraft status    show what's running
./agentcraft logs      tail logs/{hermes,runtime,face,mc}.log
./agentcraft rebuild   rebuild plugin JAR + hot-swap into a running server
./agentcraft setup     re-run one-shot setup (Paper download + plugin build + npm install)
```

Reload model — **one rule: after any change, run `./agentcraft`.** Under the hood:
runtime runs under `tsx watch` so its edits are already live on save (plugin
auto-reconnects, player stays in-game); `./agentcraft` rebuilds the plugin + restarts
Paper if the Java changed, restarts face if it changed, and is a harmless no-op for
an already-live runtime tweak. No penalty for re-running it when nothing changed. The
targeted `restart-runtime` / `restart-hermes` / `restart-face` still exist but are
rarely needed.

Each service is launched via `spawn_detached` into its **own process group**, so
**Ctrl-C in the log terminal only detaches** (stops tailing) — the stack keeps
running. `./agentcraft stop` is the way to actually stop everything; `stop_pidfile`
kills the whole group (negative-pid `kill`). This is why node/Paper survive Ctrl-C.

Per-process commands (rarely needed — the launcher orchestrates them):

```
cd runtime && npm run dev          # tsx watch — auto-reloads on TS change
cd runtime && npm run typecheck    # tsc --noEmit; the only "test" the runtime has
cd plugin && mvn -q package        # builds plugin/target/agentcraft-*.jar
./scripts/build-plugin.sh          # mvn package + copy JAR into server/plugins/
```

There is **no test suite** in either project. Verification is manual: rebuild,
restart, walk up to a villager in-game, exercise the tool path. The runtime's
`typecheck` script is the closest thing to CI.

## Architecture

Two processes talk over one WebSocket. Read `docs/ARCHITECTURE.md` for the
full wire protocol — the load-bearing facts:

1. **Plugin (`plugin/`, Java 21, Paper 1.21.4)** is the *only* code that
   touches Bukkit/the world. It opens a JDK `java.net.http.WebSocket` client
   to the runtime at `ws://127.0.0.1:8765`.
2. **Runtime (`runtime/`, Node 20+/TS, `tsx`)** is a single-process WS server.
   One `AgentManager` holds all agents; each `HermesAgent` has its own
   conversation history but they share the socket.
3. **Two brain types, picked by room kind:**
   - Operational villagers (mail/ads/lobby/generic homes) use `HermesAgent`,
     which calls **Hermes** via an OpenAI-compatible endpoint. Default is local
     `nousresearch/hermes-agent` on `http://127.0.0.1:8642/v1`. Every request
     carries `X-Hermes-Session-Id` + `X-Hermes-Session-Key` headers equal to the
     in-game agent id — that's how per-villager memory is isolated inside
     hermes-agent. All Hermes traffic goes through
     `runtime/src/inference/hermes.ts::callHermes`.
   - **Workshop villagers** (`workshop`/`code-*`/`dev-*` rooms) use `CodeAgent`,
     which wraps `@anthropic-ai/claude-agent-sdk`. The SDK provides Read/Edit/
     Bash/Grep/Glob built-in. Session continuity via `resume: this.sessionId`
     across turns. Sensitive Bash commands (git push, git reset --hard, rm -rf,
     sudo, package publishes, curl POST/PUT/DELETE) flow through the same
     `tool_request_approval` path as the Hermes side via the SDK's `canUseTool`
     callback. Auth: Claude Code's OAuth — no new API key needed.

**Threading rule (plugin side):** WS callbacks land on JDK reactor threads.
*Always* hop to the main thread before touching entities/world:
`Bukkit.getScheduler().runTask(plugin, ...)`. `bridge/IncomingHandler.java`
enforces this.

**Concurrency (runtime side):** single event loop. Each `HermesAgent` has a
`busy` flag so its own messages serialize; different agents run concurrently.

## Agent loop

`HermesAgent.runLoop()` (`runtime/src/agents/HermesAgent.ts`):

1. Player message → `history` as `role:user`, status → `thinking`.
2. `callHermes(history, tools)` — assistant turn pushed back onto history.
3. No `tool_calls` → status `idle`, done.
4. For each tool call: if `needsApproval(args)` is true, emit
   `tool_request_approval` and `await` the resolver in
   `AgentManager.pendingApprovals` (120s auto-deny). Then run the tool,
   append result as `role:tool`.
5. `finish_task` → status `done`. Otherwise loop, hard cap 12 steps.

## Rooms → tool sets

`runtime/src/rooms/registry.ts::roomKindFromName` maps room name prefix to
`RoomKind`; `runtime/src/tools/index.ts::buildRegistryForRoom` picks the tool
set.

| Prefix                  | RoomKind     | Brain        | Tools registered                              |
| ----------------------- | ------------ | ------------ | --------------------------------------------- |
| `mail`                  | `mail_room`  | HermesAgent  | control + notes + gmail                       |
| `ads`/`facebook`        | `ads_room`   | HermesAgent  | control + notes + meta ads                    |
| `lobby`                 | `lobby`      | HermesAgent  | everything                                    |
| `workshop`/`code`/`dev` | `workshop`   | **CodeAgent** | Claude Code's built-ins (Read/Edit/Bash/...) |
| (else)                  | `agent_home` | HermesAgent  | everything (sensitive tools approval-gated)   |

Control tools (`say`, `finish_task`, `request_human_approval`) and notes
(`notes_read`, `notes_write`) are always registered. Sensitive tools that
**must** keep approval gating: `gmail_send`, `meta_ads_pause`,
`meta_ads_update_budget`. Don't loosen this without the user asking.

## State

- **Rooms** persist to `server/plugins/AgentCraft/rooms.yml` via
  `RoomManager.save`. Survives plugin reloads.
- **Conversation history** is in-memory only — restart runtime → fresh
  context. Intentional; hermes-agent owns the long-lived session memory.
- **Agent notes** persist at `runtime/data/notes/<agentId>.md` via the
  `notes_write` tool. This is the cross-restart memory surface.
- **Secrets**: `runtime/.env` (Hermes + Meta Ads token) and
  `runtime/secrets/gmail.json` (OAuth). Never commit either.

## Spawning

- `/omo spawn <id> <role...>` — Hermes villager. Room kind comes from the
  room name prefix.
- `/omo spawn-code <id> <cwd> <task...>` — Claude Code villager. The `cwd`
  must be an absolute path to an existing directory; that's the working dir
  the SDK operates on. Auto-defines a `workshop-<id>` room if you aren't
  standing in one.

## Extending the system

- **New tool** — add a `ToolImpl` in `runtime/src/tools/`, register it in
  `runtime/src/tools/index.ts::buildRegistryForRoom` for the rooms that
  should have it. If it touches the outside world, set `needsApproval: () => true`.
  (Workshop villagers don't use this — their tools come from the Agent SDK.)
- **New dangerous Bash pattern** — extend `DANGEROUS_BASH` in
  `runtime/src/agents/CodeAgent.ts`. Any match routes through
  `tool_request_approval` instead of auto-allowing.
- **New room kind** — add to `RoomKind` in `runtime/src/agents/prompts.ts`,
  add a system-prompt clause, add a name-prefix entry in
  `runtime/src/rooms/registry.ts`.
- **New in-world UI** — extend `plugin/.../agents/AgentNpc.java`. Item frames,
  signs, beacons all work as additional surfaces; the floating head tag and
  the 6-line room screen are already there.
- **New wire message** — add the type to both sides:
  plugin `bridge/IncomingHandler.java` (in) / `BridgeClient.java` (out), and
  runtime `src/types.ts` + the WS handler in `src/server.ts`.

## Things to leave alone unless asked

- The local-`hermes-agent` path is primary for *operational* villagers.
  Hosted providers (OpenRouter etc.) are a fallback in `docs/HERMES.md`,
  not the default.
- Workshop villagers run on Claude (Agent SDK) by design — that exception
  to the Hermes-only rule is intentional and load-bearing. Don't try to
  route coding through Hermes "for consistency."
- Two-process model is load-bearing. Don't add a third service, a web
  dashboard, or a "headless" mode.
- `./agentcraft` is the user-facing entry point. Keep it one command.
