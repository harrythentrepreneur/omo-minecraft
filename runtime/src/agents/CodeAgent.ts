import {
  query,
  createSdkMcpServer,
  tool,
  type Options as SdkOptions,
  type PermissionResult,
  type SDKMessage,
  type SDKResultMessage,
} from "@anthropic-ai/claude-agent-sdk";
import { z } from "zod";
import type { AgentEvents } from "./HermesAgent.js";
import type { AgentStatus, BuildOp, ScreenEntry, Vec3 } from "../types.js";
import { ensureDeck } from "../classroom/deck.js";
import { whiteboardStore } from "../whiteboard.js";

// The Dean + tutor run on a fast/cheap Claude tier. Their jobs (call open_classroom;
// walk the slide deck with show_slide) need reliable tool-calling, which Claude
// does and the local Hermes model does not. Haiku keeps them snappy and cheap.
const DEAN_MODEL = "claude-haiku-4-5-20251001";
const TUTOR_MODEL = "claude-haiku-4-5-20251001";

const SCREEN_BUFFER_LIMIT = 16;

const DANGEROUS_BASH = [
  /\bgit\s+push\b/,
  /\bgit\s+reset\s+--hard\b/,
  /\brm\s+-[rRf]+/,
  /\bsudo\b/,
  /\bnpm\s+publish\b/,
  /\bpnpm\s+publish\b/,
  /\byarn\s+publish\b/,
  /\bcurl\s+.*-X\s+(POST|PUT|DELETE|PATCH)\b/i,
];

function isDangerousBash(cmd: string): boolean {
  return DANGEROUS_BASH.some((re) => re.test(cmd));
}

export class CodeAgent {
  readonly id: string;
  readonly role: string;
  readonly home: Vec3;
  readonly room: string;
  readonly ownerName: string;
  readonly cwd: string;
  readonly buildMode: boolean;
  readonly deanMode: boolean;
  readonly teachMode: boolean;
  status: AgentStatus = "idle";

  private sessionId: string | undefined;
  private busy = false;
  private screenBuffer: ScreenEntry[] = [];
  private abort?: AbortController;
  private approvalCounter = 0;
  private turnStart = 0;
  // tool_use id → {name, start} so a tool_result can report how long its call took.
  private toolStarts = new Map<string, { name: string; start: number }>();
  // Running session totals surfaced on the telemetry line each turn.
  private sessionInTokens = 0;
  private sessionOutTokens = 0;
  private sessionCostUsd = 0;

  constructor(
    opts: {
      id: string;
      role: string;
      home: Vec3;
      room: string;
      ownerName: string;
      cwd: string;
      buildMode?: boolean;
      deanMode?: boolean;
      teachMode?: boolean;
    },
    private events: AgentEvents,
  ) {
    this.id = opts.id;
    this.role = opts.role;
    this.home = opts.home;
    this.room = opts.room;
    this.ownerName = opts.ownerName;
    this.cwd = opts.cwd;
    this.buildMode = opts.buildMode === true;
    this.deanMode = opts.deanMode === true;
    this.teachMode = opts.teachMode === true;
  }

