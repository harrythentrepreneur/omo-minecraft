# AgentCraft architecture

## Two processes, one WebSocket

```
[ Minecraft client ] ──► [ Paper server + AgentCraft plugin ] ──ws──► [ Node runtime ]
                                                                          │
                                                                          ▼
                                            ┌─────────────────────────────┐
                                            │ HermesAgent (per agent)     │
                                            │  history: HermesMessage[]   │
                                            │  loop: thinking → tool_call │
                                            └─────────────┬───────────────┘
                                                          │
                                                          ▼
                                     ┌────────────────────────────────────┐
                                     │ Tools                              │
                                     │  gmail_*, meta_ads_*, notes_*,     │
                                     │  say, finish_task,                 │
                                     │  request_human_approval            │
                                     └────────────────────────────────────┘
```

- **Plugin side** is the only thing that touches Bukkit APIs. WebSocket messages
  arrive on a JDK reactor thread; `IncomingHandler` hops onto the main thread
  via `Bukkit.getScheduler().runTask(...)` before touching entities.
- **Runtime side** is a single Node process with one `AgentManager` and one
  WebSocket connection (one Paper server). Each agent has its own conversation
  state but they all stream over the same socket.

## Wire protocol

All messages are JSON, one message per WS text frame.

### Plugin → runtime (`InboundMessage`)

| `type`              | Payload                                                          |
|---------------------|------------------------------------------------------------------|
| `hello`             | `{ token, serverName }` — must be first; closes if token wrong  |
| `spawn_agent`       | `{ agentId, role, home:{x,y,z}, room, playerName }`             |
| `despawn_agent`     | `{ agentId }`                                                    |
| `player_message`    | `{ agentId, playerName, text }` — proximity chat or `/omo say` |
| `player_enter_room` | `{ room, playerName }`                                           |
| `player_leave_room` | `{ room, playerName }`                                           |
| `tool_approval`     | `{ agentId, callId, approved:boolean }`                          |

### Runtime → plugin (`OutboundMessage`)

| `type`                  | Payload                                                       |
|-------------------------|---------------------------------------------------------------|
| `ready`                 | sent once after `hello` accepted                              |
| `agent_status`          | `{ agentId, status, detail? }` — drives the floating tag color |
| `agent_say`             | `{ agentId, text, playerName? }` — chat bubble + message      |
| `agent_log`             | `{ agentId, line, level }` — server console + diagnostics     |
| `room_screen_update`    | `{ room, lines:string[] }` — updates the holographic monitor  |
| `tool_request_approval` | `{ agentId, callId, tool, summary }` — prompts player         |

## Agent loop

`HermesAgent.runLoop()` is the standard tool-call loop:

1. Append the player's message to `history` as `role:user`.
2. Set status `thinking` → call Hermes via `callHermes(history, tools)`.
3. Append the assistant turn (with `tool_calls` if any) to `history`.
4. If `tool_calls` is empty, the turn is over. Status → `idle`.
5. Otherwise, for each tool call:
   - If `needsApproval(args)` is true, fire `request_human_approval` and await
     the resolution promise tracked in `AgentManager.pendingApprovals`.
   - Run the tool, append the result as `role:tool` keyed by `tool_call_id`.
6. If any tool was `finish_task`, status → `done` and exit. Otherwise loop.
7. After 12 steps, status → `error("max steps reached")` to avoid runaway loops.

## Approval lifecycle

1. Tool with `needsApproval` triggers `events.onRequestApproval(callId, tool, summary)`.
2. `AgentManager.awaitApproval` registers a `(callId → resolver)` in
   `pendingApprovals`, sends `tool_request_approval` to the plugin, and starts
   a 120-second auto-deny timer.
3. Plugin broadcasts a clickable chat line to players in the agent's room and
   records the pending approval per player.
4. Player runs `/omo approve <callId>` (or `deny`). Plugin pops it from
   that player's queue and sends `tool_approval` back.
5. Runtime resolves the promise; the tool either runs or returns
   `{ error: "owner declined approval" }`.

## Room kinds → tool sets

`runtime/src/rooms/registry.ts::roomKindFromName` maps the room name to a
`RoomKind`, which `buildRegistryForRoom` uses to choose which tools the agent
gets registered with. This is just a sensible default — the system prompt
also tells the agent which room it's in so it can pick tools appropriately.

| Name prefix | `RoomKind`   | Tools registered                          |
|-------------|--------------|-------------------------------------------|
| `mail`      | `mail_room`  | control + notes + gmail                   |
| `ads`/`facebook` | `ads_room` | control + notes + meta ads             |
| `lobby`     | `lobby`      | everything                                |
| (else)      | `agent_home` | everything (sensitive tools need approval) |

Control tools (`say`, `finish_task`, `request_human_approval`) and notes
(`notes_read`, `notes_write`) are always registered.

## Files & state

- **Rooms** persist to `server/plugins/AgentCraft/rooms.yml` (YAML written by
  `RoomManager.save`). Survives plugin reloads.
- **Conversation history** is in-memory only. Restart the runtime → agents
  start fresh on next `/omo say`. This is intentional — agents are cheap
  to respawn and we don't want stale state poisoning the model.
- **Agent notes** persist to `runtime/data/notes/<agentId>.md` via the
  `notes_write` tool. This is the long-lived memory agents can use across
  sessions.

## Threading model

- Paper plugin: WS callbacks run on JDK reactor threads. **Always** hop to the
  main thread before touching entities or the world. `IncomingHandler`
  enforces this with `Bukkit.getScheduler().runTask(plugin, ...)`.
- Node runtime: single-threaded event loop. `HermesAgent.handleMessage` is
  fire-and-forget — multiple players can talk to different agents
  concurrently. Each agent has a `busy` flag to serialize its own messages.

## Where to extend

- **New tool**: add a `ToolImpl` in `runtime/src/tools/`, register it in
  `runtime/src/tools/index.ts::buildRegistryForRoom` for the rooms that should
  have it. If destructive, set `needsApproval: () => true`.
- **New room kind**: add it to `RoomKind` in `prompts.ts`, write a system
  prompt clause, and map a name prefix in `rooms/registry.ts`.
- **New in-world UI**: extend `AgentNpc` in the plugin. Item frames, signs,
  and beacons all work well as additional surfaces.
