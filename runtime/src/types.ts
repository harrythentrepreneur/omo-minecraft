// Shared types between the Paper plugin (via JSON over WebSocket) and the runtime.

export type Vec3 = { x: number; y: number; z: number };

export type AgentStatus =
  | "idle"
  | "thinking"
  | "tool_call"
  | "speaking"
  | "error"
  | "done";

export type AgentDescriptor = {
  id: string;
  name: string;
  role: string;
  home: Vec3;
  room: string;
  status: AgentStatus;
};

// A single line on a villager's reasoning board. Plugin renders kind → glyph + color.
export type ScreenEntryKind =
  | "think"   // model is reasoning
  | "tool"    // tool call started
  | "result"  // tool result returned
  | "say"     // assistant text spoken to player
  | "done"    // turn finished cleanly
  | "error"   // turn or tool errored
  | "system"; // meta line (user input echo, welcome, etc.)

export type ScreenEntry = {
  kind: ScreenEntryKind;
  text: string;
};

// A single build-DSL op forwarded VERBATIM to the plugin (which expands +
// validates). All coords are LOCAL plot integers (x:0..31, y:0..23, z:0..31);
// `material` is a Minecraft Material enum name. The runtime never interprets
// these — it just relays them. Fields beyond `op` are optional because each
// op shape uses a different subset.
export type BuildOp = {
  op:
    | "set"
    | "box"
    | "cuboid_frame"
    | "cylinder"
    | "sphere"
    | "pyramid"
    | "line"
    | "clear";
  material?: string;
  x?: number;
  y?: number;
  z?: number;
  x1?: number;
  y1?: number;
  z1?: number;
  x2?: number;
  y2?: number;
  z2?: number;
  cx?: number;
  cy?: number;
  cz?: number;
  radius?: number;
  height?: number;
  baseRadius?: number;
  baseY?: number;
  hollow?: boolean;
  dome?: boolean;
  solid?: boolean;
};

// --- Inbound messages (plugin -> runtime) ---

export type InboundMessage =
  | { type: "hello"; token: string; serverName: string }
  | {
      type: "spawn_agent";
      agentId: string;
      role: string;
      home: Vec3;
      room: string;
      playerName: string;
      cwd?: string;
      // Command auto-run in the PTY shell when the terminal opens. "claude"
      // for the Code box, "hermes chat" for the Hermes box, or "" for a plain
      // shell. Only honoured by PTY (workshop_team) agents.
      launch?: string;
    }
  | { type: "despawn_agent"; agentId: string }
  | {
      type: "player_message";
      agentId: string;
      playerName: string;
      text: string;
    }
  | {
      type: "player_enter_room";
      room: string;
      playerName: string;
    }
  | {
      type: "player_leave_room";
      room: string;
      playerName: string;
    }
  | {
      type: "tool_approval";
      agentId: string;
      callId: string;
      approved: boolean;
    };

// --- Outbound messages (runtime -> plugin) ---