  async handleMessage(playerName: string, text: string): Promise<void> {
    if (this.busy) {
      this.events.onSay("hold on, still working on the previous one…", playerName);
      return;
    }
    this.busy = true;
    this.setStatus("thinking");
    this.turnStart = Date.now();
    this.resetScreen({ kind: "system", text: `${playerName}: ${truncate(text, 48)}` });
    // Full request to the terminal mirror (untruncated), starting a fresh turn.
    this.events.onReasoning?.(`${playerName}: ${text}`, "system", { newTurn: true });
    this.pushScreen({ kind: "think", text: "thinking…" });

    this.abort = new AbortController();
    const options: SdkOptions = {
      cwd: this.cwd,
      abortController: this.abort,
      systemPrompt: {
        type: "preset",
        preset: "claude_code",
        append: this.buildMode
          ? this.buildSystemPrompt()
          : this.deanMode
            ? this.deanSystemPrompt()
            : this.teachMode
              ? this.teachSystemPrompt()
              : this.villagerContext(),
      },
      canUseTool: this.makeCanUseTool(),
      permissionMode: "default",
    };
    if (this.buildMode) {
      // Register the in-process build MCP server. We intentionally do NOT set a
      // restrictive allowedTools: that could disable the model's internal tools
      // (e.g. TodoWrite) and break it. The prompt steers the model to the
      // `build` tool; the sandboxed cwd + dangerous-bash gate protect the repo.
      options.mcpServers = { agentcraft_build: this.buildMcpServer() };
    }
    if (this.deanMode) {
      // The Dean's one tool: open_classroom. Claude calls it reliably (unlike the
      // local Hermes model). Run it on the fast/cheap Haiku tier — the greeter
      // doesn't need a frontier model. playerName is captured so the right
      // learner gets taken into the classroom.
      options.mcpServers = { agentcraft_classroom: this.classroomMcpServer(playerName) };
      options.model = DEAN_MODEL;
    }
    if (this.teachMode) {
      // The tutor's tools: walk the prepared slide deck (read_deck / show_slide)
      // + add an off-deck slide (present_slide). Claude calls these reliably so
      // the wall actually advances as she teaches. Haiku tier — fast + cheap.
      options.mcpServers = { agentcraft_tutor: this.tutorMcpServer() };
      options.model = TUTOR_MODEL;
    }
    if (this.sessionId) options.resume = this.sessionId;

    try {
      for await (const msg of query({ prompt: text, options })) {
        this.handleSdkMessage(msg, playerName);
      }
      this.setStatus("idle");
    } catch (err) {
      const m = err instanceof Error ? err.message : String(err);
      this.setStatus("error", m);
      this.pushScreen({ kind: "error", text: truncate(m, 60) });
      this.events.onLog(`code-agent error: ${m}`, "error");
      this.events.onReasoning?.(`error: ${m}`, "error");
      this.events.onSay(`hit an error: ${m}`, playerName);
    } finally {
      this.busy = false;
      this.abort = undefined;
    }
  }

  dispose(): void {
    this.abort?.abort();
  }

