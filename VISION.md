# AgentCraft — Vision

## The one-line version

**Your AI workforce should live somewhere you can walk to.**

Not a chat window. Not a dashboard. A village inside the Minecraft world you
already play in — where each agent is a villager you can watch, talk to, and
trust to do real work.

---

## Why this exists

Chat windows are a terrible UI for ongoing AI labor. They're flat, ephemeral,
and they make every interaction feel like starting over. You can't *see* what
your agents are doing. You can't *delegate by walking somewhere*. You can't
*tell at a glance* who's busy and who's idle.

A 3D world solves all of that for free:

- **Location encodes capability.** The mail room handles email. The ads room
  handles Meta. Walk somewhere → you've scoped the conversation.
- **Presence encodes state.** The villager's head-tag shows status. The screen
  above them shows their last six reasoning steps. You glance, you know.
- **Persistence encodes identity.** Alice lives in her house. She has notes,
  a session, a history. She isn't reset every time you reopen a tab.
- **Approval is in-band.** A sensitive action surfaces in-game; you approve
  it in-game; the loop never leaves the world.

This isn't a gimmick. Minecraft is the most fluent spatial UI most people
already know how to use. We're treating it as the operating system for our
agents.

---

## The non-negotiables

These are stack choices, not preferences. Don't propose alternatives without a
specific reason.

1. **Real Minecraft via a Paper plugin.** Java 21, paper-api 1.21.4. The user
   plays in their actual Minecraft client. Not a web voxel clone, not Three.js,
   not a custom renderer.
2. **Nous Hermes models, run locally.** Driven through `nousresearch/hermes-agent`
   on `http://127.0.0.1:8642/v1`. Per-villager isolation via `X-Hermes-Session-Id`
   + `X-Hermes-Session-Key`. Hosted providers (OpenRouter etc.) are a fallback,
   not the path.
3. **One command to start.** `./agentcraft` brings up the runtime, the Paper
   server, and tails both. Setup is automatic on first run. If it takes more
   than one command, we've failed.
4. **Real work, not demos.** Gmail triage and replies. Meta Ads monitoring,
   pausing, budget changes. Agents that earn their keep, not toys that say
   "hello world."
5. **Approval gating for anything that touches the outside world.** Sending
   mail, pausing ads, moving money — always `/omo approve <callId>` in-game.
   The world is the trust boundary.

---

## What we're building toward

### Built today

- `/omo village build` — instant plaza with Lobby, Mail Room, Ads Room,
  Workshop, and a ring of agent homes. Rooms auto-register with the right `kind`.
- `/omo spawn <id> <role>` — Hermes villager appears (mail/ads/generalist).
- `/omo spawn-code <id> <cwd> <task>` — Claude Code villager appears in
  a workshop room, pointed at the given working directory. Reads, edits,
  runs commands; sensitive Bash (git push, rm -rf, sudo, publishes) gates
  for in-game approval just like Gmail send does.
- Chat-routed prompting — type near a villager, they hear you.
- Approval flow — sensitive tool calls pause and prompt you in-game.
- Persistent notes per agent at `runtime/data/notes/<agent>.md`.
- Persistent rooms at `plugins/AgentCraft/rooms.yml`.

### A note on brains

Hermes is the brain for **operational** villagers (mail, ads, the lobby
greeter, generalists). Claude (via the Agent SDK) is the brain for **coding**
villagers, because Claude Code is the best agent for writing code and the
SDK gives us Read/Edit/Bash/Grep/Glob for free. Both flow through the same
WebSocket bridge, the same screen surface, the same approval gate — the
villager doesn't know which model is thinking inside it.

### Next rings out

These are intentions, not commitments. Order is rough.

- **Workshop → worktree mapping.** Right now each code villager takes an
  explicit `cwd` at spawn time. Next step: a coding villager's *house* in
  the village is literally a git worktree of `cwd`. Spawn three villagers
  on three branches → three houses, each with the branch name on the sign
  over the door and the diff visible inside on item frames.
- **Particles & status fx for the workshop.** Smoke from the chimney while
  the villager is `tool_call`. Fireworks when tests pass. Red screen on
  test/build fail. Currently the only feedback is the 6-line screen.
- **Calendar room.** Walk in, ask "what's my week look like." Schedule by
  conversation. Approval-gated for creating/moving events on shared calendars.
- **Research room.** Browserbase + Hermes — "go find me ten companies that
  match this ICP and bring back a CSV." The agent literally walks back to
  the lobby with the result.
- **Boss's office.** A room where one agent delegates to others. You ask the
  boss; the boss runs to the relevant specialist; you watch the village
  organize itself.
- **Cross-agent memory.** Alice should be able to mention "ask bob about the
  Q3 numbers" and have that resolve. Currently each villager is an island.
- **Voice chat.** Hermes can already do audio. The villager should be a
  thing you talk to, not just type at.
- **Idle behavior.** Villagers should *look* like they're working when they
  are — pathing between blocks, opening their screen wider on long tasks,
  going home when done.
- **Multi-world / multi-village.** Different worlds for different contexts
  (personal village, work village). Save states travel with the world.

### Explicitly out of scope (unless the user says otherwise)

- **Web dashboards.** If you find yourself wanting to build a React page to
  observe the system, the Minecraft world is the dashboard. Build it there.
- **Non-Hermes model providers as primary for operational work.** Claude,
  GPT, Gemini are not the brain for mail/ads/general villagers. Hermes is.
  The one exception is the **workshop** (coding villagers), which run on
  Claude by design.
- **A "headless" mode.** If you can run AgentCraft without Minecraft open,
  we've lost the point. The game *is* the product.
- **Selling this.** Right now this is a personal labor system, not a
  product. Optimize for the user's daily workflow, not for strangers.

---

## How to think when adding to this

When a feature request comes in, ask in this order:

1. **Does it have a place?** Every new capability should live in a *room*.
   If you can't picture which room it belongs to, the design isn't ready.
2. **Does it have a villager?** Capabilities don't float; they're embodied
   in someone you can walk up to. "An admin task" → "who's the agent that
   owns it?"
3. **Does it require approval?** Anything that touches the outside world
   (sends a message, spends money, modifies a shared resource) goes through
   `/omo approve`. No exceptions, no "trusted" auto-mode.
4. **Does it extend Paper plugin + Node runtime?** If you're tempted to add
   a third process or a web service, push back. The two-process model is
   load-bearing.
5. **Does it use `callHermes()`?** New model calls go through the existing
   wrapper. If you need a different provider SDK, ask first.

---

## The north star

A workday where you log into Minecraft, walk through your village, and your
agents are already working. Alice has triaged the inbox and queued three
drafts for your approval. Bob has paused two ads that breached CPC overnight.
You stop in at the research house and ask Carol to dig into a new lead. You
walk back to the lobby and the boss is already coordinating between them.

You don't open Gmail. You don't open Ads Manager. You don't open a chat tab.

You just play the game, and the work gets done.