export type OutboundMessage =
  | { type: "ready" }
  | {
      type: "agent_status";
      agentId: string;
      status: AgentStatus;
      detail?: string;
    }
  | {
      type: "agent_say";
      agentId: string;
      text: string;
      playerName?: string;
    }
  | {
      type: "agent_log";
      agentId: string;
      line: string;
      level: "info" | "warn" | "error" | "tool";
    }
  | {
      // Per-agent reasoning board update. Plugin updates only that agent's stand stack.
      type: "agent_screen_update";
      agentId: string;
      entries: ScreenEntry[];
    }
  | {
      // Append a single entry to the agent's full transcript (the lectern book).
      // isNewTurn=true means this entry starts a fresh turn (insert a separator before).
      type: "agent_transcript_append";
      agentId: string;
      entry: ScreenEntry;
      isNewTurn: boolean;
    }
  | {
      // Build-DSL ops for a Build Studio villager. Plugin expands/validates +
      // places blocks on the plot in front of the deck. `clearFirst` wipes the
      // plot to flat before applying. Ops are relayed verbatim (see BuildOp).
      type: "build_ops";
      agentId: string;
      clearFirst: boolean;
      ops: BuildOp[];
    }
  | {
      // Room-scoped welcome screen — broadcast to every agent in the room.
      type: "room_screen_update";
      room: string;
      entries: ScreenEntry[];
    }
  | {
      type: "tool_request_approval";
      agentId: string;
      callId: string;
      tool: string;
      summary: string;
    }
  | {
      // Teleport a player to a named room/island. Fired by the face/ voice
      // surface via POST /api/teleport. If `player` is null the plugin
      // teleports whichever player is currently online (the host).
      //
      // `room` is the canonical destination id (or any room name).
      // `roomCandidates` (optional) is an ordered list the plugin should
      // walk if `room` itself isn't registered — first candidate that
      // resolves wins. Lets the runtime ship the shared map's preference
      // order without having to know which rooms the plugin has built.
      type: "teleport_player";
      room: string;
      player: string | null;
      roomCandidates?: string[];
    }
  | {
      // A line of voice-loop transcript (either the user's speech or
      // Omo's reply) OR a system status line (voice loading progress,
      // mic-permission errors, dropped-session hints).
      //
      // Role rendering on the plugin side:
      //   user   — "[you]"  yellow / cream prose
      //   omo    — "[omo]"  aqua reply text
      //   system — "⋯ "    italic gray, no bracket prefix
      //
      // The runtime emits these from /api/voice-transcript (user + omo
      // transcripts) and /api/voice-progress (system progress lines).
      type: "chat_message";
      role: "user" | "omo" | "system";
      text: string;
    }
  // ─── Voice-driven agent-ops requests (face → runtime → plugin) ─────
  // The face's Gemini Live loop calls tools like spawn_team / open_terminal;
  // the runtime turns each into one of these requests and broadcasts it.
  // The plugin's IncomingHandler maps them back onto the existing /hermes
  // command paths so the WS surface is the only new wiring on each side.
  | {
      type: "spawn_team_request";
      cwd?: string | null;
      playerName?: string | null;
    }
  | {
      type: "spawn_village_request";
      playerName?: string | null;
    }
  | {
      type: "spawn_code_request";
      agentId: string;
      cwd: string;
      task: string;
      playerName?: string | null;
    }
  | {
      type: "despawn_agent_request";
      agentId: string;
    }
  | {
      // Dean (Hermes agent) asked to re-theme the single classroom for a new
      // subject and re-seat the tutor "ada". The plugin maps this onto the
      // /omo classroom command path.
      type: "open_classroom_request";
      subject: string;
      playerName?: string | null;
    }
  | {
      // agentId null = "open whichever terminal is most relevant" (the
      // client mod's no-arg TeamTerminalScreen). When set, the plugin
      // emits the §§ACT-TERMINAL§§ sentinel so the client mod auto-opens
      // that specific agent's pane.
      type: "open_terminal_request";
      agentId: string | null;
      playerName?: string | null;
    }
  | {
      // Runtime-side terminal attach can create the built-in claude/hermes PTY
      // directly. This asks the plugin to mirror that agent into the room so
      // the player sees the villager + status board above the same session.
      type: "ensure_terminal_agent_request";
      agentId: string;
      room: string;
      role: string;
      cwd: string;
      launch: string;
      playerName?: string | null;
    }
  | {
      // Tells the client mod to drop whatever terminal screen is open.
      // Plugin emits a new §§ACT-CLOSE-TERMINAL§§ sentinel for this.
      type: "close_terminal_request";
      playerName?: string | null;
    }
  | {
      // Omo World API: build a function's room/wing live near HQ (blocks rise).
      type: "world_build_request";
      room: string;
      anchorRoom: string;
      index: number;
      role: string;
    }
  | {
      // Omo World API: staff a function — spawn its specialist villager in `room`
      // near `anchorRoom` (HQ). The plugin creates the villager and sends back a
      // spawn_agent so the runtime builds its (specialist) brain.
      type: "world_staff_request";
      agentId: string;
      role: string;
      room: string;
      anchorRoom: string;
      index: number;
    }
  | {
      // world_consult: one function's specialist is asking another a question.
      type: "world_consult_request";
      from: string;
      to: string;
      fromRoom: string;
      toRoom: string;
    }
  | {
      // world_consult finished — the consulted function answered (or timed out).
      type: "world_consult_done";
      from: string;
      to: string;
    };