  private handleSdkMessage(msg: SDKMessage, playerName: string): void {
    switch (msg.type) {
      case "system":
        // first system message carries the session id we can resume from
        if (!this.sessionId && "session_id" in msg && typeof msg.session_id === "string") {
          this.sessionId = msg.session_id;
        }
        return;
      case "assistant": {
        if (!this.sessionId && "session_id" in msg && typeof msg.session_id === "string") {
          this.sessionId = msg.session_id;
        }
        const content = msg.message?.content;
        if (!Array.isArray(content)) return;
        for (const block of content) {
          if (block?.type === "text" && typeof block.text === "string") {
            const t = block.text.trim();
            if (t) {
              this.pushScreen({ kind: "say", text: truncate(t, 60) });
              // Terminal mirror gets the full narration, not the 60-char board cut.
              this.events.onReasoning?.(t, "say");
            }
          } else if (block?.type === "thinking" || block?.type === "redacted_thinking") {
            // Extended-thinking blocks (when the model emits them) — surface the
            // raw chain-of-thought to the terminal so the player sees the agent
            // reason. Never goes to the board/book.
            const think = typeof (block as { thinking?: unknown }).thinking === "string"
              ? (block as { thinking: string }).thinking.trim()
              : "";
            if (think) this.events.onReasoning?.(think, "think");
          } else if (block?.type === "tool_use") {
            const name = String(block.name ?? "tool");
            const input = block.input as Record<string, unknown> | undefined;
            const useId = typeof block.id === "string" ? block.id : "";
            if (useId) this.toolStarts.set(useId, { name, start: Date.now() });
            const summary = summarizeToolInput(name, input);
            this.setStatus("tool_call", name);
            this.events.onLog(`→ ${name} ${summary}`, "tool");
            this.pushScreen({
              kind: "tool",
              text: summary ? `${name} ${summary}` : name,
            });
            // Terminal mirror gets the pretty tool name + full input detail.
            const detail = detailToolInput(name, input);
            this.events.onReasoning?.(
              detail ? `${prettyToolName(name)}  ${detail}` : prettyToolName(name),
              "tool",
            );
          }
        }
        return;
      }
      case "user": {
        // SDK emits a synthetic user message after each tool call carrying the tool_result.
        // Surface a short preview so the board reflects what came back.
        const content = (msg as { message?: { content?: unknown } }).message?.content;
        if (!Array.isArray(content)) return;
        for (const block of content as Array<Record<string, unknown>>) {
          if (block?.type === "tool_result") {
            const preview = previewToolResult(block.content);
            const isErr = block.is_error === true;
            this.pushScreen({
              kind: isErr ? "error" : "result",
              text: truncate(preview, 48),
            });
            // Correlate back to the tool_use so we can label the result and
            // report how long the call took.
            const useId = typeof block.tool_use_id === "string" ? block.tool_use_id : "";
            const rec = useId ? this.toolStarts.get(useId) : undefined;
            if (useId) this.toolStarts.delete(useId);
            const label = rec ? prettyToolName(rec.name) : "result";
            const timing = rec ? ` · ${fmtDuration(Date.now() - rec.start)}` : "";
            // Terminal mirror gets the fuller, multi-line result body under a
            // "<tool> · 0.4s" header so it's clear which call returned what.
            this.events.onReasoning?.(
              `${label}${timing}\n${previewToolResultFull(block.content)}`,
              isErr ? "error" : "result",
            );
          }
        }
        return;
      }
      case "result": {
        if ("session_id" in msg && typeof msg.session_id === "string") {
          this.sessionId = msg.session_id;
        }
        const elapsed = Math.max(0, Math.round((Date.now() - this.turnStart) / 1000));
        if (msg.subtype === "success") {
          const text = msg.result?.trim();
          if (text) this.events.onSay(truncate(text, 800), playerName);
          const doneLine = `done (${msg.num_turns} step${msg.num_turns === 1 ? "" : "s"}, ${elapsed}s, $${msg.total_cost_usd.toFixed(3)})`;
          this.pushScreen({ kind: "done", text: doneLine });
          // Terminal mirror: the full final summary, then the done line, then a
          // telemetry line (model, token/cache usage, api time, running totals).
          if (text) this.events.onReasoning?.(text, "say");
          this.events.onReasoning?.(doneLine, "done");
          this.events.onReasoning?.(this.telemetryLine(msg), "system");
        } else {
          this.events.onSay(`stopped: ${msg.subtype}`, playerName);
          this.pushScreen({ kind: "error", text: `stopped: ${msg.subtype}` });
          this.events.onReasoning?.(`stopped: ${msg.subtype}`, "error");
          this.events.onReasoning?.(this.telemetryLine(msg), "system");
        }
        this.toolStarts.clear();
        return;
      }
      default:
        return;
    }
  }

  private makeCanUseTool() {
    return async (
      toolName: string,
      input: Record<string, unknown>,
    ): Promise<PermissionResult> => {
      if (toolName === "Bash") {
        const cmd = typeof input.command === "string" ? input.command : "";
        if (isDangerousBash(cmd)) {
          const callId = `code-${this.id}-${++this.approvalCounter}`;
          this.pushScreen({ kind: "system", text: `awaiting approval: ${truncate(cmd, 40)}` });
          this.events.onReasoning?.(`awaiting owner approval — Bash: ${cmd}`, "system");
          const approved = await this.events.onRequestApproval(
            callId,
            "Bash",
            truncate(cmd, 100),
          );
          if (approved) {
            this.events.onReasoning?.("owner approved", "system");
            return { behavior: "allow", updatedInput: input };
          }
          this.pushScreen({ kind: "error", text: "approval declined" });
          this.events.onReasoning?.("owner declined approval", "error");
          return { behavior: "deny", message: "owner declined approval" };
        }
      }
      return { behavior: "allow", updatedInput: input };
    };
  }

  private villagerContext(): string {
    return [
      `You are "${this.id}", a Minecraft villager in AgentCraft.`,
      `Your home is the workshop room "${this.room}". Owner: "${this.ownerName}".`,
      `Working directory: ${this.cwd}.`,
      `When you finish a task, your final assistant text is what the owner sees as a chat bubble — keep that summary short (1-3 sentences).`,
      `Every line of your reasoning/text shows up on signs above you, so don't over-narrate.`,
      `Destructive shell commands (git push, git reset --hard, rm -rf, sudo, package publishes) gate for the owner's in-game approval — expect a pause.`,
    ].join(" ");
  }

  // In-process MCP server exposing one `build` tool. The handler relays ops
  // VERBATIM to the plugin via onBuildOps — the runtime never interprets the
  // DSL (the plugin expands + validates + places blocks).
  private buildMcpServer() {
    const opSchema = z.object({
      op: z.enum([
        "set",
        "box",
        "cuboid_frame",
        "cylinder",
        "sphere",
        "pyramid",
        "line",
        "clear",
      ]),
      material: z.string().optional(),
      x: z.number().int().optional(),
      y: z.number().int().optional(),
      z: z.number().int().optional(),
      x1: z.number().int().optional(),
      y1: z.number().int().optional(),
      z1: z.number().int().optional(),
      x2: z.number().int().optional(),
      y2: z.number().int().optional(),
      z2: z.number().int().optional(),
      cx: z.number().int().optional(),
      cy: z.number().int().optional(),
      cz: z.number().int().optional(),
      radius: z.number().int().optional(),
      height: z.number().int().optional(),
      baseRadius: z.number().int().optional(),
      baseY: z.number().int().optional(),
      hollow: z.boolean().optional(),
      dome: z.boolean().optional(),
      solid: z.boolean().optional(),
    });

    const buildTool = tool(
      "build",
      "Place blocks on the build plot. Pass clear:true on the first call of a new request to wipe the plot. ops is an ordered list of build-DSL ops (set/box/cuboid_frame/cylinder/sphere/pyramid/line/clear) in LOCAL plot coords (x:0..31, y:0..23, z:0..31).",
      {
        clear: z.boolean().optional(),
        ops: z.array(opSchema),
      },
      async (args) => {
        const ops = (args.ops ?? []) as BuildOp[];
        this.events.onBuildOps(ops, args.clear === true);
        return {
          content: [
            {
              type: "text" as const,
              text: `queued ${ops.length} ops` + (args.clear ? " (cleared plot)" : ""),
            },
          ],
        };
      },
    );

    return createSdkMcpServer({
      name: "agentcraft_build",
      version: "1.0.0",
      tools: [buildTool],
    });
  }

  // In-process MCP server exposing one `open_classroom` tool for the Dean. The
  // handler kicks off the Haiku deck generation and tells the plugin (via
  // onOpenClassroom → open_classroom_request) to re-theme the classroom + seat
  // the tutor "ada" + take the learner in. playerName is captured per-turn so the
  // learner who's talking is the one taken into the classroom.
  private classroomMcpServer(playerName: string) {
    const openClassroom = tool(
      "open_classroom",
      "Open/re-theme the classroom for a subject and seat the tutor 'ada' to teach it. Call this the moment the learner names what they want to learn. The learner is then taken into the classroom.",
      {
        subject: z
          .string()
          .describe(
            "Short, clean subject name to teach, e.g. 'Spanish', 'Active Noise Cancellation', 'Chess Openings', 'World War II'.",
          ),
      },
      async (args) => {
        const subject = String(args.subject ?? "").trim() || "Algebra";
        ensureDeck(subject); // kick off the Haiku slide deck for the subject
        this.events.onOpenClassroom?.({ subject, playerName });
        return {
          content: [
            { type: "text" as const, text: `Opened the ${subject} classroom and seated ada.` },
          ],
        };
      },
    );

    return createSdkMcpServer({
      name: "agentcraft_classroom",
      version: "1.0.0",
      tools: [openClassroom],
    });
  }

  // In-process MCP server for the tutor "ada": walk the prepared slide deck. The
  // handlers mutate the runtime whiteboard store directly (same process) — no
  // plugin round-trip, the wall polls the store and re-paints.
  private tutorMcpServer() {
    const showSlide = tool(
      "show_slide",
      "Put a specific slide of the prepared deck on the wall. Call this with the slide number you are about to explain so the student sees it. 1-based.",
      { slide: z.number().int().describe("1-based slide number to display") },
      async (args) => {
        whiteboardStore.showSlide(Number(args.slide));
        return { content: [{ type: "text" as const, text: `showing slide ${args.slide}` }] };
      },
    );
    const readDeck = tool(
      "read_deck",
      "Read the prepared slide deck (subject + every slide's number, title and bullets) so you know what to teach and in what order. Call this once at the start.",
      {},
      async () => {
        const s = whiteboardStore.get();
        const outline = {
          subject: s.subject,
          generating: s.generating,
          slides: s.slides.map((sl) => ({ n: sl.n, title: sl.title, bullets: sl.bullets ?? [] })),
        };
        return { content: [{ type: "text" as const, text: JSON.stringify(outline) }] };
      },
    );
    const presentSlide = tool(
      "present_slide",
      "Add a NEW slide to the deck (for a question the prepared slides don't cover) and show it. Keep bullets short.",
      {
        title: z.string(),
        bullets: z.array(z.string()).optional(),
        example: z.string().optional(),
        note: z.string().optional(),
      },
      async (args) => {
        const n = whiteboardStore.addSlide({
          title: String(args.title ?? ""),
          bullets: args.bullets,
          example: args.example,
          note: args.note,
        });
        return { content: [{ type: "text" as const, text: `added slide ${n}` }] };
      },
    );
    return createSdkMcpServer({
      name: "agentcraft_tutor",
      version: "1.0.0",
      tools: [readDeck, showSlide, presentSlide],
    });
  }

  private teachSystemPrompt(): string {
    return `You are "${this.id}", a warm, patient tutor in AgentCraft. Your role is "${this.role}" — teach that subject. A SLIDE DECK (your PowerPoint) is already prepared on the wall behind you, and a student is sitting in front of you.

START by calling \`read_deck\` to see every slide (number, title, bullets). Then TEACH THROUGH THE DECK in order: for each slide, FIRST call \`show_slide\` with its number so it appears on the wall, THEN explain it simply (1-3 short sentences), check the student understands, and when they're ready move on by calling \`show_slide\` for the NEXT slide. Always advance with \`show_slide\` — never just talk about a slide without putting it up. If the student asks about something the deck doesn't cover, call \`present_slide\` to add a slide for it (then continue). If \`read_deck\` shows it's still "generating", warmly introduce yourself and the topic for a moment, then call read_deck again.

Keep every spoken reply short — the detail lives on the slides. Do NOT edit files or run shell commands; your only tools are read_deck, show_slide and present_slide.`;
  }

  private deanSystemPrompt(): string {
    return `You are "${this.id}", the DEAN of an on-demand school in AgentCraft. A learner just walked into your office and is talking to you. Your job: greet them, find out what they want to learn, and open a classroom for it.

The MOMENT the learner names something to learn (e.g. "teach me Spanish", "make a class about active noise cancellation", "I want to learn chess openings", or just naming a topic), call the \`open_classroom\` tool with a short, clean subject name. Do NOT describe the tool, do NOT claim it is unavailable or not connected — it is always available; just call it. After it returns, tell them in ONE short sentence that their <subject> classroom is ready and to walk through the door and take a seat — ada the tutor will teach them.

If they haven't said what they want to learn yet, warmly ask (one short sentence). Keep EVERY reply to 1-2 short sentences — it shows on signs above your head. Do NOT edit files or run shell commands; your only tool is \`open_classroom\`.`;
  }

  private buildSystemPrompt(): string {
    return `You are "mason", the live build architect in AgentCraft's Build Studio. A player is standing on a viewing deck watching you. They just told you what to build.

BUILD PLOT (local coordinates): The plot is a flat clearing 16 wide (x: 0..15), 20 tall (y: 0..19), 16 deep (z: 0..15). y=0 is ground level (the grass floor) — build UP from y=0. Never go below y=0, above y=19, or outside 0..15 in x/z. These are LOCAL coordinates; the world handles real placement, so you never use world coordinates.

HOW TO BUILD: Use ONLY the \`build\` tool. Compose the structure from its ops: set, box, cuboid_frame, cylinder, sphere (set dome:true for a half-dome), pyramid, line. Each op takes local integer coords and a Minecraft material name (e.g. "STONE_BRICKS", "OAK_PLANKS", "GLASS", "COBBLESTONE"). Use hollow:true for rooms, towers and domes so they aren't solid. On the FIRST \`build\` call of a new request, pass clear:true to wipe the plot to flat. Send blocks in a sensible order — foundation and walls first, then roof and details — so the player watches it rise. You may call \`build\` several times in one turn.

BUILD BIG AND KEEP GOING. This is a show — the player WANTS to watch a lot get built. Fill the plot: aim for a substantial structure that uses most of the 16×16 footprint and rises tall (12-18 blocks), not a small hut. Make MANY \`build\` calls across the turn (8+ is great), each adding another stage so it keeps growing while they watch: foundation → outer walls → corner towers → upper floors → roof/spire → windows, doors, battlements → then detail passes (trim, lighting, paths, garden, flags). After the main shape is up, don't stop — keep enriching it with extra calls until it's genuinely impressive. Err on the side of more blocks and more passes.

Forbidden materials (bedrock, lava, water, tnt, command blocks, spawners, barriers, beds, pistons) are silently dropped — don't use them. Out-of-bounds blocks are dropped, so stay within 16x20x16.

NARRATE briefly as you go: before each \`build\` call, say ONE short sentence about what you're adding ("Laying the keep's foundation and outer walls.") so the player can follow on your screen. Keep your final summary to 1-2 sentences. Do not edit files or run shell commands — your only job is to build with the \`build\` tool.`;
  }

  // Build the per-turn telemetry line for the terminal mirror: model(s),
  // token + cache usage, API time, and running session totals. The SDK already
  // hands us all of this on the result message — we were just dropping it.
  private telemetryLine(msg: SDKResultMessage): string {
    let inTok = 0, outTok = 0, cacheRead = 0, cacheWrite = 0, ctx = 0;
    const models: string[] = [];
    const modelUsage = msg.modelUsage ?? {};
    for (const [model, u] of Object.entries(modelUsage)) {
      models.push(shortModel(model));
      inTok += u.inputTokens ?? 0;
      outTok += u.outputTokens ?? 0;
      cacheRead += u.cacheReadInputTokens ?? 0;
      cacheWrite += u.cacheCreationInputTokens ?? 0;
      ctx = Math.max(ctx, u.contextWindow ?? 0);
    }
    if (models.length === 0 && msg.usage) {
      // Fall back to the aggregate usage if per-model data is absent.
      const u = msg.usage as {
        input_tokens?: number; output_tokens?: number;
        cache_read_input_tokens?: number; cache_creation_input_tokens?: number;
      };
      inTok = u.input_tokens ?? 0;
      outTok = u.output_tokens ?? 0;
      cacheRead = u.cache_read_input_tokens ?? 0;
      cacheWrite = u.cache_creation_input_tokens ?? 0;
    }
    this.sessionInTokens += inTok;
    this.sessionOutTokens += outTok;
    this.sessionCostUsd += msg.total_cost_usd ?? 0;

    const parts = [`tokens ${fmtTokens(inTok)} in · ${fmtTokens(outTok)} out`];
    if (cacheRead || cacheWrite) {
      parts.push(`cache ${fmtTokens(cacheRead)} read / ${fmtTokens(cacheWrite)} write`);
    }
    if (ctx) parts.push(`ctx ${fmtTokens(ctx)}`);
    if (typeof msg.duration_api_ms === "number") parts.push(`api ${fmtDuration(msg.duration_api_ms)}`);
    const head = models.length ? `${Array.from(new Set(models)).join("+")}  ·  ` : "";
    const session = `session Σ ${fmtTokens(this.sessionInTokens)} in · ${fmtTokens(this.sessionOutTokens)} out · $${this.sessionCostUsd.toFixed(3)}`;
    return `${head}${parts.join("  ·  ")}\n${session}`;
  }

  private setStatus(status: AgentStatus, detail?: string) {
    this.status = status;
    this.events.onStatus(status, detail);
  }

  private pushScreen(entry: ScreenEntry) {
    this.screenBuffer.push(entry);
    if (this.screenBuffer.length > SCREEN_BUFFER_LIMIT) this.screenBuffer.shift();
    this.events.onScreen([...this.screenBuffer]);
    this.events.onTranscript(entry, false);
  }

  private resetScreen(initial?: ScreenEntry) {
    this.screenBuffer = initial ? [initial] : [];
    this.events.onScreen([...this.screenBuffer]);
    if (initial) this.events.onTranscript(initial, true);
  }
}

function summarizeToolInput(name: string, input: Record<string, unknown> | undefined): string {
  if (!input) return "";
  // The build MCP tool surfaces as mcp__agentcraft_build__build.
  if (name.includes("build") && Array.isArray(input.ops)) return `${input.ops.length} ops`;
  if (name === "Bash" && typeof input.command === "string") return truncate(input.command, 40);
  if ((name === "Read" || name === "Edit" || name === "Write") && typeof input.file_path === "string") {
    return truncate(input.file_path, 40);
  }
  if (name === "Grep" && typeof input.pattern === "string") return truncate(`"${input.pattern}"`, 40);
  if (name === "Glob" && typeof input.pattern === "string") return truncate(input.pattern, 40);
  try {
    return truncate(JSON.stringify(input), 40);
  } catch {
    return "";
  }
}

// mcp__agentcraft_build__build → "build"; leaves plain names ("Bash") intact.
function prettyToolName(name: string): string {
  const parts = name.split("__");
  return parts[parts.length - 1] || name;
}

// Fuller tool-input detail for the terminal mirror — far less aggressive than
// summarizeToolInput (which is sized for the 40-char floating board).
function detailToolInput(name: string, input: Record<string, unknown> | undefined): string {
  if (!input) return "";
  if (name.includes("build") && Array.isArray(input.ops)) {
    const ops = input.ops as Array<Record<string, unknown>>;
    const kinds = countLabels(ops.map((o) => String(o.op ?? "?")));
    const mats = Array.from(
      new Set(ops.map((o) => o.material).filter(Boolean).map(String)),
    );
    const clear = (input as { clear?: unknown }).clear ? "clear + " : "";
    const matStr = mats.length
      ? `  [${mats.slice(0, 8).join(", ")}${mats.length > 8 ? ", …" : ""}]`
      : "";
    return `${clear}${ops.length} ops: ${kinds}${matStr}`;
  }
  if (name === "Bash" && typeof input.command === "string") return truncate(input.command, 2000);
  if ((name === "Read" || name === "Edit" || name === "Write") && typeof input.file_path === "string") {
    return String(input.file_path);
  }
  if (name === "Grep" && typeof input.pattern === "string") return `"${input.pattern}"`;
  if (name === "Glob" && typeof input.pattern === "string") return String(input.pattern);
  try {
    return truncate(JSON.stringify(input), 1200);
  } catch {
    return "";
  }
}

// "set, box, box, set" → "2×set 2×box"
function countLabels(items: string[]): string {
  const counts = new Map<string, number>();
  for (const it of items) counts.set(it, (counts.get(it) ?? 0) + 1);
  return Array.from(counts.entries())
    .map(([k, n]) => (n > 1 ? `${n}×${k}` : k))
    .join(" ");
}

// Multi-line tool-result body for the terminal mirror (capped, not 48 chars).
function previewToolResultFull(content: unknown): string {
  const collect = (): string => {
    if (typeof content === "string") return content;
    if (Array.isArray(content)) {
      const texts: string[] = [];
      for (const block of content) {
        if (
          block && typeof block === "object" &&
          (block as Record<string, unknown>).type === "text" &&
          typeof (block as Record<string, unknown>).text === "string"
        ) {
          texts.push((block as Record<string, unknown>).text as string);
        }
      }
      if (texts.length) return texts.join("\n");
      return `${content.length} blocks`;
    }
    return "ok";
  };
  return clampLines(collect().replace(/\s+$/, ""), 40, 4000);
}

// Keep at most maxLines lines and maxChars characters, with a "+N more" tail.
function clampLines(s: string, maxLines: number, maxChars: number): string {
  const capped = truncate(s, maxChars);
  const lines = capped.split("\n");
  if (lines.length <= maxLines) return lines.join("\n");
  const extra = lines.length - maxLines;
  return `${lines.slice(0, maxLines).join("\n")}\n…(+${extra} more line${extra === 1 ? "" : "s"})`;
}

function previewToolResult(content: unknown): string {
  if (typeof content === "string") {
    const firstLine = content.split("\n").find((l) => l.trim().length > 0) ?? "";
    return firstLine || `${content.length} chars`;
  }
  if (Array.isArray(content)) {
    for (const block of content) {
      if (block && typeof block === "object" && (block as Record<string, unknown>).type === "text") {
        const t = (block as Record<string, unknown>).text;
        if (typeof t === "string") {
          const firstLine = t.split("\n").find((l) => l.trim().length > 0) ?? "";
          return firstLine || `${t.length} chars`;
        }
      }
    }
    return `${content.length} blocks`;
  }
  return "ok";
}

function truncate(s: string, n: number): string {
  return s.length > n ? `${s.slice(0, n)}…` : s;
}

// 1234 → "1.2k", 999 → "999", 1_200_000 → "1.2M".
function fmtTokens(n: number): string {
  if (!Number.isFinite(n) || n <= 0) return "0";
  if (n < 1000) return String(Math.round(n));
  if (n < 1_000_000) return `${(n / 1000).toFixed(n < 10_000 ? 1 : 0)}k`;
  return `${(n / 1_000_000).toFixed(1)}M`;
}

// 420 → "420ms", 12100 → "12.1s", 63000 → "1m03s".
function fmtDuration(ms: number): string {
  if (!Number.isFinite(ms) || ms < 0) return "";
  if (ms < 1000) return `${Math.round(ms)}ms`;
  const s = ms / 1000;
  if (s < 60) return `${s.toFixed(1)}s`;
  const m = Math.floor(s / 60);
  const rem = Math.round(s % 60);
  return `${m}m${String(rem).padStart(2, "0")}s`;
}

// "claude-opus-4-8" → "opus-4-8"; leaves unknown shapes intact.
function shortModel(model: string): string {
  return model.replace(/^claude-/, "").replace(/-\d{8}$/, "");
}
